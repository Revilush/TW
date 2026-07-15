package com.rush.automationtw.node

import android.content.Context
import android.os.BatteryManager
import android.provider.Settings
import android.telephony.TelephonyManager

class DeviceProfileProvider(private val context: Context) {
    private val prefs = NodePreferences(context)

    fun getDeviceId(): String {
        return Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ).orEmpty()
    }

    fun getPhoneNumber(): String {
        val configured = prefs.phoneNumber
        if (configured.isNotBlank()) {
            return configured
        }

        val telephonyManager = context.getSystemService(TelephonyManager::class.java)
        val candidates = listOf(
            runCatching { telephonyManager?.line1Number }.getOrNull(),
            runCatching { telephonyManager?.subscriberId }.getOrNull()
        )

        return candidates.firstOrNull { !it.isNullOrBlank() }.orEmpty()
    }

    fun getPlanDueDate(): String = prefs.planDueDate

    fun getBatteryLevel(): Int {
        val batteryManager = context.getSystemService(BatteryManager::class.java)
        return batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0
    }
}
