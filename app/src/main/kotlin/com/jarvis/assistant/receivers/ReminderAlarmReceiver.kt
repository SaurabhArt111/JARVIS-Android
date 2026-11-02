package com.jarvis.assistant.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.platform.Intents
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * An AlarmManager alarm went off (a reminder or a timer). Order matters: the reminder is marked FIRED and its NOTIFICATION is posted first (that is the delivery the
 * user can rely on, even with no sound or no speech), then the engine speaks it if the user wants that. The receiver has ~10 s, so speech is cut off after 8 s.
 * A stale, duplicate or early alarm finds the reminder not PENDING and stays silent.
 */
class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val c = JarvisApp.container
        val pending = goAsync()
        c.scope.launch {
            try {
                withTimeout(8_000) {
                    when (intent.action) {
                        Intents.ACTION_REMINDER_FIRE -> {
                            val id = intent.getStringExtra(Intents.EXTRA_REMINDER_ID) ?: return@withTimeout
                            c.services.reminderScheduler.cancel(id)         // the alarm has fired: its PendingIntent must not look "scheduled" any more
                            val r = c.reminderService.onFired(id)
                            if (r == null) { c.appLog.i("Alarm", "a reminder alarm was ignored (missing, early, cancelled or already delivered)"); return@withTimeout }
                            val shown = c.notificationHelper.postReminder(r)
                            c.appLog.i("Alarm", "reminder fired; notification ${if (shown) "posted" else "NOT shown (notifications are off)"}")
                            c.engine.deliverReminder(id)
                        }
                        Intents.ACTION_TIMER_FIRE -> {
                            val id = intent.getStringExtra(Intents.EXTRA_TIMER_ID) ?: return@withTimeout
                            val t = c.timers.onFired(id)
                            if (t == null) { c.appLog.i("Alarm", "a timer alarm was ignored (already stopped)"); return@withTimeout }
                            c.notificationHelper.postTimerDone(t.id, t.label)
                            c.engine.deliverReminder(t.label, isTimer = true)
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                c.appLog.w("Alarm", "speaking the alarm took too long and was cut off (the notification was already posted)")
            } catch (e: Exception) {
                c.appLog.e("Alarm", "alarm handling failed (${e.javaClass.simpleName})", e)
            } finally {
                pending.finish()
            }
        }
    }
}
