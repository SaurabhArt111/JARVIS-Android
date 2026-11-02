package com.jarvis.assistant.data

/**
 * Every statement the app runs against its private SQLite database, as constants (no string-building around user input: values are always bound
 * parameters). `tools/offline-verify/check_sql.py` executes the DDL and prepares every `Q_*` query with the real SQLite engine.
 */
object Sql {
    const val DB_NAME = "jarvis.db"
    const val VERSION = 1

    /** Version 1 schema. Later versions add entries to [MIGRATIONS] (never edit an old one). */
    val CREATE: List<String> = listOf(
        """CREATE TABLE conversations (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            title TEXT NOT NULL DEFAULT '',
            started_ms INTEGER NOT NULL,
            last_ms INTEGER NOT NULL)""",
        """CREATE TABLE messages (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            conversation_id INTEGER NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
            role TEXT NOT NULL CHECK (role IN ('user','assistant')),
            text TEXT NOT NULL,
            at_ms INTEGER NOT NULL,
            reply_json TEXT,
            origin TEXT NOT NULL DEFAULT '')""",
        "CREATE INDEX idx_messages_conv ON messages(conversation_id, id)",
        """CREATE TABLE reminders (
            id TEXT PRIMARY KEY,
            text TEXT NOT NULL,
            due TEXT NOT NULL,
            zone TEXT NOT NULL,
            floating INTEGER NOT NULL DEFAULT 1,
            state TEXT NOT NULL CHECK (state IN ('PENDING','FIRED','DONE','CANCELLED')),
            is_alarm INTEGER NOT NULL DEFAULT 0,
            created_ms INTEGER NOT NULL,
            fired_ms INTEGER,
            note_id TEXT,
            history TEXT NOT NULL DEFAULT '[]')""",
        "CREATE INDEX idx_reminders_state_due ON reminders(state, due)",
        """CREATE TABLE notes (
            id TEXT PRIMARY KEY,
            text TEXT NOT NULL,
            created_ms INTEGER NOT NULL,
            updated_ms INTEGER NOT NULL,
            reminder_id TEXT,
            history TEXT NOT NULL DEFAULT '[]')""",
        """CREATE TABLE files (
            id TEXT PRIMARY KEY,
            name TEXT NOT NULL,
            category TEXT NOT NULL,
            uri TEXT NOT NULL,
            mime TEXT NOT NULL,
            size INTEGER NOT NULL DEFAULT 0,
            created_ms INTEGER NOT NULL,
            location TEXT NOT NULL,
            source TEXT)""",
        "CREATE INDEX idx_files_cat ON files(category, created_ms)",
        """CREATE TABLE timers (
            id TEXT PRIMARY KEY,
            label TEXT NOT NULL,
            seconds INTEGER NOT NULL,
            ends_ms INTEGER NOT NULL)""",
    )

    /** `MIGRATIONS[n]` upgrades a version-n database to version n+1. Empty while VERSION = 1. */
    val MIGRATIONS: List<List<String>> = listOf(emptyList())

    // ---- conversations / messages
    const val Q_CONV_LATEST = "SELECT id FROM conversations ORDER BY last_ms DESC, id DESC LIMIT 1"
    const val Q_CONV_INSERT = "INSERT INTO conversations (title, started_ms, last_ms) VALUES (?, ?, ?)"
    const val Q_CONV_TOUCH = "UPDATE conversations SET last_ms = ? WHERE id = ?"
    const val Q_CONV_TITLE_IF_BLANK = "UPDATE conversations SET title = ? WHERE id = ? AND title = ''"
    const val Q_CONV_LIST =
        "SELECT c.id, c.title, c.started_ms, c.last_ms, (SELECT COUNT(*) FROM messages m WHERE m.conversation_id = c.id) FROM conversations c " +
            "WHERE EXISTS (SELECT 1 FROM messages m WHERE m.conversation_id = c.id) ORDER BY c.last_ms DESC"
    const val Q_CONV_DELETE = "DELETE FROM conversations WHERE id = ?"
    const val Q_CONV_CLEAR = "DELETE FROM conversations"
    /** Deletes conversations whose last message is older than the cut-off, but never the newest conversation (the one in use). Messages go with them (cascade). */
    const val Q_CONV_PRUNE = "DELETE FROM conversations WHERE last_ms < ? AND id <> COALESCE((SELECT id FROM conversations ORDER BY last_ms DESC, id DESC LIMIT 1), -1)"
    const val Q_MSG_INSERT = "INSERT INTO messages (conversation_id, role, text, at_ms, reply_json, origin) VALUES (?, ?, ?, ?, ?, ?)"
    private const val MSG_COLS = "id, conversation_id, role, text, at_ms, reply_json, origin"
    const val Q_MSG_PAGE_LATEST = "SELECT $MSG_COLS FROM messages WHERE conversation_id = ? ORDER BY id DESC LIMIT ?"
    const val Q_MSG_PAGE_BEFORE = "SELECT $MSG_COLS FROM messages WHERE conversation_id = ? AND id < ? ORDER BY id DESC LIMIT ?"
    const val Q_MSG_SEARCH =
        "SELECT $MSG_COLS FROM messages WHERE text LIKE ? ESCAPE '\\' ORDER BY id DESC LIMIT ?"
    const val Q_MSG_TAIL_ALL = "SELECT role, text FROM messages WHERE conversation_id = ? ORDER BY id DESC LIMIT ?"
    const val Q_MSG_ATTACH_REPLY =
        "UPDATE messages SET reply_json = ?, origin = ? WHERE id = (SELECT MAX(id) FROM messages WHERE role = 'assistant' AND reply_json IS NULL AND text = ?)"
    const val Q_MSG_EXPORT = "SELECT role, text, at_ms FROM messages WHERE conversation_id = ? ORDER BY id ASC"

    // ---- reminders
    const val REMINDER_COLS = "id, text, due, zone, floating, state, is_alarm, created_ms, fired_ms, note_id, history"
    const val Q_REM_INSERT =
        "INSERT OR REPLACE INTO reminders (id, text, due, zone, floating, state, is_alarm, created_ms, fired_ms, note_id, history) VALUES (?,?,?,?,?,?,?,?,?,?,?)"
    const val Q_REM_GET = "SELECT $REMINDER_COLS FROM reminders WHERE id = ?"
    const val Q_REM_ALL = "SELECT $REMINDER_COLS FROM reminders ORDER BY due ASC, created_ms ASC"
    const val Q_REM_UPDATE =
        "UPDATE reminders SET text=?, due=?, zone=?, floating=?, state=?, is_alarm=?, fired_ms=?, note_id=?, history=? WHERE id = ?"
    const val Q_REM_DELETE = "DELETE FROM reminders WHERE id = ?"

    // ---- notes
    const val NOTE_COLS = "id, text, created_ms, updated_ms, reminder_id, history"
    const val Q_NOTE_INSERT = "INSERT OR REPLACE INTO notes (id, text, created_ms, updated_ms, reminder_id, history) VALUES (?,?,?,?,?,?)"
    const val Q_NOTE_GET = "SELECT $NOTE_COLS FROM notes WHERE id = ?"
    const val Q_NOTE_ALL = "SELECT $NOTE_COLS FROM notes ORDER BY created_ms ASC"
    const val Q_NOTE_UPDATE = "UPDATE notes SET text=?, updated_ms=?, reminder_id=?, history=? WHERE id = ?"
    const val Q_NOTE_DELETE = "DELETE FROM notes WHERE id = ?"

    // ---- files index
    const val FILE_COLS = "id, name, category, uri, mime, size, created_ms, location, source"
    const val Q_FILE_INSERT = "INSERT OR REPLACE INTO files ($FILE_COLS) VALUES (?,?,?,?,?,?,?,?,?)"
    private const val FILE_ORDER = " ORDER BY created_ms DESC, id DESC"
    private const val FILE_MATCH = "(name LIKE ? ESCAPE '\\' OR source LIKE ? ESCAPE '\\')"
    const val Q_FILE_LIST_ALL = "SELECT $FILE_COLS FROM files$FILE_ORDER"
    const val Q_FILE_LIST_CAT = "SELECT $FILE_COLS FROM files WHERE category = ?$FILE_ORDER"
    const val Q_FILE_LIST_Q = "SELECT $FILE_COLS FROM files WHERE $FILE_MATCH$FILE_ORDER"
    const val Q_FILE_LIST_CAT_Q = "SELECT $FILE_COLS FROM files WHERE category = ? AND $FILE_MATCH$FILE_ORDER"
    const val Q_FILE_NAME_TAKEN = "SELECT 1 FROM files WHERE category = ? AND name = ? LIMIT 1"
    const val Q_FILE_DELETE = "DELETE FROM files WHERE id = ?"
    const val Q_FILE_OLDER_ALL = "SELECT $FILE_COLS FROM files WHERE created_ms < ? ORDER BY created_ms ASC"
    const val Q_FILE_OLDER_CAT = "SELECT $FILE_COLS FROM files WHERE created_ms < ? AND category = ? ORDER BY created_ms ASC"

    // ---- timers
    const val Q_TIMER_INSERT = "INSERT OR REPLACE INTO timers (id, label, seconds, ends_ms) VALUES (?,?,?,?)"
    const val Q_TIMER_ALL = "SELECT id, label, seconds, ends_ms FROM timers ORDER BY ends_ms ASC"
    const val Q_TIMER_DELETE = "DELETE FROM timers WHERE id = ?"
    const val Q_TIMER_DELETE_ALL = "DELETE FROM timers"

    /** Escapes `%`, `_` and `\` for a LIKE ... ESCAPE '\' pattern. */
    fun like(s: String): String = "%" + s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
}
