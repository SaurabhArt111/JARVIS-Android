package com.jarvis.core.ports

import com.jarvis.core.model.AssistantReply
import kotlinx.coroutines.flow.Flow

data class StoredMessage(
    val id: Long,
    val conversationId: Long,
    /** "user" or "assistant" */
    val role: String,
    val text: String,
    val atMs: Long,
    /** Present for assistant messages: kind/icon/card/actions are restored from the stored JSON so history looks like it did live. */
    val reply: AssistantReply? = null,
    val origin: String = "",
)

data class ConversationSummary(val id: Long, val title: String, val startedAtMs: Long, val lastAtMs: Long, val messageCount: Int)

/** Structured chat history for the UI (the LLM-facing view is [ConversationMemory]). Backed by SQLite in the app. */
interface ConversationRepository {
    suspend fun currentConversationId(): Long
    suspend fun newConversation(title: String = ""): Long
    suspend fun append(conversationId: Long, role: String, text: String, reply: AssistantReply? = null, origin: String = ""): StoredMessage
    /** Newest-last page: up to [limit] messages older than [beforeId] (null = the latest). */
    suspend fun messages(conversationId: Long, limit: Int = 100, beforeId: Long? = null): List<StoredMessage>
    suspend fun search(query: String, limit: Int = 50): List<StoredMessage>
    suspend fun conversations(): List<ConversationSummary>
    /** Saves the conversation as a text file in Documents/JARVIS/Conversations (or private storage) and indexes it in the library. */
    suspend fun export(conversationId: Long): StoredFile
    /** Permanent removal. Only reachable from an explicit, confirmed user action in the UI. */
    suspend fun deleteConversation(conversationId: Long): Boolean
    suspend fun clearAll()
    /** Emits whenever any conversation changes (new message, delete). */
    val changes: Flow<Unit>
}
