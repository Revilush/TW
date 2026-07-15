package com.rush.androidnode

import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.provider.Settings
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

class DeviceInfoProvider(context: Context) {
    private val appContext = context.applicationContext
    private val nodePreferences = NodePreferences(appContext)

    fun getDeviceId(): String {
        return Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotBlank() }
            ?: "unknown-device"
    }

    fun getBatteryLevel(): Int {
        val batteryManager = appContext.getSystemService(BatteryManager::class.java)
        return batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.coerceIn(0, 100)
            ?: 0
    }

    fun getPhoneNumber(): String {
        val configuredNumber = nodePreferences.phoneNumber
        if (configuredNumber.isNotBlank()) {
            return configuredNumber
        }

        val hasPhonePermission = ContextCompat.checkSelfPermission(
            appContext,
            android.Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            appContext,
            android.Manifest.permission.READ_PHONE_NUMBERS
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPhonePermission) {
            return ""
        }

        val telephonyManager = appContext.getSystemService(TelephonyManager::class.java)
        return runCatching { telephonyManager?.line1Number.orEmpty() }
            .getOrDefault("")
            .trim()
    }

    fun getPlanDueDate(): String = nodePreferences.planDueDate
}
