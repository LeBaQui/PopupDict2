package com.qui.popupdict

import android.content.Context
import android.view.View
import android.widget.ScrollView

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

// ScrollView có chiều cao tối đa: nội dung ngắn thì co lại, dài thì cuộn
class MaxHeightScrollView(ctx: Context, private val maxH: Int) : ScrollView(ctx) {
    override fun onMeasure(w: Int, h: Int) {
        super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
    }
}
