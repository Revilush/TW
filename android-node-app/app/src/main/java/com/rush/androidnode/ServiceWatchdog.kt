package com.rush.androidnode

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock

object ServiceWatchdog {
    const val ACTION_RESTART_NODE_SERVICE = "com.rush.androidnode.ACTION_RESTART_NODE_SERVICE"

    fun scheduleRestart(context: Context, delayMs: Long = RESTART_DELAY_MS) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + delayMs,
            buildPendingIntent(context)
        )
        NodeLogger.info("[SERVICE_RESTART_SCHEDULED] delay_ms=$delayMs")
    }

    fun cancelRestart(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        alarmManager.cancel(buildPendingIntent(context))
    }

    private fun buildPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, BootReceiver::class.java).apply {
            action = ACTION_RESTART_NODE_SERVICE
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private const val REQUEST_CODE = 3001
    private const val RESTART_DELAY_MS = 5_000L
}
