package com.example.flowpilot.network

import com.example.flowpilot.model.PlanResponse
import com.example.flowpilot.model.PlanStep
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class PlannerClient {
    fun plan(
        backendUrl: String,
        userInstruction: String,
        screenshotDataUrl: String,
        currentUrl: String
    ): PlanResponse {
        val normalizedUrl = backendUrl.trim().trimEnd('/')
        val endpoint = "$normalizedUrl/api/workflows/plan"

        val payload = JSONObject()
            .put("userInstruction", userInstruction)
            .put("screenshotDataUrl", screenshotDataUrl)
            .put("currentUrl", currentUrl)

        val conn = URL(endpoint).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")

        OutputStreamWriter(conn.outputStream).use { writer ->
            writer.write(payload.toString())
        }

        val code = conn.responseCode
        val body = readBody(conn)
        if (code !in 200..299) {
            throw IllegalStateException("Planner API failed ($code): $body")
        }

        val json = JSONObject(body)
        return parsePlan(json)
    }

    private fun parsePlan(json: JSONObject): PlanResponse {
        val stepsArray = json.optJSONArray("steps") ?: JSONArray()
        val steps = mutableListOf<PlanStep>()

        for (i in 0 until stepsArray.length()) {
            val item = stepsArray.optJSONObject(i) ?: continue
            steps += PlanStep(
                action = item.optString("action"),
                selector = item.optNullableString("selector"),
                text = item.optNullableString("text"),
                key = item.optNullableString("key"),
                url = item.optNullableString("url"),
                targetSelector = item.optNullableString("targetSelector"),
                durationMs = item.optNullableLong("durationMs"),
                x = item.optNullableFloat("x"),
                y = item.optNullableFloat("y")
            )
        }

        return PlanResponse(
            workflowId = json.optString("workflowId"),
            summary = json.optString("summary", "Generated plan"),
            confidence = json.optDouble("confidence", 0.0),
            needsConfirmation = json.optBoolean("needsConfirmation", false),
            clarifyingQuestion = json.optNullableString("clarifyingQuestion"),
            steps = steps
        )
    }

    private fun readBody(conn: HttpURLConnection): String {
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        if (stream == null) return ""

        return stream.bufferedReader().use(BufferedReader::readText)
    }
}

private fun JSONObject.optNullableString(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val value = optString(key).trim()
    return value.ifEmpty { null }
}

private fun JSONObject.optNullableLong(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    return try {
        optLong(key)
    } catch (_: Exception) {
        null
    }
}

private fun JSONObject.optNullableFloat(key: String): Float? {
    if (!has(key) || isNull(key)) return null
    return try {
        optDouble(key).toFloat()
    } catch (_: Exception) {
        null
    }
}
