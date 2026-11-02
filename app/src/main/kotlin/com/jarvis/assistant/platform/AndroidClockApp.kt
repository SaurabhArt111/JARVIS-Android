package com.jarvis.assistant.platform

import android.content.Intent
import android.provider.AlarmClock
import com.jarvis.core.ports.ActionResult
import com.jarvis.core.ports.ClockApp

/**
 * Alarms and timers handed to the system Clock app (AlarmClock intents; needs the normal SET_ALARM permission). The Clock app does not report back whether it
 * saved the alarm, so these results are never `verified`, and the assistant words them as "sent to the Clock app", not "alarm set".
 */
class AndroidClockApp(private val starter: ActivityStarter) : ClockApp {
    override fun setAlarm(hour: Int, minute: Int, label: String): ActionResult {
        if (hour !in 0..23 || minute !in 0..59) return ActionResult.fail("That isn't a valid time.")
        val i = Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, hour).putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return starter.start(i, "the Clock app", "Sent the alarm to the Clock app.")
    }

    override fun setTimer(seconds: Int, label: String): ActionResult {
        if (seconds !in 1..86_400) return ActionResult.fail("The Clock app takes timers from 1 second to 24 hours.")
        val i = Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, seconds).putExtra(AlarmClock.EXTRA_MESSAGE, label)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return starter.start(i, "the Clock app", "Sent the timer to the Clock app.")
    }
}
