package com.rush.automationtw.node

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher

class BatteryOptimizationHelper(
    private val activity: Activity,
    private val specialPermissionLauncher: ActivityResultLauncher<Intent>
) {
    fun isBatteryOptimizationDisabled(): Boolean {
        val powerManager = activity.getSystemService(PowerManager::class.java) ?: return false
        return powerManager.isIgnoringBatteryOptimizations(activity.packageName)
    }

    fun requestDisableBatteryOptimization() {
        if (isBatteryOptimizationDisabled()) {
            return
        }

        val packageUri = Uri.parse("package:${activity.packageName}")
        val requestIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri)
        val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        val intentToLaunch = when {
            requestIntent.resolveActivity(activity.packageManager) != null -> requestIntent
            fallbackIntent.resolveActivity(activity.packageManager) != null -> fallbackIntent
            else -> null
        } ?: throw ActivityNotFoundException("No battery optimization settings activity available")

        specialPermissionLauncher.launch(intentToLaunch)
    }
}
