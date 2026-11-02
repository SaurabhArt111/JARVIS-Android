package com.jarvis.core.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * GOLDEN test: every row was produced by running the ORIGINAL Python `Backend/TimeParse.parse` at three fixed "now" values
 * (0 = Sun 25 Oct 2026 10:00, 1 = Sun 25 Oct 2026 22:30, 2 = Wed 28 Oct 2026 08:05). The Kotlin port must give the same answer.
 */
class TimeParserGoldenTest {
    private class G(
        val nowIdx: Int, val text: String, val whenAt: String?, val rest: String, val hasTime: Boolean, val hasDate: Boolean,
        val relative: Boolean, val dateOnly: Boolean, val pastToday: Boolean, val assumed: List<String>, val hour12: Int?,
    )

    private val nows = listOf(
        LocalDateTime.of(2026, 10, 25, 10, 0), LocalDateTime.of(2026, 10, 25, 22, 30), LocalDateTime.of(2026, 10, 28, 8, 5),
    )

    private val rows = listOf(
        G(0, "remind me to call mom at 5 pm", "2026-10-25T17:00", "remind me to call mom", true, false, false, false, false, listOf(), null),
        G(0, "at 5", "2026-10-25T17:00", "", true, false, false, false, false, listOf("5:00 PM"), 5),
        G(0, "tomorrow at 7", "2026-10-26T07:00", "", true, true, false, false, false, listOf("7:00 AM"), 7),
        G(0, "in 20 minutes", "2026-10-25T10:20", "", true, false, true, false, false, listOf(), null),
        G(0, "in an hour and a half", "2026-10-25T11:30", "", true, false, true, false, false, listOf(), null),
        G(0, "half past five", "2026-10-25T17:30", "", true, false, false, false, false, listOf("5:30 PM"), 5),
        G(0, "quarter to six", "2026-10-25T17:45", "", true, false, false, false, false, listOf("5:45 PM"), 5),
        G(0, "noon", "2026-10-25T12:00", "", true, false, false, false, false, listOf(), null),
        G(0, "midnight tonight", "2026-10-25T00:00", "", true, false, false, false, true, listOf(), null),
        G(0, "at 9 am today", "2026-10-25T09:00", "", true, false, false, false, true, listOf(), null),
        G(0, "friday at 3 pm", "2026-10-30T15:00", "", true, true, false, false, false, listOf(), null),
        G(0, "tomorrow", "2026-10-26T09:00", "", false, true, false, true, false, listOf(), null),
        G(0, "on 30 october at 6 pm", "2026-10-30T18:00", "", true, true, false, false, false, listOf(), null),
        G(0, "at 17:30", "2026-10-25T17:30", "", true, false, false, false, false, listOf(), null),
        G(0, "at 7 tonight", "2026-10-25T19:00", "", true, false, false, false, false, listOf(), null),
        G(0, "in the morning at 8", "2026-10-26T08:00", "", true, false, false, false, false, listOf("tomorrow"), null),
        G(0, "wake me at 6", "2026-10-25T18:00", "wake me", true, false, false, false, false, listOf("6:00 PM"), 6),
        G(0, "in 2 hours", "2026-10-25T12:00", "", true, false, true, false, false, listOf(), null),
        G(0, "at 12", "2026-10-25T12:00", "", true, false, false, false, false, listOf("12:00 PM"), 12),
        G(0, "next monday at 9", "2026-10-26T09:00", "", true, true, false, false, false, listOf("9:00 AM"), 9),
        G(0, "5 oclock", "2026-10-25T17:00", "", true, false, false, false, false, listOf("5:00 PM"), 5),
        G(0, "at 9 today", "2026-10-25T21:00", "", true, false, false, false, false, listOf("9:00 PM"), 9),
        G(0, "today at 11 pm", "2026-10-25T23:00", "", true, false, false, false, false, listOf(), null),
        G(0, "5:15 am", "2026-10-26T05:15", "", true, false, false, false, false, listOf("tomorrow"), null),
        G(0, "remind me to take medicine at 8:30 in the evening", "2026-10-25T20:30", "remind me to take medicine", true, false, false, false, false, listOf(), null),
        G(0, "call dad tomorrow at 6:45 pm", "2026-10-26T18:45", "call dad", true, true, false, false, false, listOf(), null),
        G(0, "half an hour later", "2026-10-25T10:30", "", true, false, true, false, false, listOf(), null),
        G(0, "in 45 seconds", "2026-10-25T10:00", "", true, false, true, false, false, listOf(), null),
        G(0, "the day after tomorrow at 10", "2026-10-27T10:00", "the", true, true, false, false, false, listOf("10:00 AM"), 10),
        G(0, "meeting on friday", "2026-10-30T09:00", "meeting", false, true, false, true, false, listOf(), null),
        G(0, "at five thirty", "2026-10-25T17:30", "", true, false, false, false, false, listOf("5:30 PM"), 5),
        G(0, "buy milk", null, "buy milk", false, false, false, false, false, listOf(), null),
        G(0, "at 3", "2026-10-25T15:00", "", true, false, false, false, false, listOf("3:00 PM"), 3),
        G(0, "in a couple of hours", "2026-10-25T12:00", "", true, false, true, false, false, listOf(), null),
        G(0, "tomorrow morning at 9", "2026-10-26T09:00", "", true, true, false, false, false, listOf(), null),
        G(0, "5 pm tomorrow", "2026-10-26T17:00", "", true, true, false, false, false, listOf(), null),
        G(0, "on 3 november at 7 am", "2026-11-03T07:00", "", true, true, false, false, false, listOf(), null),
        G(0, "at 00:15", "2026-10-25T12:15", "", true, false, false, false, false, listOf("12:15 PM"), 0),
        G(0, "at 1 pm today", "2026-10-25T13:00", "", true, false, false, false, false, listOf(), null),
        G(0, "tonight at 9", "2026-10-25T21:00", "", true, false, false, false, false, listOf(), null),
        G(0, "this evening at 6", "2026-10-25T18:00", "", true, false, false, false, false, listOf(), null),
        G(0, "in 90 minutes", "2026-10-25T11:30", "", true, false, true, false, false, listOf(), null),
        G(0, "three o'clock", "2026-10-25T15:00", "", true, false, false, false, false, listOf("3:00 PM"), 3),
        G(1, "remind me to call mom at 5 pm", "2026-10-26T17:00", "remind me to call mom", true, false, false, false, false, listOf("tomorrow"), null),
        G(1, "at 5", "2026-10-26T17:00", "", true, false, false, false, false, listOf("5:00 PM", "tomorrow"), 5),
        G(1, "tomorrow at 7", "2026-10-26T07:00", "", true, true, false, false, false, listOf("7:00 AM"), 7),
        G(1, "in 20 minutes", "2026-10-25T22:50", "", true, false, true, false, false, listOf(), null),
        G(1, "in an hour and a half", "2026-10-26T00:00", "", true, false, true, false, false, listOf(), null),
        G(1, "half past five", "2026-10-26T17:30", "", true, false, false, false, false, listOf("5:30 PM", "tomorrow"), 5),
        G(1, "quarter to six", "2026-10-26T17:45", "", true, false, false, false, false, listOf("5:45 PM", "tomorrow"), 5),
        G(1, "noon", "2026-10-26T12:00", "", true, false, false, false, false, listOf("tomorrow"), null),
        G(1, "midnight tonight", "2026-10-25T00:00", "", true, false, false, false, true, listOf(), null),
        G(1, "at 9 am today", "2026-10-25T09:00", "", true, false, false, false, true, listOf(), null),
        G(1, "friday at 3 pm", "2026-10-30T15:00", "", true, true, false, false, false, listOf(), null),
        G(1, "tomorrow", "2026-10-26T09:00", "", false, true, false, true, false, listOf(), null),
        G(1, "on 30 october at 6 pm", "2026-10-30T18:00", "", true, true, false, false, false, listOf(), null),
        G(1, "at 17:30", "2026-10-26T17:30", "", true, false, false, false, false, listOf("tomorrow"), null),
        G(1, "at 7 tonight", "2026-10-25T19:00", "", true, false, false, false, true, listOf(), null),
        G(1, "in the morning at 8", "2026-10-26T08:00", "", true, false, false, false, false, listOf("tomorrow"), null),
        G(1, "wake me at 6", "2026-10-26T18:00", "wake me", true, false, false, false, false, listOf("6:00 PM", "tomorrow"), 6),
        G(1, "in 2 hours", "2026-10-26T00:30", "", true, false, true, false, false, listOf(), null),
        G(1, "at 12", "2026-10-26T12:00", "", true, false, false, false, false, listOf("12:00 PM", "tomorrow"), 12),
        G(1, "next monday at 9", "2026-10-26T09:00", "", true, true, false, false, false, listOf("9:00 AM"), 9),
        G(1, "5 oclock", "2026-10-26T17:00", "", true, false, false, false, false, listOf("5:00 PM", "tomorrow"), 5),
        G(1, "at 9 today", "2026-10-25T09:00", "", true, false, false, false, true, listOf(), 9),
        G(1, "today at 11 pm", "2026-10-25T23:00", "", true, false, false, false, false, listOf(), null),
        G(1, "5:15 am", "2026-10-26T05:15", "", true, false, false, false, false, listOf("tomorrow"), null),
        G(1, "remind me to take medicine at 8:30 in the evening", "2026-10-26T20:30", "remind me to take medicine", true, false, false, false, false, listOf("tomorrow"), null),
        G(1, "call dad tomorrow at 6:45 pm", "2026-10-26T18:45", "call dad", true, true, false, false, false, listOf(), null),
        G(1, "half an hour later", "2026-10-25T23:00", "", true, false, true, false, false, listOf(), null),
        G(1, "in 45 seconds", "2026-10-25T22:30", "", true, false, true, false, false, listOf(), null),
        G(1, "the day after tomorrow at 10", "2026-10-27T10:00", "the", true, true, false, false, false, listOf("10:00 AM"), 10),
        G(1, "meeting on friday", "2026-10-30T09:00", "meeting", false, true, false, true, false, listOf(), null),
        G(1, "at five thirty", "2026-10-26T17:30", "", true, false, false, false, false, listOf("5:30 PM", "tomorrow"), 5),
        G(1, "buy milk", null, "buy milk", false, false, false, false, false, listOf(), null),
        G(1, "at 3", "2026-10-26T15:00", "", true, false, false, false, false, listOf("3:00 PM", "tomorrow"), 3),
        G(1, "in a couple of hours", "2026-10-26T00:30", "", true, false, true, false, false, listOf(), null),
        G(1, "tomorrow morning at 9", "2026-10-26T09:00", "", true, true, false, false, false, listOf(), null),
        G(1, "5 pm tomorrow", "2026-10-26T17:00", "", true, true, false, false, false, listOf(), null),
        G(1, "on 3 november at 7 am", "2026-11-03T07:00", "", true, true, false, false, false, listOf(), null),
        G(1, "at 00:15", "2026-10-26T12:15", "", true, false, false, false, false, listOf("12:15 PM", "tomorrow"), 0),
        G(1, "at 1 pm today", "2026-10-25T13:00", "", true, false, false, false, true, listOf(), null),
        G(1, "tonight at 9", "2026-10-25T21:00", "", true, false, false, false, true, listOf(), null),
        G(1, "this evening at 6", "2026-10-25T18:00", "", true, false, false, false, true, listOf(), null),
        G(1, "in 90 minutes", "2026-10-26T00:00", "", true, false, true, false, false, listOf(), null),
        G(1, "three o'clock", "2026-10-26T15:00", "", true, false, false, false, false, listOf("3:00 PM", "tomorrow"), 3),
        G(2, "remind me to call mom at 5 pm", "2026-10-28T17:00", "remind me to call mom", true, false, false, false, false, listOf(), null),
        G(2, "at 5", "2026-10-28T17:00", "", true, false, false, false, false, listOf("5:00 PM"), 5),
        G(2, "tomorrow at 7", "2026-10-29T07:00", "", true, true, false, false, false, listOf("7:00 AM"), 7),
        G(2, "in 20 minutes", "2026-10-28T08:25", "", true, false, true, false, false, listOf(), null),
        G(2, "in an hour and a half", "2026-10-28T09:35", "", true, false, true, false, false, listOf(), null),
        G(2, "half past five", "2026-10-28T17:30", "", true, false, false, false, false, listOf("5:30 PM"), 5),
        G(2, "quarter to six", "2026-10-28T17:45", "", true, false, false, false, false, listOf("5:45 PM"), 5),
        G(2, "noon", "2026-10-28T12:00", "", true, false, false, false, false, listOf(), null),
        G(2, "midnight tonight", "2026-10-28T00:00", "", true, false, false, false, true, listOf(), null),
        G(2, "at 9 am today", "2026-10-28T09:00", "", true, false, false, false, false, listOf(), null),
        G(2, "friday at 3 pm", "2026-10-30T15:00", "", true, true, false, false, false, listOf(), null),
        G(2, "tomorrow", "2026-10-29T09:00", "", false, true, false, true, false, listOf(), null),
        G(2, "on 30 october at 6 pm", "2026-10-30T18:00", "", true, true, false, false, false, listOf(), null),
        G(2, "at 17:30", "2026-10-28T17:30", "", true, false, false, false, false, listOf(), null),
        G(2, "at 7 tonight", "2026-10-28T19:00", "", true, false, false, false, false, listOf(), null),
        G(2, "in the morning at 8", "2026-10-29T08:00", "", true, false, false, false, false, listOf("tomorrow"), null),
        G(2, "wake me at 6", "2026-10-28T18:00", "wake me", true, false, false, false, false, listOf("6:00 PM"), 6),
        G(2, "in 2 hours", "2026-10-28T10:05", "", true, false, true, false, false, listOf(), null),
        G(2, "at 12", "2026-10-28T12:00", "", true, false, false, false, false, listOf("12:00 PM"), 12),
        G(2, "next monday at 9", "2026-11-02T09:00", "", true, true, false, false, false, listOf("9:00 AM"), 9),
        G(2, "5 oclock", "2026-10-28T17:00", "", true, false, false, false, false, listOf("5:00 PM"), 5),
        G(2, "at 9 today", "2026-10-28T09:00", "", true, false, false, false, false, listOf(), 9),
        G(2, "today at 11 pm", "2026-10-28T23:00", "", true, false, false, false, false, listOf(), null),
        G(2, "5:15 am", "2026-10-29T05:15", "", true, false, false, false, false, listOf("tomorrow"), null),
        G(2, "remind me to take medicine at 8:30 in the evening", "2026-10-28T20:30", "remind me to take medicine", true, false, false, false, false, listOf(), null),
        G(2, "call dad tomorrow at 6:45 pm", "2026-10-29T18:45", "call dad", true, true, false, false, false, listOf(), null),
        G(2, "half an hour later", "2026-10-28T08:35", "", true, false, true, false, false, listOf(), null),
        G(2, "in 45 seconds", "2026-10-28T08:05", "", true, false, true, false, false, listOf(), null),
        G(2, "the day after tomorrow at 10", "2026-10-30T10:00", "the", true, true, false, false, false, listOf("10:00 AM"), 10),
        G(2, "meeting on friday", "2026-10-30T09:00", "meeting", false, true, false, true, false, listOf(), null),
        G(2, "at five thirty", "2026-10-28T17:30", "", true, false, false, false, false, listOf("5:30 PM"), 5),
        G(2, "buy milk", null, "buy milk", false, false, false, false, false, listOf(), null),
        G(2, "at 3", "2026-10-28T15:00", "", true, false, false, false, false, listOf("3:00 PM"), 3),
        G(2, "in a couple of hours", "2026-10-28T10:05", "", true, false, true, false, false, listOf(), null),
        G(2, "tomorrow morning at 9", "2026-10-29T09:00", "", true, true, false, false, false, listOf(), null),
        G(2, "5 pm tomorrow", "2026-10-29T17:00", "", true, true, false, false, false, listOf(), null),
        G(2, "on 3 november at 7 am", "2026-11-03T07:00", "", true, true, false, false, false, listOf(), null),
        G(2, "at 00:15", "2026-10-28T12:15", "", true, false, false, false, false, listOf("12:15 PM"), 0),
        G(2, "at 1 pm today", "2026-10-28T13:00", "", true, false, false, false, false, listOf(), null),
        G(2, "tonight at 9", "2026-10-28T21:00", "", true, false, false, false, false, listOf(), null),
        G(2, "this evening at 6", "2026-10-28T18:00", "", true, false, false, false, false, listOf(), null),
        G(2, "in 90 minutes", "2026-10-28T09:35", "", true, false, true, false, false, listOf(), null),
        G(2, "three o'clock", "2026-10-28T15:00", "", true, false, false, false, false, listOf("3:00 PM"), 3),
    )

    @Test fun portMatchesPythonOnAllGoldenRows() {
        val bad = ArrayList<String>()
        for (g in rows) {
            val p = TimeParser.parse(g.text, nows[g.nowIdx])
            val got = "${p.whenAt?.truncatedTo(java.time.temporal.ChronoUnit.MINUTES)?.toString()}|${p.rest}|${p.hasTime}|${p.hasDate}|${p.relative}|${p.dateOnly}|${p.pastToday}|${p.assumed}|${p.hour12}"
            val want = "${g.whenAt}|${g.rest}|${g.hasTime}|${g.hasDate}|${g.relative}|${g.dateOnly}|${g.pastToday}|${g.assumed}|${g.hour12}"
            if (got != want) bad.add("now#${g.nowIdx} '${g.text}': want $want got $got")
        }
        assertTrue("${bad.size} of ${rows.size} rows differ:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test fun goldenTableIsNotEmpty() = assertEquals(129, rows.size)
}
