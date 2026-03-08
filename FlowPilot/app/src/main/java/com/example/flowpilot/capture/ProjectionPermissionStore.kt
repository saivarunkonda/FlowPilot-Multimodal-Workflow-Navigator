package com.example.flowpilot.capture

import android.content.Intent

object ProjectionPermissionStore {
    private val lock = Any()

    private var resultCode: Int? = null
    private var resultData: Intent? = null

    fun set(resultCode: Int, data: Intent) {
        synchronized(lock) {
            this.resultCode = resultCode
            this.resultData = Intent(data)
        }
    }

    fun consume(): Pair<Int, Intent>? {
        synchronized(lock) {
            val code = resultCode ?: return null
            val data = resultData ?: return null

            resultCode = null
            resultData = null

            return code to Intent(data)
        }
    }

    fun hasToken(): Boolean {
        synchronized(lock) {
            return resultCode != null && resultData != null
        }
    }
}
