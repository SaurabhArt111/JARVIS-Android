package com.jarvis.assistant.ui.kit

import android.content.Context
import android.widget.FrameLayout

/** A FrameLayout that is as tall as it is wide (picture grids). */
class SquareFrame(context: Context) : FrameLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) { super.onMeasure(widthMeasureSpec, widthMeasureSpec) }
}
