package com.rush.automationtw.node

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class KeepAliveManager(private val context: Context) {
    private val appContext = context.applicationContext
    private var wakeLock: PowerManager.WakeLock? = null

    fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) {
            return
        }

        val powerManager = appContext.getSystemService(PowerManager::class.java) ?: return
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            WAKE_LOCK_TAG
        ).apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
        Log.i(TAG, "Acquired PARTIAL_WAKE_LOCK")
    }

    fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        Log.i(TAG, "Released PARTIAL_WAKE_LOCK")
    }

    fun onServiceStarted() {
        MainService.isRunning = true
        acquireWakeLock()
        schedulePing()
        scheduleBackupWatchdog()
        cancelImmediateRestart()
        Log.i(TAG, "Service marked as running")
    }

    fun onServiceStopped(reason: String) {
        MainService.isRunning = false
        scheduleImmediateRestart(reason)
        Log.w(TAG, "Service marked as stopped: $reason")
    }

    fun schedulePing(delayMs: Long = PING_INTERVAL_MS) {
        val alarmManager = appContext.getSystemService(AlarmManager::class.java) ?: return
        val triggerAtMillis = System.currentTimeMillis() + delayMs
        val pendingIntent = createKeepAlivePendingIntent()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
        } else {
            alarmManager.setExact(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
        }

        Log.i(TAG, "Scheduled keep-alive ping in ${delayMs}ms")
    }

    fun scheduleImmediateRestart(reason: String, delayMs: Long = RESTART_DELAY_MS) {
        val alarmManager = appContext.getSystemService(AlarmManager::class.java) ?: return
        val triggerAtMillis = System.currentTimeMillis() + delayMs
        val pendingIntent = createRestartPendingIntent()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
        } else {
            alarmManager.setExact(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
        }

        Log.w(TAG, "Scheduled restart attempt in ${delayMs}ms. Reason: $reason")
    }

    fun cancelImmediateRestart() {
        val alarmManager = appContext.getSystemService(AlarmManager::class.java) ?: return
        alarmManager.cancel(createRestartPendingIntent())
        Log.i(TAG, "Cancelled pending restart alarm")
    }

    fun scheduleBackupWatchdog() {
        val request = PeriodicWorkRequestBuilder<KeepAliveWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
            KEEP_ALIVE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
        Log.i(TAG, "Scheduled backup watchdog work")
    }

    private fun createKeepAlivePendingIntent(): PendingIntent {
        val intent = Intent(appContext, KeepAliveReceiver::class.java).apply {
            action = ACTION_KEEP_ALIVE_PING
        }
        return PendingIntent.getBroadcast(
            appContext,
            KEEP_ALIVE_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createRestartPendingIntent(): PendingIntent {
        val intent = Intent(appContext, BootReceiver::class.java).apply {
            action = ServiceWatchdog.ACTION_RESTART_SERVICE
        }
        return PendingIntent.getBroadcast(
            appContext,
            RESTART_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        private const val TAG = "KeepAliveManager"
        private const val WAKE_LOCK_TAG = "automationtw:keep-alive"
        private const val KEEP_ALIVE_REQUEST_CODE = 5101
        private const val RESTART_REQUEST_CODE = 5102
        private const val PING_INTERVAL_MS = 5 * 60 * 1000L
        private const val RESTART_DELAY_MS = 5_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 24 * 60 * 60 * 1000L
        private const val KEEP_ALIVE_WORK_NAME = "keep-alive-backup-worker"
        const val ACTION_KEEP_ALIVE_PING = "com.rush.automationtw.node.ACTION_KEEP_ALIVE_PING"
    }
}

class KeepAliveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != KeepAliveManager.ACTION_KEEP_ALIVE_PING) {
            return
        }

        val keepAliveManager = KeepAliveManager(context)
        Log.i("KeepAliveReceiver", "Received keep-alive ping")

        if (!MainService.isRunning) {
            Log.w("KeepAliveReceiver", "MainService not running, restarting")
            ContextCompat.startForegroundService(context, Intent(context, MainService::class.java))
        }

        keepAliveManager.schedulePing()
    }
}

class KeepAliveWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        Log.i("KeepAliveWorker", "Backup watchdog check running")
        if (!MainService.isRunning) {
            Log.w("KeepAliveWorker", "MainService not running, starting foreground service")
            ContextCompat.startForegroundService(
                applicationContext,
                Intent(applicationContext, MainService::class.java)
            )
        }

        KeepAliveManager(applicationContext).schedulePing()
        return Result.success()
    }
}
