package com.qui.popupdict

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private companion object {
        const val REQ_OVERLAY = 1
        const val REQ_CAPTURE = 2
        const val REQ_NOTIF = 3
    }

    private lateinit var statusTv: TextView
    private lateinit var keyEt: EditText
    private lateinit var modelEt: EditText
    private lateinit var listBtn: Button
    private lateinit var thinkSp: Spinner
    private lateinit var wordEt: EditText
    private lateinit var sentEt: EditText

    // Mức reasoning của Groq (chỉ model gpt-oss mới nhận tham số này)
    private val levels = listOf("" to "Reasoning: mặc định", "low" to "Low", "medium" to "Medium", "high" to "High")

    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refreshStatus()
            h.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(24))
        }
        val sv = ScrollView(this)
        sv.addView(col)

        col.addView(TextView(this).apply {
            text = "Popup Dict — chạm từ, Groq dịch"
            textSize = 20f
        })

        col.addView(TextView(this).apply {
            text = "Bật bong bóng, rồi bấm bong bóng để quét màn hình:\n" +
                "• Chạm 1 từ: dịch từ đó (kèm câu chứa từ làm ngữ cảnh)\n" +
                "• Giữ 1 từ: dịch cả câu chứa từ đó\n" +
                "• Kéo ngón tay qua nhiều từ: dịch đoạn vừa kéo\n" +
                "• Chạm lại từ/câu hoặc bấm › để tắt popup\n" +
                "• Bấm bong bóng lần nữa: tắt lớp quét (quét lại thì bấm tiếp)\n" +
                "• Giữ yên bong bóng 1,5 giây, hoặc bấm 'Tắt' ở thông báo: thoát app"
            textSize = 13f
            setPadding(0, dp(10), 0, dp(4))
        })

        statusTv = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(8), 0, dp(8))
        }
        col.addView(statusTv)

        col.addView(button("Bật bong bóng") { start() })
        col.addView(button("Tắt bong bóng") {
            stopService(Intent(this, FloatingService::class.java))
            toast("Đã tắt")
        })

        col.addView(label("API key"))
        keyEt = edit("API key Groq (console.groq.com/keys)", Prefs.key(this))
        keyEt.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        col.addView(keyEt)

        col.addView(label("Model"))
        modelEt = edit("Tên model", Prefs.model(this))
        col.addView(modelEt)

        listBtn = button("Tìm list model (bấm để chọn)") { fetchModels() }
        col.addView(listBtn)

        thinkSp = Spinner(this)
        col.addView(thinkSp)
        refreshLevels(Prefs.think(this))

        col.addView(label(
            "Prompt khi CHẠM 1 TỪ. Dùng {text} = từ được chạm, {context} = câu chứa từ. " +
                "Mỗi prompt cách nhau 1 dòng chỉ có ===; dòng đầu của khối là tên. " +
                "Trong popup bấm vào tên prompt để đổi sang prompt kế tiếp."
        ))
        wordEt = edit("", Prefs.rawWordPrompts(this))
        wordEt.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        wordEt.minLines = 10
        wordEt.gravity = Gravity.TOP
        col.addView(wordEt)

        col.addView(label(
            "Prompt khi GIỮ 1 TỪ (dịch câu) hoặc KÉO qua nhiều từ. {text} = đoạn được chọn. Cùng cách viết như trên."
        ))
        sentEt = edit("", Prefs.rawSentPrompts(this))
        sentEt.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        sentEt.minLines = 10
        sentEt.gravity = Gravity.TOP
        col.addView(sentEt)

        col.addView(button("Đặt lại prompt mặc định") {
            wordEt.setText(Prefs.DEFAULT_WORD_PROMPTS)
            sentEt.setText(Prefs.DEFAULT_SENT_PROMPTS)
            save()
            toast("Đã đặt lại prompt mặc định")
        })
        col.addView(button("Lưu") {
            save()
            toast("Đã lưu")
        })

        setContentView(sv)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
    }

    override fun onResume() {
        super.onResume()
        h.post(tick)
    }

    override fun onPause() {
        super.onPause()
        h.removeCallbacks(tick)
    }

    private fun refreshStatus() {
        statusTv.text = "Bong bóng: " + (if (FloatingService.running) "ĐANG CHẠY" else "chưa bật") +
            "\nOverlay: " + (if (Settings.canDrawOverlays(this)) "đã cấp quyền" else "chưa cấp quyền")
    }

    // ---------- bật bong bóng: quyền overlay -> quyền chụp màn hình -> chạy service ----------

    private fun start() {
        save()
        if (FloatingService.running) {
            toast("Bong bóng đang chạy rồi")
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("Cấp quyền 'Hiển thị trên ứng dụng khác' rồi quay lại")
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                REQ_OVERLAY
            )
        } else {
            requestCapture()
        }
    }

    private fun requestCapture() {
        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(pm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_OVERLAY -> {
                if (Settings.canDrawOverlays(this)) requestCapture()
                else toast("Chưa cấp quyền hiển thị trên ứng dụng khác")
            }
            REQ_CAPTURE -> {
                if (resultCode == RESULT_OK && data != null) {
                    val i = Intent(this, FloatingService::class.java)
                        .putExtra("RESULT_CODE", resultCode)
                        .putExtra("DATA_INTENT", data)
                    startForegroundService(i)
                    finish()   // đóng app để ra màn hình chính, bong bóng nằm lại
                } else {
                    toast("Cần cấp quyền chụp màn hình!")
                }
            }
        }
    }

    // ---------- tìm list model ----------

    private fun fetchModels() {
        val key = keyEt.text.toString().trim()
        if (key.isEmpty()) {
            toast("Nhập API key trước nha")
            return
        }
        listBtn.isEnabled = false
        listBtn.text = "Đang lấy danh sách model…"
        Thread {
            try {
                val list = GeminiClient.listModels(key)
                runOnUiThread {
                    resetListBtn()
                    if (!isFinishing && !isDestroyed) showModels(list)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    resetListBtn()
                    toast("Lỗi: " + (e.message ?: e.toString()))
                }
            }
        }.start()
    }

    private fun resetListBtn() {
        listBtn.isEnabled = true
        listBtn.text = "Tìm list model (bấm để chọn)"
    }

    private fun showModels(list: List<ModelInfo>) {
        if (list.isEmpty()) {
            toast("Không có model nào dùng được")
            return
        }
        val items = list.map {
            if (it.displayName.isBlank()) it.id else it.id + "\n" + it.displayName
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(list.size.toString() + " model dùng được — bấm để chọn")
            .setItems(items) { _, which ->
                val id = list[which].id
                modelEt.setText(id)
                Prefs.setModel(this, id)
                toast("Đã chọn: $id")
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    // ---------- thinking / lưu ----------

    private fun refreshLevels(keep: String) {
        thinkSp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, levels.map { it.second })
        val i = levels.indexOfFirst { it.first == keep }
        thinkSp.setSelection(if (i >= 0) i else 0)
    }

    private fun curThink(): String = levels.getOrNull(thinkSp.selectedItemPosition)?.first ?: ""

    private fun save() {
        Prefs.save(
            this,
            keyEt.text.toString().trim(),
            modelEt.text.toString().trim(),
            curThink(),
            wordEt.text.toString(),
            sentEt.text.toString()
        )
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun label(t: String) = TextView(this).apply {
        text = t
        textSize = 13f
        setPadding(0, dp(14), 0, dp(2))
    }

    private fun edit(hint: String, value: String) = EditText(this).apply {
        this.hint = hint
        setText(value)
        textSize = 14f
    }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        setOnClickListener { onClick() }
    }
}
