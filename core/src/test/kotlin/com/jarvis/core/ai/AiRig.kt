package com.jarvis.core.ai

import com.jarvis.core.json.Json
import com.jarvis.core.net.NetworkException
import com.jarvis.core.net.ScriptedHttp
import com.jarvis.core.net.ScriptedHttp.Companion.json
import com.jarvis.core.settings.Keys
import com.jarvis.core.testing.TestEnv
import kotlinx.coroutines.*

/** A gateway wired to a scripted network, with fast retry timings and every event / log line captured. */
class AiRig(val http: ScriptedHttp = ScriptedHttp()) {
    val env = TestEnv()
    val gw = DefaultLlmGateway(env.settings, http, env.clock, env.log, LlmTuning(modelCacheMs = 600_000, rateRetryDefaultMs = 0, rateRetryMaxMs = 50, ollamaProbeCacheMs = 15_000))
    val events = ArrayList<LlmEvent>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    init { scope.launch { gw.events.collect { events.add(it) } } }
    fun close() = scope.cancel()

    fun set(vararg kv: Pair<String, String>) = kv.forEach { env.settings.set(it.first, it.second) }
    fun groq(key: String = KEY_GROQ) = set(Keys.GROQ_API_KEY to key)
    fun cohere(key: String = KEY_COHERE) = set(Keys.COHERE_API_KEY to key)
    fun openai(key: String = KEY_OPENAI) = set(Keys.OPENAI_API_KEY to key)
    fun ollama(url: String = "http://10.0.2.2:11434") = set(Keys.OLLAMA_URL to url)

    private val ask = listOf(mapOf("role" to "user", "content" to "hello"))
    fun chat(role: LlmRole = LlmRole.CHAT, messages: List<Map<String, Any?>> = ask): String = runBlocking { gw.chat(messages, role) }
    fun tools(force: Boolean = false, role: LlmRole = LlmRole.AGENT, messages: List<Map<String, Any?>> = ask): AiMessage =
        runBlocking { gw.chatWithTools(messages, listOf(TOOL), role, force = force) }
    fun failure(block: () -> Unit): LlmException = try { block(); throw AssertionError("expected an LlmException") } catch (e: LlmException) { e }

    /** Everything the gateway logged plus every request URL/header/body, as one string (for the "no secrets leak" scans). */
    fun everything(): String = env.logLines.joinToString("\n")

    companion object {
        const val KEY_GROQ = "gsk_SECRETGROQ1234567890"
        const val KEY_COHERE = "co_SECRETCOHERE1234567890"
        const val KEY_OPENAI = "sk-SECRETOPENAI1234567890"
        const val GROQ = "https://api.groq.com/openai/v1"

        val TOOL: Map<String, Any?> = mapOf("type" to "function", "function" to mapOf("name" to "system", "description" to "d", "parameters" to mapOf("type" to "object", "properties" to emptyMap<String, Any?>())))

        fun groqModels(vararg ids: String) = json(200, Json.stringify(mapOf("data" to ids.map { mapOf("id" to it, "active" to true) })))
        fun oaiText(text: String) = json(200, Json.stringify(mapOf("choices" to listOf(mapOf("message" to mapOf("content" to text))))))
        fun oaiCall(name: String, args: Any?, content: String = "", id: String = "call_1") = json(200, Json.stringify(mapOf("choices" to listOf(mapOf(
            "message" to mapOf("content" to content, "tool_calls" to listOf(mapOf("id" to id, "type" to "function", "function" to mapOf("name" to name, "arguments" to args)))))))))
        fun err(status: Int, message: String, extra: Map<String, Any?> = emptyMap(), headers: Map<String, String> = emptyMap()) =
            json(status, Json.stringify(mapOf("error" to (mapOf("message" to message) + extra))), headers)
        fun cohereText(text: String) = json(200, Json.stringify(mapOf("message" to mapOf("content" to listOf(mapOf("type" to "text", "text" to text))))))
        fun cohereCall(name: String, args: String, plan: String = "") = json(200, Json.stringify(mapOf("message" to mapOf(
            "tool_plan" to plan, "tool_calls" to listOf(mapOf("id" to "c1", "type" to "function", "function" to mapOf("name" to name, "arguments" to args)))))))
        fun cohereModels(vararg names: String) = json(200, Json.stringify(mapOf("models" to names.map { mapOf("name" to it) })))
        fun ollamaTags(vararg names: String) = json(200, Json.stringify(mapOf("models" to names.map { mapOf("name" to it) })))
        fun ollamaText(text: String) = json(200, Json.stringify(mapOf("message" to mapOf("role" to "assistant", "content" to text))))
        fun timeout() = ScriptedHttp.fail(NetworkException.Kind.TIMEOUT)
    }
}
