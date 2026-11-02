package com.jarvis.assistant.ui.kit

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController

/** Insets in pixels. */
data class InsetsPx(val left: Int, val top: Int, val right: Int, val bottom: Int, val imeBottom: Int)

/**
 * Edge-to-edge for API 29-36 with public APIs only. Android 15+ enforces edge-to-edge, so every screen must pad for the system bars, the display cutout and the keyboard itself.
 *  - API 30+: setDecorFitsSystemWindows(false) and getInsets(systemBars | displayCutout | ime)
 *  - API 29: the systemUiVisibility layout flags and systemWindowInset*
 */
object Edge {
    fun enable(activity: Activity, lightBars: Boolean) {
        val w = activity.window
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false)
            val controller = w.decorView.windowInsetsController
            controller?.setSystemBarsAppearance(
                if (lightBars) WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS else 0,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            )
        } else {
            @Suppress("DEPRECATION")
            var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            if (lightBars) {
                @Suppress("DEPRECATION")
                flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                if (Build.VERSION.SDK_INT >= 26) @Suppress("DEPRECATION") { flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR }
            }
            @Suppress("DEPRECATION")
            w.decorView.systemUiVisibility = flags
        }
    }

    /** Calls [onInsets] with the current system-bar/cutout insets and the keyboard height. The listener consumes nothing so children can also react. */
    fun listen(root: View, onInsets: (InsetsPx) -> Unit) {
        root.setOnApplyWindowInsetsListener { v, wi ->
            onInsets(read(wi))
            v.onApplyWindowInsets(wi)
            wi
        }
        root.requestApplyInsets()
    }

    private fun read(wi: WindowInsets): InsetsPx {
        if (Build.VERSION.SDK_INT >= 30) {
            val bars = wi.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = wi.getInsets(WindowInsets.Type.ime()).bottom
            return InsetsPx(bars.left, bars.top, bars.right, bars.bottom, ime)
        }
        @Suppress("DEPRECATION")
        return InsetsPx(wi.systemWindowInsetLeft, wi.systemWindowInsetTop, wi.systemWindowInsetRight, wi.systemWindowInsetBottom, 0)
    }
}
