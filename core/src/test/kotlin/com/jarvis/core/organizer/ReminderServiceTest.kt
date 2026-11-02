package com.jarvis.core.organizer

import com.jarvis.core.ports.ReminderState
import com.jarvis.core.testing.TestEnv
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ReminderServiceTest {
    private val env = TestEnv()
    private val svc = ReminderService(env.services)
    private fun at(d: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 10, d, h, m)

    @Test fun addSchedulesStoresAndReadsBack() = runBlocking {
        val res = svc.add("call mom", at(25, 17))
        assertTrue(res.ok)
        val r = res.reminder!!
        assertEquals("call mom", r.text)
        assertEquals(at(25, 17), r.due)
        assertEquals("Asia/Kolkata", r.zoneId)
        assertEquals(ReminderState.PENDING, r.state)
        assertTrue(env.scheduler.isScheduled(r.id))
        assertEquals(r, env.reminders.items[r.id])
        assertFalse(res.inexact)
    }

    @Test fun addReportsInexactSchedulingHonestly() = runBlocking {
        env.scheduler.exact = false
        val res = svc.add("stretch", at(25, 11))
        assertTrue(res.ok)
        assertTrue(res.inexact)
    }

    @Test fun addCreatesNothingWhenTheAlarmCannotBeRegistered() = runBlocking {
        env.scheduler.failToSchedule = true
        val res = svc.add("call mom", at(25, 17))
        assertFalse(res.ok)
        assertNull(res.reminder)
        assertNotNull(res.error)
        assertTrue(env.reminders.items.isEmpty())
    }

    @Test fun addCreatesNothingWhenThePlatformDoesNotConfirmTheAlarm() = runBlocking {
        val s = ReminderService(env.servicesWith(sched = LyingScheduler()))
        val res = s.add("call mom", at(25, 17))
        assertFalse(res.ok)
        assertTrue(env.reminders.items.isEmpty())
    }

    @Test fun addWithdrawsTheAlarmWhenTheRowCannotBeReadBack() = runBlocking {
        val sched = com.jarvis.core.testing.FakeReminderScheduler()
        val s = ReminderService(env.servicesWith(store = AmnesiacStore(), sched = sched))
        val res = s.add("call mom", at(25, 17))
        assertFalse(res.ok)
        assertTrue("no orphan alarm may remain", sched.scheduled.isEmpty())
    }

    @Test fun blankTextIsRefused() = runBlocking {
        assertFalse(svc.add("   ", at(25, 17)).ok)
        assertTrue(env.reminders.items.isEmpty())
    }

    @Test fun rescheduleMovesAndKeepsHistory() = runBlocking {
        val r = svc.add("call mom", at(25, 17)).reminder!!
        val moved = svc.reschedule(r.id, at(25, 18, 30))!!
        assertEquals(at(25, 18, 30), moved.due)
        assertEquals(1, moved.history.size)
        assertTrue(moved.history[0], moved.history[0].endsWith("|2026-10-25T17:00|call mom"))
        assertEquals(at(25, 18, 30), env.scheduler.scheduled[r.id]!!.due)
    }

    @Test fun rescheduleBringsAFinishedReminderBackToLife() = runBlocking {
        val r = svc.add("call mom", at(25, 17)).reminder!!
        svc.cancel(r.id)
        val back = svc.reschedule(r.id, at(26, 9))!!
        assertEquals(ReminderState.PENDING, back.state)
        assertTrue(env.scheduler.isScheduled(r.id))
    }

    @Test fun failedRescheduleLeavesTheReminderUntouched() = runBlocking {
        val r = svc.add("call mom", at(25, 17)).reminder!!
        env.scheduler.failToSchedule = true
        val res = svc.rescheduleChecked(r.id, at(25, 19))
        assertFalse(res.ok)
        assertNull(svc.reschedule(r.id, at(25, 19)))
        assertEquals(at(25, 17), env.reminders.items[r.id]!!.due)
        assertTrue(env.reminders.items[r.id]!!.history.isEmpty())
    }

    @Test fun rescheduleUnknownIdIsNull() = runBlocking { assertNull(svc.reschedule("nope", at(25, 19))) }

    @Test fun cancelIsAFlagNotADeletion() = runBlocking {
        val r = svc.add("call mom", at(25, 17)).reminder!!
        val c = svc.cancel(r.id)!!
        assertEquals(ReminderState.CANCELLED, c.state)
        assertFalse(env.scheduler.isScheduled(r.id))
        assertTrue(r.id in env.reminders.items)
        assertTrue(svc.upcoming().isEmpty())
        assertEquals(1, svc.recentDone(5).size)
    }

    @Test fun markDoneWithdrawsTheAlarmAndOnlyChangesLiveReminders() = runBlocking {
        val r = svc.add("call mom", at(25, 17)).reminder!!
        assertEquals(ReminderState.DONE, svc.markDone(r.id)!!.state)
        assertEquals(ReminderState.DONE, svc.cancel(r.id)!!.state)         // already finished: unchanged
        assertFalse(env.scheduler.isScheduled(r.id))
    }

    @Test fun setTextKeepsTheOldWording() = runBlocking {
        val r = svc.add("call mom", at(25, 17)).reminder!!
        val e = svc.setText(r.id, "call dad")!!
        assertEquals("call dad", e.text)
        assertTrue(e.history[0].endsWith("|call mom"))
        assertNull(svc.setText(r.id, "  "))
    }

    @Test fun onFiredDeliversOnceOnly() = runBlocking {
        val r = svc.add("call mom", at(25, 10, 0)).reminder!!
        val fired = svc.onFired(r.id)!!
        assertEquals(ReminderState.FIRED, fired.state)
        assertNotNull(fired.firedAt)
        assertNull("a duplicate alarm must be silent", svc.onFired(r.id))
        assertNull(svc.onFired("unknown"))
    }

    @Test fun onFiredIgnoresCancelledReminders() = runBlocking {
        val r = svc.add("call mom", at(25, 10, 0)).reminder!!
        svc.cancel(r.id)
        assertNull(svc.onFired(r.id))
    }

    @Test fun anAlarmThatArrivesMuchTooEarlyIsReRegisteredNotDelivered() = runBlocking {
        val r = svc.add("call mom", at(25, 17)).reminder!!
        env.scheduler.scheduled.clear()
        assertNull(svc.onFired(r.id))
        assertEquals(ReminderState.PENDING, svc.get(r.id)!!.state)
        assertTrue(env.scheduler.isScheduled(r.id))
    }

    @Test fun upcomingIsSortedAndListsOnlyPending() = runBlocking {
        svc.add("later", at(25, 20)); svc.add("sooner", at(25, 11)); val x = svc.add("gone", at(25, 12)).reminder!!
        svc.markDone(x.id)
        assertEquals(listOf("sooner", "later"), svc.upcoming().map { it.text })
    }

    @Test fun rescheduleAllPendingRegistersEverythingAgainAndReportsOverdue() = runBlocking {
        val a = svc.add("future", at(25, 17)).reminder!!
        val b = svc.add("missed", at(25, 9, 30)).reminder!!
        val c = svc.add("cancelled", at(25, 18)).reminder!!
        svc.cancel(c.id)
        env.scheduler.scheduled.clear()                                // simulate reboot
        val report = svc.rescheduleAllPending()
        assertEquals(2, report.rescheduled)
        assertEquals(listOf(b.id), report.overdue.map { it.id })
        assertTrue(report.failed.isEmpty())
        assertTrue(env.scheduler.isScheduled(a.id) && env.scheduler.isScheduled(b.id) && !env.scheduler.isScheduled(c.id))
    }

    @Test fun rescheduleAllPendingReportsFailuresAndKeepsTheReminders() = runBlocking {
        val a = svc.add("future", at(25, 17)).reminder!!
        env.scheduler.failToSchedule = true
        val report = svc.rescheduleAllPending()
        assertEquals(listOf(a.id), report.failed.map { it.id })
        assertEquals(ReminderState.PENDING, env.reminders.items[a.id]!!.state)
    }

    @Test fun floatingReminderKeepsItsWallClockTimeWhenTheZoneChanges() = runBlocking {
        val a = svc.add("call mom", at(25, 17)).reminder!!
        env.clock.setZone(ZoneId.of("Europe/London"))
        val report = svc.rescheduleAllPending()
        assertEquals(1, report.zoneAdjusted)
        val moved = env.reminders.items[a.id]!!
        assertEquals("Europe/London", moved.zoneId)
        assertEquals(at(25, 17), moved.due)                            // still 17:00 on the wall clock
        assertEquals(at(25, 17).atZone(ZoneId.of("Europe/London")).toInstant(), moved.triggerAt())
    }
}
