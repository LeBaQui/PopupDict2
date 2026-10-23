package com.qui.popupdict

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Lớp phủ trong suốt phủ cả màn hình, xử lý 3 cử chỉ:
 *  - chạm 1 từ        -> onWordTap
 *  - giữ 1 từ         -> onSentence (dịch cả câu chứa từ đó)
 *  - kéo qua nhiều từ -> onRange (dịch đoạn vừa kéo)
 * Đồng thời tô sáng các từ đang có popup.
 */
class OverlayView(ctx: Context, private val host: Host) : View(ctx) {

    interface Host {
        fun hit(x: Int, y: Int): Int          // index token dưới ngón tay, -1 nếu không có
        fun nearest(x: Int, y: Int): Int      // token gần nhất (dùng khi kéo), -1 nếu quá xa
        fun rects(a: Int, b: Int): List<Rect> // khung các token từ a..b
        fun onWordTap(i: Int)
        fun onSentence(i: Int)
        fun onRange(a: Int, b: Int)
        fun onBlank(x: Int, y: Int)
    }

    private val marks = LinkedHashMap<String, List<Rect>>()
    private var drag: List<Rect> = emptyList()

    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x663B82F6
        style = Paint.Style.FILL
    }
    private val dragPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66F59E0B
        style = Paint.Style.FILL
    }

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val loc = IntArray(2)

    private var downX = 0f
    private var downY = 0f
    private var startTok = -1
    private var endTok = -1
    private var moved = false
    private var longFired = false

    private val longRun = Runnable {
        if (!moved && startTok >= 0) {
            longFired = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            host.onSentence(startTok)
        }
    }

    fun setMark(key: String, rects: List<Rect>) {
        marks[key] = rects
        invalidate()
    }

    fun clearMark(key: String) {
        if (marks.remove(key) != null) invalidate()
    }

    override fun onDraw(c: Canvas) {
        getLocationOnScreen(loc)
        c.save()
        c.translate(-loc[0].toFloat(), -loc[1].toFloat())
        val r = 6f * resources.displayMetrics.density
        for (list in marks.values) for (b in list) c.drawRoundRect(RectF(b), r, r, markPaint)
        for (b in drag) c.drawRoundRect(RectF(b), r, r, dragPaint)
        c.restore()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.rawX.toInt()
        val y = e.rawY.toInt()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX
                downY = e.rawY
                moved = false
                longFired = false
                startTok = host.hit(x, y)
                endTok = startTok
                drag = emptyList()
                if (startTok >= 0) postDelayed(longRun, 450)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!moved && (abs(e.rawX - downX) > slop || abs(e.rawY - downY) > slop)) {
                    moved = true
                    removeCallbacks(longRun)
                }
                if (moved && startTok >= 0 && !longFired) {
                    val t = host.nearest(x, y)
                    if (t >= 0) endTok = t
                    drag = host.rects(min(startTok, endTok), max(startTok, endTok))
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longRun)
                val a = min(startTok, endTok)
                val b = max(startTok, endTok)
                val wasDrag = moved && startTok >= 0 && !longFired
                drag = emptyList()
                invalidate()
                if (longFired) {
                    // đã xử lý ở longRun
                } else if (wasDrag) {
                    if (a == b) host.onWordTap(a) else host.onRange(a, b)
                } else if (!moved) {
                    if (startTok >= 0) host.onWordTap(startTok) else host.onBlank(x, y)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longRun)
                drag = emptyList()
                invalidate()
            }
        }
        return true
    }
}
