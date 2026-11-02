package com.jarvis.core.organizer

import com.jarvis.core.ports.Reminder
import com.jarvis.core.ports.ReminderScheduler
import com.jarvis.core.ports.ReminderStore
import com.jarvis.core.ports.Services
import com.jarvis.core.testing.FakeReminderScheduler
import com.jarvis.core.testing.InMemoryReminderStore
import com.jarvis.core.testing.TestEnv

/** A [Services] like env.services but with a different reminder store / scheduler (to inject failures). */
fun TestEnv.servicesWith(store: ReminderStore = reminders, sched: ReminderScheduler = scheduler) = Services(
    settings, clock, log, permissions, store, sched, notes, timers, clockApp, apps, contacts, calls, messaging, notifications,
    device, navigator, media, speaker, documents, memory, web, weather, images,
)

/** The alarm is "accepted" but the platform does not list it afterwards. */
class LyingScheduler(private val inner: FakeReminderScheduler = FakeReminderScheduler()) : ReminderScheduler by inner {
    override fun isScheduled(reminderId: String) = false
}

/** Saves, but cannot read the row back. */
class AmnesiacStore(private val inner: InMemoryReminderStore = InMemoryReminderStore()) : ReminderStore by inner {
    override suspend fun get(id: String): Reminder? = null
}
