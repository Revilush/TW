package com.rush.automationtw.node

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class SetupActivity : ComponentActivity() {
    private lateinit var permissionManager: PermissionManager
    private lateinit var statusView: TextView
    private lateinit var actionButton: Button

    private val runtimePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshSetupState()
        }

    private val specialPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshSetupState()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (isSetupCompleted()) {
            launchMainActivity()
            return
        }

        permissionManager = PermissionManager(
            activity = this,
            runtimePermissionLauncher = runtimePermissionLauncher,
            specialPermissionLauncher = specialPermissionLauncher
        )

        setContentView(buildContentView())
        refreshSetupState()
    }

    override fun onResume() {
        super.onResume()
        if (::permissionManager.isInitialized) {
            refreshSetupState()
        }
    }

    private fun buildContentView(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 64)
        }.also { container ->
            val titleView = TextView(this).apply {
                text = "First Launch Setup"
                textSize = 24f
            }

            val stepsView = TextView(this).apply {
                text = buildString {
                    appendLine("Step 1: Grant permissions")
                    appendLine("Step 2: Disable battery optimization")
                    append("Step 3: Set default dialer (recommended)")
                }
            }

            statusView = TextView(this).apply {
                text = "Checking setup requirements..."
            }

            actionButton = Button(this).apply {
                text = "Continue Setup"
                setOnClickListener {
                    advanceSetupFlow()
                }
            }

            listOf(titleView, stepsView, statusView, actionButton).forEach(container::addView)
        }
    }

    private fun refreshSetupState() {
        val permissionState = permissionManager.checkAllPermissions()

        if (permissionState.allGranted) {
            markSetupCompleted()
            startMainService()
            launchMainActivity()
            return
        }

        statusView.text = buildStatusText(permissionState)
        actionButton.text = when {
            permissionState.missingRuntimePermissions.isNotEmpty() -> "Grant Permissions"
            !permissionState.batteryOptimizationDisabled -> "Disable Battery Optimization"
            !permissionState.defaultDialerGranted -> "Set Default Dialer"
            !permissionState.autoStartHandled -> "Open Auto-Start Settings"
            else -> "Continue Setup"
        }
    }

    private fun advanceSetupFlow() {
        val permissionState = permissionManager.checkAllPermissions()

        when {
            permissionState.missingRuntimePermissions.isNotEmpty() -> {
                permissionManager.requestRuntimePermissions()
            }

            !permissionState.batteryOptimizationDisabled -> {
                permissionManager.requestBatteryOptimizationDisable()
            }

            !permissionState.defaultDialerGranted -> {
                permissionManager.requestDefaultDialerRole()
            }

            !permissionState.autoStartHandled -> {
                permissionManager.openAutoStartSettings()
            }
        }
    }

    private fun startMainService() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, MainService::class.java)
        )
    }

    private fun launchMainActivity() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
        )
        finish()
    }

    private fun buildStatusText(state: PermissionManager.PermissionState): String {
        val missingRuntime = if (state.missingRuntimePermissions.isEmpty()) {
            "None"
        } else {
            state.missingRuntimePermissions.joinToString()
        }

        return buildString {
            appendLine("Complete all setup steps to continue.")
            appendLine("Missing runtime permissions: $missingRuntime")
            appendLine("Battery optimization disabled: ${state.batteryOptimizationDisabled}")
            appendLine("Default dialer granted: ${state.defaultDialerGranted}")
            appendLine("Auto-start settings opened: ${state.autoStartHandled}")
            append("Setup is re-checked every time this screen resumes.")
        }
    }

    private fun isSetupCompleted(): Boolean {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(KEY_SETUP_COMPLETED, false)
    }

    private fun markSetupCompleted() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SETUP_COMPLETED, true)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "setup_prefs"
        private const val KEY_SETUP_COMPLETED = "setup_completed"
    }
}
