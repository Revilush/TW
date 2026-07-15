package com.rush.androidnode

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

class NodeForegroundService : Service(), WebSocketManager.Listener {
    private lateinit var commandHandler: CommandHandler
    private lateinit var webSocketManager: WebSocketManager
    private lateinit var nodeStateStore: NodeStateStore

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serviceWakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        installCrashHandler()
        commandHandler = CommandHandler(applicationContext)
        webSocketManager = WebSocketManager(applicationContext, this)
        nodeStateStore = NodeStateStore(applicationContext)
        NodeLogger.info("[STATE_RESTORED] ${nodeStateStore.snapshot()}")
        acquireServiceWakeLock()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Device connected and active"))
        NodeLogger.info("[SERVICE_CREATED]")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isRunning = true
        ServiceWatchdog.cancelRestart(applicationContext)
        webSocketManager.start()
        refreshNotification()
        NodeLogger.info("[SERVICE_STARTED]")
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        webSocketManager.shutdown()
        commandHandler.release()
        releaseServiceWakeLock()
        serviceScope.cancel()
        ServiceWatchdog.scheduleRestart(applicationContext)
        NodeLogger.info("[SERVICE_DESTROYED] restart scheduled")
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        isRunning = false
        ServiceWatchdog.scheduleRestart(applicationContext)
        NodeLogger.info("[TASK_REMOVED] restart scheduled")
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onSocketConnected() {
        refreshNotification()
    }

    override fun onSocketDisconnected(reason: String) {
        refreshNotification()
        webSocketManager.ensureConnected()
    }

    override fun onCommandReceived(commandId: String, commandPayload: JSONObject) {
        serviceScope.launch {
            val result = commandHandler.handle(commandPayload)
            webSocketManager.sendCommandResult(commandId, result)
            refreshNotification()
        }
    }

    private fun acquireServiceWakeLock() {
        val powerManager = getSystemService(PowerManager::class.java) ?: return
        serviceWakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AndroidNodeApp:ServiceWakeLock"
        ).apply {
            setReferenceCounted(false)
            if (!isHeld) {
                acquire()
            }
        }
    }

    private fun releaseServiceWakeLock() {
        serviceWakeLock?.let { wakeLock ->
            if (wakeLock.isHeld) {
                runCatching { wakeLock.release() }
            }
        }
        serviceWakeLock = null
    }

    private fun refreshNotification() {
        val notificationManager = getSystemService(NotificationManager::class.java) ?: return
        val state = nodeStateStore.snapshot()
        val connectionText = if (webSocketManager.isConnected()) {
            "Device connected and active"
        } else {
            "Reconnecting to server"
        }
        val contentText = "$connectionText | task=${state.currentTask} | errors=${state.errorCount}"
        notificationManager.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    private fun buildNotification(contentText: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            2001,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Device connected and active")
            .setContentText(contentText)
            .setContentIntent(openAppIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val notificationManager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Android Node Service",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        @Volatile
        var isRunning: Boolean = false

        @Volatile
        private var crashHandlerInstalled: Boolean = false

        private const val CHANNEL_ID = "android_node_service"
        private const val NOTIFICATION_ID = 2002
    }

    private fun installCrashHandler() {
        if (crashHandlerInstalled) {
            return
        }

        synchronized(NodeForegroundService::class.java) {
            if (crashHandlerInstalled) {
                return
            }

            val previousCrashHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                NodeLogger.error("Uncaught service crash on ${thread.name}", error)
                ServiceWatchdog.scheduleRestart(applicationContext)
                previousCrashHandler?.uncaughtException(thread, error)
            }
            crashHandlerInstalled = true
        }
    }
}
