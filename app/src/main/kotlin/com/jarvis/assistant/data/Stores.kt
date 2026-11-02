package com.jarvis.assistant.data

import android.database.Cursor
import com.jarvis.core.json.Json
import com.jarvis.core.ports.*
import java.time.Instant
import java.time.LocalDateTime

internal fun Cursor.str(i: Int): String = if (isNull(i)) "" else getString(i)
internal fun Cursor.strOrNull(i: Int): String? = if (isNull(i)) null else getString(i)
internal fun Cursor.longOrNull(i: Int): Long? = if (isNull(i)) null else getLong(i)

internal fun historyJson(h: List<String>): String = Json.stringify(h)
internal fun historyOf(s: String?): List<String> = try {
    Json.list(Json.parse(s ?: "[]")).orEmpty().mapNotNull { Json.str(it) }
} catch (e: Exception) {
    emptyList() // damaged history text never blocks reading the reminder / note itself
}

private fun reminderOf(c: Cursor): Reminder? = try {
    Reminder(
        id = c.str(0), text = c.str(1), due = LocalDateTime.parse(c.str(2)), zoneId = c.str(3), floating = c.getInt(4) != 0,
        state = ReminderState.valueOf(c.str(5)), isAlarm = c.getInt(6) != 0, createdAt = Instant.ofEpochMilli(c.getLong(7)),
        firedAt = c.longOrNull(8)?.let(Instant::ofEpochMilli), noteId = c.strOrNull(9), history = historyOf(c.strOrNull(10)),
    )
} catch (e: Exception) {
    null // one damaged row must never hide the others
}

class SqlReminderStore(private val db: Db) : ReminderStore {
    private fun args(r: Reminder): Array<Any?> = arrayOf(
        r.id, r.text, r.due.toString(), r.zoneId, if (r.floating) 1 else 0, r.state.name, if (r.isAlarm) 1 else 0, r.createdAt.toEpochMilli(),
        r.firedAt?.toEpochMilli(), r.noteId, historyJson(r.history),
    )

    override suspend fun insert(reminder: Reminder): Reminder = db.run { d -> d.execSQL(Sql.Q_REM_INSERT, args(reminder)); reminder }

    override suspend fun get(id: String): Reminder? = db.run { d -> d.rawQuery(Sql.Q_REM_GET, arrayOf(id)).use { if (it.moveToFirst()) reminderOf(it) else null } }

    override suspend fun all(): List<Reminder> = db.run { d -> d.rawQuery(Sql.Q_REM_ALL, null).use { c -> buildList { while (c.moveToNext()) reminderOf(c)?.let(::add) } } }

    override suspend fun update(reminder: Reminder): Reminder? = db.run { d ->
        d.execSQL(Sql.Q_REM_UPDATE, arrayOf(
            reminder.text, reminder.due.toString(), reminder.zoneId, if (reminder.floating) 1 else 0, reminder.state.name, if (reminder.isAlarm) 1 else 0,
            reminder.firedAt?.toEpochMilli(), reminder.noteId, historyJson(reminder.history), reminder.id,
        ))
        d.rawQuery(Sql.Q_REM_GET, arrayOf(reminder.id)).use { if (it.moveToFirst()) reminderOf(it) else null }
    }

    override suspend fun deletePermanently(id: String): Boolean = db.run { d -> d.compileStatement(Sql.Q_REM_DELETE).use { s -> s.bindString(1, id); s.executeUpdateDelete() } > 0 }
}

private fun noteOf(c: Cursor): Note? = try {
    Note(c.str(0), c.str(1), Instant.ofEpochMilli(c.getLong(2)), Instant.ofEpochMilli(c.getLong(3)), c.strOrNull(4), historyOf(c.strOrNull(5)))
} catch (e: Exception) { null }

class SqlNoteStore(private val db: Db) : NoteStore {
    override suspend fun insert(note: Note): Note = db.run { d ->
        d.execSQL(Sql.Q_NOTE_INSERT, arrayOf(note.id, note.text, note.createdAt.toEpochMilli(), note.updatedAt.toEpochMilli(), note.reminderId, historyJson(note.history))); note
    }

    override suspend fun get(id: String): Note? = db.run { d -> d.rawQuery(Sql.Q_NOTE_GET, arrayOf(id)).use { if (it.moveToFirst()) noteOf(it) else null } }

    override suspend fun all(): List<Note> = db.run { d -> d.rawQuery(Sql.Q_NOTE_ALL, null).use { c -> buildList { while (c.moveToNext()) noteOf(c)?.let(::add) } } }

    override suspend fun update(note: Note): Note? = db.run { d ->
        d.execSQL(Sql.Q_NOTE_UPDATE, arrayOf(note.text, note.updatedAt.toEpochMilli(), note.reminderId, historyJson(note.history), note.id))
        d.rawQuery(Sql.Q_NOTE_GET, arrayOf(note.id)).use { if (it.moveToFirst()) noteOf(it) else null }
    }

    override suspend fun deletePermanently(id: String): Boolean = db.run { d -> d.compileStatement(Sql.Q_NOTE_DELETE).use { s -> s.bindString(1, id); s.executeUpdateDelete() } > 0 }
}

/** The files index (what the Library screen lists). The files themselves live in Documents/JARVIS or private storage; see DocumentStoreAndroid. */
class FileIndex(private val db: Db) {
    private fun fileOf(c: Cursor): StoredFile? = try {
        StoredFile(
            c.str(0), c.str(1), ContentCategory.valueOf(c.str(2)), c.str(3), c.str(4), c.getLong(5), Instant.ofEpochMilli(c.getLong(6)),
            StorageLocation.valueOf(c.str(7)), c.strOrNull(8),
        )
    } catch (e: Exception) { null }

    suspend fun add(f: StoredFile) = db.run { d ->
        d.execSQL(Sql.Q_FILE_INSERT, arrayOf(f.id, f.name, f.category.name, f.uri, f.mime, f.sizeBytes, f.createdAt.toEpochMilli(), f.location.name, f.source))
    }

    suspend fun nameTaken(category: ContentCategory, name: String): Boolean = db.run { d -> d.rawQuery(Sql.Q_FILE_NAME_TAKEN, arrayOf(category.name, name)).use { it.moveToFirst() } }

    suspend fun list(category: ContentCategory?, query: String?): List<StoredFile> = db.run { d ->
        val q = query?.trim()?.takeIf { it.isNotEmpty() }?.let { Sql.like(it) }
        val (sql, args) = when {
            category != null && q != null -> Sql.Q_FILE_LIST_CAT_Q to arrayOf(category.name, q, q)
            category != null -> Sql.Q_FILE_LIST_CAT to arrayOf(category.name)
            q != null -> Sql.Q_FILE_LIST_Q to arrayOf(q, q)
            else -> Sql.Q_FILE_LIST_ALL to emptyArray()
        }
        d.rawQuery(sql, args).use { c -> buildList { while (c.moveToNext()) fileOf(c)?.let(::add) } }
    }

    suspend fun remove(id: String): Boolean = db.run { d -> d.compileStatement(Sql.Q_FILE_DELETE).use { s -> s.bindString(1, id); s.executeUpdateDelete() } > 0 }

    suspend fun olderThan(cutoffMs: Long, category: ContentCategory?): List<StoredFile> = db.run { d ->
        val (sql, args) = if (category != null) Sql.Q_FILE_OLDER_CAT to arrayOf(cutoffMs.toString(), category.name) else Sql.Q_FILE_OLDER_ALL to arrayOf(cutoffMs.toString())
        d.rawQuery(sql, args).use { c -> buildList { while (c.moveToNext()) fileOf(c)?.let(::add) } }
    }
}

/** Persisted timers (so a running timer survives the app being killed). */
class TimerStore(private val db: Db) {
    fun save(t: TimerInfo) = db.runBlockingIo { d -> d.execSQL(Sql.Q_TIMER_INSERT, arrayOf(t.id, t.label, t.seconds, t.endsAtMs)) }
    fun all(): List<TimerInfo> = db.runBlockingIo { d ->
        d.rawQuery(Sql.Q_TIMER_ALL, null).use { c -> buildList { while (c.moveToNext()) add(TimerInfo(c.str(0), c.str(1), c.getInt(2), c.getLong(3))) } }
    }
    fun remove(id: String) = db.runBlockingIo { d -> d.execSQL(Sql.Q_TIMER_DELETE, arrayOf(id)) }
    fun removeAll() = db.runBlockingIo { d -> d.execSQL(Sql.Q_TIMER_DELETE_ALL) }
}

