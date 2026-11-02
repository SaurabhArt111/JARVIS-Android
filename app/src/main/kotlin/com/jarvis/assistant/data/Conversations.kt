package com.jarvis.assistant.data

import com.jarvis.core.engine.AssistantEngine
import com.jarvis.core.engine.EngineEvent
import com.jarvis.core.model.AssistantReply
import com.jarvis.core.model.ReplyJson
import com.jarvis.core.ports.*
import com.jarvis.core.util.JarvisClock
import com.jarvis.core.util.JarvisLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Chat history for the UI, in SQLite. The LLM-facing view of the same rows is [SqlConversationMemory]. */
class SqlConversationRepository(
    private val db: Db,
    private val clock: JarvisClock,
    /** Lazily resolved because the document store itself needs the database. */
    private val documents: () -> DocumentStore,
) : ConversationRepository {

    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val changes: Flow<Unit> = _changes.asSharedFlow()

    override suspend fun currentConversationId(): Long = db.run { d ->
        d.rawQuery(Sql.Q_CONV_LATEST, null).use { if (it.moveToFirst()) it.getLong(0) else null } ?: insertConversation(d, "")
    }

    private fun insertConversation(d: android.database.sqlite.SQLiteDatabase, title: String): Long {
        val now = clock.nowMs()
        d.compileStatement(Sql.Q_CONV_INSERT).use { s -> s.bindString(1, title); s.bindLong(2, now); s.bindLong(3, now); return s.executeInsert() }
    }

    override suspend fun newConversation(title: String): Long = db.run { insertConversation(it, title) }.also { _changes.tryEmit(Unit) }

    override suspend fun append(conversationId: Long, role: String, text: String, reply: AssistantReply?, origin: String): StoredMessage {
        val now = clock.nowMs()
        val id = db.transaction { d ->
            val mid = d.compileStatement(Sql.Q_MSG_INSERT).use { s ->
                s.bindLong(1, conversationId); s.bindString(2, role); s.bindString(3, text); s.bindLong(4, now)
                if (reply != null) s.bindString(5, ReplyJson.toJson(reply)) else s.bindNull(5)
                s.bindString(6, origin); s.executeInsert()
            }
            d.execSQL(Sql.Q_CONV_TOUCH, arrayOf(now, conversationId))
            if (role == "user") d.execSQL(Sql.Q_CONV_TITLE_IF_BLANK, arrayOf(titleOf(text), conversationId))
            mid
        }
        _changes.tryEmit(Unit)
        return StoredMessage(id, conversationId, role, text, now, reply, origin)
    }

    private fun titleOf(text: String) = text.trim().replace(Regex("\\s+"), " ").take(48).ifEmpty { "Conversation" }

    private fun messageOf(c: android.database.Cursor) = StoredMessage(
        c.getLong(0), c.getLong(1), c.str(2), c.str(3), c.getLong(4), c.strOrNull(5)?.let(ReplyJson::fromJson), c.str(6),
    )

    override suspend fun messages(conversationId: Long, limit: Int, beforeId: Long?): List<StoredMessage> = db.run { d ->
        val lim = limit.coerceIn(1, 500).toString()
        val (sql, args) = if (beforeId == null) Sql.Q_MSG_PAGE_LATEST to arrayOf(conversationId.toString(), lim)
        else Sql.Q_MSG_PAGE_BEFORE to arrayOf(conversationId.toString(), beforeId.toString(), lim)
        d.rawQuery(sql, args).use { c -> buildList { while (c.moveToNext()) add(messageOf(c)) }.reversed() }
    }

    override suspend fun search(query: String, limit: Int): List<StoredMessage> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return db.run { d -> d.rawQuery(Sql.Q_MSG_SEARCH, arrayOf(Sql.like(q), limit.coerceIn(1, 200).toString())).use { c -> buildList { while (c.moveToNext()) add(messageOf(c)) } } }
    }

    override suspend fun conversations(): List<ConversationSummary> = db.run { d ->
        d.rawQuery(Sql.Q_CONV_LIST, null).use { c -> buildList { while (c.moveToNext()) add(ConversationSummary(c.getLong(0), c.str(1), c.getLong(2), c.getLong(3), c.getInt(4))) } }
    }

    override suspend fun export(conversationId: Long): StoredFile {
        val zone = clock.zone()
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ENGLISH)
        val title = conversations().firstOrNull { it.id == conversationId }?.title?.ifBlank { null } ?: "Conversation"
        val lines = db.run { d ->
            d.rawQuery(Sql.Q_MSG_EXPORT, arrayOf(conversationId.toString())).use { c ->
                buildList { while (c.moveToNext()) add("[${Instant.ofEpochMilli(c.getLong(2)).atZone(zone).format(fmt)}] ${if (c.str(0) == "user") "You" else "JARVIS"}: ${c.str(1)}") }
            }
        }
        return documents().saveText(ContentCategory.CONVERSATIONS, title, "$title\n\n" + lines.joinToString("\n\n") + "\n", "txt", "conversation $conversationId")
    }

    override suspend fun deleteConversation(conversationId: Long): Boolean {
        val n = db.run { d -> d.compileStatement(Sql.Q_CONV_DELETE).use { s -> s.bindLong(1, conversationId); s.executeUpdateDelete() } }
        if (n > 0) _changes.tryEmit(Unit)
        return n > 0
    }

    /** Deletes conversations not touched since [cutoffMs] (never the current one). Returns how many were removed. Only runs when the user chose a retention period. */
    suspend fun pruneOlderThan(cutoffMs: Long): Int {
        val n = db.run { d -> d.compileStatement(Sql.Q_CONV_PRUNE).use { s -> s.bindLong(1, cutoffMs); s.executeUpdateDelete() } }
        if (n > 0) _changes.tryEmit(Unit)
        return n
    }

    override suspend fun clearAll() {
        db.run { it.execSQL(Sql.Q_CONV_CLEAR) }
        _changes.tryEmit(Unit)
    }

    /** Attaches the structured reply (card, buttons, icon) to the assistant message the engine just stored. Skipped when the text differs (a redacted lock-screen reply). */
    suspend fun attachReply(reply: AssistantReply, origin: String) {
        val n = db.run { d -> d.compileStatement(Sql.Q_MSG_ATTACH_REPLY).use { s -> s.bindString(1, ReplyJson.toJson(reply)); s.bindString(2, origin); s.bindString(3, reply.text); s.executeUpdateDelete() } }
        if (n > 0) _changes.tryEmit(Unit)
    }

}

/** The conversation the model sees: user / assistant turns of the current conversation, neighbours of the same side merged (some providers insist on alternation). */
class SqlConversationMemory(private val db: Db, private val repo: SqlConversationRepository) : ConversationMemory {
    override suspend fun recent(n: Int): List<ChatTurn> {
        val cid = repo.currentConversationId()
        val rows = db.run { d ->
            d.rawQuery(Sql.Q_MSG_TAIL_ALL, arrayOf(cid.toString(), (n * 2).coerceIn(1, 400).toString())).use { c -> buildList { while (c.moveToNext()) add(ChatTurn(c.str(0), c.str(1))) } }
        }.reversed()
        val out = ArrayList<ChatTurn>()
        for (t in rows) {
            val text = t.content.trim()
            if (text.isEmpty()) continue
            if (out.isNotEmpty() && out.last().role == t.role) out[out.lastIndex] = ChatTurn(t.role, out.last().content + "\n" + text) else out.add(ChatTurn(t.role, text))
        }
        return out.takeLast(n)
    }

    override suspend fun remember(role: String, content: String) {
        if (role != "user" && role != "assistant") return
        if (content.isBlank()) return
        repo.append(repo.currentConversationId(), role, content.trim(), null, "")
    }
}

/**
 * Listens to the engine and adds the structured reply and its origin to the history rows. The text itself is stored by [SqlConversationMemory], which the
 * engine calls, so nothing is written twice.
 */
class ChatRecorder(private val engine: AssistantEngine, private val repo: SqlConversationRepository, private val scope: CoroutineScope, private val log: JarvisLog) {
    fun start() {
        scope.launch {
            engine.events.collect { ev ->
                if (ev is EngineEvent.AssistantSaid) {
                    try { repo.attachReply(ev.reply, ev.origin.name.lowercase()) } catch (e: Exception) { log.w("Chat", "could not attach a reply (${e.javaClass.simpleName})") }
                }
            }
        }
    }
}
