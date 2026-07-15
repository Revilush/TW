package com.rush.androidnode

import android.util.Log

object NodeLogger {
    private const val TAG = "AndroidNodeApp"

    fun connected(message: String) {
        Log.i(TAG, "[CONNECTED] $message")
    }

    fun reconnected(message: String) {
        Log.i(TAG, "[RECONNECTED] $message")
    }

    fun disconnected(message: String) {
        Log.w(TAG, "[DISCONNECTED] $message")
    }

    fun commandReceived(message: String) {
        Log.i(TAG, "[COMMAND_RECEIVED] $message")
    }

    fun commandExecuted(message: String) {
        Log.i(TAG, "[COMMAND_EXECUTED] $message")
    }

    fun error(message: String, throwable: Throwable? = null) {
        Log.e(TAG, "[ERROR] $message", throwable)
    }

    fun info(message: String) {
        Log.i(TAG, message)
    }
}
