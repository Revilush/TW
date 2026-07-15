package com.rush.androidnode

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat

class PermissionHelper(private val activity: Activity) {

    fun missingRuntimePermissions(): List<String> {
        return requiredRuntimePermissions().filter { permission ->
            ContextCompat.checkSelfPermission(activity, permission) != PackageManager.PERMISSION_GRANTED
        }
    }

    fun hasMandatoryPermissions(): Boolean {
        return missingRuntimePermissions().isEmpty()
    }

    fun requestRuntimePermissions(launcher: ActivityResultLauncher<Array<String>>) {
        val missing = missingRuntimePermissions()
        if (missing.isNotEmpty()) {
            launcher.launch(missing.toTypedArray())
        }
    }

    fun hasOverlayPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(activity)
    }

    fun openOverlaySettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || hasOverlayPermission()) {
            return
        }

        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${activity.packageName}")
        )
        activity.startActivity(intent)
    }

    private fun requiredRuntimePermissions(): List<String> {
        return buildList {
            add(Manifest.permission.CALL_PHONE)
            add(Manifest.permission.ANSWER_PHONE_CALLS)
            add(Manifest.permission.READ_PHONE_STATE)
            add(Manifest.permission.SEND_SMS)
            add(Manifest.permission.RECEIVE_SMS)
            add(Manifest.permission.READ_SMS)
            add(Manifest.permission.READ_PHONE_NUMBERS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
