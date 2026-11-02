package com.jarvis.assistant.ui.kit

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import com.jarvis.assistant.R

/** Dialogs drawn with the app toolkit (rounded card, current palette) so they match light/dark/accent exactly. */
object Dialogs {
    fun show(ctx: Context, ui: Ui, build: (LinearLayout, dismiss: () -> Unit) -> Unit): Dialog {
        val d = Dialog(ctx, R.style.Theme_Jarvis_Dialog)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val card = ui.card(padH = 20, padV = 20, gap = 12, radius = 24)
        build(card) { d.dismiss() }
        val frame = android.widget.FrameLayout(ctx)
        frame.setPadding(ui.dp(20), ui.dp(20), ui.dp(20), ui.dp(20))
        frame.addView(ui.scroll(card), android.widget.FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER))
        d.setContentView(frame, ViewGroup.LayoutParams(MATCH, WRAP))
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(minOf(ctx.resources.displayMetrics.widthPixels, ui.dp(480)), WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
        }
        d.show()
        return d
    }

    fun message(ctx: Context, ui: Ui, title: String, body: String, button: String = "OK", onDone: (() -> Unit)? = null): Dialog = show(ctx, ui) { c, dismiss ->
        c.addView(ui.title(title, 19f)); c.addView(ui.text(body, 14.5f, ui.pal.text, selectable = true))
        c.addView(ui.button(button, Ui.Kind.PRIMARY) { dismiss(); onDone?.invoke() })
    }

    fun confirm(ctx: Context, ui: Ui, title: String, body: String, yes: String, danger: Boolean = false, no: String = "Cancel", onYes: () -> Unit): Dialog = show(ctx, ui) { c, dismiss ->
        c.addView(ui.title(title, 19f)); c.addView(ui.text(body, 14.5f))
        val row = ui.row(12) {
            add(ui.button(no, Ui.Kind.GHOST) { dismiss() }, 0, WRAP, 1f)
            add(ui.button(yes, if (danger) Ui.Kind.DANGER else Ui.Kind.PRIMARY) { dismiss(); onYes() }, 0, WRAP, 1f)
        }
        c.addView(row)
    }

    /** A list of choices; [onPick] gets the index. */
    fun choose(ctx: Context, ui: Ui, title: String, options: List<String>, selected: Int = -1, onPick: (Int) -> Unit): Dialog = show(ctx, ui) { c, dismiss ->
        c.addView(ui.title(title, 19f))
        options.forEachIndexed { i, label ->
            c.addView(ui.settingRow(label, icon = if (i == selected) "check-circle" else null, chevron = false) { dismiss(); onPick(i) })
        }
    }

    /** One-line text input; [onDone] gets the text when the user taps Save. */
    fun input(ctx: Context, ui: Ui, title: String, hint: String, initial: String = "", secret: Boolean = false, multiline: Boolean = false, onDone: (String) -> Unit): Dialog = show(ctx, ui) { c, dismiss ->
        c.addView(ui.title(title, 19f))
        val f = ui.field(hint, initial, secret = secret, multiline = multiline)
        c.addView(f)
        c.addView(ui.row(12) {
            add(ui.button("Cancel", Ui.Kind.GHOST) { dismiss() }, 0, WRAP, 1f)
            add(ui.button("Save") { dismiss(); onDone(f.text.toString()) }, 0, WRAP, 1f)
        })
    }
}
