package com.jarvis.core.organizer

import com.jarvis.core.testing.TestEnv
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteServiceTest {
    private val env = TestEnv()
    private val svc = NoteService(env.services)

    @Test fun addTrimsAndStores() = runBlocking {
        val n = svc.add("  buy milk  ")
        assertEquals("buy milk", n.text)
        assertEquals(n, env.notes.items[n.id])
        assertEquals(env.clock.instant(), n.createdAt)
    }

    @Test(expected = IllegalArgumentException::class) fun blankNoteIsRefused(): Unit = runBlocking { svc.add("   "); Unit }

    @Test fun editKeepsTheOldWording() = runBlocking {
        val n = svc.add("buy milk")
        val e = svc.edit(n.id, "buy oat milk")!!
        assertEquals("buy oat milk", e.text)
        assertEquals(1, e.history.size)
        assertTrue(e.history[0], e.history[0].endsWith("|buy milk"))
        assertNull(svc.edit("missing", "x"))
    }

    @Test fun appendJoinsWithAFullStop() = runBlocking {
        val n = svc.add("Buy milk.")
        assertEquals("Buy milk. and eggs", svc.append(n.id, "and eggs")!!.text)
        assertEquals(1, svc.get(n.id)!!.history.size)
    }

    @Test fun latestIsNewestFirstAndAllIsOldestFirst() = runBlocking {
        svc.add("one"); env.clock.advanceMs(60_000); svc.add("two"); env.clock.advanceMs(60_000); svc.add("three")
        assertEquals(listOf("three", "two"), svc.latest(2).map { it.text })
        assertEquals(listOf("one", "two", "three"), svc.all().map { it.text })
    }

    @Test fun findRanksByWordOverlapAndIgnoresFillerWords() = runBlocking {
        svc.add("pay the electricity bill"); env.clock.advanceMs(1000)
        svc.add("call the plumber about the bill and the tap"); env.clock.advanceMs(1000)
        svc.add("buy milk")
        assertEquals("call the plumber about the bill and the tap", svc.find("plumber bill").first().text)
        assertEquals(2, svc.find("my note about the bill").size)
        assertTrue(svc.find("the a an").isEmpty())
        assertTrue(svc.find("zebra").isEmpty())
    }

    @Test fun linkReminderKeepsTheTextAndHistory() = runBlocking {
        val n = svc.add("call mom at 5")
        val l = svc.linkReminder(n.id, "r1")!!
        assertEquals("r1", l.reminderId)
        assertEquals("call mom at 5", l.text)
        assertTrue(l.history.isEmpty())
    }
}
