package com.rush.androidnode

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (
            action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == ServiceWatchdog.ACTION_RESTART_NODE_SERVICE
        ) {
            startNodeService(context, action)
            if (action == Intent.ACTION_BOOT_COMPLETED) {
                ServiceWatchdog.scheduleRestart(context, BOOT_RETRY_DELAY_MS)
            }
        }
    }

    private fun startNodeService(context: Context, reason: String) {
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, NodeForegroundService::class.java)
            )
            NodeLogger.info("[SERVICE_START_REQUESTED] reason=$reason")
        } catch (error: Exception) {
            NodeLogger.error("Failed to start NodeForegroundService after $reason", error)
            ServiceWatchdog.scheduleRestart(context, BOOT_RETRY_DELAY_MS)
        }
    }

    private companion object {
        private const val BOOT_RETRY_DELAY_MS = 30_000L
    }
}
