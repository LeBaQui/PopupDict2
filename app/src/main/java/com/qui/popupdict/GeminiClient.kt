package com.qui.popupdict

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class ModelInfo(val id: String, val displayName: String)

// Giữ tên object GeminiClient để FloatingService.kt / MainActivity.kt không phải đổi chỗ gọi.
// Bên trong giờ gọi Groq (OpenAI-compatible): https://api.groq.com/openai/v1
object GeminiClient {

    private const val BASE = "https://api.groq.com/openai/v1"

    // Lấy thông báo lỗi từ phản hồi của API (Groq trả {"error":{"message":...}})
    private fun errorMessage(conn: HttpURLConnection, code: Int): String {
        val err = try {
            (conn.errorStream ?: conn.inputStream).bufferedReader().readText()
        } catch (_: Exception) {
            ""
        }
        var msg = try {
            JSONObject(err).getJSONObject("error").getString("message")
        } catch (_: Exception) {
            "HTTP $code"
        }
        if (code == 429) {
            val wait = conn.getHeaderField("retry-after")
            msg += if (wait.isNullOrBlank()) " (hết hạn mức, thử lại sau)" else " (chờ khoảng $wait giây rồi thử lại)"
        }
        return msg
    }

    // Model có hỗ trợ tham số reasoning.effort (low / medium / high)
    private fun supportsReasoning(model: String) = model.lowercase().contains("gpt-oss")

    // Liệt kê model chat được: GET /models. Bỏ whisper / orpheus / guard (không phải model chat).
    // Chạy trên thread nền.
    fun listModels(key: String): List<ModelInfo> {
        val conn = URL("$BASE/models").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.setRequestProperty("Authorization", "Bearer $key")

            val code = conn.responseCode
            if (code !in 200..299) throw RuntimeException(errorMessage(conn, code))

            val data = JSONObject(conn.inputStream.bufferedReader().readText())
            val arr = data.optJSONArray("data") ?: JSONArray()
            val result = ArrayList<ModelInfo>()
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                val id = m.optString("id")
                if (id.isEmpty()) continue
                if (!m.optBoolean("active", true)) continue
                val low = id.lowercase()
                if (low.contains("whisper") || low.contains("orpheus") || low.contains("guard") || low.contains("tts")) continue
                result.add(ModelInfo(id, m.optString("owned_by")))
            }
            result.sortBy { it.id }
            return result
        } finally {
            conn.disconnect()
        }
    }

    // Gọi Responses API (SSE) - POST /openai/v1/responses, stream = true. Chạy trên thread nền.
    // Chữ trả về là event response.output_text.delta; bỏ qua reasoning.
    fun stream(
        key: String,
        model: String,
        think: String,
        text: String,
        cancelled: () -> Boolean,
        onChunk: (String) -> Unit
    ) {
        val conn = URL("$BASE/responses").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $key")

            val body = JSONObject()
                .put("model", model)
                .put("input", text)
                .put("stream", true)
            val effort = think.trim().lowercase()
            if (effort.isNotEmpty() && supportsReasoning(model)) {
                body.put("reasoning", JSONObject().put("effort", effort))
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code !in 200..299) throw RuntimeException(errorMessage(conn, code))

            var streamError: String? = null

            val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
            while (true) {
                if (cancelled()) break
                val line = reader.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val js = line.substring(5).trim()
                if (js.isEmpty() || js == "[DONE]") continue
                try {
                    val ev = JSONObject(js)
                    when (ev.optString("type")) {
                        "response.output_text.delta" -> {
                            val t = ev.optString("delta")
                            if (t.isNotEmpty()) onChunk(t)
                        }

                        "response.failed" -> {
                            streamError = ev.optJSONObject("response")?.optJSONObject("error")?.optString("message")
                                ?: "Lỗi từ API"
                        }

                        "error" -> {
                            streamError = ev.optJSONObject("error")?.optString("message")
                                ?: ev.optString("message", "Lỗi từ API")
                        }
                    }
                } catch (_: Exception) {
                    // bỏ qua chunk lỗi
                }
                if (streamError != null) break
            }
            if (streamError != null) throw RuntimeException(streamError)
        } finally {
            conn.disconnect()
        }
    }
}
