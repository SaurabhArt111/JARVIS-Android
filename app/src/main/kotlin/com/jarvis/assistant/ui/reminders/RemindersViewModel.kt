package com.jarvis.assistant.ui.reminders

import com.jarvis.assistant.di.AppContainer
import com.jarvis.assistant.ui.kit.ViewModel
import com.jarvis.core.engine.EngineEvent
import com.jarvis.core.model.Requirement
import com.jarvis.core.organizer.AddResult
import com.jarvis.core.ports.Note
import com.jarvis.core.ports.PermState
import com.jarvis.core.ports.Reminder
import com.jarvis.core.ports.ReminderState
import com.jarvis.core.ports.TimerInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/** Reminders, notes and running timers, read from the same stores the assistant writes to, so what JARVIS says it saved is what you see here. */
class RemindersViewModel(private val app: AppContainer) : ViewModel({ tag, e -> app.appLog.e(tag, "unexpected ${e.javaClass.simpleName}", e) }) {
    enum class Tab { REMINDERS, NOTES }

    data class State(
        val tab: Tab = Tab.REMINDERS,
        val upcoming: List<Reminder> = emptyList(),
        val earlier: List<Reminder> = emptyList(),
        val notes: List<Note> = emptyList(),
        val timers: List<TimerInfo> = emptyList(),
        val loading: Boolean = true,
        val failed: Boolean = false,
        val exactMissing: Boolean = false,
        val notificationsMissing: Boolean = false,
    )

    sealed class Result {
        object Done : Result()
        data class Failed(val message: String) : Result()
        /** Saved, but Android may deliver it late. */
        object Inexact : Result()
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> get() = _state
    private var job: Job? = null

    private fun update(f: State.() -> State) { _state.value = _state.value.f() }

    init {
        refresh()
        // anything the assistant just did (a reminder set by voice, one that rang) shows up here without leaving the screen
        scope.launch { app.engine.events.collect { if (it is EngineEvent.AssistantSaid || it is EngineEvent.ReminderFired) refresh(150) } }
    }

    fun setTab(t: Tab) { update { copy(tab = t) } }

    fun refresh(delayMs: Long = 0) {
        job?.cancel()
        job = scope.launch {
            if (delayMs > 0) delay(delayMs)
            try {
                val all = app.reminderService.all()
                val upcoming = all.filter { it.state == ReminderState.PENDING }.sortedBy { it.due }
                val earlier = all.filter { it.state != ReminderState.PENDING }.sortedByDescending { it.due }
                val notes = app.noteService.all().sortedByDescending { it.updatedAt }
                val timers = app.timers.active().sortedBy { it.endsAtMs }
                update {
                    copy(
                        upcoming = upcoming, earlier = earlier, notes = notes, timers = timers, loading = false, failed = false,
                        exactMissing = upcoming.isNotEmpty() && app.permissions.state(Requirement.EXACT_ALARM) == PermState.DENIED,
                        notificationsMissing = app.permissions.state(Requirement.NOTIFICATIONS) == PermState.DENIED,
                    )
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                app.appLog.w("Reminders", "could not load (${e.javaClass.simpleName})")
                update { copy(loading = false, failed = true) }
            }
        }
    }

    private fun result(r: AddResult): Result = when {
        !r.ok -> Result.Failed(r.error ?: "That didn't work.")
        r.inexact -> Result.Inexact
        else -> Result.Done
    }

    private fun run(done: (Result) -> Unit, block: suspend () -> Result) {
        scope.launch {
            val r = try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { app.appLog.w("Reminders", "action failed (${e.javaClass.simpleName})"); Result.Failed("That didn't work.") }
            refresh()
            done(r)
        }
    }

    fun add(text: String, due: LocalDateTime, done: (Result) -> Unit) = run(done) { result(app.reminderService.add(text.trim(), due)) }
    fun reschedule(id: String, due: LocalDateTime, done: (Result) -> Unit) = run(done) { result(app.reminderService.rescheduleChecked(id, due)) }
    fun setText(id: String, text: String, done: (Result) -> Unit) = run(done) { if (app.reminderService.setText(id, text) != null) Result.Done else Result.Failed("I couldn't change it.") }
    fun markDone(id: String, done: (Result) -> Unit) = run(done) { if (app.reminderService.markDone(id) != null) Result.Done else Result.Failed("That reminder no longer exists.") }
    fun cancel(id: String, done: (Result) -> Unit) = run(done) { if (app.reminderService.cancel(id) != null) Result.Done else Result.Failed("That reminder no longer exists.") }

    /** Permanent. Only called after the user confirmed in a dialog. The alarm is withdrawn first so nothing rings for a reminder that is gone. */
    fun deleteReminder(id: String, done: (Result) -> Unit) = run(done) {
        app.services.reminderScheduler.cancel(id)
        if (app.services.reminders.deletePermanently(id)) Result.Done else Result.Failed("I couldn't delete it.")
    }

    fun addNote(text: String, done: (Result) -> Unit) = run(done) { app.noteService.add(text); Result.Done }
    fun editNote(id: String, text: String, done: (Result) -> Unit) = run(done) { if (app.noteService.edit(id, text) != null) Result.Done else Result.Failed("I couldn't change the note.") }
    /** Permanent. Only called after the user confirmed in a dialog. */
    fun deleteNote(id: String, done: (Result) -> Unit) = run(done) { if (app.services.notes.deletePermanently(id)) Result.Done else Result.Failed("I couldn't delete the note.") }

    fun stopTimers(done: (Int) -> Unit) { scope.launch { val n = app.timers.stopAll(); refresh(); done(n) } }
}
