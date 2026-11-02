package com.jarvis.core.testing

import com.jarvis.core.ai.AiMessage
import com.jarvis.core.ai.LlmCallMeta
import com.jarvis.core.ai.LlmEvent
import com.jarvis.core.ai.LlmException
import com.jarvis.core.ai.LlmGateway
import com.jarvis.core.ai.LlmRole
import com.jarvis.core.ai.ToolCall
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** A scripted AI: queue up what it should answer, then inspect what it was asked. */
class FakeLlm(var isConfigured: Boolean = true) : LlmGateway {
    private val script = ArrayDeque<Any>()
    private val chatScript = ArrayDeque<Any>()
    /** Every message list the agent sent, in order. */
    val requests = ArrayList<List<Map<String, Any?>>>()
    val forceFlags = ArrayList<Boolean>()
    val toolNamesOffered = ArrayList<List<String>>()
    val chatRequests = ArrayList<List<Map<String, Any?>>>()
    private val bus = MutableSharedFlow<LlmEvent>(extraBufferCapacity = 8)
    private var ids = 0

    fun say(text: String): FakeLlm { script.add(AiMessage(content = text)); return this }
    fun call(name: String, args: Map<String, Any?> = emptyMap(), content: String = ""): FakeLlm {
        script.add(AiMessage(content, listOf(ToolCall(name, args, "call_${++ids}")))); return this
    }
    fun calls(vararg calls: Pair<String, Map<String, Any?>>, content: String = ""): FakeLlm {
        script.add(AiMessage(content, calls.map { ToolCall(it.first, it.second, "call_${++ids}") })); return this
    }
    fun fail(e: LlmException): FakeLlm { script.add(e); return this }
    fun chatSay(text: String): FakeLlm { chatScript.add(text); return this }
    fun chatFail(e: LlmException): FakeLlm { chatScript.add(e); return this }
    val remaining get() = script.size
    fun emit(e: LlmEvent) = bus.tryEmit(e)

    override fun configured() = isConfigured

    override suspend fun chat(messages: List<Map<String, Any?>>, role: LlmRole, temperature: Double, maxTokens: Int, timeoutMs: Int): String {
        chatRequests.add(messages.toList())
        val n = chatScript.removeFirstOrNull() ?: throw AssertionError("chat() called more often than scripted")
        if (n is Throwable) throw n
        return n as String
    }

    override suspend fun chatWithTools(
        messages: List<Map<String, Any?>>, tools: List<Map<String, Any?>>, role: LlmRole, temperature: Double, maxTokens: Int, timeoutMs: Int, force: Boolean,
    ): AiMessage {
        requests.add(messages.toList())
        forceFlags.add(force)
        toolNamesOffered.add(tools.mapNotNull { ((it["function"] as? Map<*, *>)?.get("name")) as? String })
        val n = script.removeFirstOrNull() ?: throw AssertionError("chatWithTools() called more often than scripted")
        if (n is Throwable) throw n
        return n as AiMessage
    }

    override fun lastCall(): LlmCallMeta? = null
    override val events: SharedFlow<LlmEvent> get() = bus
}
