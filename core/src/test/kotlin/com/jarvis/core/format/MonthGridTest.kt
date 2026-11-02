package com.jarvis.core.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class MonthGridTest {
    @Test fun october2026StartsOnThursdayWithMondayFirst() {
        val w = MonthGrid.weeks(LocalDate.of(2026, 10, 30))
        assertEquals(5, w.size)
        assertEquals(listOf(null, null, null, 1, 2, 3, 4), w[0])
        assertEquals(listOf(26, 27, 28, 29, 30, 31, null), w[4])
        assertTrue(w.all { it.size == 7 })
    }

    @Test fun sundayFirstShiftsTheColumns() {
        val w = MonthGrid.weeks(LocalDate.of(2026, 10, 1), DayOfWeek.SUNDAY)
        assertEquals(listOf(null, null, null, null, 1, 2, 3), w[0])
    }

    @Test fun everyDayAppearsExactlyOnceInOrder() {
        for (m in 1..12) {
            val d = LocalDate.of(2024, m, 1)
            val days = MonthGrid.weeks(d).flatten().filterNotNull()
            assertEquals((1..d.lengthOfMonth()).toList(), days)
        }
    }

    @Test fun februaryOfALeapYearHasTwentyNineDays() {
        assertEquals(29, MonthGrid.weeks(LocalDate.of(2028, 2, 10)).flatten().filterNotNull().max())
    }

    @Test fun aMonthThatFitsFourRowsHasFour() {
        // 2026-02-01 is a Sunday; Sunday-first: exactly 4 rows
        assertEquals(4, MonthGrid.weeks(LocalDate.of(2026, 2, 14), DayOfWeek.SUNDAY).size)
    }

    @Test fun headersFollowTheFirstDay() {
        assertEquals(DayOfWeek.MONDAY, MonthGrid.headers()[0])
        assertEquals(DayOfWeek.SUNDAY, MonthGrid.headers()[6])
        assertEquals(DayOfWeek.SATURDAY, MonthGrid.headers(DayOfWeek.SATURDAY)[0])
    }

    @Test fun parseAcceptsIsoDatesOnly() {
        assertEquals(LocalDate.of(2026, 10, 30), MonthGrid.parse(" 2026-10-30 "))
        assertNull(MonthGrid.parse("30/10/2026"))
        assertNull(MonthGrid.parse(""))
        assertNull(MonthGrid.parse(null))
    }

    @Test fun marksKeepOnlyTheShownMonth() {
        val m = LocalDate.of(2026, 10, 30)
        assertEquals(setOf(5, 30), MonthGrid.marksIn(m, "2026-10-05,2026-11-02,garbage,2026-10-30,2025-10-07"))
        assertEquals(emptySet<Int>(), MonthGrid.marksIn(m, null))
    }
}
