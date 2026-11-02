package com.jarvis.core.format

import java.time.DayOfWeek
import java.time.LocalDate

/** Layout of one calendar month for the small calendar card: rows of 7 cells, null where the cell belongs to the previous or next month. */
object MonthGrid {
    fun weeks(date: LocalDate, firstDay: DayOfWeek = DayOfWeek.MONDAY): List<List<Int?>> {
        val first = date.withDayOfMonth(1)
        val lead = (first.dayOfWeek.value - firstDay.value + 7) % 7
        val cells = ArrayList<Int?>()
        repeat(lead) { cells += null }
        for (d in 1..date.lengthOfMonth()) cells += d
        while (cells.size % 7 != 0) cells += null
        return cells.chunked(7)
    }

    /** Column headings in display order, e.g. Monday first: MON..SUN. */
    fun headers(firstDay: DayOfWeek = DayOfWeek.MONDAY): List<DayOfWeek> = (0 until 7).map { firstDay.plus(it.toLong()) }

    /** "2026-10-30" -> LocalDate, or null for anything that is not an ISO date. */
    fun parse(s: String?): LocalDate? = try { if (s.isNullOrBlank()) null else LocalDate.parse(s.trim()) } catch (e: Exception) { null }

    /** Days of [month] that appear in a comma-separated list of ISO dates (the card's "marks" field); other months and junk are ignored. */
    fun marksIn(month: LocalDate, marks: String?): Set<Int> =
        marks.orEmpty().split(',').mapNotNull { parse(it) }.filter { it.year == month.year && it.month == month.month }.map { it.dayOfMonth }.toSet()
}
