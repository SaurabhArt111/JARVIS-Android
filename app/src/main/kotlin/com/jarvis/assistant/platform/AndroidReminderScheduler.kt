package com.jarvis.assistant.platform

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.jarvis.assistant.receivers.ReminderAlarmReceiver
import com.jarvis.core.ports.Reminder
import com.jarvis.core.ports.ReminderScheduler
import com.jarvis.core.ports.ScheduleMode
import com.jarvis.core.ports.ScheduleResult
import com.jarvis.core.util.JarvisClock
import com.jarvis.core.util.JarvisLog
import java.time.ZoneId

/**
 * Reminders on AlarmManager.
 *  - exact (setExactAndAllowWhileIdle) only when Android lets this app: canScheduleExactAlarms() on API 31+; otherwise setAndAllowWhileIdle and the result says INEXACT
 *    (the user is told it may be late and how to fix it);
 *  - one immutable, explicit PendingIntent per reminder (data URI = the id, so two reminders can never share one even if their request codes collide);
 *  - [isScheduled] asks the system whether that PendingIntent is registered (FLAG_NO_CREATE). It cannot look inside AlarmManager, so the receiver cancels the
 *    PendingIntent when an alarm fires, and a fired alarm is never reported as still scheduled.
 */
class AndroidReminderScheduler(context: Context, private val clock: JarvisClock, private val log: JarvisLog = JarvisLog.None) : ReminderScheduler {
    private val ctx = context.applicationContext
    private val am: AlarmManager = ctx.getSystemService(AlarmManager::class.java)

    private fun intent(id: String) = Intent(ctx, ReminderAlarmReceiver::class.java)
        .setAction(Intents.ACTION_REMINDER_FIRE).setData(Uri.parse("jarvis://reminder/$id")).putExtra(Intents.EXTRA_REMINDER_ID, id)

    private fun pending(id: String, flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(ctx, id.hashCode(), intent(id), flags or PendingIntent.FLAG_IMMUTABLE)

    override fun canScheduleExact(): Boolean = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()

    override fun schedule(reminder: Reminder): ScheduleResult {
        val zone = if (reminder.floating) ZoneId.systemDefault() else ZoneId.of(reminder.zoneId)
        val now = clock.nowMs()
        // A time that has already passed (e.g. after a reboot) is registered to fire almost immediately instead of being dropped.
        val at = maxOf(reminder.triggerAt(zone).toEpochMilli(), now + 1500)
        val pi = pending(reminder.id, PendingIntent.FLAG_UPDATE_CURRENT) ?: return ScheduleResult(ScheduleMode.NONE, at, "Android did not create the alarm")
        val exactAllowed = canScheduleExact()
        try {
            if (exactAllowed) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                return ScheduleResult(ScheduleMode.EXACT, at)
            }
        } catch (e: SecurityException) {
            log.w("Alarms", "exact alarm refused (permission changed) — using an inexact alarm")
        }
        return try {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            ScheduleResult(
                ScheduleMode.INEXACT, at,
                "Android only allows an approximate alarm for JARVIS right now, so it may ring a few minutes late. Allow \"Alarms & reminders\" for JARVIS in settings to make it exact.",
            )
        } catch (e: Exception) {
            log.e("Alarms", "alarm could not be set (${e.javaClass.simpleName})")
            pi.cancel()
            ScheduleResult(ScheduleMode.NONE, at, "Android refused to register the alarm (${e.javaClass.simpleName})")
        }
    }

    override fun cancel(reminderId: String) {
        pending(reminderId, PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it); it.cancel() }
    }

    override fun isScheduled(reminderId: String): Boolean = pending(reminderId, PendingIntent.FLAG_NO_CREATE) != null
}
