package com.qui.popupdict

import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.TextUtils
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Bấm bong bóng -> quét màn hình (OCR) -> lớp phủ.
 *  - Chạm 1 từ: Gemini dịch từ đó (kèm câu chứa từ làm ngữ cảnh)
 *  - Giữ 1 từ: Gemini dịch cả câu chứa từ đó
 *  - Kéo qua nhiều từ: Gemini dịch đoạn vừa kéo
 * Bấm lại từ/câu đó (hoặc nút ›) để tắt popup, nhiều popup cùng lúc.
 * Nghĩa của TỪ được lưu vĩnh viễn (TranslationStore): lần sau chạm lại là hiện ngay, khỏi gọi Gemini.
 * Nút 🔁 = bỏ nghĩa cũ, gọi Gemini dịch lại với ngữ cảnh hiện tại rồi ghi đè.
 * Bấm bong bóng lần nữa -> tắt lớp phủ + tắt hết popup.
 * Thứ tự z (dưới -> trên): lớp phủ < bong bóng < popup.
 */
class FloatingService : Service(), OverlayView.Host {

    companion object {
        const val ACTION_STOP = "com.qui.popupdict.STOP"
        @Volatile var running = false

        private const val TINT_SCANNING = 0x22FFFF00   // vàng nhạt: đang OCR
        private const val TINT_READY = 0x2200FF00      // xanh nhạt: sẵn sàng
        private const val KEY_NOTE = "note"

        private const val MODE_WORD = 0
        private const val MODE_SENT = 1
        private const val MODE_NOTE = 2
    }

    private class Tok(val text: String, val box: Rect, val block: Int, val off: Int)

    private inner class Pop(
        val key: String,
        val mode: Int,
        val query: String,
        val context: String,
        val anchor: Rect
    ) {
        @Volatile var closed = false
        var attached = false
        var job = 0
        var above = true
        var w = 0
        val buf = StringBuilder()
        lateinit var box: LinearLayout
        lateinit var lp: WindowManager.LayoutParams
        lateinit var promptTv: TextView
        lateinit var bodyTv: TextView
    }

    private lateinit var wm: WindowManager
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val ui = Handler(Looper.getMainLooper())
    private val density by lazy { resources.displayMetrics.density }
    private fun px(v: Int) = (v * density).toInt()

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var latest: Image? = null   // frame màn hình mới nhất
    private var screenW = 0
    private var screenH = 0

    private var bubble: TextView? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var overlay: OverlayView? = null
    private val pops = LinkedHashMap<String, Pop>()

    // Kết quả OCR của lần quét hiện tại
    private val toks = ArrayList<Tok>()
    private val blockTexts = ArrayList<String>()
    private var ready = false
    private var scanId = 0   // tăng mỗi lần quét/hủy; kết quả OCR cũ không khớp id thì bỏ

    // Nhớ kết quả đã dịch: mở lại cùng từ/câu thì khỏi gọi API
    private val cache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 200
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        startAsForeground()   // phải gọi trước getMediaProjection (Android 14+)
        running = true
        TranslationStore.preload(this)   // đọc kho nghĩa đã lưu ở thread nền
    }

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val data = intent?.let { IntentCompat.getParcelableExtra(it, "DATA_INTENT", Intent::class.java) }
        if (data == null || projection != null) return START_NOT_STICKY

        val code = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_OK) ?: Activity.RESULT_OK
        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = pm.getMediaProjection(code, data) ?: run { stopSelf(); return START_NOT_STICKY }
        projection = mp

        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, ui)

        val m = DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }
        screenW = m.widthPixels
        screenH = m.heightPixels

        reader = ImageReader.newInstance(m.widthPixels, m.heightPixels, PixelFormat.RGBA_8888, 3).apply {
            setOnImageAvailableListener({ r ->
                val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                latest?.close()
                latest = img
            }, ui)
        }

        display = mp.createVirtualDisplay(
            "PopupDictCapture", m.widthPixels, m.heightPixels, m.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, ui
        )

        showBubble()
        return START_NOT_STICKY
    }

    // ---------- Bong bóng: kéo được, bấm để quét / tắt lớp phủ, giữ lâu để thoát ----------

    private fun setBubbleColor(v: View, active: Boolean) {
        v.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (active) 0xF0059669.toInt() else 0xF06200EE.toInt())
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        val view = TextView(this).apply {
            text = "文"
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        setBubbleColor(view, false)

        val lp = layoutParams(px(26), px(26), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE).apply {
            gravity = Gravity.TOP or Gravity.START
            x = px(16)
            y = px(240)
        }

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var startX = 0
        var startY = 0
        var downX = 0f
        var downY = 0f
        var moved = false
        val quitRun = Runnable {
            Toast.makeText(this, "Đã tắt Popup Dict", Toast.LENGTH_SHORT).show()
            stopSelf()
        }

        view.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    downX = e.rawX
                    downY = e.rawY
                    moved = false
                    ui.postDelayed(quitRun, 1500)   // giữ yên ~1.5 giây = thoát app
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!moved && (abs(e.rawX - downX) > slop || abs(e.rawY - downY) > slop)) {
                        moved = true
                        ui.removeCallbacks(quitRun)
                    }
                    if (moved) {
                        lp.x = startX + (e.rawX - downX).toInt()
                        lp.y = startY + (e.rawY - downY).toInt()
                        runCatching { wm.updateViewLayout(view, lp) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    ui.removeCallbacks(quitRun)
                    if (!moved) onBubbleTap()
                }
                MotionEvent.ACTION_CANCEL -> ui.removeCallbacks(quitRun)
            }
            true
        }

        bubble = view
        bubbleLp = lp
        wm.addView(view, lp)
    }

    private fun onBubbleTap() {
        if (overlay != null) {      // đang có lớp phủ (kể cả đang quét dở) -> tắt hết
            scanId++                // hủy kết quả OCR đang chạy
            closeAllPops()
            closeOverlay()
        } else {
            scan()
        }
    }

    // Window add sau thì nằm trên. Remove rồi add lại để nổi lên trên lớp phủ.
    private fun bringBubbleToFront() {
        val v = bubble ?: return
        val lp = bubbleLp ?: return
        runCatching { wm.removeView(v) }
        wm.addView(v, lp)
    }

    private fun bringPopToFront(p: Pop) {
        if (!p.attached) return
        runCatching { wm.removeView(p.box) }
        wm.addView(p.box, p.lp)
    }

    // ---------- Quét: hiện lớp phủ ngay, OCR chạy ngầm ----------

    private fun scan() {
        if (overlay != null) return
        closeAllPops()

        if (Prefs.key(this).isBlank()) {
            return note("Chưa nhập API key. Mở app Popup Dict, nhập key rồi bấm Lưu.")
        }

        val img = latest ?: return note("Chưa có ảnh màn hình, thử lại")

        // Bitmap giữ nguyên phần đệm bên phải; tọa độ chữ vẫn khớp màn hình
        val plane = img.planes[0]
        val bmp = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, img.height, Bitmap.Config.ARGB_8888)
        plane.buffer.rewind()
        bmp.copyPixelsFromBuffer(plane.buffer)

        val id = ++scanId
        toks.clear()
        blockTexts.clear()
        ready = false
        showOverlay()   // hiện ngay, chưa cần chờ OCR

        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { t ->
                if (id != scanId || overlay == null) return@addOnSuccessListener   // đã hủy
                for ((bi, b) in t.textBlocks.withIndex()) {
                    val sb = StringBuilder()
                    for (line in b.lines) {
                        if (sb.isNotEmpty()) sb.append(' ')
                        val lt = line.text
                        val start = sb.length
                        var cursor = 0
                        for (el in line.elements) {
                            val box = el.boundingBox ?: continue
                            var idx = lt.indexOf(el.text, cursor)
                            if (idx < 0) idx = min(cursor, lt.length) else cursor = idx + el.text.length
                            toks.add(Tok(el.text, Rect(box), bi, start + idx))
                        }
                        sb.append(lt)
                    }
                    blockTexts.add(sb.toString())
                }
                ready = true
                overlay?.setBackgroundColor(TINT_READY)
                closePop(KEY_NOTE)
                if (toks.isEmpty()) note("Không thấy chữ nào trên màn hình")
            }
            .addOnFailureListener {
                if (id != scanId || overlay == null) return@addOnFailureListener
                closeOverlay()
                closeAllPops()
                note("OCR lỗi: ${it.message}")
            }
            .addOnCompleteListener { bmp.recycle() }
    }

    private fun showOverlay() {
        val view = OverlayView(this, this)
        view.setBackgroundColor(TINT_SCANNING)
        overlay = view
        wm.addView(
            view,
            layoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            )
        )
        bubble?.let { setBubbleColor(it, true) }
        bringBubbleToFront()   // bong bóng nằm trên lớp phủ để còn bấm tắt được
        pops.values.toList().forEach { bringPopToFront(it) }
    }

    private fun closeOverlay() {
        overlay?.let { runCatching { wm.removeView(it) } }
        overlay = null
        ready = false
        bubble?.let { setBubbleColor(it, false) }
    }

    // ---------- OverlayView.Host: tra token theo tọa độ ----------

    override fun hit(x: Int, y: Int): Int {
        if (!ready) return -1
        for (i in toks.indices) {
            val b = toks[i].box
            if (x >= b.left && x <= b.right && y >= b.top && y <= b.bottom) return i
        }
        val s = px(4)   // nới khung 4dp cho dễ chạm
        for (i in toks.indices) {
            val b = toks[i].box
            if (x >= b.left - s && x <= b.right + s && y >= b.top - s && y <= b.bottom + s) return i
        }
        return -1
    }

    override fun nearest(x: Int, y: Int): Int {
        var best = -1
        var bestD = px(40).toDouble()
        for (i in toks.indices) {
            val b = toks[i].box
            val dx = max(max(b.left - x, x - b.right), 0)
            val dy = max(max(b.top - y, y - b.bottom), 0)
            val d = hypot(dx.toDouble(), dy.toDouble())
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }

    override fun rects(a: Int, b: Int): List<Rect> {
        val out = ArrayList<Rect>()
        for (i in a..b) if (i in toks.indices) out.add(Rect(toks[i].box))
        return out
    }

    override fun onBlank(x: Int, y: Int) {
        if (!ready) openPop(KEY_NOTE, MODE_NOTE, "Đang quét…", "", Rect(x, y, x, y), emptyList())
    }

    // ---------- Cử chỉ -> mở popup ----------

    override fun onWordTap(i: Int) {
        val tk = toks.getOrNull(i) ?: return
        val word = tk.text.trim { !it.isLetterOrDigit() }
        if (word.isEmpty()) return
        val key = "w$i"
        if (pops.containsKey(key)) {   // bấm lần 2 -> tắt
            closePop(key)
            return
        }
        val s = blockTexts[tk.block]
        val (a, e) = sentenceBounds(s, tk.off)
        val ctx = s.substring(a, e).trim()
        openPop(key, MODE_WORD, word, ctx, Rect(tk.box), listOf(Rect(tk.box)))
    }

    override fun onSentence(i: Int) {
        val tk = toks.getOrNull(i) ?: return
        val s = blockTexts[tk.block]
        val (a, e) = sentenceBounds(s, tk.off)
        val key = "s${tk.block}:$a"
        if (pops.containsKey(key)) {
            closePop(key)
            return
        }
        val idxs = toks.indices.filter { toks[it].block == tk.block && toks[it].off >= a && toks[it].off < e }
        if (idxs.isEmpty()) return
        val rs = idxs.map { Rect(toks[it].box) }
        val text = s.substring(a, e).trim()
        openPop(key, MODE_SENT, text, text, union(rs), rs)
    }

    override fun onRange(a: Int, b: Int) {
        val sb = StringBuilder()
        var prev = -1
        for (i in a..b) {
            val t = toks.getOrNull(i) ?: continue
            if (prev >= 0) sb.append(if (toks[prev].block == t.block) " " else "\n")
            sb.append(t.text)
            prev = i
        }
        val text = sb.toString().trim()
        if (text.isEmpty()) return
        val key = "r$a-$b"
        if (pops.containsKey(key)) {
            closePop(key)
            return
        }
        val rs = rects(a, b)
        openPop(key, MODE_SENT, text, text, union(rs), rs)
    }

    private fun union(rs: List<Rect>): Rect {
        val u = Rect(rs[0])
        for (r in rs) u.union(r)
        return u
    }

    // Tìm đầu/cuối câu quanh vị trí off trong đoạn s
    private fun sentenceBounds(s: String, off: Int): Pair<Int, Int> {
        val o = off.coerceIn(0, max(0, s.length - 1))
        var a = o
        while (a > 0) {
            val c = s[a - 1]
            if (c in ".!?…" && (a >= s.length || !s[a].isLetterOrDigit())) break
            a--
        }
        var e = o
        while (e < s.length) {
            val c = s[e]
            e++
            if (c in ".!?…" && (e >= s.length || !s[e].isLetterOrDigit())) break
        }
        if (e - a > 600) {   // đoạn quá dài: cắt quanh từ được chạm
            a = max(a, o - 300)
            e = min(e, o + 300)
        }
        return Pair(a, e)
    }

    // ---------- Popup ----------

    private fun chip(label: String): TextView = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 13f
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(px(10), px(6), px(10), px(6))
        background = GradientDrawable().apply {
            setColor(0x33FFFFFF)
            cornerRadius = px(10).toFloat()
        }
    }

    private fun openPop(key: String, mode: Int, query: String, context: String, anchor: Rect, marks: List<Rect>) {
        closePop(key)
        val p = Pop(key, mode, query, context, anchor)
        val gap = px(6)

        p.w = if (mode == MODE_WORD) min(screenW - px(16), px(340)) else screenW - px(16)

        // Chọn phía có chỗ (trên hoặc dưới vùng chữ)
        val spaceAbove = anchor.top - gap
        val spaceBelow = screenH - anchor.bottom - gap
        val want = px(220)
        p.above = when {
            spaceAbove >= want -> true
            spaceBelow >= want -> false
            else -> spaceAbove >= spaceBelow
        }
        val avail = if (p.above) spaceAbove else spaceBelow
        val maxBody = (avail - px(70)).coerceIn(px(80), (screenH * 0.6f).toInt())

        val title = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            if (mode == MODE_NOTE) {
                maxLines = 4
                this.text = query
            } else {
                maxLines = 1
                val one = query.replace('\n', ' ')
                this.text = if (mode == MODE_SENT && one.length > 40) one.take(40) + "…" else one
            }
            ellipsize = TextUtils.TruncateAt.END
        }

        val promptTv = chip("")
        promptTv.maxWidth = px(120)
        promptTv.setOnClickListener { cyclePrompt(p) }
        val reloadTv = chip("🔁")
        reloadTv.setOnClickListener { fillQuery(p, force = true) }
        val copyTv = chip("Chép")
        copyTv.setOnClickListener { copyText(p.buf.toString()) }
        val closeTv = chip("›")
        closeTv.textSize = 18f
        closeTv.setOnClickListener { closePop(key) }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val views = if (mode == MODE_NOTE) listOf(closeTv) else listOf(promptTv, reloadTv, copyTv, closeTv)
            for (v in views) {
                addView(v, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = px(6) })
            }
        }

        val bodyTv = TextView(this).apply {
            setTextColor(0xFFE5E7EB.toInt())
            textSize = 15f
            setLineSpacing(0f, 1.15f)
            setPadding(0, px(6), px(6), 0)
        }
        val scroll = MaxHeightScrollView(this, maxBody).apply {
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(bodyTv)
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(14), px(8), px(10), px(12))
            background = GradientDrawable().apply {
                setColor(0xF2212121.toInt())
                cornerRadius = px(16).toFloat()
            }
            addView(header)
            if (mode != MODE_NOTE) {
                addView(scroll, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ))
            }
        }

        val lp = layoutParams(p.w, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE).apply {
            gravity = Gravity.TOP or Gravity.START
            x = anchor.left.coerceIn(0, max(0, screenW - p.w))
            y = 0
        }

        p.box = box
        p.lp = lp
        p.promptTv = promptTv
        p.bodyTv = bodyTv
        pops[key] = p
        overlay?.setMark(key, marks)

        if (mode != MODE_NOTE) fillQuery(p) else relayout(p)
        wm.addView(box, lp)   // add sau cùng -> nằm trên cùng
        p.attached = true
    }

    // Đo lại popup rồi đặt y: popup mở lên trên thì phải dịch lên khi chữ dài ra
    private fun relayout(p: Pop) {
        if (p.closed) return
        p.box.measure(
            View.MeasureSpec.makeMeasureSpec(p.w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val h = p.box.measuredHeight
        val gap = px(6)
        val y = (if (p.above) p.anchor.top - h - gap else p.anchor.bottom + gap)
            .coerceIn(0, max(0, screenH - h))
        if (y != p.lp.y) {
            p.lp.y = y
            if (p.attached) runCatching { wm.updateViewLayout(p.box, p.lp) }
        }
    }

    private fun closePop(key: String) {
        val p = pops.remove(key) ?: return
        p.closed = true
        runCatching { wm.removeView(p.box) }
        overlay?.clearMark(key)
    }

    private fun closeAllPops() {
        pops.keys.toList().forEach { closePop(it) }
    }

    // thông báo lỗi/trạng thái cũng là popup overlay, không dùng Toast
    private fun note(msg: String) {
        val a = Rect(px(40), px(160), px(40), px(160))
        openPop(KEY_NOTE, MODE_NOTE, msg, "", a, emptyList())
    }

    private fun copyText(s: String) {
        if (s.isBlank()) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("popup_dict", s))
        Toast.makeText(this, "Đã chép", Toast.LENGTH_SHORT).show()
    }

    // ---------- Gọi Gemini ----------

    private fun promptsFor(mode: Int): List<PromptItem> =
        if (mode == MODE_WORD) Prefs.wordPrompts(this) else Prefs.sentPrompts(this)

    private fun idxFor(mode: Int): Int =
        if (mode == MODE_WORD) Prefs.wordIdx(this) else Prefs.sentIdx(this)

    // Bấm chip tên prompt -> chuyển sang prompt kế tiếp (trở thành mặc định) và hỏi lại
    private fun cyclePrompt(p: Pop) {
        if (p.mode == MODE_NOTE) return
        val list = promptsFor(p.mode)
        if (list.size < 2) return
        val next = (idxFor(p.mode).coerceIn(0, list.size - 1) + 1) % list.size
        if (p.mode == MODE_WORD) Prefs.setWordIdx(this, next) else Prefs.setSentIdx(this, next)
        fillQuery(p)
    }

    private fun buildContent(pr: PromptItem, query: String, context: String): String {
        val hasText = pr.text.contains("{text}")
        var t = pr.text.replace("{text}", query).replace("{context}", context.ifBlank { query })
        if (!hasText) t += "\n\n" + query
        return t + "\n\n(Trả lời bằng văn bản thuần, không dùng Markdown.)"
    }

    private fun clean(s: String): String = s
        .replace("**", "")
        .replace("`", "")
        .replace(Regex("(?m)^#+\\s*"), "")
        .replace(Regex("(?m)^[ \\t]*[*-][ \\t]+"), "• ")
        .trim()

    // force = true (nút 🔁): bỏ qua nghĩa đã lưu và cache, gọi Gemini dịch lại rồi ghi đè
    private fun fillQuery(p: Pop, force: Boolean = false) {
        val list = promptsFor(p.mode)
        val pr = list[idxFor(p.mode).coerceIn(0, list.size - 1)]
        p.promptTv.text = pr.name + " ▾"
        p.buf.setLength(0)

        // Chỉ lưu vĩnh viễn nghĩa của TỪ (theo từ + tên prompt). Câu/đoạn kéo chọn thì không lưu.
        val saveKey = if (p.mode == MODE_WORD) TranslationStore.key(pr.name, p.query) else null
        if (!force && saveKey != null) {
            val saved = TranslationStore.get(this, saveKey)
            if (saved != null) {
                p.job++   // hủy lượt gọi đang chạy (nếu có)
                p.buf.append(saved)
                p.bodyTv.text = saved
                relayout(p)
                return
            }
        }

        val key = Prefs.key(this)
        val model = Prefs.model(this)
        val think = Prefs.think(this)
        if (key.isBlank() || model.isBlank()) {
            p.job++
            p.bodyTv.text = "Chưa nhập API key / model trong app Popup Dict."
            relayout(p)
            return
        }

        val content = buildContent(pr, p.query, p.context)
        val ck = "$model|$think|$content"
        val hit = if (force) null else cache[ck]
        if (hit != null) {
            p.job++
            p.buf.append(hit)
            p.bodyTv.text = hit
            relayout(p)
            return
        }

        val my = ++p.job
        p.bodyTv.text = "Đang hỏi Gemini…"
        relayout(p)

        Thread {
            try {
                GeminiClient.stream(key, model, think, content, { p.closed || my != p.job }) { chunk ->
                    ui.post {
                        if (!p.closed && my == p.job) {
                            p.buf.append(chunk)
                            p.bodyTv.text = clean(p.buf.toString())
                            relayout(p)
                        }
                    }
                }
                ui.post {
                    if (!p.closed && my == p.job) {
                        if (p.buf.isEmpty()) {
                            p.bodyTv.text = "(không có nội dung trả về)"
                            relayout(p)
                        } else {
                            val done = clean(p.buf.toString())
                            cache[ck] = done
                            if (saveKey != null) TranslationStore.put(this@FloatingService, saveKey, done)
                        }
                    }
                }
            } catch (ex: Exception) {
                ui.post {
                    if (!p.closed && my == p.job) {
                        p.bodyTv.text = "Lỗi: " + (ex.message ?: ex.toString())
                        relayout(p)
                    }
                }
            }
        }.start()
    }

    // ---------- Tiện ích ----------

    private fun layoutParams(w: Int, h: Int, flags: Int) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT
    ).apply {
        // Máy có tai thỏ: nếu thiếu dòng này cửa sổ bị đẩy xuống ~45px so với màn hình thật
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun startAsForeground() {
        val channelId = "popup_dict_service"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channelId, "Popup Dict", NotificationManager.IMPORTANCE_LOW)
        )
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, FloatingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Popup Dict đang chạy")
            .setContentText("Bấm bong bóng để quét, chạm từ để dịch")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .addAction(0, "Tắt", stopPi)
            .build()
        ServiceCompat.startForeground(this, 1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    override fun onDestroy() {
        running = false
        scanId++
        ui.removeCallbacksAndMessages(null)
        closeAllPops()
        closeOverlay()
        bubble?.let { runCatching { wm.removeView(it) } }
        latest?.close()
        display?.release()
        reader?.close()
        projection?.stop()
        recognizer.close()
        super.onDestroy()
    }
}
