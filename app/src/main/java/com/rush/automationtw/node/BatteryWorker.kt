package com.rush.automationtw.node

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters

class BatteryWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val webSocketManager = WebSocketManager(
            context = applicationContext,
            onEvent = object : WebSocketManager.Listener {
                override fun onSocketConnected() = Unit
                override fun onSocketDisconnected(reason: String) = Unit
                override fun onCommand(commandId: String, command: org.json.JSONObject) = Unit
            }
        )

        return try {
            val sent = webSocketManager.sendBatteryReportOnce()
            if (sent) {
                Result.success()
            } else {
                Result.retry()
            }
        } catch (error: Exception) {
            Log.e("BatteryWorker", "Battery report failed", error)
            Result.retry()
        } finally {
            webSocketManager.shutdown()
        }
    }
}
