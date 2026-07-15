package com.rush.androidnode

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.telecom.TelecomManager
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.Timer
import java.util.TimerTask

class CommandHandler(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val nodeStateStore = NodeStateStore(appContext)
    private val deviceWakeHelper = DeviceWakeHelper(appContext)

    private var ringPlayer: MediaPlayer? = null
    private var ringTimer: Timer? = null
    private var screenWakeLock: PowerManager.WakeLock? = null
    private var vibrator: Vibrator? = null

    @Synchronized
    fun handle(commandPayload: JSONObject): CommandResult {
        val commandType = commandPayload.optString("type").uppercase().ifBlank { "UNKNOWN" }
        nodeStateStore.updateTask(commandType, commandType)
        NodeLogger.commandReceived(commandPayload.toString())

        val result = when (commandType) {
            "CALL" -> startCall(commandPayload)
            "END_CALL" -> endCall()
            "SMS" -> sendSms(commandPayload)
            "YT", "YOUTUBE" -> openYouTube(commandPayload)
            "RING" -> startRinging(commandPayload)
            "STOP_RING", "END_RING" -> stopRinging("Stopped by remote command")
            else -> CommandResult.failure("Unsupported command: $commandType")
        }

        if (result.isSuccess()) {
            nodeStateStore.markSuccess()
            NodeLogger.commandExecuted("$commandType -> ${result.message}")
        } else {
            nodeStateStore.markError()
            NodeLogger.error("$commandType failed: ${result.message}")
        }

        return result
    }

    private fun startCall(commandPayload: JSONObject): CommandResult {
        val phoneNumber = resolvePhoneNumber(commandPayload)
        if (phoneNumber.isBlank()) {
            return CommandResult.failure("CALL requires phone_number")
        }

        return try {
            deviceWakeHelper.prepareDevice("CALL")
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phoneNumber")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ContextCompat.startActivity(appContext, intent, null)
            CommandResult.success("Dialing $phoneNumber")
        } catch (error: SecurityException) {
            CommandResult.failure("CALL_PHONE denied: ${error.message}")
        } catch (error: Exception) {
            CommandResult.failure("Unable to start call: ${error.message}")
        }
    }

    private fun endCall(): CommandResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return CommandResult.failure("END_CALL requires Android 9 or newer")
        }

        return try {
            val telecomManager = appContext.getSystemService(TelecomManager::class.java)
            if (telecomManager?.endCall() == true) {
                CommandResult.success("Call ended")
            } else {
                CommandResult.failure("END_CALL is not available on this device")
            }
        } catch (error: SecurityException) {
            CommandResult.failure("Unable to end call: ${error.message}")
        } catch (error: Exception) {
            CommandResult.failure("Unable to end call: ${error.message}")
        }
    }

    private fun sendSms(commandPayload: JSONObject): CommandResult {
        val phoneNumber = resolvePhoneNumber(commandPayload)
        val message = commandPayload.optString("message").trim()
        if (phoneNumber.isBlank() || message.isBlank()) {
            return CommandResult.failure("SMS requires phone_number and message")
        }

        return try {
            deviceWakeHelper.prepareDevice("SMS")
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                appContext.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            smsManager.sendTextMessage(phoneNumber, null, message, null, null)
            CommandResult.success("SMS sent to $phoneNumber")
        } catch (error: SecurityException) {
            CommandResult.failure("SEND_SMS denied: ${error.message}")
        } catch (error: Exception) {
            CommandResult.failure("Unable to send SMS: ${error.message}")
        }
    }

    private fun openYouTube(commandPayload: JSONObject): CommandResult {
        val url = commandPayload.optString("url").trim()
        if (url.isBlank()) {
            return CommandResult.failure("YT requires url")
        }

        return try {
            deviceWakeHelper.prepareDevice("YT")
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ContextCompat.startActivity(appContext, intent, null)
            CommandResult.success("Opened $url")
        } catch (error: Exception) {
            CommandResult.failure("Unable to open YouTube URL: ${error.message}")
        }
    }

    @Synchronized
    private fun startRinging(commandPayload: JSONObject): CommandResult {
        val durationMs = commandPayload.optLong("duration", DEFAULT_RING_DURATION_MS)
            .takeIf { it > 0 }
            ?: DEFAULT_RING_DURATION_MS

        return try {
            stopRingingInternal()
            deviceWakeHelper.prepareDevice("RING")
            wakeScreen(durationMs)
            maximizeAlarmVolume()
            startVibration()

            val ringUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: return CommandResult.failure("No ringtone URI available")

            ringPlayer = MediaPlayer().apply {
                setDataSource(appContext, ringUri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }

            ringTimer = Timer("ring-stop", true).apply {
                schedule(object : TimerTask() {
                    override fun run() {
                        stopRinging("Ring timeout reached")
                    }
                }, durationMs)
            }

            CommandResult.success("Ringing for ${durationMs}ms")
        } catch (error: Exception) {
            stopRingingInternal()
            CommandResult.failure("Unable to ring device: ${error.message}")
        }
    }

    @Synchronized
    fun stopRinging(message: String = "Ring stopped"): CommandResult {
        stopRingingInternal()
        return CommandResult.success(message)
    }

    @Synchronized
    fun release() {
        stopRingingInternal()
    }

    private fun resolvePhoneNumber(commandPayload: JSONObject): String {
        return listOf(
            commandPayload.optString("phone_number"),
            commandPayload.optString("number"),
            commandPayload.optString("target_number")
        ).firstOrNull { it.isNotBlank() }.orEmpty().trim()
    }

    private fun maximizeAlarmVolume() {
        val manager = audioManager ?: return
        runCatching {
            manager.ringerMode = AudioManager.RINGER_MODE_NORMAL
            val maxVolume = manager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            manager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)
            val maxRingVolume = manager.getStreamMaxVolume(AudioManager.STREAM_RING)
            manager.setStreamVolume(AudioManager.STREAM_RING, maxRingVolume, 0)
        }.onFailure { error ->
            NodeLogger.error("Unable to maximize alarm volume", error)
        }
    }

    private fun startVibration() {
        val activeVibrator = resolveVibrator() ?: return
        vibrator = activeVibrator
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activeVibrator.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 600, 300, 600), 0)
                )
            } else {
                @Suppress("DEPRECATION")
                activeVibrator.vibrate(longArrayOf(0, 600, 300, 600), 0)
            }
            NodeLogger.info("[RING_ALERT] vibration started")
        }.onFailure { error ->
            NodeLogger.error("Unable to start vibration", error)
        }
    }

    private fun resolveVibrator(): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            appContext.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    @Suppress("DEPRECATION")
    private fun wakeScreen(timeoutMs: Long) {
        val powerManager = appContext.getSystemService(PowerManager::class.java) ?: return
        screenWakeLock?.let { lock ->
            if (lock.isHeld) {
                lock.release()
            }
        }
        screenWakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "AndroidNodeApp:RingWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(timeoutMs.coerceAtMost(MAX_SCREEN_WAKE_MS))
        }
    }

    private fun stopRingingInternal() {
        ringTimer?.cancel()
        ringTimer = null

        ringPlayer?.let { player ->
            runCatching {
                if (player.isPlaying) {
                    player.stop()
                }
            }
            player.release()
        }
        ringPlayer = null

        screenWakeLock?.let { lock ->
            if (lock.isHeld) {
                runCatching { lock.release() }
            }
        }
        screenWakeLock = null

        vibrator?.let { activeVibrator ->
            runCatching { activeVibrator.cancel() }
        }
        vibrator = null
    }

    data class CommandResult(
        val status: String,
        val message: String
    ) {
        fun isSuccess(): Boolean = status == STATUS_SUCCESS

        companion object {
            private const val STATUS_SUCCESS = "SUCCESS"
            private const val STATUS_FAILED = "FAILED"

            fun success(message: String): CommandResult = CommandResult(STATUS_SUCCESS, message)
            fun failure(message: String): CommandResult = CommandResult(STATUS_FAILED, message)
        }
    }

    private companion object {
        private const val DEFAULT_RING_DURATION_MS = 60_000L
        private const val MAX_SCREEN_WAKE_MS = 60_000L
    }
}
