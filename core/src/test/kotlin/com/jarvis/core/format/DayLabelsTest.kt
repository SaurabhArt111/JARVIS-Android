package com.jarvis.core.format

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class DayLabelsTest {
    private val today = LocalDate.of(2026, 10, 30)   // a Friday
    private fun label(d: LocalDate) = DayLabels.of(d, today, RelativeWords(), Locale.ENGLISH)

    @Test fun todayTomorrowYesterday() {
        assertEquals("Today", label(today))
        assertEquals("Tomorrow", label(today.plusDays(1)))
        assertEquals("Yesterday", label(today.minusDays(1)))
    }

    @Test fun weekdayWithinSixDaysEitherWay() {
        assertEquals("Sunday", label(today.plusDays(2)))
        assertEquals("Thursday", label(today.plusDays(6)))
        assertEquals("Wednesday", label(today.minusDays(2)))
        assertEquals("Saturday", label(today.minusDays(6)))
    }

    @Test fun furtherAwayIsADate() {
        assertEquals("6 Nov", label(today.plusDays(7)))
        assertEquals("23 Oct", label(today.minusDays(7)))
        assertEquals("1 Jan 2027", label(LocalDate.of(2027, 1, 1)))
        assertEquals("31 Dec 2025", label(LocalDate.of(2025, 12, 31)))
    }

    @Test fun localizedWordsAreUsed() {
        assertEquals("Morgen", DayLabels.of(today.plusDays(1), today, RelativeWords("Heute", "Morgen", "Gestern"), Locale.GERMAN))
    }
}
