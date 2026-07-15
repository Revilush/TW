package com.rush.automationtw.node

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (
            action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == ServiceWatchdog.ACTION_RESTART_SERVICE
        ) {
            startMainService(context)

            if (action == Intent.ACTION_BOOT_COMPLETED) {
                ServiceWatchdog.scheduleRestart(context, BOOT_RETRY_DELAY_MS)
            }
        }
    }

    private fun startMainService(context: Context) {
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, MainService::class.java)
            )
        } catch (error: Exception) {
            Log.e(TAG, "Failed to start MainService", error)
            ServiceWatchdog.scheduleRestart(context, BOOT_FALLBACK_DELAY_MS)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
        private const val BOOT_RETRY_DELAY_MS = 15_000L
        private const val BOOT_FALLBACK_DELAY_MS = 30_000L
    }
}
