package com.jarvis.core.ai

import com.jarvis.core.json.Json
import kotlinx.coroutines.flow.SharedFlow

/** Which job an AI call is for. Providers rank their models per role (see Backend/LLM.py). */
enum class LlmRole { CHAT, DECISION, AGENT, CONTENT, REALTIME }

enum class LlmErrorKind { NETWORK, AUTH, NO_KEY, ACCESS, MODEL, RATE, QUOTA, ERROR }

/** Every failure of the AI layer. [userMessage] is safe to show (never contains keys or request bodies). */
class LlmException(
    val userMessage: String,
    val kind: LlmErrorKind = LlmErrorKind.ERROR,
    val provider: String = "",
    val status: Int = 0,
    cause: Throwable? = null,
) : Exception(userMessage, cause)

data class ToolCall(val name: String, val args: Map<String, Any?>, val id: String)

/** One assistant turn: text, tool calls, or both. */
data class AiMessage(val content: String = "", val toolCalls: List<ToolCall> = emptyList(), val plan: String = "") {
    /** The OpenAI-shaped assistant message to append to the conversation before the tool results. */
    fun canonical(): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>("role" to "assistant", "content" to content)
        if (toolCalls.isNotEmpty()) {
            m["tool_calls"] = toolCalls.map {
                mapOf("id" to it.id, "type" to "function", "function" to mapOf("name" to it.name, "arguments" to Json.stringify(it.args)))
            }
        }
        return m
    }
}

data class LlmCallMeta(val provider: String, val model: String, val fallback: Boolean, val millis: Long)

sealed class LlmEvent {
    data class Fallback(val to: String, val failed: List<String>, val why: String) : LlmEvent()
    data class Failure(val provider: String, val kind: LlmErrorKind, val message: String) : LlmEvent()
}

/**
 * The AI gateway used by the agent loop and by plain chat: provider order, fallback, cooldowns, model discovery.
 * Messages are OpenAI-shaped maps (`role`, `content`, `tool_calls`, `tool_call_id`, `name`).
 * Implementations MUST honour coroutine cancellation (cancelling aborts the HTTP request).
 */
interface LlmGateway {
    /** True when at least one provider in the current mode has what it needs (key / reachable local server). */
    fun configured(): Boolean

    @Throws(LlmException::class)
    suspend fun chat(
        messages: List<Map<String, Any?>>,
        role: LlmRole = LlmRole.CHAT,
        temperature: Double = 0.7,
        maxTokens: Int = 1024,
        timeoutMs: Int = 25_000,
    ): String

    @Throws(LlmException::class)
    suspend fun chatWithTools(
        messages: List<Map<String, Any?>>,
        tools: List<Map<String, Any?>>,
        role: LlmRole = LlmRole.AGENT,
        temperature: Double = 0.3,
        maxTokens: Int = 900,
        timeoutMs: Int = 30_000,
        /** Require the model to call a tool (used when a request obviously needs one and the model only talked). */
        force: Boolean = false,
    ): AiMessage

    /** Provider / model / fallback info of the most recent successful call. */
    fun lastCall(): LlmCallMeta?

    val events: SharedFlow<LlmEvent>
}
