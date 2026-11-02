package com.jarvis.core.format

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

data class RelativeWords(val today: String = "Today", val tomorrow: String = "Tomorrow", val yesterday: String = "Yesterday")

/** "Today", "Tomorrow", "Yesterday", a weekday for the next/previous six days, otherwise a short date: for reminder lists, where the day matters more than the exact date. */
object DayLabels {
    fun of(date: LocalDate, today: LocalDate, words: RelativeWords = RelativeWords(), locale: Locale = Locale.getDefault()): String {
        val diff = date.toEpochDay() - today.toEpochDay()
        return when {
            diff == 0L -> words.today
            diff == 1L -> words.tomorrow
            diff == -1L -> words.yesterday
            diff in 2..6 || diff in -6..-2 -> date.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
            date.year == today.year -> date.format(DateTimeFormatter.ofPattern("d MMM", locale))
            else -> date.format(DateTimeFormatter.ofPattern("d MMM yyyy", locale))
        }
    }
}
