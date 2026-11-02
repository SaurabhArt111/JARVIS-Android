package com.jarvis.core.greeting

import com.jarvis.core.greeting.Greeting.Period
import com.jarvis.core.ports.Reminder
import com.jarvis.core.ports.ReminderState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import kotlin.random.Random

class GreetingTest {
    private fun t(h: Int, m: Int = 0) = LocalDateTime.of(2026, 10, 25, h, m)
    private fun rem(id: String, text: String, due: LocalDateTime, state: ReminderState = ReminderState.PENDING) =
        Reminder(id, text, due, "Asia/Kolkata", state = state, createdAt = Instant.EPOCH)

    @Test fun periodBoundaries() {
        assertEquals(Period.LATE_NIGHT, Greeting.period(t(0)))
        assertEquals(Period.LATE_NIGHT, Greeting.period(t(3, 59)))
        assertEquals(Period.EARLY, Greeting.period(t(4)))
        assertEquals(Period.MORNING, Greeting.period(t(5)))
        assertEquals(Period.MORNING, Greeting.period(t(11, 59)))
        assertEquals(Period.AFTERNOON, Greeting.period(t(12)))
        assertEquals(Period.AFTERNOON, Greeting.period(t(16, 59)))
        assertEquals(Period.EVENING, Greeting.period(t(17)))
        assertEquals(Period.EVENING, Greeting.period(t(20, 59)))
        assertEquals(Period.NIGHT, Greeting.period(t(21)))
        assertEquals(Period.NIGHT, Greeting.period(t(23, 59)))
    }

    @Test fun addressDefaultsToLowercaseSir() {
        assertEquals("sir", Greeting.address(null))
        assertEquals("sir", Greeting.address("  "))
        assertEquals("sir", Greeting.address("Sir"))
        assertEquals("Tony", Greeting.address(" Tony "))
    }

    @Test fun everyPossibleGreetingMatchesItsPeriodAndAddress() {
        for (p in Period.values()) assertTrue(Greeting.lines(p, "Tony").all { "Tony" in it })
        repeat(30) { assertTrue(Greeting.greeting(t(9), "sir", Random(it)) in Greeting.lines(Period.MORNING, "sir")) }
        repeat(30) { assertTrue(Greeting.greeting(t(23), "sir", Random(it)) in Greeting.lines(Period.NIGHT, "sir")) }
    }

    @Test fun iconFollowsDaylight() {
        assertEquals("sun", Greeting.icon(t(9)))
        assertEquals("sun", Greeting.icon(t(14)))
        assertEquals("moon", Greeting.icon(t(19)))
        assertEquals("moon", Greeting.icon(t(2)))
    }

    @Test fun startupWithoutRemindersIsJustTheGreeting() {
        val msg = Greeting.startupMessage(emptyList(), t(9), "sir", Random(1))
        assertTrue(msg in Greeting.lines(Period.MORNING, "sir"))
    }

    @Test fun startupMentionsASingleReminder() {
        val msg = Greeting.startupMessage(listOf(rem("a", "call mom", t(17))), t(9), "sir", Random(1))
        assertTrue(msg, msg.endsWith(" You have a reminder today: call mom, 5:00 PM today."))
    }

    @Test fun startupCountsSeveralAndNamesTheNext() {
        val list = listOf(rem("b", "gym", t(18)), rem("a", "call mom", t(17)))
        val msg = Greeting.startupMessage(list, t(9), "sir", Random(1))
        assertTrue(msg, msg.endsWith(" You have 2 reminders today. The next is call mom, 5:00 PM today."))
    }

    @Test fun startupIgnoresOtherDaysAndFinishedReminders() {
        val list = listOf(
            rem("a", "tomorrow thing", t(17).plusDays(1)),
            rem("b", "old", t(8), ReminderState.DONE),
            rem("c", "cancelled", t(18), ReminderState.CANCELLED),
        )
        val msg = Greeting.startupMessage(list, t(9), "sir", Random(1))
        assertTrue(msg in Greeting.lines(Period.MORNING, "sir"))
    }

    @Test fun repliesToHellos() {
        for (s in listOf("hello", "Hi!", "hey there", "good morning", "heyyy", "namaste", "Hello jarvis")) assertNotNull(s, Greeting.replyTo(s, t(9), "sir", Random(2)))
    }

    @Test fun repliesToGoodNight() =
        assertEquals("Good night, sir. Rest well. I'll be here when you need me.", Greeting.replyTo("Good night", t(23), "sir"))

    @Test fun doesNotSwallowRealRequests() {
        assertNull(Greeting.replyTo("hello what's the weather", t(9), "sir"))
        assertNull(Greeting.replyTo("remind me to say hi", t(9), "sir"))
        assertNull(Greeting.replyTo("", t(9), "sir"))
    }
}
