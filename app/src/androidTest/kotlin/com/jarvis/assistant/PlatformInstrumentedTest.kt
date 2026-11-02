package com.jarvis.assistant

import com.jarvis.assistant.data.Db
import com.jarvis.assistant.data.FileIndex
import com.jarvis.assistant.platform.AndroidReminderScheduler
import com.jarvis.assistant.platform.DocumentStoreAndroid
import com.jarvis.assistant.platform.PermissionHelper
import com.jarvis.core.model.Requirement
import com.jarvis.core.ports.ContentCategory
import com.jarvis.core.ports.Reminder
import com.jarvis.core.ports.ReminderState
import com.jarvis.core.ports.ScheduleMode
import com.jarvis.core.ports.StorageLocation
import com.jarvis.core.settings.InMemorySettingsStore
import com.jarvis.core.settings.Keys
import com.jarvis.core.settings.Settings
import com.jarvis.core.util.SystemClock
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Needs a real device or emulator (MediaStore, AlarmManager, system settings). Not run in the offline build environment. */
class PlatformInstrumentedTest {
    private val ctx = TestSupport.context
    private val dbName = TestSupport.uniqueDb()
    private val db = Db(ctx, name = dbName)
    private val settings = Settings(InMemorySettingsStore())
    private val created = ArrayList<com.jarvis.core.ports.StoredFile>()
    private val docs = DocumentStoreAndroid(ctx, settings, FileIndex(db), SystemClock)

    @After fun cleanup() { runBlocking { created.forEach { docs.deletePermanently(it) } }; db.close(); ctx.deleteDatabase(dbName) }

    // ---- documents
    @Test fun savedTextIsListedReadableAndUnique() = runBlocking {
        val a = docs.saveText(ContentCategory.NOTES, "Test note", "hello JARVIS", "txt", "unit test").also { created.add(it) }
        val b = docs.saveText(ContentCategory.NOTES, "Test note", "second", "txt").also { created.add(it) }
        assertNotEquals("never overwrites: names must differ", a.name, b.name)
        assertEquals("hello JARVIS", docs.readText(a))
        assertTrue(docs.list(ContentCategory.NOTES, "test_note").size >= 2)
        // either public Documents or the private fallback is acceptable on a given device, but the file must say which
        assertTrue(a.location == StorageLocation.PUBLIC_DOCUMENTS || a.location == StorageLocation.APP_PRIVATE)
        assertEquals("text/plain", a.mime)
    }

    @Test fun privateModeKeepsFilesInsideTheAppFolder() = runBlocking {
        settings.set(Keys.STORAGE_LOCATION, "private")
        val f = docs.saveText(ContentCategory.GENERATED_TEXT, "private one", "x", "md").also { created.add(it) }
        assertEquals(StorageLocation.APP_PRIVATE, f.location)
        assertTrue(f.uri.startsWith(ctx.filesDir.absolutePath))
    }

    @Test fun deletingReallyRemovesTheFileAndTheEntry() = runBlocking {
        val f = docs.saveText(ContentCategory.EXPORTS, "to delete", "bye", "txt")
        assertTrue(docs.deletePermanently(f))
        assertTrue(docs.list(ContentCategory.EXPORTS, "to_delete").none { it.id == f.id })
    }

    @Test fun wrongTypesAndFakeBinariesAreRefused() = runBlocking {
        try { docs.saveText(ContentCategory.IMAGES, "page", "<html>", "html"); fail("html must not be allowed in Images") } catch (e: Exception) { }
        try { docs.saveBytes(ContentCategory.IMAGES, "fake", "not a png".toByteArray(), "image/png", "png"); fail("bytes are not a PNG") } catch (e: Exception) { }
        try { docs.saveText(ContentCategory.OTHER, "tool", "MZ", "exe"); fail("exe must not be allowed") } catch (e: Exception) { }
    }

    @Test fun cleanupOnlyRemovesOldFiles() = runBlocking {
        val f = docs.saveText(ContentCategory.OTHER, "fresh", "new", "txt").also { created.add(it) }
        assertEquals(0, docs.cleanupOlderThan(1))
        assertNotNull(docs.list(ContentCategory.OTHER, null).firstOrNull { it.id == f.id })
    }

    // ---- reminders
    @Test fun schedulerRegistersReadsBackAndCancels() {
        val s = AndroidReminderScheduler(ctx, SystemClock)
        val r = Reminder("t" + System.nanoTime().toString().takeLast(6), "test", LocalDateTime.now().plusHours(2), ZoneId.systemDefault().id, true,
            ReminderState.PENDING, false, Instant.now())
        val res = s.schedule(r)
        try {
            assertTrue(res.scheduled)
            assertEquals(if (s.canScheduleExact()) ScheduleMode.EXACT else ScheduleMode.INEXACT, res.mode)
            assertTrue(s.isScheduled(r.id))
        } finally { s.cancel(r.id) }
        assertFalse(s.isScheduled(r.id))
    }

    // ---- permissions
    @Test fun everyRequirementCanBeReadWithoutThrowing() {
        val p = PermissionHelper(ctx, settings)
        for (req in Requirement.entries) { p.state(req); p.title(req); p.rationale(req) }
        assertTrue(p.allRequirements().isNotEmpty())
    }

    @Test fun grantingRoutesAreDefinedForEveryListedRequirement() {
        val p = PermissionHelper(ctx, settings)
        for (req in p.allRequirements()) {
            val runtime = p.runtimePermissions(req).isNotEmpty()
            val screen = p.settingsIntent(req) != null
            assertTrue("$req needs a way to be granted", runtime || screen)
        }
    }
}
