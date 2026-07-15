package com.rush.androidnode

import android.content.Context

class NodePreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL).orEmpty()
        set(value) {
            prefs.edit().putString(KEY_SERVER_URL, value.trim()).apply()
        }

    var phoneNumber: String
        get() = prefs.getString(KEY_PHONE_NUMBER, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_PHONE_NUMBER, value.trim()).apply()
        }

    var planDueDate: String
        get() = prefs.getString(KEY_PLAN_DUE_DATE, DEFAULT_PLAN_DUE_DATE).orEmpty()
        set(value) {
            prefs.edit().putString(KEY_PLAN_DUE_DATE, value.trim()).apply()
        }

    companion object {
        private const val PREFS_NAME = "node_settings_prefs"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_PHONE_NUMBER = "phone_number"
        private const val KEY_PLAN_DUE_DATE = "plan_due_date"
        private const val DEFAULT_SERVER_URL = "ws://127.0.0.1:3000/ws"
        private const val DEFAULT_PLAN_DUE_DATE = "2099-12-31T23:59:59.000Z"
    }
}
