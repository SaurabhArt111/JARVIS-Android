package com.jarvis.core.format

import com.jarvis.core.model.AssistantReply
import com.jarvis.core.ports.StoredMessage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** A message the UI saw on the engine's event stream that may not be in the history database yet (or, for errors, never will be). */
data class LiveMessage(
    /** Increasing number in arrival order. */
    val seq: Long,
    /** Highest stored message id when this one arrived: only rows stored after that can be the same message. */
    val afterId: Long,
    val role: String,
    val text: String,
    val atMs: Long,
    val reply: AssistantReply? = null,
)

sealed class TimelineItem {
    abstract val key: String

    data class Day(val label: String, val dayKey: String) : TimelineItem() { override val key: String get() = "day:$dayKey" }

    data class Message(
        val role: String,
        val text: String,
        val atMs: Long,
        val reply: AssistantReply?,
        /** Stored row id, or null for a live message not stored yet. */
        val storedId: Long?,
        val seq: Long = 0,
    ) : TimelineItem() {
        override val key: String get() = if (storedId != null) "m:$storedId" else "l:$seq"
        val isUser: Boolean get() = role == "user"
    }
}

/** Day-separator wording. The app passes localized words; the defaults are English. */
data class DayWords(val today: String = "Today", val yesterday: String = "Yesterday")

/**
 * Builds the list the chat screen shows from the stored history and the live tail.
 *
 * The same message can arrive twice: first live (engine event), then from the database once it is stored. A live message is dropped as soon as a stored message
 * of the same role and text, with a higher id than the history had when the live one arrived, exists. Each stored row can cancel only one live message, so two
 * identical questions in a row still show twice. Live messages that never reach the database (an error reply) stay at the end, in order.
 */
object ChatTimeline {

    fun unmatchedLive(history: List<StoredMessage>, live: List<LiveMessage>): List<LiveMessage> {
        val used = HashSet<Long>()
        val out = ArrayList<LiveMessage>()
        for (l in live.sortedBy { it.seq }) {
            val hit = history.firstOrNull { h -> h.id > l.afterId && h.id !in used && h.role == l.role && h.text.trim() == l.text.trim() }
            if (hit != null) used += hit.id else out += l
        }
        return out
    }

    fun merge(history: List<StoredMessage>, live: List<LiveMessage>, nowMs: Long, zone: ZoneId, words: DayWords = DayWords(), locale: Locale = Locale.getDefault()): List<TimelineItem> {
        val messages = ArrayList<TimelineItem.Message>(history.size + live.size)
        history.sortedBy { it.id }.forEach { messages += TimelineItem.Message(it.role, it.text, it.atMs, it.reply, it.id) }
        unmatchedLive(history, live).forEach { messages += TimelineItem.Message(it.role, it.text, it.atMs, it.reply, null, it.seq) }

        val out = ArrayList<TimelineItem>(messages.size + 8)
        var lastDay: String? = null
        for (m in messages) {
            val day = Instant.ofEpochMilli(m.atMs).atZone(zone).toLocalDate().toString()
            if (day != lastDay) { out += TimelineItem.Day(dayLabel(m.atMs, nowMs, zone, words, locale), day); lastDay = day }
            out += m
        }
        return out
    }

    fun dayLabel(atMs: Long, nowMs: Long, zone: ZoneId, words: DayWords = DayWords(), locale: Locale = Locale.getDefault()): String {
        val d = Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val diff = today.toEpochDay() - d.toEpochDay()
        return when {
            diff == 0L -> words.today
            diff == 1L -> words.yesterday
            diff in 2..6 -> d.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
            d.year == today.year -> d.format(DateTimeFormatter.ofPattern("d MMM", locale))
            else -> d.format(DateTimeFormatter.ofPattern("d MMM yyyy", locale))
        }
    }
}
