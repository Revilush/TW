package com.rush.androidnode

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

class BatteryOptimizationHelper(private val activity: Activity) {

    fun isIgnoringBatteryOptimizations(): Boolean {
        val powerManager = activity.getSystemService(PowerManager::class.java) ?: return false
        return powerManager.isIgnoringBatteryOptimizations(activity.packageName)
    }

    fun openBatteryOptimizationSettings() {
        if (isIgnoringBatteryOptimizations()) {
            return
        }

        val requestIntent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${activity.packageName}")
        )

        try {
            activity.startActivity(requestIntent)
        } catch (_: ActivityNotFoundException) {
            activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}
