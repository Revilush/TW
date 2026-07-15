package com.rush.automationtw.node

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class MainService : Service(), WebSocketManager.Listener {
    private lateinit var webSocketManager: WebSocketManager
    private lateinit var commandHandler: CommandHandler
    private lateinit var keepAliveManager: KeepAliveManager
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        commandHandler = CommandHandler(applicationContext)
        webSocketManager = WebSocketManager(applicationContext, this)
        keepAliveManager = KeepAliveManager(applicationContext)
        keepAliveManager.acquireWakeLock()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Keeping node active in background"))
        scheduleBatteryWorker()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        keepAliveManager.onServiceStarted()
        webSocketManager.ensureConnected()
        return START_STICKY
    }

    override fun onDestroy() {
        webSocketManager.shutdown()
        keepAliveManager.releaseWakeLock()
        keepAliveManager.onServiceStopped("onDestroy")
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        keepAliveManager.onServiceStopped("onTaskRemoved")
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onSocketConnected() {
        updateNotification("Service running. Connected to portal")
    }

    override fun onSocketDisconnected(reason: String) {
        updateNotification("Service running. Reconnecting to portal")
        webSocketManager.ensureConnected()
    }

    override fun onCommand(commandId: String, command: org.json.JSONObject) {
        serviceScope.launch {
            val result = commandHandler.handle(command)
            webSocketManager.sendCommandResponse(commandId, result)
        }
    }

    private fun scheduleBatteryWorker() {
        val request = PeriodicWorkRequestBuilder<BatteryWorker>(1, TimeUnit.HOURS).build()
        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            BATTERY_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    private fun buildNotification(content: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            1002,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Service Running")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(openAppIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(content: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification(content))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Automation TW Node",
            NotificationManager.IMPORTANCE_HIGH
        )
        channel.setShowBadge(false)
        channel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        @Volatile
        var isRunning: Boolean = false

        private const val CHANNEL_ID = "automation_tw_node"
        private const val BATTERY_WORK_NAME = "battery-report-worker"
        private const val NOTIFICATION_ID = 1001
    }
}
