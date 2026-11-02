package com.jarvis.core.time

import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Result of [TimeParser.parse]. Port of `Backend/TimeParse.Parsed`.
 *  - [whenAt]: the resolved wall-clock time (null = nothing found)
 *  - [rest]: the text with the date/time words removed ("remind me about the meeting")
 *  - [hasTime]: a clock time (or a relative duration) was found; [hasDate]: a day/date was named; [relative]: "in 10 minutes"
 *  - [dateOnly]: a day but no time - the caller must ask for the time
 *  - [pastToday]: the user said "today" and that time has already passed - the caller can ASK instead of guessing
 *  - [assumed]: things we guessed, in plain words ("5:00 PM", "tomorrow")
 *  - [hour12]: the spoken hour when AM/PM was missing (for "make it AM")
 */
data class Parsed(
    val whenAt: LocalDateTime? = null,
    val rest: String = "",
    val hasTime: Boolean = false,
    val hasDate: Boolean = false,
    val relative: Boolean = false,
    val dateOnly: Boolean = false,
    val pastToday: Boolean = false,
    val assumed: List<String> = emptyList(),
    val hour12: Int? = null,
) {
    val ok: Boolean get() = whenAt != null
}

/**
 * Understands the times and dates people actually say (port of Backend/TimeParse.py + the time helpers of Backend/Tools.py).
 *
 * Rules that matter (they come straight from how JARVIS is used):
 *  * "in 10 minutes", "in an hour and a half", "half an hour later" are relative to NOW.
 *  * "5:00 pm", "5 pm", "5 o'clock", "five thirty", "half past five", "quarter to six", "noon", "midnight", "17:30".
 *  * If AM/PM is NOT said and no day is named, the nearest time that is still ahead is used.
 *  * If a day is named ("tomorrow at 7") a missing AM/PM follows common sense (7-11 = morning, 12 and 1-6 = afternoon).
 *  * If the user says "today" and that time has passed, [Parsed.pastToday] is set so the caller can ASK.
 *
 * Everything works on wall-clock [LocalDateTime]. Pass a [ZonedDateTime] "now" to get DST-correct relative times
 * ("in 10 minutes" across a spring-forward gap is 10 real minutes, not 10 wall-clock minutes).
 */
object TimeParser {
    private val NUMW = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
        "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16,
        "seventeen" to 17, "eighteen" to 18, "nineteen" to 19, "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
    )
    private val HOURW = NUMW.filter { it.value in 1..12 }.keys.sortedByDescending { it.length }.joinToString("|")
    private val NUM = "(?:\\d{1,2}|$HOURW)"
    private const val MINW = "(?:\\d{2}|fifteen|thirty|forty[\\s-]?five|oh[\\s-]?\\w+|ten|twenty|fifty)"
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private const val MONTH = "(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)"
    private val WEEKDAYS = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
    private val WD_ABBR = linkedMapOf("mon" to 0, "tue" to 1, "tues" to 1, "wed" to 2, "thu" to 3, "thur" to 3, "thurs" to 3, "fri" to 4, "sat" to 5, "sun" to 6)
    private const val AMPM = "(a\\.?\\s?m\\.?|p\\.?\\s?m\\.?)"

    private const val DUR_CORE =
        "(?:(?:\\d+(?:\\.\\d+)?|a|an|one|two|three|four|five|six|seven|eight|nine|ten|fifteen|twenty|thirty|forty|fifty|half|couple)\\s*" +
            "(?:and\\s*)?(?:a\\s+half\\s*)?(?:of\\s+)?(?:seconds?|secs?|minutes?|mins?|hours?|hrs?)(?:\\s*(?:and\\s*)?(?:a\\s+half)?)?\\s*)+"

    /** Regex source that FINDS a duration inside a sentence (for callers like the organizer's "push it by 10 minutes"). */
    const val DURATION: String = "(?:half an? hour|an? hour and a half|(?:a )?couple of hours|$DUR_CORE)"

    private const val PREP = "(?:for|of|at|on|by|in|around)"

    private fun num(s: String): Int {
        val x = s.trim().lowercase()
        return x.toIntOrNull() ?: NUMW[x] ?: 0
    }

    private fun minutes(s: String?): Int {
        if (s.isNullOrEmpty()) return 0
        var x = s.trim().lowercase().replace("-", " ")
        if (x.all { it.isDigit() } && x.isNotEmpty()) return x.toInt()
        if (x.startsWith("oh")) {
            val parts = x.split(Regex("\\s+"))
            return if (parts.size > 1) num(parts.last()) else 0
        }
        x = x.replace("forty five", "45")
        return x.toIntOrNull() ?: NUMW[x] ?: 0
    }

    // ------------------------------------------------------------------------------------------------ duration
    private val RE_HALF_HOUR = Regex("\\bhalf an? hour\\b")
    private val RE_HOUR_HALF = Regex("\\b(?:an?|one) hour and a half\\b|\\b1\\.5 hours?\\b")
    private val RE_COUPLE = Regex("\\b(?:a|one) couple of hours\\b|\\bcouple of hours\\b")
    private val DUR_UNITS = mapOf("s" to 1, "sec" to 1, "second" to 1, "m" to 60, "min" to 60, "minute" to 60, "h" to 3600, "hr" to 3600, "hour" to 3600)
    private const val DUR_WORDS = "a|an|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen|twenty|thirty|forty|forty five|fifty|sixty"
    private val RE_DURATION = Regex("(\\d+(?:\\.\\d+)?|$DUR_WORDS)\\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?|s|m|h)\\b")

    /** '5 minutes', '1 hour 30 minutes', 'half an hour', 'an hour and a half', '90 seconds' -> seconds, or null. */
    fun parseDurationSeconds(text: String?): Int? {
        val t = (text ?: "").lowercase()
        if (RE_HALF_HOUR.containsMatchIn(t)) return 1800
        if (RE_HOUR_HALF.containsMatchIn(t)) return 5400
        if (RE_COUPLE.containsMatchIn(t)) return 7200
        var total = 0.0
        var found = false
        for (m in RE_DURATION.findAll(t)) {
            val n = m.groupValues[1]
            val value = if (n.replace(".", "").all { it.isDigit() } && n.isNotEmpty()) n.toDouble()
            else (mapOf("a" to 1, "an" to 1, "forty five" to 45, "sixty" to 60)[n] ?: NUMW[n] ?: 0).toDouble()
            val raw = m.groupValues[2]
            val u = if (raw.length > 1) raw.trimEnd('s') else raw
            val mult = DUR_UNITS[u] ?: DUR_UNITS[u.take(3)] ?: 60
            total += value * mult
            found = true
        }
        return if (found && total > 0) total.toInt() else null
    }

    // ------------------------------------------------------------------------------------------------ helpers
    private class Span(val start: Int, val end: Int)

    private fun cut(text: String, s: Span): String = text.substring(0, s.start) + " " + text.substring(s.end)

    private val RE_WS = Regex("\\s+")
    private val RE_DOUBLE_PREP = Regex("\\b$PREP\\s+(?=$PREP\\b|to\\b|that\\b|about\\b)", RegexOption.IGNORE_CASE)
    private val RE_LEAD_JUNK = Regex("^(?:and|then|at|on|by|for|of|in|to|that|about|around|,|\\.)\\s+", RegexOption.IGNORE_CASE)
    private val RE_TRAIL_JUNK = Regex("\\s+(?:and|then|at|on|by|for|of|in|around|,|\\.)$", RegexOption.IGNORE_CASE)
    private val RE_ONLY_JUNK = Regex("(?:and|then|at|on|by|for|of|in|to|that|about|around)", RegexOption.IGNORE_CASE)

    private fun tidy(rest: String): String {
        var r = RE_WS.replace(rest, " ").trim()
        r = RE_DOUBLE_PREP.replace(r, "")
        repeat(3) {
            r = RE_LEAD_JUNK.replace(r, "")
            r = RE_TRAIL_JUNK.replace(r, "")
        }
        r = r.trim(' ', ',', '.', ';', ':', '-')
        return if (RE_ONLY_JUNK.matches(r)) "" else r
    }

    /** 7-11 -> morning, 12 and 1-6 -> afternoon/evening. Returns the 24h hour. */
    private fun guessMeridiem(h12: Int): Int = if (h12 == 12) 12 else if (h12 in 7..11) h12 else h12 + 12

    // ------------------------------------------------------------------------------------------------ dates
    private class DateHit(val date: LocalDate?, val span: Span?, val word: String)

    private val RE_DAY_AFTER = Regex("\\bday after (?:tomorrow|tmrw)\\b|\\bovermorrow\\b")
    private val RE_TOMORROW = Regex("\\b(?:tomorrow|tomorow|tmrw|tmr)\\b")
    private val RE_TODAY = Regex("\\bto ?day\\b|\\bthis (?:morning|afternoon|evening)\\b|\\btonight\\b|\\bto ?night\\b")
    private val RE_DMY_WORD = Regex("\\b(\\d{1,2})(?:st|nd|rd|th)?(?:\\s+of)?\\s+$MONTH\\b(?:\\s+(\\d{4}))?")
    private val RE_MDY_WORD = Regex("\\b$MONTH\\s+(\\d{1,2})(?:st|nd|rd|th)?\\b(?:,?\\s+(\\d{4}))?")
    private val RE_NUMERIC_DATE = Regex("\\b(\\d{1,2})[/-](\\d{1,2})(?:[/-](\\d{2,4}))?\\b")
    private val RE_WEEKDAY = Regex(
        "\\b(?:(next|this(?:\\s+coming)?|coming|on)\\s+)?(" + (WEEKDAYS + WD_ABBR.keys).joinToString("|") + ")\\b",
    )

    private fun spanOf(m: MatchResult) = Span(m.range.first, m.range.last + 1)

    private fun makeDate(y: Int, mon: Int, d: Int, now: LocalDateTime, yearGiven: Boolean): LocalDate? = try {
        var day = LocalDate.of(y, mon, d)
        if (day.isBefore(now.toLocalDate()) && !yearGiven) day = LocalDate.of(y + 1, mon, d)
        day
    } catch (e: DateTimeException) {
        null
    }

    private fun findDate(t: String, now: LocalDateTime): DateHit {
        RE_DAY_AFTER.find(t)?.let { return DateHit(now.toLocalDate().plusDays(2), spanOf(it), "day after tomorrow") }
        RE_TOMORROW.find(t)?.let { return DateHit(now.toLocalDate().plusDays(1), spanOf(it), "tomorrow") }
        RE_TODAY.find(t)?.let {
            val g = it.value
            return DateHit(now.toLocalDate(), if (g == "today" || g == "to day") spanOf(it) else null, "today")
        }
        RE_DMY_WORD.find(t)?.let { m ->
            val d = m.groupValues[1].toInt()
            val mon = MONTHS.indexOf(m.groupValues[2].take(3)) + 1
            val yg = m.groupValues[3].isNotEmpty()
            val y = if (yg) m.groupValues[3].toInt() else now.year
            makeDate(y, mon, d, now, yg)?.let { return DateHit(it, spanOf(m), "date") }
        }
        RE_MDY_WORD.find(t)?.let { m ->
            val d = m.groupValues[2].toInt()
            val mon = MONTHS.indexOf(m.groupValues[1].take(3)) + 1
            val yg = m.groupValues[3].isNotEmpty()
            val y = if (yg) m.groupValues[3].toInt() else now.year
            makeDate(y, mon, d, now, yg)?.let { return DateHit(it, spanOf(m), "date") }
        }
        RE_NUMERIC_DATE.find(t)?.let { m ->
            val d = m.groupValues[1].toInt()
            val mon = m.groupValues[2].toInt()
            val yg = m.groupValues[3].isNotEmpty()
            var y = if (yg) m.groupValues[3].toInt() else now.year
            if (y < 100) y += 2000
            makeDate(y, mon, d, now, yg)?.let { return DateHit(it, spanOf(m), "date") }
        }
        RE_WEEKDAY.find(t)?.let { m ->
            val name = m.groupValues[2]
            val target = if (name in WEEKDAYS) WEEKDAYS.indexOf(name) else WD_ABBR.getValue(name)
            val today = now.dayOfWeek.value - 1
            var delta = Math.floorMod(target - today, 7)
            if (m.groupValues[1] == "next" && delta == 0) delta = 7
            return DateHit(now.toLocalDate().plusDays(delta.toLong()), spanOf(m), if (delta != 0) "weekday" else "today-weekday")
        }
        return DateHit(null, null, "")
    }

    // ------------------------------------------------------------------------------------------------ clock
    private class ClockHit(val h: Int, val mi: Int, val mer: String?, val span: Span, val kind: String)

    private val RE_CLOCK_AMPM = Regex("\\b(?:at\\s+|by\\s+|around\\s+|@\\s*)?($NUM)(?:[:.](\\d{2}))?\\s*$AMPM(?![a-z])")
    private val RE_CLOCK_NAMED = Regex("\\b(?:at\\s+)?(noon|midday|mid-?night)\\b")
    private val RE_CLOCK_PAST_TO = Regex("\\b(?:at\\s+)?(half|quarter)\\s+(past|to)\\s+($NUM)\\b")
    private val RE_CLOCK_OCLOCK = Regex("\\b(?:at\\s+)?($NUM)(?::(\\d{2}))?\\s*(?:a\\s+)?o['’]?\\s?clock\\b")
    private val RE_CLOCK_AT_MIN = Regex("\\b(?:at|by|around)\\s+($NUM)\\s*[: .]\\s*($MINW)\\b(?:\\s*$AMPM(?![a-z]))?")
    private val RE_CLOCK_AT_HOUR = Regex(
        "\\b(?:at|by|around)\\s+($NUM)\\b(?!\\s*(?:%|percent|minutes?|mins?|hours?|hrs?|seconds?|secs?|days?|times|people|items?|kg|km|rs|rupees|dollars))",
    )
    private val RE_CLOCK_COLON = Regex("\\b(\\d{1,2}):(\\d{2})\\b")

    private fun findClock(t: String): ClockHit? {
        RE_CLOCK_AMPM.find(t)?.let { m ->
            return ClockHit(num(m.groupValues[1]), m.groupValues[2].toIntOrNull() ?: 0, if (m.groupValues[3][0] == 'a') "am" else "pm", spanOf(m), "ampm")
        }
        RE_CLOCK_NAMED.find(t)?.let { m ->
            return if (m.groupValues[1] == "noon" || m.groupValues[1] == "midday") ClockHit(12, 0, "pm", spanOf(m), "named")
            else ClockHit(12, 0, "am", spanOf(m), "named")
        }
        RE_CLOCK_PAST_TO.find(t)?.let { m ->
            var h = num(m.groupValues[3])
            var mins = if (m.groupValues[1] == "half") 30 else 15
            if (m.groupValues[2] == "to") {
                h = if (h - 1 == 0) 12 else h - 1
                mins = 60 - mins
            }
            return ClockHit(h, mins, null, spanOf(m), "words")
        }
        RE_CLOCK_OCLOCK.find(t)?.let { m ->
            return ClockHit(num(m.groupValues[1]), m.groupValues[2].toIntOrNull() ?: 0, null, spanOf(m), "oclock")
        }
        RE_CLOCK_AT_MIN.find(t)?.let { m ->
            val ap = m.groupValues[3]
            val mer = if (ap.isNotEmpty()) (if (ap[0] == 'a') "am" else "pm") else null
            return ClockHit(num(m.groupValues[1]), minutes(m.groupValues[2]), mer, spanOf(m), "at")
        }
        RE_CLOCK_AT_HOUR.find(t)?.let { m ->
            return ClockHit(num(m.groupValues[1]), 0, null, spanOf(m), "at")
        }
        RE_CLOCK_COLON.find(t)?.let { m ->
            return ClockHit(m.groupValues[1].toInt(), m.groupValues[2].toInt(), null, spanOf(m), "colon")
        }
        return null
    }

    private val RE_PART_OF_DAY = Regex("\\b(?:in the |this |at )?(morning|afternoon|evening|night)\\b")
    private val RE_RELATIVE_A = Regex(
        "\\b(?:in|after|within)\\s+((?:half an? hour|an? hour and a half|a couple of hours|couple of hours)|$DUR_CORE)",
    )
    private val RE_RELATIVE_B = Regex(
        "\\b((?:half an? hour|an? hour and a half|a couple of hours|couple of hours)|$DUR_CORE)\\s+(?:later|from now)\\b",
    )
    private val RE_NIGHT = Regex("\\bto ?night\\b")
    private val RE_EXPLICIT_TODAY = Regex("\\btoday\\b|\\btonight\\b|\\bthis (?:morning|afternoon|evening)\\b")

    // ------------------------------------------------------------------------------------------------ main entry
    /** Finds a date/time in [text]. The matched words are removed from the returned [Parsed.rest]. */
    fun parse(text: String?, now: LocalDateTime): Parsed = parseCore(text, now, null)

    /** DST-correct variant: relative times ("in 10 minutes") are added on the real timeline of [now]'s zone. */
    fun parse(text: String?, now: ZonedDateTime): Parsed = parseCore(text, now.toLocalDateTime(), now.zone)

    private fun addSeconds(local: LocalDateTime, secs: Long, zone: ZoneId?): LocalDateTime =
        if (zone == null) local.plusSeconds(secs) else local.atZone(zone).plusSeconds(secs).toLocalDateTime()

    private fun parseCore(text: String?, nowIn: LocalDateTime, zone: ZoneId?): Parsed {
        val now = nowIn.withSecond(0).withNano(0)
        val raw = (text ?: "").replace(' ', ' ')
        val t = raw.lowercase()
        val base = Parsed(rest = tidy(raw))

        // ---- relative: "in 10 minutes", "after half an hour", "10 minutes later"
        val rel = RE_RELATIVE_A.find(t) ?: RE_RELATIVE_B.find(t)
        if (rel != null) {
            val secs = parseDurationSeconds(rel.groupValues[1])
            if (secs != null && secs > 0) {
                return base.copy(
                    whenAt = addSeconds(now, secs.toLong(), zone), hasTime = true, relative = true,
                    rest = tidy(raw.substring(0, rel.range.first) + " " + raw.substring(rel.range.last + 1)),
                )
            }
        }

        val dh = findDate(t, now)
        val day = dh.date
        val dspan = dh.span
        val dword = dh.word
        val clock = findClock(t)
        val pod = RE_PART_OF_DAY.find(t)

        if (clock == null) {
            var out = base
            if (day != null && dword == "today" && dspan != null) out = out.copy(rest = tidy(cut(raw, dspan)))
            if (day != null && dword in setOf("tomorrow", "day after tomorrow", "date", "weekday")) {
                val rest = if (dspan != null) cut(raw, dspan) else raw
                out = out.copy(hasDate = true, dateOnly = true, whenAt = LocalDateTime.of(day, LocalTime.of(9, 0)), rest = tidy(rest))
            }
            return out
        }

        var mer = clock.mer
        val h = clock.h
        val mi = clock.mi
        if (h > 23 || mi > 59) return base
        val spans = ArrayList<Span>()
        spans.add(clock.span)
        if (dspan != null) spans.add(dspan)

        val clockText = raw.substring(clock.span.start, clock.span.end)
        val is24 = clock.kind == "colon" && (h >= 13 || h == 0 || clockText.trimStart().startsWith("0"))
        val tonight = RE_NIGHT.find(t)
        if (mer == null && !is24 && h <= 12 && (pod != null || tonight != null)) {
            val word = pod?.groupValues?.get(1) ?: "night"
            mer = when (word) {
                "morning" -> "am"
                "afternoon", "evening" -> "pm"
                else -> if (h == 12 || h <= 5) "am" else "pm"   // night: 12 and 1-5 are after midnight, 6-11 are evening
            }
        }
        if (pod != null) spans.add(spanOf(pod))
        if (tonight != null) spans.add(spanOf(tonight))

        val hasDate = day != null && dword != "today"
        val explicitToday = dword == "today" && RE_EXPLICIT_TODAY.containsMatchIn(t)
        val assumed = ArrayList<String>()
        var pastToday = false
        var hour12: Int? = null

        val h24: Int?
        val ambiguous: Boolean
        if (is24) {
            h24 = h; ambiguous = false
        } else if (mer == "am" || mer == "pm") {
            h24 = (h % 12) + (if (mer == "pm") 12 else 0); ambiguous = false
        } else if (h > 12) {
            h24 = h; ambiguous = false
        } else {
            h24 = null; ambiguous = true
        }

        fun at(d: LocalDate, hh: Int): LocalDateTime = LocalDateTime.of(d, LocalTime.of(hh, mi))
        var whenAt: LocalDateTime

        if (!ambiguous) {
            val d0 = day ?: now.toLocalDate()
            whenAt = at(d0, h24!!)
            if (!whenAt.isAfter(now)) {
                if (day != null && dword in setOf("tomorrow", "day after tomorrow", "date", "weekday")) {
                    if (dword == "weekday" && now.toLocalDate() == d0) whenAt = whenAt.plusDays(7)
                } else if (explicitToday) {
                    pastToday = true
                } else if (day == null || dword == "today-weekday") {
                    whenAt = whenAt.plusDays(if (day == null) 1 else 7)
                    assumed.add(if (day == null) "tomorrow" else "next week")
                }
            }
        } else {
            hour12 = h
            if (day != null && dword in setOf("tomorrow", "day after tomorrow", "date", "weekday", "today-weekday")) {
                val hh = if (h <= 12) guessMeridiem(h) else h
                whenAt = at(day, hh)
                if (!whenAt.isAfter(now) && (dword == "weekday" || dword == "today-weekday")) whenAt = whenAt.plusDays(7)
                assumed.add(clock12(hh, mi))
            } else {
                val d0 = day ?: now.toLocalDate()
                val b = h % 12
                val cands = listOf(at(d0, b), at(d0, b + 12))
                val ahead = cands.filter { it.isAfter(now.plusSeconds(20)) }
                if (ahead.isNotEmpty()) {
                    whenAt = ahead.minOrNull()!!
                    if (whenAt == cands[1] && !cands[0].isAfter(now)) assumed.add(clock12(whenAt.hour, whenAt.minute))
                } else if (explicitToday) {
                    whenAt = at(d0, guessMeridiem(h))
                    pastToday = true
                } else {
                    val hh = guessMeridiem(h)
                    whenAt = at(d0.plusDays(1), hh)
                    assumed.add(clock12(whenAt.hour, whenAt.minute))
                    assumed.add("tomorrow")
                }
            }
        }

        var rest = raw
        for (sp in spans.sortedByDescending { it.start }) rest = cut(rest, sp)
        return Parsed(
            whenAt = whenAt, rest = tidy(rest), hasTime = true, hasDate = hasDate, relative = false, dateOnly = false,
            pastToday = pastToday, assumed = assumed, hour12 = hour12,
        )
    }

    // ------------------------------------------------------------------------------------------------ formatting
    /** "5:00 PM" (no leading zero), locale independent. */
    fun clock12(hour24: Int, minute: Int): String {
        val h12 = if (hour24 % 12 == 0) 12 else hour24 % 12
        return "$h12:${minute.toString().padStart(2, '0')} ${if (hour24 < 12) "AM" else "PM"}"
    }

    fun clock12(t: LocalDateTime): String = clock12(t.hour, t.minute)

    private fun weekdayName(d: LocalDate): String = d.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH)
    private fun monthName(d: LocalDate): String = d.month.getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH)

    /** "5:00 PM today" / "9:30 AM tomorrow" / "6:00 PM on Friday" / "6:00 PM on Friday 16 October". */
    fun nice(whenAt: LocalDateTime, now: LocalDateTime): String {
        val t = clock12(whenAt)
        val d = ChronoUnit.DAYS.between(now.toLocalDate(), whenAt.toLocalDate())
        return when {
            d == 0L -> "$t today"
            d == 1L -> "$t tomorrow"
            d in 2..6 -> "$t on ${weekdayName(whenAt.toLocalDate())}"
            else -> "$t on ${weekdayName(whenAt.toLocalDate())} ${whenAt.dayOfMonth} ${monthName(whenAt.toLocalDate())}"
        }
    }

    /** The day as it is said back to the user: "Friday" within a week, otherwise "16 October". */
    fun dayPhrase(day: LocalDate, now: LocalDateTime): String {
        val d = ChronoUnit.DAYS.between(now.toLocalDate(), day)
        return if (d < 7) weekdayName(day) else "${day.dayOfMonth} ${monthName(day)}"
    }

    // ------------------------------------------------------------------------------------------------ Tools.py helpers
    private val RE_ISO = Regex("^(\\d{4})-(\\d{2})-(\\d{2})[T ](\\d{2})(?::(\\d{2}))?(?::(\\d{2}))?(?:\\.\\d+)?(?:Z|[+-]\\d{2}:?\\d{2})?$")

    /** ISO date-time ('2026-10-16T15:30', with or without offset - the wall-clock part is kept) -> minute precision, or null. */
    fun parseIso(s: String?): LocalDateTime? {
        val m = RE_ISO.find((s ?: "").trim()) ?: return null
        return try {
            LocalDateTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt(), m.groupValues[4].toInt(), m.groupValues[5].toIntOrNull() ?: 0)
        } catch (e: DateTimeException) {
            null
        }
    }

    private val RE_PAST_WORDS = Regex("\\byesterday\\b|\\blast (?:night|week|month|year)\\b|\\bago\\b", RegexOption.IGNORE_CASE)

    /**
     * Port of Tools.parse_when. Accepts ISO ('2026-10-16T15:30') or natural language ('5 pm', 'tomorrow at 7', 'in 20 minutes').
     * Returns (time, null) or (null, a sentence saying what is wrong).
     */
    fun parseWhen(s: String?, now: LocalDateTime): Pair<LocalDateTime?, String?> = parseWhenCore(s, now, null)

    fun parseWhen(s: String?, now: ZonedDateTime): Pair<LocalDateTime?, String?> = parseWhenCore(s, now.toLocalDateTime(), now.zone)

    private fun parseWhenCore(sIn: String?, nowIn: LocalDateTime, zone: ZoneId?): Pair<LocalDateTime?, String?> {
        val now = nowIn
        val s = (sIn ?: "").trim()
        if (s.isEmpty()) return null to "I need a time for that."
        if (RE_PAST_WORDS.containsMatchIn(s)) return null to "“$s” is in the past."
        var w = parseIso(s)
        if (w == null) {
            val p = parseCore(s, now, zone)
            if (!p.ok) return null to "I couldn't understand the time “$s”."
            if (p.dateOnly || !p.hasTime) return null to "I need a clock time as well as the day."
            w = p.whenAt!!
        }
        if (w.isBefore(now.minusMinutes(1))) return null to "${nice(w, now)} has already passed."
        if (w.isBefore(now)) w = now.plusMinutes(1)
        return w to null
    }

    /** Port of Tools._day: today / tomorrow / yesterday / ISO date / natural date -> LocalDate, or null for "all"/unknown. */
    fun parseDay(sIn: String?, now: LocalDateTime): LocalDate? {
        val s = (sIn ?: "").trim().lowercase()
        if (s in setOf("", "all", "any", "upcoming")) return null
        when (s) {
            "today" -> return now.toLocalDate()
            "tomorrow" -> return now.toLocalDate().plusDays(1)
            "yesterday" -> return now.toLocalDate().minusDays(1)
        }
        try {
            return LocalDate.parse(s.take(10))
        } catch (e: DateTimeException) {
            // fall through to natural language
        }
        val p = parse(s, now)
        return p.whenAt?.toLocalDate()
    }

    /** Port of Tools._clock: '15:30' / '3:30 pm' -> (15, 30), or null. */
    fun parseClock(sIn: String?, now: LocalDateTime): Pair<Int, Int>? {
        val s = (sIn ?: "").trim()
        if (s.isEmpty()) return null
        Regex("^(\\d{1,2}):(\\d{2})$").find(s)?.let { return it.groupValues[1].toInt() to it.groupValues[2].toInt() }
        val p = parse("at $s", now)
        return if (p.ok && p.hasTime) p.whenAt!!.hour to p.whenAt.minute else null
    }

    @Suppress("unused")
    private fun unusedDow(d: DayOfWeek) = d
}
