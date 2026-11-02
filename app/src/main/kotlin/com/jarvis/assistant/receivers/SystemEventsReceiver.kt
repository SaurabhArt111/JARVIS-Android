package com.jarvis.assistant.receivers

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jarvis.assistant.JarvisApp
import com.jarvis.core.model.Requirement
import com.jarvis.core.ports.PermState
import com.jarvis.core.settings.Keys
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Alarms do not survive a reboot, an app update, or (for floating reminders) a time-zone change, and exact-alarm access can be switched at any time. So on
 * BOOT_COMPLETED, MY_PACKAGE_REPLACED, TIMEZONE_CHANGED, TIME_SET and SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED every pending reminder and timer is registered again
 * (and read back). Reminders whose time already passed are NOT spoken (the phone may just have been switched on at 3 a.m.): they are marked fired and shown as notifications.
 *
 * Background listening cannot be restarted here: since Android 14 a microphone foreground service may not be started from BOOT_COMPLETED. If the user had it on, a
 * "tap to resume listening" notification is posted instead.
 */
class SystemEventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val handled = action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED || action == Intent.ACTION_TIMEZONE_CHANGED ||
            action == Intent.ACTION_TIME_CHANGED || action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
        if (!handled) return
        val c = JarvisApp.container
        val pending = goAsync()
        c.scope.launch {
            try {
                withTimeout(8_000) {
                    val report = c.reminderService.rescheduleAllPending()
                    val finishedTimers = c.timers.restoreAll()
                    c.appLog.i("System", "$action: ${report.rescheduled} reminder(s) re-registered, ${report.overdue.size} overdue, ${report.failed.size} could not be scheduled, ${finishedTimers.size} timer(s) finished while off")

                    val missed = ArrayList<com.jarvis.core.ports.Reminder>()
                    for (r in report.overdue) {
                        c.services.reminderScheduler.cancel(r.id)
                        c.reminderService.onFired(r.id)?.let { missed.add(it) }
                    }
                    if (missed.size in 1..3) missed.forEach { c.notificationHelper.postReminder(it) } else c.notificationHelper.postOverdueSummary(missed.map { it.text })
                    finishedTimers.forEach { c.notificationHelper.postTimerDone(it.id, it.label) }

                    if ((action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) && c.settings.flag(Keys.ISLAND_ENABLED, false)) {
                        // a foreground service that is not of a restricted type may be started from these broadcasts; the result is logged, not assumed
                        val r = try { c.islandSwitch.sync() } catch (e: Exception) { null }
                        c.appLog.i("System", "island restart after $action: ${r ?: "failed"}")
                    }
                    if ((action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) &&
                        c.settings.flag(Keys.BACKGROUND_LISTENING, false) && c.settings.flag(Keys.LISTEN_ON_BOOT_PROMPT, true) &&
                        c.permissions.state(Requirement.MICROPHONE) == PermState.GRANTED
                    ) c.notificationHelper.postResumeListening()
                }
            } catch (e: Exception) {
                c.appLog.e("System", "re-registering alarms failed (${e.javaClass.simpleName})", e)
            } finally { pending.finish() }
        }
    }
}
