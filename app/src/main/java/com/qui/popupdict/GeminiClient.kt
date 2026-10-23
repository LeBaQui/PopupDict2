package com.qui.popupdict

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class ModelInfo(val id: String, val displayName: String)

object GeminiClient {

    private const val BASE = "https://generativelanguage.googleapis.com/v1beta"
    private const val API_REVISION = "2026-05-20"

    // Lấy thông báo lỗi từ phản hồi của API
    private fun errorMessage(conn: HttpURLConnection, code: Int): String {
        val err = try {
            (conn.errorStream ?: conn.inputStream).bufferedReader().readText()
        } catch (_: Exception) {
            ""
        }
        return try {
            JSONObject(err).getJSONObject("error").getString("message")
        } catch (_: Exception) {
            "HTTP $code"
        }
    }

    // Liệt kê các model chat được (có generateContent), giống nút List trong gemini-chat-1.html.
    // Chạy trên thread nền.
    fun listModels(key: String): List<ModelInfo> {
        val result = ArrayList<ModelInfo>()
        var pageToken = ""
        do {
            var u = "$BASE/models?pageSize=1000"
            if (pageToken.isNotEmpty()) u += "&pageToken=" + URLEncoder.encode(pageToken, "UTF-8")
            val conn = URL(u).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "GET"
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.setRequestProperty("x-goog-api-key", key)

                val code = conn.responseCode
                if (code !in 200..299) throw RuntimeException(errorMessage(conn, code))

                val data = JSONObject(conn.inputStream.bufferedReader().readText())
                val arr = data.optJSONArray("models") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val m = arr.getJSONObject(i)
                    val methods = m.optJSONArray("supportedGenerationMethods") ?: continue
                    var ok = false
                    for (j in 0 until methods.length()) {
                        if (methods.optString(j) == "generateContent") {
                            ok = true
                            break
                        }
                    }
                    if (!ok) continue
                    val id = m.optString("name").removePrefix("models/")
                    if (id.isNotEmpty()) result.add(ModelInfo(id, m.optString("displayName")))
                }
                pageToken = data.optString("nextPageToken", "")
            } finally {
                conn.disconnect()
            }
        } while (pageToken.isNotEmpty())
        return result
    }

    // Gọi Interactions API (SSE) - POST /v1beta/interactions?alt=sse. Chạy trên thread nền.
    // Chữ trả về là các step.delta (type = text) của step model_output; bỏ qua step thought.
    fun stream(
        key: String,
        model: String,
        think: String,
        text: String,
        cancelled: () -> Boolean,
        onChunk: (String) -> Unit
    ) {
        val url = URL("$BASE/interactions?alt=sse")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", key)
            conn.setRequestProperty("Api-Revision", API_REVISION)

            val body = JSONObject()
                .put("model", model)
                .put("input", text)
                .put("stream", true)
            if (think.isNotEmpty()) {
                body.put("generation_config", JSONObject().put("thinking_level", think))
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code !in 200..299) throw RuntimeException(errorMessage(conn, code))

            // index của step -> loại step (thought / model_output ...)
            val stepTypes = HashMap<Int, String>()
            var streamError: String? = null

            val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
            while (true) {
                if (cancelled()) break
                val line = reader.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val js = line.substring(5).trim()
                if (js.isEmpty()) continue
                try {
                    val ev = JSONObject(js)
                    when (ev.optString("event_type")) {
                        "step.start" -> {
                            val idx = ev.optInt("index", -1)
                            val type = ev.optJSONObject("step")?.optString("type") ?: ""
                            if (idx >= 0) stepTypes[idx] = type
                        }

                        "step.delta" -> {
                            val idx = ev.optInt("index", -1)
                            if (stepTypes[idx] == "thought") continue
                            val delta = ev.optJSONObject("delta") ?: continue
                            if (delta.optString("type") != "text") continue
                            val t = delta.optString("text")
                            if (t.isNotEmpty()) onChunk(t)
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
