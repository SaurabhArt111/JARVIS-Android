package com.jarvis.assistant.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.platform.Intents
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** The Done / Snooze buttons on a reminder notification. The notification is removed only when the change really happened. */
class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(Intents.EXTRA_REMINDER_ID) ?: return
        val nid = intent.getIntExtra(Intents.EXTRA_NOTIFICATION_ID, -1)
        val c = JarvisApp.container
        val pending = goAsync()
        c.scope.launch {
            try {
                when (intent.action) {
                    Intents.ACTION_REMINDER_DONE -> {
                        val r = c.reminderService.markDone(id)
                        if (r != null) { if (nid >= 0) c.notificationHelper.cancel(nid) } else c.appLog.w("Reminder", "Done: reminder not found")
                    }
                    Intents.ACTION_REMINDER_SNOOZE -> {
                        val clock = c.services.clock
                        val at = LocalDateTime.ofInstant(clock.instant(), clock.zone()).plusMinutes(Intents.SNOOZE_MINUTES).truncatedTo(ChronoUnit.MINUTES)
                        val r = c.reminderService.reschedule(id, at)
                        if (r != null) { if (nid >= 0) c.notificationHelper.cancel(nid) } else c.appLog.w("Reminder", "Snooze failed — the notification was kept so it isn't lost")
                    }
                }
            } catch (e: Exception) {
                c.appLog.e("Reminder", "notification action failed (${e.javaClass.simpleName})", e)
            } finally { pending.finish() }
        }
    }
}
