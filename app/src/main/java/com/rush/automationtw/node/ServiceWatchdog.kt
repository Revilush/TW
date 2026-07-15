package com.rush.automationtw.node

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

object ServiceWatchdog {
    fun scheduleRestart(context: Context, delayMs: Long = RESTART_DELAY_MS) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val triggerAtMillis = System.currentTimeMillis() + delayMs
        val pendingIntent = createPendingIntent(context)

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
    }

    fun cancelRestart(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        alarmManager.cancel(createPendingIntent(context))
    }

    private fun createPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, BootReceiver::class.java).apply {
            action = ACTION_RESTART_SERVICE
        }

        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private const val REQUEST_CODE = 4101
    private const val RESTART_DELAY_MS = 5_000L
    const val ACTION_RESTART_SERVICE = "com.rush.automationtw.node.ACTION_RESTART_SERVICE"
}
