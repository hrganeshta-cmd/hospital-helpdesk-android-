package `in`.ganpatrai.helpdesk

import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal client for the hospital help desk server (server/app.py).
 * Uses only built-in classes, no extra libraries.
 * Every function here is blocking — call it from a background thread.
 */
class ApiClient(private val baseUrl: String) {

    data class StartResult(val sessionId: String, val greeting: String)
    data class ChatResult(val sessionId: String, val reply: String)

    fun startSession(): StartResult {
        val json = post("/api/session/start", JSONObject())
        return StartResult(
            sessionId = json.getString("session_id"),
            greeting = json.getString("greeting"),
        )
    }

    fun chat(sessionId: String, message: String): ChatResult {
        val body = JSONObject()
            .put("session_id", sessionId)
            .put("message", message)
        val json = post("/api/chat", body)
        return ChatResult(
            sessionId = json.getString("session_id"),
            reply = json.getString("reply"),
        )
    }

    fun endSession(sessionId: String) {
        try {
            post("/api/session/end", JSONObject().put("session_id", sessionId))
        } catch (_: Exception) {
            // Ending a session is best-effort; ignore failures.
        }
    }

    private fun post(path: String, body: JSONObject): JSONObject {
        val url = URL(baseUrl.trimEnd('/') + path)
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 60_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val stream = if (conn.responseCode in 200..299)
                conn.inputStream else conn.errorStream
            val text = stream.bufferedReader().use(BufferedReader::readText)
            if (conn.responseCode !in 200..299) {
                throw RuntimeException("Server error ${conn.responseCode}: $text")
            }
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }
}
