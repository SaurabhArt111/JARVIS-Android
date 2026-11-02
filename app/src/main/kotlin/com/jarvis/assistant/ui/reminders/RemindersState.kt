package com.jarvis.assistant.ui.reminders

/** One-shot hint from outside the Reminders screen ("open my notes"): which sub-tab to show next time. */
object RemindersState {
    @Volatile private var notes = false
    fun requestNotesTab() { notes = true }
    fun consumeNotesTab(): Boolean { val v = notes; notes = false; return v }
}
