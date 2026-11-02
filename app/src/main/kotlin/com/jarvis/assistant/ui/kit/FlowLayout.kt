package com.jarvis.assistant.ui.kit

import android.content.Context
import android.view.View
import android.view.ViewGroup
import kotlin.math.max

/** Lays children out left to right and wraps to the next line (button rows under a reply, chips). Honors layout direction. */
class FlowLayout(context: Context, private val gapPx: Int) : ViewGroup(context) {
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val maxW = MeasureSpec.getSize(widthSpec) - paddingLeft - paddingRight
        var x = 0; var y = 0; var lineH = 0; var widest = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i); if (c.visibility == View.GONE) continue
            measureChild(c, MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + c.measuredWidth > maxW) { y += lineH + gapPx; x = 0; lineH = 0 }
            x += c.measuredWidth + gapPx; lineH = max(lineH, c.measuredHeight); widest = max(widest, x - gapPx)
        }
        val h = y + lineH + paddingTop + paddingBottom
        setMeasuredDimension(resolveSize(widest + paddingLeft + paddingRight, widthSpec), resolveSize(h, heightSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val maxW = r - l - paddingLeft - paddingRight
        var x = 0; var y = paddingTop; var lineH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i); if (c.visibility == View.GONE) continue
            if (x > 0 && x + c.measuredWidth > maxW) { y += lineH + gapPx; x = 0; lineH = 0 }
            val left = if (rtl) (r - l - paddingRight - x - c.measuredWidth) else (paddingLeft + x)
            c.layout(left, y, left + c.measuredWidth, y + c.measuredHeight)
            x += c.measuredWidth + gapPx; lineH = max(lineH, c.measuredHeight)
        }
    }
}
