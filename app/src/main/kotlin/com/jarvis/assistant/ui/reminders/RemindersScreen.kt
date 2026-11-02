package com.jarvis.assistant.ui.reminders

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import com.jarvis.assistant.R
import com.jarvis.assistant.platform.FileActions
import com.jarvis.assistant.ui.kit.CountdownLabel
import com.jarvis.assistant.ui.kit.Dialogs
import com.jarvis.assistant.ui.kit.MATCH
import com.jarvis.assistant.ui.kit.Pickers
import com.jarvis.assistant.ui.kit.Screen
import com.jarvis.assistant.ui.kit.Themes
import com.jarvis.assistant.ui.kit.TopAction
import com.jarvis.assistant.ui.kit.Ui
import com.jarvis.assistant.ui.kit.ViewModels
import com.jarvis.assistant.ui.kit.WRAP
import com.jarvis.assistant.ui.kit.add
import com.jarvis.assistant.ui.kit.collectWhileAttached
import com.jarvis.core.format.DayLabels
import com.jarvis.core.format.RelativeWords
import com.jarvis.core.model.Requirement
import com.jarvis.core.ports.Note
import com.jarvis.core.ports.Reminder
import com.jarvis.core.ports.ReminderState
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date

/** Reminders (upcoming and earlier), running timers and notes. Everything here is real data from the same stores the assistant uses. */
class RemindersScreen : Screen() {
    override val id = "reminders"
    override val topLevel = true
    override fun title() = activity.getString(R.string.ui_tab_reminders)

    private val vm: RemindersViewModel get() = ViewModels.get("reminders") { RemindersViewModel(app) }
    private val timeFmt: DateFormat by lazy { DateFormat.getTimeInstance(DateFormat.SHORT) }
    private fun s(id: Int, vararg a: Any) = if (a.isEmpty()) activity.getString(id) else activity.getString(id, *a)

    override fun actions(): List<TopAction> = listOf(
        TopAction("plus", s(R.string.ui_rem_add)) { if (vm.state.value.tab == RemindersViewModel.Tab.NOTES) addNote() else addReminder() },
    )

    override fun onShown() { if (RemindersState.consumeNotesTab()) vm.setTab(RemindersViewModel.Tab.NOTES); vm.refresh() }
    override fun onActivityResumed() { vm.refresh() }

    // ================================================================================================ build
    override fun build(): View {
        val ui = ui; val pal = ui.pal
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val tabs = ui.row(8) { setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(8)) }
        root.addView(tabs)
        val content = ui.column(padH = 16, padV = 4, gap = 10)
        root.addView(ui.scroll(content), LinearLayout.LayoutParams(MATCH, 0, 1f))

        root.collectWhileAttached(vm.state) { st ->
            tabs.removeAllViews()
            tabs.addView(ui.chip(s(R.string.ui_rem_tab_reminders), st.tab == RemindersViewModel.Tab.REMINDERS) { vm.setTab(RemindersViewModel.Tab.REMINDERS) })
            tabs.addView(ui.chip(s(R.string.ui_rem_tab_notes), st.tab == RemindersViewModel.Tab.NOTES) { vm.setTab(RemindersViewModel.Tab.NOTES) })
            content.removeAllViews()
            when {
                st.loading -> content.addView(ui.loadingState())
                st.failed -> content.addView(ui.errorState(s(R.string.ui_rem_error)) { vm.refresh() })
                st.tab == RemindersViewModel.Tab.REMINDERS -> reminders(content, st)
                else -> notes(content, st)
            }
        }
        return root
    }

    // ================================================================================================ reminders tab
    private fun reminders(c: LinearLayout, st: RemindersViewModel.State) {
        val ui = ui; val pal = ui.pal
        if (st.notificationsMissing) c.addView(ui.banner(s(R.string.ui_rem_no_notifications), pal.warn, "bell-off", s(R.string.ui_allow) to { activity.grant(Requirement.NOTIFICATIONS) { vm.refresh() } }))
        if (st.exactMissing) c.addView(ui.banner(s(R.string.ui_rem_no_exact), pal.warn, "clock", s(R.string.ui_allow) to { activity.grant(Requirement.EXACT_ALARM) { vm.refresh() } }))

        if (st.timers.isNotEmpty()) {
            c.addView(ui.sectionHeader(s(R.string.ui_rem_timers)))
            st.timers.forEach { t ->
                c.addView(ui.card(14, 12, 4) {
                    addView(ui.row(10) {
                        addView(ui.icon("clock", 20, pal.accent))
                        addView(ui.text(t.label.ifBlank { s(R.string.ui_rem_timer) }, 15f, bold = true), LinearLayout.LayoutParams(0, WRAP, 1f))
                        addView(CountdownLabel(ui, t.endsAtMs, s(R.string.ui_timer_done)))
                    })
                    if (!t.exact) addView(ui.muted(s(R.string.ui_rem_timer_approx), 12f))
                })
            }
            c.addView(ui.button(s(R.string.ui_rem_stop_timers), Ui.Kind.GHOST) { vm.stopTimers { n -> toastText(s(R.string.ui_rem_timers_stopped, n)) } })
        }

        c.addView(ui.sectionHeader(s(R.string.ui_rem_upcoming)))
        if (st.upcoming.isEmpty()) c.addView(ui.emptyState("bell", s(R.string.ui_rem_none), s(R.string.ui_rem_none_body), s(R.string.ui_rem_add) to { addReminder() }))
        else st.upcoming.forEach { c.addView(reminderCard(it)) }

        if (st.earlier.isNotEmpty()) {
            c.addView(ui.sectionHeader(s(R.string.ui_rem_earlier)))
            st.earlier.take(40).forEach { c.addView(reminderCard(it)) }
        }
        c.addView(ui.spacer(24))
    }

    private fun whenText(r: Reminder): String {
        val zone = ZoneId.of(r.zoneId.ifBlank { ZoneId.systemDefault().id })
        val today = LocalDateTime.now(ZoneId.systemDefault()).toLocalDate()
        val day = DayLabels.of(r.due.toLocalDate(), today, RelativeWords(s(R.string.ui_today), s(R.string.ui_tomorrow), s(R.string.ui_yesterday)))
        val time = timeFmt.format(Date.from(r.due.atZone(zone).toInstant()))
        val z = if (!r.floating && zone != ZoneId.systemDefault()) " (${zone.id})" else ""
        return "$day · $time$z"
    }

    private fun reminderCard(r: Reminder): View {
        val ui = ui; val pal = ui.pal
        val (icon, tint, label) = when (r.state) {
            ReminderState.PENDING -> Triple(if (r.isAlarm) "clock" else "bell", pal.accent, null)
            ReminderState.FIRED -> Triple("bell", pal.warn, s(R.string.ui_rem_state_rang))
            ReminderState.DONE -> Triple("check-circle", pal.ok, s(R.string.ui_rem_state_done))
            ReminderState.CANCELLED -> Triple("bell-off", pal.muted, s(R.string.ui_rem_state_cancelled))
        }
        return ui.card(14, 12, 4) {
            background = ui.ripple(ui.rounded(pal.panel, 18, pal.border), 18); isClickable = true; isFocusable = true
            setOnClickListener { reminderActions(r) }
            addView(ui.row(12) {
                addView(ui.icon(icon, 22, tint))
                addView(ui.column(gap = 2) {
                    addView(ui.text(r.text, 15f, if (r.state == ReminderState.PENDING || r.state == ReminderState.FIRED) pal.text else pal.muted))
                    addView(ui.muted(whenText(r) + if (label != null) " · $label" else "", 12.5f))
                }, LinearLayout.LayoutParams(0, WRAP, 1f))
                addView(ui.icon("chevron-right", 18, pal.muted))
            })
        }
    }

    private fun reminderActions(r: Reminder) {
        val open = r.state == ReminderState.PENDING || r.state == ReminderState.FIRED
        val entries = ArrayList<Pair<String, () -> Unit>>()
        if (open) entries += s(R.string.ui_rem_mark_done) to { vm.markDone(r.id) { handle(it) } }
        entries += (if (open) s(R.string.ui_rem_change_time) else s(R.string.ui_rem_restore)) to { pickTime(r.due.coerceAtLeast(LocalDateTime.now().plusMinutes(5))) { due -> vm.reschedule(r.id, due) { res -> handle(res) } } }
        entries += s(R.string.ui_rem_edit_text) to { Dialogs.input(activity, ui, s(R.string.ui_rem_edit_text), s(R.string.ui_rem_text_hint), r.text) { t -> if (t.isNotBlank()) vm.setText(r.id, t) { handle(it) } } }
        if (r.state == ReminderState.PENDING) entries += s(R.string.ui_rem_cancel) to { vm.cancel(r.id) { handle(it) } }
        entries += s(R.string.ui_rem_delete) to {
            Dialogs.confirm(activity, ui, s(R.string.ui_rem_delete_title), s(R.string.ui_rem_delete_body, r.text), s(R.string.ui_delete_yes), danger = true) { vm.deleteReminder(r.id) { handle(it) } }
        }
        Dialogs.choose(activity, ui, r.text.take(60), entries.map { it.first }) { i -> entries[i].second() }
    }

    private fun addReminder() {
        Dialogs.input(activity, ui, s(R.string.ui_rem_add), s(R.string.ui_rem_text_hint)) { text ->
            if (text.isBlank()) { toastText(s(R.string.ui_rem_need_text)); return@input }
            pickTime(LocalDateTime.now().plusHours(1).withSecond(0).withNano(0)) { due -> vm.add(text, due) { handle(it) } }
        }
    }

    private fun pickTime(initial: LocalDateTime, onPicked: (LocalDateTime) -> Unit) {
        Pickers.dateTime(activity, initial) { due ->
            if (!due.isAfter(LocalDateTime.now())) Dialogs.message(activity, ui, s(R.string.ui_rem_past_title), s(R.string.ui_rem_past_body))
            else onPicked(due)
        }
    }

    private fun handle(r: RemindersViewModel.Result) {
        when (r) {
            RemindersViewModel.Result.Done -> {}
            RemindersViewModel.Result.Inexact -> toastText(s(R.string.ui_rem_saved_inexact))
            is RemindersViewModel.Result.Failed -> Dialogs.message(activity, ui, s(R.string.ui_rem_failed_title), r.message)
        }
    }

    // ================================================================================================ notes tab
    private fun notes(c: LinearLayout, st: RemindersViewModel.State) {
        val ui = ui; val pal = ui.pal
        if (st.notes.isEmpty()) { c.addView(ui.emptyState("file-text", s(R.string.ui_note_none), s(R.string.ui_note_none_body), s(R.string.ui_note_add) to { addNote() })); return }
        val dateFmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        st.notes.forEach { n ->
            c.addView(ui.card(14, 12, 4) {
                background = ui.ripple(ui.rounded(pal.panel, 18, pal.border), 18); isClickable = true; isFocusable = true
                setOnClickListener { noteActions(n) }
                addView(ui.text(n.text, 15f))
                addView(ui.row(6) {
                    addView(ui.muted(dateFmt.format(Date.from(n.updatedAt)), 12f))
                    if (n.reminderId != null) { addView(ui.icon("bell", 12, pal.accent)) }
                })
            })
        }
        c.addView(ui.spacer(24))
    }

    private fun addNote() {
        Dialogs.input(activity, ui, s(R.string.ui_note_add), s(R.string.ui_note_hint), multiline = true) { t ->
            if (t.isBlank()) toastText(s(R.string.ui_note_need_text)) else vm.addNote(t) { handle(it) }
        }
    }

    private fun noteActions(n: Note) {
        val entries = listOf<Pair<String, () -> Unit>>(
            s(R.string.ui_note_edit) to { Dialogs.input(activity, ui, s(R.string.ui_note_edit), s(R.string.ui_note_hint), n.text, multiline = true) { t -> if (t.isNotBlank()) vm.editNote(n.id, t) { handle(it) } } },
            s(R.string.ui_share) to { if (!FileActions.shareText(activity, n.text)) toastText(s(R.string.ui_err_generic)) },
            s(R.string.ui_rem_delete) to { Dialogs.confirm(activity, ui, s(R.string.ui_note_delete_title), s(R.string.ui_note_delete_body), s(R.string.ui_delete_yes), danger = true) { vm.deleteNote(n.id) { handle(it) } } },
        )
        Dialogs.choose(activity, ui, s(R.string.ui_rem_tab_notes), entries.map { it.first }) { i -> entries[i].second() }
    }

    private fun toastText(t: String) = Toast.makeText(activity, t, Toast.LENGTH_SHORT).show()
}
