package com.example.flowpilot.core

import android.content.Context

data class FlowPilotConfig(
    val backendUrl: String,
    val instruction: String,
    val strictSafetyEnabled: Boolean
)

object FlowPilotSettings {
    private const val PREFS_NAME = "flowpilot_settings"
    private const val KEY_BACKEND_URL = "backend_url"
    private const val KEY_INSTRUCTION = "instruction"
    private const val KEY_STRICT_SAFETY = "strict_safety"

    private const val DEFAULT_BACKEND_URL = "http://10.0.2.2:8080"
    private const val DEFAULT_INSTRUCTION = ""
    private const val DEFAULT_STRICT_SAFETY = true

    fun read(context: Context): FlowPilotConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return FlowPilotConfig(
            backendUrl = prefs.getString(KEY_BACKEND_URL, DEFAULT_BACKEND_URL) ?: DEFAULT_BACKEND_URL,
            instruction = prefs.getString(KEY_INSTRUCTION, DEFAULT_INSTRUCTION) ?: DEFAULT_INSTRUCTION,
            strictSafetyEnabled = prefs.getBoolean(KEY_STRICT_SAFETY, DEFAULT_STRICT_SAFETY)
        )
    }

    fun setBackendUrl(context: Context, backendUrl: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BACKEND_URL, backendUrl.trim())
            .apply()
    }

    fun setInstruction(context: Context, instruction: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_INSTRUCTION, instruction)
            .apply()
    }

    fun setStrictSafetyEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_STRICT_SAFETY, enabled)
            .apply()
    }
}
