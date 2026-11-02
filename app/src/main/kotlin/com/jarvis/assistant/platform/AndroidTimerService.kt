package com.jarvis.assistant.platform

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.jarvis.assistant.data.TimerStore
import com.jarvis.assistant.receivers.ReminderAlarmReceiver
import com.jarvis.core.ports.TimerInfo
import com.jarvis.core.ports.TimerService
import com.jarvis.core.util.JarvisClock
import com.jarvis.core.util.JarvisLog
import java.util.UUID

/**
 * Countdown timers: persisted (so a timer survives the app being killed and is re-registered after a reboot) and rung by AlarmManager, not by a coroutine that
 * would die with the process. [active] lists only timers whose alarm Android still has registered, so "a timer is running" is never an assumption.
 */
class AndroidTimerService(
    context: Context,
    private val store: TimerStore,
    private val clock: JarvisClock,
    private val log: JarvisLog = JarvisLog.None,
) : TimerService {
    private val ctx = context.applicationContext
    private val am: AlarmManager = ctx.getSystemService(AlarmManager::class.java)

    private fun intent(id: String) = Intent(ctx, ReminderAlarmReceiver::class.java)
        .setAction(Intents.ACTION_TIMER_FIRE).setData(Uri.parse("jarvis://timer/$id")).putExtra(Intents.EXTRA_TIMER_ID, id)

    private fun pending(id: String, flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(ctx, id.hashCode(), intent(id), flags or PendingIntent.FLAG_IMMUTABLE)

    private fun canExact() = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()

    /** Registers the alarm; returns whether it was exact, or null when Android refused. */
    private fun register(id: String, endsAtMs: Long): Boolean? {
        val pi = pending(id, PendingIntent.FLAG_UPDATE_CURRENT) ?: return null
        try {
            if (canExact()) { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endsAtMs, pi); return true }
        } catch (e: SecurityException) { log.w("Timers", "exact alarm refused — using an inexact one") }
        return try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endsAtMs, pi); false } catch (e: Exception) { pi.cancel(); null }
    }

    override fun start(seconds: Int, label: String): TimerInfo? {
        if (seconds <= 0) return null
        val id = UUID.randomUUID().toString().replace("-", "").take(8)
        val ends = clock.nowMs() + seconds * 1000L
        val exact = register(id, ends) ?: run { log.e("Timers", "Android refused to register the timer alarm"); return null }
        val info = TimerInfo(id, label.ifBlank { "Timer" }, seconds, ends, exact)
        return try {
            store.save(info); info
        } catch (e: Exception) {
            log.e("Timers", "could not save the timer (${e.javaClass.simpleName})")
            cancelAlarm(id); null
        }
    }

    private fun cancelAlarm(id: String) { pending(id, PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it); it.cancel() } }

    /** Stops every running timer; returns how many were actually running. */
    override fun stopAll(): Int {
        val running = active().size
        for (t in store.all()) cancelAlarm(t.id)
        store.removeAll()
        return running
    }

    override fun active(): List<TimerInfo> {
        val now = clock.nowMs()
        val exactNow = canExact()
        return store.all().filter { it.endsAtMs > now && pending(it.id, PendingIntent.FLAG_NO_CREATE) != null }.sortedBy { it.endsAtMs }.map { it.copy(exact = exactNow) }
    }

    /** The alarm for [id] fired: removes it from the store and returns it (null when it was already stopped, so a stale alarm stays silent). */
    fun onFired(id: String): TimerInfo? {
        cancelAlarm(id)
        val t = store.all().firstOrNull { it.id == id } ?: return null
        store.remove(id)
        return t
    }

    /**
     * After a reboot / update the alarms are gone: registers every stored timer that has time left again, and returns the ones that finished while the phone was
     * off (already removed from the store) so the caller can tell the user.
     */
    fun restoreAll(): List<TimerInfo> {
        val now = clock.nowMs()
        val finished = ArrayList<TimerInfo>()
        for (t in store.all()) {
            if (t.endsAtMs <= now) { store.remove(t.id); cancelAlarm(t.id); finished.add(t) }
            else if (pending(t.id, PendingIntent.FLAG_NO_CREATE) == null && register(t.id, t.endsAtMs) == null) log.w("Timers", "could not restore a timer")
        }
        return finished
    }
}
