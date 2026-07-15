package com.rush.automationtw.node

import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject

class CommandHandler(private val context: Context) {

    fun handle(commandPayload: JSONObject): CommandResult {
        val type = commandPayload.optString("type").uppercase()

        return when (type) {
            "CALL" -> startCall(commandPayload)
            "END_CALL" -> endCall()
            "SMS" -> sendSms(commandPayload)
            "RING" -> playRingtone()
            "YT" -> openYoutube(commandPayload)
            else -> CommandResult.failure("Unsupported command type: $type")
        }
    }

    private fun startCall(commandPayload: JSONObject): CommandResult {
        val phoneNumber = commandPayload.optString("phone_number")
        if (phoneNumber.isBlank()) {
            return CommandResult.failure("phone_number is required for CALL")
        }

        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phoneNumber")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ContextCompat.startActivity(context, intent, null)
        return CommandResult.success("Call started")
    }

    private fun endCall(): CommandResult {
        return try {
            val telecomManager = context.getSystemService(TelecomManager::class.java)
            if (telecomManager?.endCall() == true) {
                CommandResult.success("Call ended")
            } else {
                CommandResult.failure("Unable to end call on this device")
            }
        } catch (error: SecurityException) {
            CommandResult.failure("END_CALL requires privileged permission: ${error.message}")
        }
    }

    private fun sendSms(commandPayload: JSONObject): CommandResult {
        val phoneNumber = commandPayload.optString("phone_number")
        val message = commandPayload.optString("message")
        if (phoneNumber.isBlank() || message.isBlank()) {
            return CommandResult.failure("phone_number and message are required for SMS")
        }

        return try {
            SmsManager.getDefault().sendTextMessage(phoneNumber, null, message, null, null)
            CommandResult.success("SMS sent")
        } catch (error: SecurityException) {
            CommandResult.failure("SEND_SMS permission error: ${error.message}")
        } catch (error: IllegalStateException) {
            CommandResult.failure("SMS manager unavailable: ${error.message}")
        } catch (error: Exception) {
            CommandResult.failure("Unable to send SMS: ${error.message}")
        }
    }

    private fun playRingtone(): CommandResult {
        return try {
            val ringtone = RingtoneManager.getRingtone(
                context,
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ) ?: return CommandResult.failure("No ringtone available")

            ringtone.play()
            CommandResult.success("Ringtone started")
        } catch (error: SecurityException) {
            CommandResult.failure("Ringtone permission error: ${error.message}")
        } catch (error: IllegalStateException) {
            CommandResult.failure("Ringtone playback failed: ${error.message}")
        } catch (error: Exception) {
            CommandResult.failure("Unable to play ringtone: ${error.message}")
        }
    }

    private fun openYoutube(commandPayload: JSONObject): CommandResult {
        val url = commandPayload.optString("url")
        if (url.isBlank()) {
            return CommandResult.failure("url is required for YT")
        }

        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ContextCompat.startActivity(context, intent, null)
            CommandResult.success("YouTube link opened")
        } catch (error: SecurityException) {
            CommandResult.failure("Unable to open YouTube due to permission error: ${error.message}")
        } catch (error: IllegalStateException) {
            CommandResult.failure("Unable to open YouTube: ${error.message}")
        } catch (error: Exception) {
            CommandResult.failure("Unable to open YouTube: ${error.message}")
        }
    }

    data class CommandResult(
        val status: String,
        val message: String
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("status", status)
            .put("result", JSONObject().put("message", message))

        companion object {
            fun success(message: String): CommandResult = CommandResult("SUCCESS", message)
            fun failure(message: String): CommandResult {
                Log.e("CommandHandler", message)
                return CommandResult("FAILED", message)
            }
        }
    }
}
