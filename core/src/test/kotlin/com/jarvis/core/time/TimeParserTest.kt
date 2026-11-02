package com.jarvis.core.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class TimeParserTest {
    // Sunday 25 October 2026, 10:00
    private val now = LocalDateTime.of(2026, 10, 25, 10, 0)

    private fun at(d: Int, h: Int, m: Int = 0, mo: Int = 10) = LocalDateTime.of(2026, mo, d, h, m)

    @Test fun explicitPmToday() = assertEquals(at(25, 17), TimeParser.parse("at 5 pm", now).whenAt)

    @Test fun missingMeridiemPicksNearestAhead() {
        val p = TimeParser.parse("at 5", now)
        assertEquals(at(25, 17), p.whenAt)
        assertEquals(listOf("5:00 PM"), p.assumed)
        assertEquals(5, p.hour12)
    }

    @Test fun nothingAheadTodayRollsToTomorrow() {
        val late = LocalDateTime.of(2026, 10, 25, 22, 30)
        val p = TimeParser.parse("at 5:15 am", late)
        assertEquals(at(26, 5, 15), p.whenAt)
        assertTrue("tomorrow" in p.assumed)
    }

    @Test fun relativeMinutes() {
        val p = TimeParser.parse("in 20 minutes", now)
        assertEquals(at(25, 10, 20), p.whenAt)
        assertTrue(p.relative)
        assertTrue(p.hasTime)
    }

    @Test fun relativeAnHourAndAHalf() = assertEquals(at(25, 11, 30), TimeParser.parse("in an hour and a half", now).whenAt)

    @Test fun relativeLaterForm() = assertEquals(at(25, 10, 30), TimeParser.parse("half an hour later", now).whenAt)

    @Test fun dayWordMakesMissingMeridiemSensible() {
        assertEquals(at(26, 7), TimeParser.parse("tomorrow at 7", now).whenAt)       // 7-11 = morning
        assertEquals(at(26, 15), TimeParser.parse("tomorrow at 3", now).whenAt)      // 1-6 = afternoon
    }

    @Test fun dateOnlyAsksForATime() {
        val p = TimeParser.parse("tomorrow", now)
        assertTrue(p.dateOnly)
        assertFalse(p.hasTime)
        assertTrue(p.ok)
        assertEquals(at(26, 9).toLocalDate(), p.whenAt!!.toLocalDate())
    }

    @Test fun explicitTodayInThePastIsFlagged() {
        val p = TimeParser.parse("at 9 am today", now)
        assertTrue(p.pastToday)
        assertEquals(at(25, 9), p.whenAt)
    }

    @Test fun weekdayAndNamedDate() {
        assertEquals(at(30, 15), TimeParser.parse("friday at 3 pm", now).whenAt)
        assertEquals(at(30, 18), TimeParser.parse("on 30 october at 6 pm", now).whenAt)
    }

    @Test fun restKeepsTheSubject() = assertEquals("remind me to call mom", TimeParser.parse("remind me to call mom at 5 pm", now).rest)

    @Test fun spokenClockForms() {
        assertEquals(at(25, 17, 30), TimeParser.parse("half past five", now).whenAt)
        assertEquals(at(25, 17, 45), TimeParser.parse("quarter to six", now).whenAt)
        assertEquals(at(25, 12), TimeParser.parse("noon", now).whenAt)
        assertEquals(at(25, 17, 30), TimeParser.parse("at 17:30", now).whenAt)
    }

    @Test fun textWithoutATimeIsNotOk() {
        val p = TimeParser.parse("buy milk", now)
        assertFalse(p.ok)
        assertFalse(p.hasTime)
        assertEquals("buy milk", p.rest)
    }

    @Test fun nullTextIsSafe() = assertFalse(TimeParser.parse(null, now).ok)

    @Test fun durations() {
        assertEquals(5400, TimeParser.parseDurationSeconds("an hour and a half"))
        assertEquals(600, TimeParser.parseDurationSeconds("10 minutes"))
        assertEquals(1800, TimeParser.parseDurationSeconds("half an hour"))
        assertEquals(9000, TimeParser.parseDurationSeconds("2 hours 30 minutes"))
        assertNull(TimeParser.parseDurationSeconds("banana"))
    }

    @Test fun niceWording() {
        assertEquals("5:00 PM today", TimeParser.nice(at(25, 17), now))
        assertEquals("7:00 AM tomorrow", TimeParser.nice(at(26, 7), now))
        assertEquals("6:00 PM on Wednesday", TimeParser.nice(at(28, 18), now))
        assertEquals("6:00 PM on Monday 16 November", TimeParser.nice(at(16, 18, 0, 11), now))
    }

    @Test fun clock12Edges() {
        assertEquals("12:00 AM", TimeParser.clock12(0, 0))
        assertEquals("12:05 PM", TimeParser.clock12(12, 5))
        assertEquals("11:59 PM", TimeParser.clock12(23, 59))
    }

    @Test fun dayPhrase() {
        assertEquals("Friday", TimeParser.dayPhrase(at(30, 0).toLocalDate(), now))
        assertEquals("16 November", TimeParser.dayPhrase(at(16, 0, 0, 11).toLocalDate(), now))
    }

    @Test fun parseWhenIsoAndNatural() {
        assertEquals(at(26, 15, 30) to null, TimeParser.parseWhen("2026-10-26T15:30", now))
        assertEquals(at(26, 7) to null, TimeParser.parseWhen("tomorrow at 7", now))
    }

    @Test fun parseWhenRejectsPastEmptyAndDateOnly() {
        assertNotNull(TimeParser.parseWhen("yesterday at 5 pm", now).second)
        assertNotNull(TimeParser.parseWhen("2026-10-25T09:30", now).second)
        assertNotNull(TimeParser.parseWhen("", now).second)
        assertNotNull(TimeParser.parseWhen("tomorrow", now).second)
        assertNotNull(TimeParser.parseWhen("blah blah", now).second)
        assertNull(TimeParser.parseWhen("blah blah", now).first)
    }

    @Test fun parseIsoIgnoresOffsetAndRejectsGarbage() {
        assertEquals(at(26, 15, 30), TimeParser.parseIso("2026-10-26T15:30:00+05:30"))
        assertNull(TimeParser.parseIso("2026-13-40T99:99"))
        assertNull(TimeParser.parseIso("tomorrow"))
    }

    @Test fun relativeTimeIsRealElapsedTimeAcrossASpringForwardGap() {
        // London 2027-03-28: 01:00 GMT jumps to 02:00 BST. 00:30 + 60 real minutes = 02:30 on the wall clock.
        val z = ZonedDateTime.of(2027, 3, 28, 0, 30, 0, 0, ZoneId.of("Europe/London"))
        assertEquals(LocalDateTime.of(2027, 3, 28, 2, 30), TimeParser.parse("in 60 minutes", z).whenAt)
        assertEquals(LocalDateTime.of(2027, 3, 28, 1, 30), TimeParser.parse("in 60 minutes", z.toLocalDateTime()).whenAt) // naive wall clock
    }
}
