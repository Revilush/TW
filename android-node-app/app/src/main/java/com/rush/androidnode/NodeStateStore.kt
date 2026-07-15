package com.rush.androidnode

import android.content.Context

class NodeStateStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun updateTask(task: String, commandType: String) {
        prefs.edit()
            .putString(KEY_CURRENT_TASK, task)
            .putString(KEY_LAST_COMMAND, commandType)
            .apply()
    }

    @Synchronized
    fun markSuccess() {
        prefs.edit()
            .putLong(KEY_LAST_SUCCESS_TIME, System.currentTimeMillis())
            .putString(KEY_CURRENT_TASK, "IDLE")
            .apply()
    }

    @Synchronized
    fun markError() {
        prefs.edit()
            .putInt(KEY_ERROR_COUNT, prefs.getInt(KEY_ERROR_COUNT, 0) + 1)
            .putString(KEY_CURRENT_TASK, "ERROR")
            .apply()
    }

    @Synchronized
    fun snapshot(): RuntimeState {
        return RuntimeState(
            currentTask = prefs.getString(KEY_CURRENT_TASK, "IDLE").orEmpty(),
            lastCommand = prefs.getString(KEY_LAST_COMMAND, "NONE").orEmpty(),
            lastSuccessTime = prefs.getLong(KEY_LAST_SUCCESS_TIME, 0L),
            errorCount = prefs.getInt(KEY_ERROR_COUNT, 0)
        )
    }

    data class RuntimeState(
        val currentTask: String,
        val lastCommand: String,
        val lastSuccessTime: Long,
        val errorCount: Int
    )

    private companion object {
        private const val PREFS_NAME = "node_state_prefs"
        private const val KEY_CURRENT_TASK = "current_task"
        private const val KEY_LAST_COMMAND = "last_command"
        private const val KEY_LAST_SUCCESS_TIME = "last_success_time"
        private const val KEY_ERROR_COUNT = "error_count"
    }
}
