package com.rush.androidnode

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager

class DeviceWakeHelper(context: Context) {
    private val appContext = context.applicationContext

    fun prepareDevice(actionLabel: String, waitAfterLaunchMs: Long = DEFAULT_WAIT_MS) {
        val powerManager = appContext.getSystemService(PowerManager::class.java)
        val keyguardManager = appContext.getSystemService(KeyguardManager::class.java)
        val interactive = powerManager?.isInteractive == true
        val locked = keyguardManager?.isKeyguardLocked == true

        NodeLogger.info("[DEVICE_PREP] action=$actionLabel interactive=$interactive locked=$locked")

        if (!interactive || locked) {
            val unlockIntent = Intent(appContext, UnlockActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )
            }
            runCatching {
                appContext.startActivity(unlockIntent)
                NodeLogger.info("[DEVICE_PREP] unlock activity launched for $actionLabel")
            }.onFailure { error ->
                NodeLogger.error("Unable to launch unlock activity for $actionLabel", error)
            }
        }

        if (!interactive || locked) {
            Thread.sleep(waitAfterLaunchMs)
        } else {
            Thread.sleep(SHORT_WAIT_MS)
        }

        val stillLocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            keyguardManager?.isDeviceLocked == true
        } else {
            keyguardManager?.isKeyguardLocked == true
        }
        NodeLogger.info("[DEVICE_PREP] action=$actionLabel lock_after=$stillLocked")
    }

    private companion object {
        private const val DEFAULT_WAIT_MS = 1_500L
        private const val SHORT_WAIT_MS = 250L
    }
}
