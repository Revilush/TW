package com.rush.automationtw.node

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class WebSocketManager(
    context: Context,
    private val onEvent: Listener
) {
    interface Listener {
        fun onSocketConnected()
        fun onSocketDisconnected(reason: String)
        fun onCommand(commandId: String, command: JSONObject)
    }

    private val appContext = context.applicationContext
    private val prefs = NodePreferences(appContext)
    private val profileProvider = DeviceProfileProvider(appContext)
    private val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reconnectAttempt = AtomicInteger(0)

    @Volatile
    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null
    private val intentionallyClosed = AtomicBoolean(false)
    private val isConnected = AtomicBoolean(false)
    private val isConnecting = AtomicBoolean(false)
    private val connectionEvents = Channel<Boolean>(
        capacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.i(TAG, "Network available, forcing immediate reconnect")
            reconnectAttempt.set(0)
            triggerImmediateReconnect("network_available")
        }

        override fun onLost(network: Network) {
            Log.w(TAG, "Network lost")
        }
    }

    init {
        registerNetworkCallback()
    }

    fun connect() {
        intentionallyClosed.set(false)
        openSocket()
    }

    fun ensureConnected() {
        if (isConnected.get() || isConnecting.get()) {
            return
        }

        Log.i(TAG, "ensureConnected() triggering socket connect")
        if (reconnectJob?.isActive == true) {
            return
        }

        reconnectAttempt.set(0)
        connect()
    }

    fun disconnect() {
        intentionallyClosed.set(true)
        isConnected.set(false)
        heartbeatJob?.cancel()
        reconnectJob?.cancel()
        webSocket?.close(1000, "Service stopped")
        webSocket = null
    }

    suspend fun sendBatteryReportOnce(): Boolean {
        intentionallyClosed.set(false)
        openSocket()

        val connected = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            connectionEvents.receive()
        } ?: false

        if (!connected) {
            disconnect()
            return false
        }

        sendHeartbeat()
        delay(1_000L)
        disconnect()
        return true
    }

    private fun openSocket() {
        if (isConnected.get() || isConnecting.get()) {
            return
        }

        val serverUrl = prefs.serverUrl
        if (serverUrl.isBlank()) {
            Log.w(TAG, "Server URL is empty; skipping WebSocket connect")
            return
        }

        isConnecting.set(true)
        val request = Request.Builder()
            .url(serverUrl)
            .build()

        webSocket = client.newWebSocket(request, createListener())
    }

    private fun createListener(): WebSocketListener {
        return object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected")
                this@WebSocketManager.webSocket = webSocket
                isConnecting.set(false)
                isConnected.set(true)
                reconnectAttempt.set(0)
                reconnectJob?.cancel()
                reconnectJob = null
                connectionEvents.trySend(true)
                sendRegistration()
                startHeartbeat()
                onEvent.onSocketConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { JSONObject(text) }
                    .onSuccess { handleMessage(it) }
                    .onFailure { Log.e(TAG, "Invalid socket message: $text", it) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                handleDisconnect("closed: $code/$reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                handleDisconnect("failure: ${t.message}")
            }
        }
    }

    private fun handleMessage(payload: JSONObject) {
        when (payload.optString("type")) {
            "registered" -> Log.i(TAG, "Device registered: ${payload.optJSONObject("device")}")
            "heartbeat_ack" -> Log.d(TAG, "Heartbeat acknowledged")
            "command" -> {
                val commandId = payload.optString("command_id")
                val command = payload.optJSONObject("command") ?: JSONObject()
                onEvent.onCommand(commandId, command)
            }
            "error" -> Log.e(TAG, "Server error: ${payload.optString("message")}")
        }
    }

    fun sendCommandResponse(commandId: String, result: CommandHandler.CommandResult) {
        val payload = JSONObject()
            .put("type", "response")
            .put("device_id", profileProvider.getDeviceId())
            .put("command_id", commandId)
            .put("status", result.status)
            .put("result", JSONObject().put("message", result.message))

        send(payload)
    }

    private fun sendRegistration() {
        val payload = JSONObject()
            .put("type", "register")
            .put("device_id", profileProvider.getDeviceId())
            .put("phone_number", profileProvider.getPhoneNumber())
            .put("battery_level", profileProvider.getBatteryLevel())
            .put("plan_due_date", profileProvider.getPlanDueDate())
            .put("last_seen", System.currentTimeMillis())

        send(payload)
    }

    private fun sendHeartbeat() {
        val payload = JSONObject()
            .put("type", "heartbeat")
            .put("device_id", profileProvider.getDeviceId())
            .put("phone_number", profileProvider.getPhoneNumber())
            .put("battery_level", profileProvider.getBatteryLevel())
            .put("plan_due_date", profileProvider.getPlanDueDate())
            .put("last_seen", System.currentTimeMillis())

        send(payload)
    }

    private fun send(payload: JSONObject) {
        val sent = webSocket?.send(payload.toString()) ?: false
        if (!sent) {
            Log.w(TAG, "Unable to send payload, socket unavailable")
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                sendHeartbeat()
            }
        }
    }

    private fun handleDisconnect(reason: String) {
        heartbeatJob?.cancel()
        isConnecting.set(false)
        isConnected.set(false)
        webSocket = null

        if (intentionallyClosed.get()) {
            onEvent.onSocketDisconnected(reason)
            return
        }

        scheduleReconnect(reason)
        onEvent.onSocketDisconnected(reason)
    }

    private fun scheduleReconnect(reason: String) {
        if (reconnectJob?.isActive == true) {
            return
        }

        reconnectJob = scope.launch {
            while (isActive && !intentionallyClosed.get() && !isConnected.get()) {
                val attemptIndex = reconnectAttempt.getAndIncrement()
                val delayMs = RECONNECT_BACKOFF_MS[attemptIndex.coerceAtMost(RECONNECT_BACKOFF_MS.lastIndex)]
                Log.i(TAG, "Scheduling reconnect in ${delayMs}ms due to $reason")
                delay(delayMs)
                if (isConnected.get() || intentionallyClosed.get()) {
                    break
                }

                Log.i(TAG, "Attempting reconnect")
                openSocket()
            }
        }
    }

    private fun triggerImmediateReconnect(reason: String) {
        if (intentionallyClosed.get() || isConnected.get() || isConnecting.get()) {
            return
        }

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            Log.i(TAG, "Immediate reconnect attempt: $reason")
            openSocket()
        }
    }

    private fun registerNetworkCallback() {
        runCatching {
            connectivityManager?.registerDefaultNetworkCallback(networkCallback)
        }.onFailure {
            Log.w(TAG, "Unable to register network callback", it)
        }
    }

    private fun unregisterNetworkCallback() {
        runCatching {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        }.onFailure {
            Log.w(TAG, "Unable to unregister network callback", it)
        }
    }

    fun shutdown() {
        disconnect()
        unregisterNetworkCallback()
        scope.cancel()
        client.dispatcher.executorService.shutdown()
    }

    fun isConnected(): Boolean = isConnected.get()

    companion object {
        private const val TAG = "WebSocketManager"
        private const val HEARTBEAT_INTERVAL_MS = 60_000L
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private val RECONNECT_BACKOFF_MS = longArrayOf(
            1_000L,
            2_000L,
            5_000L,
            10_000L,
            30_000L
        )
    }
}
