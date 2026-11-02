package com.jarvis.assistant.ui.kit

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.text.format.DateFormat
import java.time.LocalDateTime

/** System date then time pickers, returning one LocalDateTime (seconds dropped). Nothing is returned if the user cancels either step. */
object Pickers {
    fun dateTime(a: Activity, initial: LocalDateTime, onPicked: (LocalDateTime) -> Unit) {
        val dlg = DatePickerDialog(a, { _, y, m, d ->
            TimePickerDialog(a, { _, h, min -> onPicked(LocalDateTime.of(y, m + 1, d, h, min)) }, initial.hour, initial.minute, DateFormat.is24HourFormat(a)).show()
        }, initial.year, initial.monthValue - 1, initial.dayOfMonth)
        dlg.datePicker.minDate = System.currentTimeMillis() - 1000
        dlg.show()
    }
}

/** "4:12" counting down to [endsAtMs], ticking once a second while on screen. Shows [doneText] at zero. (The chat card uses the same idea; this one is for list rows.) */
class CountdownLabel(private val ui: Ui, private val endsAtMs: Long, private val doneText: String) : android.widget.TextView(ui.ctx) {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (endsAtMs - now <= 0) { text = doneText; setTextColor(ui.pal.ok); return }
            text = com.jarvis.core.format.Formats.countdown(endsAtMs, now)
            handler.postDelayed(this, 1000 - (now % 1000))
        }
    }
    init { setTextColor(ui.pal.accent); textSize = 18f * ui.fontScale; typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD); includeFontPadding = false }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); handler.removeCallbacks(tick); tick.run() }
    override fun onDetachedFromWindow() { handler.removeCallbacks(tick); super.onDetachedFromWindow() }
}
