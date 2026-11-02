package com.jarvis.assistant

import com.jarvis.assistant.data.Db
import com.jarvis.assistant.data.FileIndex
import com.jarvis.assistant.data.SqlConversationMemory
import com.jarvis.assistant.data.SqlConversationRepository
import com.jarvis.assistant.data.SqlNoteStore
import com.jarvis.assistant.data.SqlReminderStore
import com.jarvis.assistant.data.TimerStore
import com.jarvis.core.model.AssistantReply
import com.jarvis.core.ports.ContentCategory
import com.jarvis.core.ports.Note
import com.jarvis.core.ports.Reminder
import com.jarvis.core.ports.ReminderState
import com.jarvis.core.ports.StorageLocation
import com.jarvis.core.ports.StoredFile
import com.jarvis.core.ports.TimerInfo
import com.jarvis.core.util.SystemClock
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime

/** Round-trips every store through a real SQLite database on the device. (The SQL text itself is also checked with python's sqlite3 in tools/offline-verify/check_sql.py.) */
class SqlStoresInstrumentedTest {
    private val name = TestSupport.uniqueDb()
    private lateinit var db: Db

    @Before fun setUp() { db = Db(TestSupport.context, name = name) }
    @After fun tearDown() { db.close(); TestSupport.context.deleteDatabase(name) }

    private fun reminder(id: String, text: String = "call mom", due: LocalDateTime = LocalDateTime.of(2030, 1, 2, 7, 30)) =
        Reminder(id, text, due, "Asia/Kolkata", true, ReminderState.PENDING, false, Instant.ofEpochMilli(1_700_000_000_000))

    @Test fun reminderRoundTripKeepsEveryField() = runBlocking {
        val store = SqlReminderStore(db)
        val r = reminder("r1").copy(isAlarm = true, noteId = "n1", history = listOf("2030-01-01T10:00|2030-01-02T07:30|old text"))
        store.insert(r)
        val back = store.get("r1")
        assertEquals(r, back)
    }

    @Test fun reminderUpdateChangesStateAndKeepsHistory() = runBlocking {
        val store = SqlReminderStore(db)
        store.insert(reminder("r1"))
        store.update(reminder("r1").copy(state = ReminderState.DONE, firedAt = Instant.ofEpochMilli(1_800_000_000_000)))
        val back = store.get("r1")!!
        assertEquals(ReminderState.DONE, back.state)
        assertNotNull(back.firedAt)
    }

    @Test fun remindersAreListedOldestDueFirstAndCanBeDeleted() = runBlocking {
        val store = SqlReminderStore(db)
        store.insert(reminder("late", due = LocalDateTime.of(2031, 1, 1, 9, 0)))
        store.insert(reminder("early", due = LocalDateTime.of(2030, 1, 1, 9, 0)))
        assertEquals(listOf("early", "late"), store.all().map { it.id })
        assertTrue(store.deletePermanently("early"))
        assertNull(store.get("early"))
        assertFalse(store.deletePermanently("early"))
    }

    @Test fun notesRoundTrip() = runBlocking {
        val store = SqlNoteStore(db)
        val n = Note("n1", "buy milk", Instant.ofEpochMilli(1_700_000_000_000), Instant.ofEpochMilli(1_700_000_100_000), "r1", listOf("earlier text"))
        store.insert(n)
        assertEquals(n, store.get("n1"))
        store.update(n.copy(text = "buy milk and eggs"))
        assertEquals("buy milk and eggs", store.get("n1")!!.text)
        assertTrue(store.deletePermanently("n1"))
    }

    @Test fun fileIndexSearchesByNameAndSourceAndFiltersByCategory() = runBlocking {
        val idx = FileIndex(db)
        fun f(id: String, name: String, cat: ContentCategory, at: Long, src: String? = null) =
            StoredFile(id, name, cat, "content://x/$id", "text/plain", 10, Instant.ofEpochMilli(at), StorageLocation.PUBLIC_DOCUMENTS, src)
        idx.add(f("1", "trip_plan.txt", ContentCategory.GENERATED_TEXT, 1000, "plan a trip to goa"))
        idx.add(f("2", "shopping.txt", ContentCategory.NOTES, 2000))
        idx.add(f("3", "100%_sure.txt", ContentCategory.NOTES, 3000))
        assertEquals(listOf("3", "2", "1"), idx.list(null, null).map { it.id })
        assertEquals(listOf("1"), idx.list(null, "goa").map { it.id })
        assertEquals(listOf("3", "2"), idx.list(ContentCategory.NOTES, null).map { it.id })
        assertEquals(listOf("3"), idx.list(null, "100%").map { it.id })        // % is literal, not a wildcard
        assertTrue(idx.nameTaken(ContentCategory.NOTES, "shopping.txt"))
        assertFalse(idx.nameTaken(ContentCategory.IMAGES, "shopping.txt"))
        assertEquals(listOf("1"), idx.olderThan(1500, null).map { it.id })
        assertTrue(idx.remove("2"))
    }

    @Test fun timersPersist() {
        val t = TimerStore(db)
        t.save(TimerInfo("a", "pasta", 600, 123456789L))
        assertEquals(listOf(TimerInfo("a", "pasta", 600, 123456789L)), t.all())
        t.remove("a")
        assertTrue(t.all().isEmpty())
    }

    @Test fun conversationHistoryAndModelMemory() = runBlocking {
        val repo = SqlConversationRepository(db, SystemClock) { throw IllegalStateException("not needed") }
        val mem = SqlConversationMemory(db, repo)
        val cid = repo.currentConversationId()
        repo.append(cid, "user", "hello")
        repo.append(cid, "assistant", "hi there", AssistantReply("hi there"), "text")
        repo.append(cid, "assistant", "anything else?")
        val msgs = repo.messages(cid)
        assertEquals(listOf("user", "assistant", "assistant"), msgs.map { it.role })
        assertNotNull(msgs[1].reply)
        // consecutive turns of the same side are merged for the model
        val recent = mem.recent(10)
        assertEquals(2, recent.size)
        assertEquals("hi there\nanything else?", recent[1].content)
        assertEquals(1, repo.search("hello").size)
        assertTrue(repo.deleteConversation(cid))
        assertTrue(repo.messages(cid).isEmpty())
    }

    @Test fun aCorruptDatabaseIsMovedAsideNotDeleted() {
        db.close()
        val f = TestSupport.context.getDatabasePath(name)
        f.parentFile!!.mkdirs()
        f.writeBytes(ByteArray(4096) { 7 })
        val fresh = Db(TestSupport.context, name = name)
        runBlocking { assertTrue(SqlReminderStore(fresh).all().isEmpty()) }
        val siblings = f.parentFile!!.listFiles()!!.map { it.name }
        assertTrue("corrupt copy should be kept: $siblings", siblings.any { it.startsWith("$name.corrupt") })
        fresh.close()
        siblings.filter { it.startsWith(name) }.forEach { java.io.File(f.parentFile, it).delete() }
    }
}
