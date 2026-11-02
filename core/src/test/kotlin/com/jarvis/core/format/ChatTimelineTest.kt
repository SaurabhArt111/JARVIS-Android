package com.jarvis.core.format

import com.jarvis.core.model.AssistantReply
import com.jarvis.core.ports.StoredMessage
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class ChatTimelineTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun at(y: Int, mo: Int, d: Int, h: Int = 12, mi: Int = 0) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()
    private val now = at(2026, 10, 30, 15)
    private fun stored(id: Long, role: String, text: String, atMs: Long = now - 1000 * (100 - id)) = StoredMessage(id, 1, role, text, atMs)
    private fun live(seq: Long, afterId: Long, role: String, text: String, atMs: Long = now) = LiveMessage(seq, afterId, role, text, atMs)
    private fun merge(h: List<StoredMessage>, l: List<LiveMessage>) = ChatTimeline.merge(h, l, now, zone, locale = Locale.ENGLISH)
    private fun msgs(items: List<TimelineItem>) = items.filterIsInstance<TimelineItem.Message>()

    @Test fun storedMessagesComeInIdOrderWithOneDaySeparator() {
        val items = merge(listOf(stored(2, "assistant", "Hi"), stored(1, "user", "Hello")), emptyList())
        assertEquals(listOf("Day", "Message", "Message"), items.map { it.javaClass.simpleName })
        assertEquals(listOf("Hello", "Hi"), msgs(items).map { it.text })
    }

    @Test fun aLiveMessageIsDroppedOnceItIsStored() {
        val h = listOf(stored(1, "user", "old"), stored(2, "assistant", "old answer"), stored(3, "user", "what time is it"), stored(4, "assistant", "It's 3 pm."))
        val l = listOf(live(1, 2, "user", "what time is it"), live(2, 2, "assistant", "It's 3 pm."))
        assertEquals(listOf("old", "old answer", "what time is it", "It's 3 pm."), msgs(merge(h, l)).map { it.text })
    }

    @Test fun aLiveMessageNotStoredYetIsShownAfterTheHistory() {
        val h = listOf(stored(1, "user", "a"), stored(2, "assistant", "b"))
        val l = listOf(live(1, 2, "user", "remind me"))
        val m = msgs(merge(h, l))
        assertEquals(listOf("a", "b", "remind me"), m.map { it.text })
        assertEquals(null, m.last().storedId)
        assertEquals("l:1", m.last().key)
    }

    @Test fun anErrorReplyThatIsNeverStoredStaysVisible() {
        val h = listOf(stored(1, "user", "q"))
        val err = AssistantReply.error("Something went wrong")
        val l = listOf(live(1, 0, "user", "q"), LiveMessage(2, 0, "assistant", err.text, now, err))
        val m = msgs(merge(h, l))
        assertEquals(listOf("q", "Something went wrong"), m.map { it.text })
        assertEquals(err, m.last().reply)
    }

    @Test fun identicalQuestionsAskedTwiceShowTwiceUntilBothAreStored() {
        val h = listOf(stored(1, "user", "hi"), stored(2, "assistant", "hello"))
        val l = listOf(live(1, 0, "user", "hi"), live(2, 0, "assistant", "hello"), live(3, 2, "user", "hi"))
        // rows 1-2 cancel the first pair; the second "hi" (asked after id 2) is not stored yet
        assertEquals(listOf("hi", "hello", "hi"), msgs(merge(h, l)).map { it.text })
        assertEquals(listOf(3L), ChatTimeline.unmatchedLive(h, l).map { it.seq })
    }

    @Test fun aStoredRowOlderThanTheLiveMessageCannotCancelIt() {
        val h = listOf(stored(5, "user", "hi"))
        val l = listOf(live(1, 5, "user", "hi"))
        assertEquals(1, ChatTimeline.unmatchedLive(h, l).size)
    }

    @Test fun separatorsChangeWhenTheDayChanges() {
        val h = listOf(stored(1, "user", "a", at(2026, 10, 28, 9)), stored(2, "assistant", "b", at(2026, 10, 29, 23, 59)), stored(3, "user", "c", at(2026, 10, 30, 0, 1)))
        val labels = merge(h, emptyList()).filterIsInstance<TimelineItem.Day>().map { it.label }
        assertEquals(listOf("Wednesday", "Yesterday", "Today"), labels)
    }

    @Test fun dayLabelsAreRelativeThenWeekdayThenDate() {
        val w = DayWords("Today", "Yesterday")
        fun label(ms: Long) = ChatTimeline.dayLabel(ms, now, zone, w, Locale.ENGLISH)
        assertEquals("Today", label(at(2026, 10, 30, 0, 5)))
        assertEquals("Yesterday", label(at(2026, 10, 29, 23, 59)))
        assertEquals("Wednesday", label(at(2026, 10, 28)))
        assertEquals("Sunday", label(at(2026, 10, 25)))
        assertEquals("1 Sep", label(at(2026, 9, 1)))
        assertEquals("31 Dec 2025", label(at(2025, 12, 31)))
    }

    @Test fun dayLabelsUseTheLocalizedWordsGiven() {
        assertEquals("Heute", ChatTimeline.dayLabel(now, now, zone, DayWords("Heute", "Gestern"), Locale.GERMAN))
        assertEquals("Gestern", ChatTimeline.dayLabel(now - 86_400_000, now, zone, DayWords("Heute", "Gestern"), Locale.GERMAN))
    }

    @Test fun midnightIsDecidedByTheUsersTimeZoneNotUtc() {
        // 2026-10-29 19:00 UTC is 2026-10-30 00:30 in Kolkata: "Today" there, "Yesterday" for someone in UTC (where it is 09:30 on the 30th now)
        val ms = java.time.Instant.parse("2026-10-29T19:00:00Z").toEpochMilli()
        assertEquals("Today", ChatTimeline.dayLabel(ms, now, zone, locale = Locale.ENGLISH))
        assertEquals("Yesterday", ChatTimeline.dayLabel(ms, now, ZoneId.of("UTC"), locale = Locale.ENGLISH))
    }

    @Test fun emptyInputGivesEmptyTimeline() { assertTrue(merge(emptyList(), emptyList()).isEmpty()) }
}
