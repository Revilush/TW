package com.rush.automationtw.node

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.telecom.TelecomManager
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat

class PermissionManager(
    private val activity: Activity,
    private val runtimePermissionLauncher: ActivityResultLauncher<Array<String>>,
    private val specialPermissionLauncher: ActivityResultLauncher<Intent>
) {
    private val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val batteryOptimizationHelper = BatteryOptimizationHelper(
        activity = activity,
        specialPermissionLauncher = specialPermissionLauncher
    )

    fun checkAllPermissions(): PermissionState {
        val runtimeMissing = getRuntimePermissions().filterNot(::isPermissionGranted)

        val batteryOptimizationDisabled = batteryOptimizationHelper.isBatteryOptimizationDisabled()
        val defaultDialerGranted = isDefaultDialerGranted()
        val autoStartHandled = prefs.getBoolean(KEY_AUTOSTART_PROMPTED, false)

        val allGranted = runtimeMissing.isEmpty() &&
            batteryOptimizationDisabled &&
            defaultDialerGranted &&
            autoStartHandled

        return PermissionState(
            missingRuntimePermissions = runtimeMissing,
            batteryOptimizationDisabled = batteryOptimizationDisabled,
            defaultDialerGranted = defaultDialerGranted,
            autoStartHandled = autoStartHandled,
            allGranted = allGranted
        )
    }

    fun requestRuntimePermissions() {
        val missingRuntime = getRuntimePermissions().filterNot(::isPermissionGranted)
        if (missingRuntime.isNotEmpty()) {
            runtimePermissionLauncher.launch(missingRuntime.toTypedArray())
        }
    }

    fun requestBatteryOptimizationDisable() {
        batteryOptimizationHelper.requestDisableBatteryOptimization()
    }

    fun requestDefaultDialerRole() {
        if (isDefaultDialerGranted()) {
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = activity.getSystemService(RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                specialPermissionLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER))
                return
            }
        }

        val telecomManager = activity.getSystemService(TelecomManager::class.java)
        if (telecomManager != null) {
            val intent = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
                putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, activity.packageName)
            }
            launchSpecialIntent(intent, Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            return
        }

        specialPermissionLauncher.launch(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
    }

    fun openAutoStartSettings() {
        val intents = buildAutoStartIntents(activity.packageName)
        val fallbackIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${activity.packageName}")
        }
        val intentToLaunch = intents.firstOrNull()
            ?: fallbackIntent.takeIf { it.resolveActivity(activity.packageManager) != null }
            ?: throw ActivityNotFoundException("No auto-start settings activity available")

        specialPermissionLauncher.launch(intentToLaunch)

        prefs.edit().putBoolean(KEY_AUTOSTART_PROMPTED, true).apply()
    }

    private fun launchSpecialIntent(primary: Intent, fallback: Intent) {
        val intentToLaunch = when {
            primary.resolveActivity(activity.packageManager) != null -> primary
            fallback.resolveActivity(activity.packageManager) != null -> fallback
            else -> throw ActivityNotFoundException("No matching activity for special permission intent")
        }

        specialPermissionLauncher.launch(intentToLaunch)
    }

    private fun isPermissionGranted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun isDefaultDialerGranted(): Boolean {
        val telecomManager = activity.getSystemService(TelecomManager::class.java) ?: return false
        return telecomManager.defaultDialerPackage == activity.packageName
    }

    private fun getRuntimePermissions(): List<String> {
        return buildList {
            add(Manifest.permission.CALL_PHONE)
            add(Manifest.permission.SEND_SMS)
            add(Manifest.permission.READ_PHONE_STATE)
            add(Manifest.permission.READ_PHONE_NUMBERS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun buildAutoStartIntents(packageName: String): List<Intent> {
        return listOf(
            Intent().apply {
                component = android.content.ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            },
            Intent().apply {
                component = android.content.ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                )
            },
            Intent().apply {
                component = android.content.ComponentName(
                    "com.oppo.safe",
                    "com.oppo.safe.permission.startup.StartupAppListActivity"
                )
            },
            Intent().apply {
                component = android.content.ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                )
            },
            Intent().apply {
                component = android.content.ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                )
            },
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
        ).filter { intent ->
            intent.resolveActivity(activity.packageManager) != null
        }
    }

    data class PermissionState(
        val missingRuntimePermissions: List<String>,
        val batteryOptimizationDisabled: Boolean,
        val defaultDialerGranted: Boolean,
        val autoStartHandled: Boolean,
        val allGranted: Boolean
    )

    companion object {
        private const val PREFS_NAME = "permission_prefs"
        private const val KEY_AUTOSTART_PROMPTED = "autostart_prompted"
    }
}
