package com.jarvis.assistant.island

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.view.DisplayCutout
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import com.jarvis.core.island.InsetsPx
import com.jarvis.core.island.RectPx

/** What the window system reports about the display right now, in the units [com.jarvis.core.island.IslandGeometry] takes. */
data class DisplayInfo(val width: Int, val height: Int, val density: Float, val rotation: Int, val statusBar: Int, val insets: InsetsPx, val cutouts: List<RectPx>)

/**
 * Reads the window bounds, status-bar height and display-cutout rectangles from the framework. It reports ONLY what Android says about the cutout (bounding
 * rectangles and safe insets); it can't know where the physical camera is and never claims to. Re-run it whenever the configuration, the window insets or the
 * display changes (rotation, folding, resizing).
 *
 *  - API 30+: [WindowManager.getCurrentWindowMetrics] of a window context (follows fold state) and its [WindowInsets];
 *  - API 31+: also Display.getCutout() when the window insets carry none (the overlay window has not been laid out yet);
 *  - API 29: real display metrics, and the cutout from an attached view's root insets (null until attached; the controller re-reads then).
 */
object CutoutDetector {
    fun read(windowContext: Context, anchor: View?): DisplayInfo {
        val wm = windowContext.getSystemService(WindowManager::class.java)
        val res = windowContext.resources
        val density = res.displayMetrics.density
        var width: Int
        var height: Int
        var statusBar = 0
        var cutout: DisplayCutout? = null
        val rotation: Int

        if (Build.VERSION.SDK_INT >= 30) {
            val m = wm.currentWindowMetrics
            width = m.bounds.width()
            height = m.bounds.height()
            val wi = m.windowInsets
            statusBar = wi.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top
            cutout = wi.displayCutout
            rotation = windowContext.display?.rotation ?: 0
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            width = dm.widthPixels
            height = dm.heightPixels
            @Suppress("DEPRECATION") run { rotation = wm.defaultDisplay.rotation }
            cutout = anchor?.rootWindowInsets?.displayCutout
        }
        if (cutout == null && Build.VERSION.SDK_INT >= 31) cutout = anchor?.rootWindowInsets?.displayCutout ?: windowContext.display?.cutout
        if (statusBar <= 0) {
            // Fallback only for APIs/moments without window insets: the framework's own status_bar_height dimension.
            val id = res.getIdentifier("status_bar_height", "dimen", "android")
            if (id != 0) statusBar = res.getDimensionPixelSize(id)
        }
        return DisplayInfo(width, height, density, rotation, statusBar, insetsOf(cutout), rectsOf(cutout))
    }

    private fun rectsOf(c: DisplayCutout?): List<RectPx> = c?.boundingRects.orEmpty().map { it.toPx() }

    private fun insetsOf(c: DisplayCutout?): InsetsPx {
        if (c == null) return InsetsPx()
        var l = c.safeInsetLeft; var t = c.safeInsetTop; var r = c.safeInsetRight; var b = c.safeInsetBottom
        if (Build.VERSION.SDK_INT >= 30) {
            val w = c.waterfallInsets
            l = maxOf(l, w.left); t = maxOf(t, w.top); r = maxOf(r, w.right); b = maxOf(b, w.bottom)
        }
        return InsetsPx(l, t, r, b)
    }

    private fun Rect.toPx() = RectPx(left, top, right, bottom)
}
