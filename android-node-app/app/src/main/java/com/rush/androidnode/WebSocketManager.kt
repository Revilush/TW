package com.rush.androidnode

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class WebSocketManager(
    context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onSocketConnected()
        fun onSocketDisconnected(reason: String)
        fun onCommandReceived(commandId: String, commandPayload: JSONObject)
    }

    private val appContext = context.applicationContext
    private val nodePreferences = NodePreferences(appContext)
    private val deviceInfoProvider = DeviceInfoProvider(appContext)
    private val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile
    private var socket: WebSocket? = null

    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var registrationRefreshJob: Job? = null
    private val manuallyStopped = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)
    private val connecting = AtomicBoolean(false)

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            NodeLogger.info("[NETWORK_AVAILABLE] retrying websocket connection")
            if (!manuallyStopped.get()) {
                connectNow()
            }
        }

        override fun onLost(network: Network) {
            NodeLogger.disconnected("network lost")
        }
    }

    init {
        registerNetworkCallback()
    }

    fun start() {
        manuallyStopped.set(false)
        connectNow()
    }

    fun ensureConnected() {
        if (connected.get() || connecting.get() || manuallyStopped.get()) {
            return
        }
        scheduleReconnect("ensureConnected")
    }

    fun stop() {
        manuallyStopped.set(true)
        connected.set(false)
        connecting.set(false)
        heartbeatJob?.cancel()
        registrationRefreshJob?.cancel()
        reconnectJob?.cancel()
        socket?.close(NORMAL_CLOSE_CODE, "service stopped")
        socket = null
    }

    fun shutdown() {
        stop()
        unregisterNetworkCallback()
        ioScope.cancel()
        client.dispatcher.executorService.shutdown()
    }

    fun sendCommandResult(commandId: String, result: CommandHandler.CommandResult) {
        val payload = JSONObject()
            .put("type", "response")
            .put("device_id", deviceInfoProvider.getDeviceId())
            .put("command_id", commandId)
            .put("status", result.status)
            .put("result", JSONObject().put("message", result.message))

        send(payload)
    }

    private fun connectNow() {
        if (connected.get() || connecting.get() || manuallyStopped.get()) {
            return
        }

        val serverUrl = nodePreferences.serverUrl
        if (serverUrl.isBlank()) {
            NodeLogger.error("Server URL is blank")
            scheduleReconnect("missing server URL")
            return
        }

        reconnectJob?.cancel()
        connecting.set(true)

        runCatching {
            val request = Request.Builder().url(serverUrl).build()
            socket = client.newWebSocket(request, createWebSocketListener())
        }.onFailure { error ->
            connecting.set(false)
            NodeLogger.error("Unable to create WebSocket connection", error)
            scheduleReconnect("socket creation failed")
        }
    }

    private fun createWebSocketListener(): WebSocketListener {
        return object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socket = webSocket
                connecting.set(false)
                connected.set(true)
                sendRegistration()
                startRegistrationRefreshLoop()
                startHeartbeatLoop()
                NodeLogger.connected("connected to ${nodePreferences.serverUrl}")
                listener.onSocketConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { JSONObject(text) }
                    .onSuccess { payload -> handleServerPayload(payload) }
                    .onFailure { error -> NodeLogger.error("Invalid server message: $text", error) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                handleDisconnect("closed code=$code reason=$reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                handleDisconnect("failure=${t.message.orEmpty()}")
            }
        }
    }

    private fun handleServerPayload(payload: JSONObject) {
        when (payload.optString("type")) {
            "registered" -> NodeLogger.reconnected("device registration acknowledged")
            "heartbeat_ack" -> NodeLogger.info("[HEARTBEAT_ACK]")
            "command" -> {
                val commandId = payload.optString("command_id").ifBlank {
                    System.currentTimeMillis().toString()
                }
                val command = payload.optJSONObject("command") ?: JSONObject()
                listener.onCommandReceived(commandId, command)
            }
            "error" -> NodeLogger.error("server error: ${payload.optString("message")}")
            else -> NodeLogger.info("[SERVER_EVENT] $payload")
        }
    }

    private fun sendRegistration() {
        val nowMs = System.currentTimeMillis()
        val payload = JSONObject()
            .put("type", "register")
            .put("device_id", deviceInfoProvider.getDeviceId())
            .put("phone_number", deviceInfoProvider.getPhoneNumber())
            .put("battery_level", deviceInfoProvider.getBatteryLevel())
            .put("plan_due_date", deviceInfoProvider.getPlanDueDate())
            .put("last_seen", nowMs)
            .put("status", "ACTIVE")
            .put("timestamp", nowMs)

        send(payload)
    }

    private fun sendHeartbeat() {
        val nowMs = System.currentTimeMillis()
        val payload = JSONObject()
            .put("type", "heartbeat")
            .put("device_id", deviceInfoProvider.getDeviceId())
            .put("phone_number", deviceInfoProvider.getPhoneNumber())
            .put("battery_level", deviceInfoProvider.getBatteryLevel())
            .put("plan_due_date", deviceInfoProvider.getPlanDueDate())
            .put("last_seen", nowMs)
            .put("status", "ACTIVE")
            .put("timestamp", nowMs)

        send(payload)
    }

    private fun startHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = ioScope.launch {
            while (isActive && connected.get() && !manuallyStopped.get()) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (!isActive || !connected.get() || manuallyStopped.get()) {
                    break
                }
                sendHeartbeat()
            }
        }
    }

    private fun startRegistrationRefreshLoop() {
        registrationRefreshJob?.cancel()
        registrationRefreshJob = ioScope.launch {
            while (isActive && connected.get() && !manuallyStopped.get()) {
                delay(REGISTRATION_REFRESH_INTERVAL_MS)
                if (!isActive || !connected.get() || manuallyStopped.get()) {
                    break
                }
                sendRegistration()
            }
        }
    }

    private fun send(payload: JSONObject) {
        val sent = socket?.send(payload.toString()) ?: false
        if (!sent) {
            NodeLogger.error("Failed to send payload: $payload")
            scheduleReconnect("send failed")
        }
    }

    private fun handleDisconnect(reason: String) {
        heartbeatJob?.cancel()
        registrationRefreshJob?.cancel()
        socket = null
        connecting.set(false)
        connected.set(false)
        NodeLogger.disconnected(reason)
        listener.onSocketDisconnected(reason)

        if (!manuallyStopped.get()) {
            scheduleReconnect(reason)
        }
    }

    private fun scheduleReconnect(reason: String) {
        if (manuallyStopped.get() || connected.get() || reconnectJob?.isActive == true) {
            return
        }

        reconnectJob = ioScope.launch {
            NodeLogger.info("[RECONNECT_SCHEDULED] retry_in_ms=$RECONNECT_INTERVAL_MS reason=$reason")
            delay(RECONNECT_INTERVAL_MS)
            if (isActive && !manuallyStopped.get() && !connected.get()) {
                NodeLogger.reconnected("retrying after disconnect")
                connectNow()
            }
        }
    }

    private fun registerNetworkCallback() {
        runCatching {
            connectivityManager?.registerDefaultNetworkCallback(networkCallback)
        }.onFailure { error ->
            NodeLogger.error("Failed to register network callback", error)
        }
    }

    private fun unregisterNetworkCallback() {
        runCatching {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        }.onFailure { error ->
            NodeLogger.error("Failed to unregister network callback", error)
        }
    }

    fun isConnected(): Boolean = connected.get()

    private companion object {
        private const val HEARTBEAT_INTERVAL_MS = 300_000L
        private const val REGISTRATION_REFRESH_INTERVAL_MS = 60_000L
        private const val RECONNECT_INTERVAL_MS = 30_000L
        private const val NORMAL_CLOSE_CODE = 1000
    }
}
