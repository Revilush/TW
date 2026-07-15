package com.rush.automationtw.node

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private lateinit var prefs: NodePreferences
    private lateinit var statusView: TextView
    private lateinit var serverUrlInput: EditText
    private lateinit var phoneNumberInput: EditText
    private lateinit var planDueDateInput: EditText
    private lateinit var saveButton: Button
    private lateinit var stopButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = NodePreferences(this)

        if (!isSetupCompleted()) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        setContentView(buildContentView())
    }

    private fun buildContentView(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 64)
        }.also { container ->
            val titleView = TextView(this).apply {
                text = "Persistent node service configuration"
                textSize = 22f
            }

            statusView = TextView(this).apply {
                text = "Setup completed. Configure the node and keep the service running."
            }

            serverUrlInput = EditText(this).apply {
                hint = "WebSocket URL"
                setText(prefs.serverUrl)
            }

            phoneNumberInput = EditText(this).apply {
                hint = "Phone number"
                inputType = InputType.TYPE_CLASS_PHONE
                setText(prefs.phoneNumber)
            }

            planDueDateInput = EditText(this).apply {
                hint = "Plan due date (ISO-8601)"
                setText(prefs.planDueDate)
            }

            saveButton = Button(this).apply {
                text = "Save + Start Service"
                setOnClickListener {
                    prefs.serverUrl = serverUrlInput.text.toString()
                    prefs.phoneNumber = phoneNumberInput.text.toString()
                    prefs.planDueDate = planDueDateInput.text.toString()
                    startNodeService()
                    statusView.text = "Foreground service started."
                }
            }

            stopButton = Button(this).apply {
                text = "Stop Service"
                setOnClickListener {
                    stopService(Intent(this@MainActivity, MainService::class.java))
                    statusView.text = "Foreground service stopped."
                }
            }

            listOf(
                titleView,
                statusView,
                serverUrlInput,
                phoneNumberInput,
                planDueDateInput,
                saveButton,
                stopButton
            ).forEach(container::addView)
        }
    }

    private fun startNodeService() {
        val intent = Intent(this, MainService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun isSetupCompleted(): Boolean {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(KEY_SETUP_COMPLETED, false)
    }

    companion object {
        private const val PREFS_NAME = "setup_prefs"
        private const val KEY_SETUP_COMPLETED = "setup_completed"
    }
}
