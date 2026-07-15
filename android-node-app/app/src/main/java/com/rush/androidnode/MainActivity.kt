package com.rush.androidnode

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private lateinit var permissionHelper: PermissionHelper
    private lateinit var batteryOptimizationHelper: BatteryOptimizationHelper
    private lateinit var nodePreferences: NodePreferences
    private lateinit var nodeStateStore: NodeStateStore

    private lateinit var statusText: TextView
    private lateinit var serverUrlInput: EditText
    private lateinit var phoneInput: EditText
    private lateinit var planDateInput: EditText
    private lateinit var serviceStateText: TextView

    private var runtimePermissionRequested = false
    private var batteryPromptRequested = false

    private val runtimePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshUi()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionHelper = PermissionHelper(this)
        batteryOptimizationHelper = BatteryOptimizationHelper(this)
        nodePreferences = NodePreferences(this)
        nodeStateStore = NodeStateStore(this)
        setContentView(buildContentView())
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
        maybePromptForPermissions()
        maybePromptBatteryOptimization()
        maybeStartService()
    }

    private fun buildContentView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 72, 48, 48)
        }

        root.addView(TextView(this).apply {
            text = "Android Node Setup"
            textSize = 24f
        })

        statusText = TextView(this).apply {
            textSize = 15f
            setPadding(0, 32, 0, 24)
            setTextIsSelectable(true)
        }
        root.addView(statusText)

        serverUrlInput = EditText(this).apply {
            hint = "WebSocket URL"
            setText(nodePreferences.serverUrl)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        root.addView(serverUrlInput)

        phoneInput = EditText(this).apply {
            hint = "Phone number"
            setText(nodePreferences.phoneNumber)
            inputType = InputType.TYPE_CLASS_PHONE
        }
        root.addView(phoneInput)

        planDateInput = EditText(this).apply {
            hint = "Plan due date ISO string"
            setText(nodePreferences.planDueDate)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        root.addView(planDateInput)

        val saveButton = Button(this).apply {
            text = "Save Settings + Start Service"
            setOnClickListener {
                saveSettings()
                refreshUi()
                maybePromptForPermissions()
                maybePromptBatteryOptimization()
                maybeStartService()
            }
        }
        root.addView(saveButton)

        val permissionButton = Button(this).apply {
            text = "Grant Runtime Permissions"
            setOnClickListener {
                runtimePermissionRequested = true
                permissionHelper.requestRuntimePermissions(runtimePermissionLauncher)
            }
        }
        root.addView(permissionButton)

        val batteryButton = Button(this).apply {
            text = "Disable Battery Optimization"
            setOnClickListener {
                batteryPromptRequested = true
                batteryOptimizationHelper.openBatteryOptimizationSettings()
            }
        }
        root.addView(batteryButton)

        val overlayButton = Button(this).apply {
            text = "Grant Overlay Permission (Optional)"
            setOnClickListener {
                permissionHelper.openOverlaySettings()
            }
        }
        root.addView(overlayButton)

        serviceStateText = TextView(this).apply {
            textSize = 14f
            setPadding(0, 28, 0, 0)
            setTextIsSelectable(true)
        }
        root.addView(serviceStateText)

        return ScrollView(this).apply {
            addView(root)
        }
    }

    private fun refreshUi() {
        val missingPermissions = permissionHelper.missingRuntimePermissions()
        val batteryIgnored = batteryOptimizationHelper.isIgnoringBatteryOptimizations()
        val overlayGranted = permissionHelper.hasOverlayPermission()
        val runtimeState = nodeStateStore.snapshot()

        statusText.text = buildString {
            appendLine("Server URL: ${nodePreferences.serverUrl}")
            appendLine("Runtime permissions: ${if (missingPermissions.isEmpty()) "GRANTED" else "MISSING"}")
            if (missingPermissions.isNotEmpty()) {
                appendLine(missingPermissions.joinToString(prefix = "Missing: "))
            }
            appendLine("Battery optimization disabled: ${if (batteryIgnored) "YES" else "NO"}")
            appendLine("Overlay permission: ${if (overlayGranted) "GRANTED" else "OPTIONAL / NOT GRANTED"}")
        }

        serviceStateText.text = buildString {
            appendLine("Service running: ${NodeForegroundService.isRunning}")
            appendLine("current_task=${runtimeState.currentTask}")
            appendLine("last_command=${runtimeState.lastCommand}")
            appendLine("last_success_time=${runtimeState.lastSuccessTime}")
            appendLine("error_count=${runtimeState.errorCount}")
        }
    }

    private fun saveSettings() {
        nodePreferences.serverUrl = serverUrlInput.text?.toString().orEmpty()
        nodePreferences.phoneNumber = phoneInput.text?.toString().orEmpty()
        nodePreferences.planDueDate = planDateInput.text?.toString().orEmpty()
    }

    private fun maybePromptForPermissions() {
        if (!runtimePermissionRequested && !permissionHelper.hasMandatoryPermissions()) {
            runtimePermissionRequested = true
            permissionHelper.requestRuntimePermissions(runtimePermissionLauncher)
        }
    }

    private fun maybePromptBatteryOptimization() {
        if (
            permissionHelper.hasMandatoryPermissions() &&
            !batteryPromptRequested &&
            !batteryOptimizationHelper.isIgnoringBatteryOptimizations()
        ) {
            batteryPromptRequested = true
            batteryOptimizationHelper.openBatteryOptimizationSettings()
        }
    }

    private fun maybeStartService() {
        if (
            permissionHelper.hasMandatoryPermissions() &&
            batteryOptimizationHelper.isIgnoringBatteryOptimizations()
        ) {
            runCatching {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, NodeForegroundService::class.java)
                )
            }.onFailure { error ->
                NodeLogger.error("Unable to start NodeForegroundService from MainActivity", error)
            }
        }
    }
}
