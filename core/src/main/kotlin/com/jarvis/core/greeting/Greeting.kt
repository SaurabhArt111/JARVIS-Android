package com.jarvis.core.greeting

import com.jarvis.core.ports.Reminder
import com.jarvis.core.time.TimeParser
import java.time.LocalDateTime
import kotlin.random.Random

/**
 * Time-aware greetings (port of Backend/Greeting.py). Decided by the clock, never by an AI, so it works offline and instantly.
 *
 *     00:00-03:59  late night      04:00-04:59  very early      05:00-11:59  morning
 *     12:00-16:59  afternoon       17:00-20:59  evening         21:00-23:59  night
 */
object Greeting {
    enum class Period { LATE_NIGHT, EARLY, MORNING, AFTERNOON, EVENING, NIGHT }

    fun period(now: LocalDateTime): Period {
        val h = now.hour
        return when {
            h < 4 -> Period.LATE_NIGHT
            h < 5 -> Period.EARLY
            h < 12 -> Period.MORNING
            h < 17 -> Period.AFTERNOON
            h < 21 -> Period.EVENING
            else -> Period.NIGHT
        }
    }

    /** How the user is addressed: the saved name, or "sir". */
    fun address(name: String?): String {
        val n = (name ?: "").trim().ifEmpty { "Sir" }
        return if (n.equals("sir", ignoreCase = true)) "sir" else n
    }

    private val LINES: Map<Period, List<String>> = mapOf(
        Period.LATE_NIGHT to listOf(
            "Hello, {a}. You're up late. Is everything alright?", "It's quite late, {a}. I'm here if you need me.",
            "Burning the midnight oil, {a}? I'm ready when you are.",
        ),
        Period.EARLY to listOf("Good early morning, {a}. You're up before the sun.", "Hello, {a}. Up very early today. How can I help?"),
        Period.MORNING to listOf("Good morning, {a}.", "Good morning, {a}. Ready when you are.", "Morning, {a}. What shall we do first?"),
        Period.AFTERNOON to listOf("Good afternoon, {a}.", "Good afternoon, {a}. How can I help?", "Hello, {a}. Hope the afternoon is going well."),
        Period.EVENING to listOf("Good evening, {a}.", "Good evening, {a}. How can I help?", "Hello, {a}. Winding down, or still working?"),
        Period.NIGHT to listOf("Good evening, {a}. It's getting late.", "Hello, {a}. Working late tonight?", "Good night owl hours, {a}. I'm here if you need me."),
    )

    /** Every line the greeting can use for [period] (tests assert on these, so the wording is part of the contract). */
    fun lines(period: Period, userName: String?): List<String> = LINES.getValue(period).map { it.replace("{a}", address(userName)) }

    fun greeting(now: LocalDateTime, userName: String?, rng: Random = Random.Default): String = lines(period(now), userName).random(rng)

    fun icon(now: LocalDateTime): String = if (period(now) in setOf(Period.MORNING, Period.AFTERNOON, Period.EARLY)) "sun" else "moon"

    /** The greeting plus one useful line: what is coming up today. [upcomingToday] may contain anything; only today's PENDING ones are used. */
    fun startupMessage(upcomingToday: List<Reminder>, now: LocalDateTime, userName: String, rng: Random = Random.Default): String {
        var text = greeting(now, userName, rng)
        val today = upcomingToday.filter { it.pending && it.due.toLocalDate() == now.toLocalDate() }.sortedBy { it.due }
        if (today.isNotEmpty()) {
            val next = today.first()
            val whenText = TimeParser.nice(next.due, now)
            text += if (today.size > 1) " You have ${today.size} reminders today. The next is ${next.text}, $whenText."
            else " You have a reminder today: ${next.text}, $whenText."
        }
        return text
    }

    private val HELLO = Regex(
        "^(?:hello|hi+|hey+|yo|hola|namaste|heya|howdy|hello there|hi there|hey there|good (?:morning|afternoon|evening)|gm|morning|evening)" +
            "(?: there| jarvis| null| sir| buddy)?$",
    )
    private val BYE = Regex("^(?:good ?night|gn|night night|nighty night|sleep well)(?: jarvis| null| sir)?$")

    /** If [text] is just a greeting, the right reply for the time of day; otherwise null (the request goes on to the next layer). */
    fun replyTo(text: String, now: LocalDateTime, userName: String, rng: Random = Random.Default): String? {
        val t = text.trim(' ', '.', '!', '?', ',').lowercase()
        if (BYE.matches(t)) return "Good night, ${address(userName)}. Rest well. I'll be here when you need me."
        if (HELLO.matches(t)) return greeting(now, userName, rng)
        return null
    }
}
