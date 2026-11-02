package com.jarvis.core.organizer

import com.jarvis.core.ports.Reminder
import com.jarvis.core.ports.ReminderState
import com.jarvis.core.ports.ScheduleMode
import com.jarvis.core.ports.ScheduleResult
import com.jarvis.core.ports.Services
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Result of creating or moving a reminder. [error] != null means NOTHING was created / changed. */
data class AddResult(val reminder: Reminder?, val schedule: ScheduleResult?, val error: String?) {
    val ok: Boolean get() = error == null && reminder != null
    /** The system may deliver it a little late (exact alarms not allowed). The reminder still exists and is scheduled. */
    val inexact: Boolean get() = schedule?.mode == ScheduleMode.INEXACT
}

/** What [ReminderService.rescheduleAllPending] did. Everything is re-read from the store / scheduler, nothing is assumed. */
data class RescheduleReport(
    val rescheduled: Int,
    /** Pending reminders whose time passed while the device was off / the app was killed (still PENDING: the caller should deliver them via [ReminderService.onFired]). */
    val overdue: List<Reminder>,
    /** Reminders the scheduler could not register. They stay PENDING in the store so the next attempt can retry. */
    val failed: List<Reminder>,
    /** Floating reminders whose stored zone was updated because the device time zone changed. */
    val zoneAdjusted: Int,
)

/**
 * Persistent reminders (port of Backend/Scheduler.py reminder half), with the honesty rules of the Android port:
 *
 *  * a reminder is only created when the alarm was REALLY registered (scheduler says so AND `isScheduled` confirms) and the row can be read back;
 *  * cancel / done are flags, never deletions; every change keeps the earlier wording and time in `history`
 *    as `"<changed ISO minute>|<old due ISO minute>|<old text>"`;
 *  * all mutations are serialized by one mutex, so a double-tapped "remind me" cannot interleave.
 */
class ReminderService(private val services: Services) {
    private val lock = Mutex()
    private val store get() = services.reminders
    private val scheduler get() = services.reminderScheduler

    private fun zone(): ZoneId = services.clock.zone()
    private fun nowLocal(): LocalDateTime = LocalDateTime.ofInstant(services.clock.instant(), zone())
    private fun stamp(): String = nowLocal().truncatedTo(ChronoUnit.MINUTES).toString()
    private fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(8)
    private fun entry(r: Reminder) = "${stamp()}|${r.due}|${r.text}"

    private fun trySchedule(r: Reminder): ScheduleResult = try {
        scheduler.schedule(r)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        services.log.w("Reminders", "scheduler threw: ${e.message}", e)
        ScheduleResult(ScheduleMode.NONE, 0, e.message ?: "scheduler error")
    }

    private fun why(s: ScheduleResult) = s.note.ifBlank { "the system would not accept the alarm" }

    // ------------------------------------------------------------------------------------------------ create
    /** Schedules FIRST, then stores, then reads both back. When any step fails nothing is left behind and [AddResult.error] says why. */
    suspend fun add(text: String, due: LocalDateTime, isAlarm: Boolean = false, noteId: String? = null): AddResult =
        lock.withLock {
            val clean = text.trim()
            if (clean.isEmpty()) return AddResult(null, null, "A reminder needs some text.")
            var id = newId()
            while (store.get(id) != null) id = newId()
            val r = Reminder(
                id = id, text = clean, due = due.truncatedTo(ChronoUnit.MINUTES), zoneId = zone().id, floating = true,
                state = ReminderState.PENDING, isAlarm = isAlarm, createdAt = services.clock.instant(), noteId = noteId,
            )
            val sched = trySchedule(r)
            if (!sched.scheduled) return AddResult(null, sched, "I couldn't register the alarm (${why(sched)}), so I did not create the reminder.")
            if (!scheduler.isScheduled(id)) {
                scheduler.cancel(id)
                return AddResult(null, sched, "The system did not confirm the alarm, so I did not create the reminder.")
            }
            val saved = try {
                store.insert(r)
                store.get(id)
            } catch (e: CancellationException) {
                scheduler.cancel(id); throw e
            } catch (e: Exception) {
                services.log.w("Reminders", "insert failed: ${e.message}", e)
                null
            }
            if (saved == null) {
                scheduler.cancel(id)
                return AddResult(null, sched, "I couldn't save the reminder, so I did not create it.")
            }
            AddResult(saved, sched, null)
        }

    // ------------------------------------------------------------------------------------------------ change
    /**
     * Moves a reminder (a fired / done / cancelled one comes back to life). The old time and wording stay in `history`.
     * Returns the reminder, or null when it does not exist OR could not be re-scheduled (use [rescheduleChecked] to tell which).
     */
    suspend fun reschedule(id: String, due: LocalDateTime): Reminder? = rescheduleChecked(id, due).reminder

    suspend fun rescheduleChecked(id: String, due: LocalDateTime): AddResult = lock.withLock {
        val r = store.get(id) ?: return AddResult(null, null, "That reminder no longer exists.")
        val moved = r.copy(
            due = due.truncatedTo(ChronoUnit.MINUTES), zoneId = if (r.floating) zone().id else r.zoneId,
            state = ReminderState.PENDING, firedAt = null, history = r.history + entry(r),
        )
        val sched = trySchedule(moved)
        if (!sched.scheduled) return AddResult(null, sched, "I couldn't register the new alarm (${why(sched)}), so the reminder is unchanged.")
        if (!scheduler.isScheduled(id)) return AddResult(null, sched, "The system did not confirm the new alarm, so the reminder is unchanged.")
        val saved = store.update(moved)?.let { store.get(id) }
        if (saved == null) return AddResult(null, sched, "I couldn't save the change.")
        AddResult(saved, sched, null)
    }

    suspend fun setText(id: String, text: String): Reminder? = lock.withLock {
        if (text.isBlank()) return null
        val r = store.get(id) ?: return null
        store.update(r.copy(text = text.trim(), history = r.history + entry(r)))?.let { store.get(id) }
    }

    /** PENDING / FIRED -> DONE, and the alarm is withdrawn. Anything else is returned unchanged. */
    suspend fun markDone(id: String): Reminder? = lock.withLock { finish(id, ReminderState.DONE) }

    /** PENDING / FIRED -> CANCELLED (a flag: the reminder stays in the list and can be brought back with [reschedule]). */
    suspend fun cancel(id: String): Reminder? = lock.withLock { finish(id, ReminderState.CANCELLED) }

    private suspend fun finish(id: String, to: ReminderState): Reminder? {
        val r = store.get(id) ?: return null
        if (r.state != ReminderState.PENDING && r.state != ReminderState.FIRED) return r
        scheduler.cancel(id)
        return store.update(r.copy(state = to))?.let { store.get(id) }
    }

    // ------------------------------------------------------------------------------------------------ read
    suspend fun get(id: String): Reminder? = store.get(id)
    suspend fun all(): List<Reminder> = store.all().sortedBy { it.due }
    suspend fun upcoming(): List<Reminder> = all().filter { it.pending }

    /** Finished reminders (fired, done or cancelled), newest due first. */
    suspend fun recentDone(n: Int): List<Reminder> = all().filter { !it.pending }.sortedByDescending { it.due }.take(n)

    // ------------------------------------------------------------------------------------------------ platform callbacks
    /**
     * Called by the alarm receiver. PENDING -> FIRED and returns the reminder to deliver. Returns null when there is nothing to
     * deliver: unknown id, already fired / done / cancelled (so a duplicate or stale alarm is silent), or the alarm came much too
     * early (e.g. the clock was changed) - in that case it is registered again for its real time.
     */
    suspend fun onFired(id: String): Reminder? = lock.withLock {
        val r = store.get(id) ?: return null
        if (!r.pending) return null
        val now = services.clock.instant()
        if (r.triggerAt(zone()).isAfter(now.plusSeconds(EARLY_TOLERANCE_S))) {
            trySchedule(r)
            return null
        }
        store.update(r.copy(state = ReminderState.FIRED, firedAt = now))?.let { store.get(id) }
    }

    /** After boot / time zone / time change / app update: registers every PENDING reminder again. Safe to call any number of times. */
    suspend fun rescheduleAllPending(): RescheduleReport = lock.withLock {
        val now: Instant = services.clock.instant()
        var ok = 0
        var zones = 0
        val overdue = ArrayList<Reminder>()
        val failed = ArrayList<Reminder>()
        for (r0 in store.all().filter { it.pending }) {
            var r = r0
            if (r.floating && r.zoneId != zone().id) {
                r = store.update(r.copy(zoneId = zone().id)) ?: r
                zones++
            }
            val s = trySchedule(r)
            if (s.scheduled && scheduler.isScheduled(r.id)) ok++ else failed.add(r)
            if (!r.triggerAt(zone()).isAfter(now)) overdue.add(r)
        }
        RescheduleReport(ok, overdue, failed, zones)
    }

    companion object {
        const val EARLY_TOLERANCE_S = 120L
    }
}
