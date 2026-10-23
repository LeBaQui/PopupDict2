package com.qui.popupdict

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Kho lưu vĩnh viễn nghĩa của các TỪ đã dịch (file translations.json trong bộ nhớ riêng của app).
 * Khóa = tên prompt + từ (viết thường). Chỉ ghi đè khi dịch lại thành công.
 */
object TranslationStore {

    private const val FILE = "translations.json"

    private val map = ConcurrentHashMap<String, String>()
    private val io = Executors.newSingleThreadExecutor()
    private val dirty = AtomicBoolean(false)
    private var loaded = false

    fun key(promptName: String, word: String): String =
        promptName.trim() + "\u0001" + word.trim().lowercase()

    // Đọc file ở thread nền để lần chạm đầu tiên không bị khựng
    fun preload(c: Context) {
        val app = c.applicationContext
        Thread { ensureLoaded(app) }.start()
    }

    @Synchronized
    private fun ensureLoaded(c: Context) {
        if (loaded) return
        loaded = true
        val f = File(c.applicationContext.filesDir, FILE)
        if (!f.exists()) return
        try {
            val o = JSONObject(f.readText(Charsets.UTF_8))
            val it = o.keys()
            while (it.hasNext()) {
                val k = it.next()
                map[k] = o.optString(k)
            }
        } catch (_: Exception) {
            // file hỏng: giữ lại bản .bad để khỏi bị ghi đè mất
            f.renameTo(File(f.parentFile, "$FILE.bad"))
        }
    }

    fun get(c: Context, key: String): String? {
        ensureLoaded(c)
        return map[key]
    }

    fun put(c: Context, key: String, value: String) {
        ensureLoaded(c)
        map[key] = value
        save(c.applicationContext)
    }

    fun size(c: Context): Int {
        ensureLoaded(c)
        return map.size
    }

    private fun save(app: Context) {
        if (!dirty.getAndSet(true)) {
            io.execute {
                dirty.set(false)   // put mới trong lúc đang ghi sẽ xếp thêm 1 lượt ghi nữa
                try {
                    val o = JSONObject()
                    for ((k, v) in map) o.put(k, v)
                    val f = File(app.filesDir, FILE)
                    val tmp = File(app.filesDir, "$FILE.tmp")
                    tmp.writeText(o.toString(), Charsets.UTF_8)
                    if (!tmp.renameTo(f)) {
                        f.delete()
                        tmp.renameTo(f)
                    }
                } catch (_: Exception) {
                    // bỏ qua lỗi ghi, lần put sau sẽ ghi lại
                }
            }
        }
    }
}
