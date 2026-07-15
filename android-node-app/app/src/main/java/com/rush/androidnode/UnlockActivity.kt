package com.rush.androidnode

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity

class UnlockActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureWindow()
        requestKeyguardDismiss()
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, FINISH_DELAY_MS)
    }

    private fun configureWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
    }

    private fun requestKeyguardDismiss() {
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: return
        if (!keyguardManager.isKeyguardLocked) {
            NodeLogger.info("[UNLOCK_ACTIVITY] Device already unlocked")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            keyguardManager.requestDismissKeyguard(
                this,
                object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() {
                        NodeLogger.info("[UNLOCK_ACTIVITY] Keyguard dismissed")
                    }

                    override fun onDismissCancelled() {
                        NodeLogger.info("[UNLOCK_ACTIVITY] Keyguard dismiss cancelled")
                    }

                    override fun onDismissError() {
                        NodeLogger.error("Keyguard dismiss failed")
                    }
                }
            )
        }
    }

    private companion object {
        private const val FINISH_DELAY_MS = 1_500L
    }
}
