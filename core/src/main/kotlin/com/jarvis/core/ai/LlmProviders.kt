package com.jarvis.core.ai

import com.jarvis.core.json.Json
import com.jarvis.core.net.HttpClient
import com.jarvis.core.net.HttpRequest
import com.jarvis.core.net.HttpResponse
import com.jarvis.core.net.NetworkException
import com.jarvis.core.settings.Keys
import com.jarvis.core.settings.Settings
import com.jarvis.core.util.JarvisClock
import com.jarvis.core.util.JarvisLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Timing knobs (tests shrink them). */
data class LlmTuning(
    val modelCacheMs: Long = 600_000,
    val rateRetryDefaultMs: Long = 1_000,
    val rateRetryMaxMs: Long = 3_000,
    val ollamaProbeCacheMs: Long = 15_000,
)

internal class ChatResult<T>(val value: T, val model: String)

/** One AI service. Port of the `Provider` class family in Backend/LLM.py. */
internal abstract class LlmProvider(
    val name: String,
    protected val settings: Settings,
    protected val http: HttpClient,
    protected val clock: JarvisClock,
    protected val log: JarvisLog,
    protected val tuning: LlmTuning,
) {
    private var models: List<String>? = null
    private var modelsAtMs = 0L
    private val bad = HashMap<String, Long>()

    protected val label get() = LlmWire.nameOf(name)

    abstract fun key(): String
    open fun configured(): Boolean = key().isNotEmpty()
    /** May do a (cached) network probe. Used by the dispatcher; [configured] is the cheap synchronous check. */
    open suspend fun ready(): Boolean = configured()
    open fun rank(model: String, role: LlmRole): Int = 0
    open fun configuredModel(): String = ""
    protected abstract suspend fun listModels(): List<String>

    abstract suspend fun chat(messages: List<Map<String, Any?>>, temperature: Double, maxTokens: Int, timeoutMs: Int, role: LlmRole): ChatResult<String>
    open suspend fun chatTools(
        messages: List<Map<String, Any?>>, tools: List<Map<String, Any?>>, temperature: Double, maxTokens: Int, timeoutMs: Int, role: LlmRole, force: Boolean,
    ): ChatResult<AiMessage> = throw LlmException("$label can't call tools.", LlmErrorKind.MODEL, name)

    @Synchronized fun markBad(model: String, seconds: Int = 1800) { bad[model] = clock.nowMs() + seconds * 1000L }
    @Synchronized private fun isBad(model: String) = (bad[model] ?: 0L) >= clock.nowMs() && (bad[model] ?: 0L) != 0L
    @Synchronized fun reset() { models = null; bad.clear() }

    /** Usable chat models (cached 10 min). Empty when discovery is not possible. Auth/quota errors propagate. */
    suspend fun discover(force: Boolean = false): List<String> {
        val cached = synchronized(this) { models?.takeIf { !force && clock.nowMs() - modelsAtMs < tuning.modelCacheMs } }
        if (cached != null) return cached
        return try {
            val found = listModels().filter { !LlmWire.NON_CHAT.containsMatchIn(it) }
            synchronized(this) { models = found; modelsAtMs = clock.nowMs() }
            found
        } catch (e: LlmException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.i("LLM", "$name model discovery failed (${e.javaClass.simpleName})")
            synchronized(this) { models }.orEmpty()
        }
    }

    open suspend fun candidates(role: LlmRole): List<String> {
        val found = discover()
        val cfg = configuredModel()
        val ranked = found.sortedBy { rank(it, role) }
        val out = ArrayList<String>()
        val preferred = if (cfg.isNotEmpty() && role != LlmRole.DECISION) listOf(cfg) + ranked else ranked
        for (m in preferred) {
            if (m.isNotEmpty() && m !in out && !isBad(m) && (found.isEmpty() || m in found || m == cfg)) out.add(m)
        }
        val filtered = if (cfg.isNotEmpty() && found.isNotEmpty() && cfg !in found) out.filter { it != cfg } else out
        return filtered.take(4).ifEmpty { if (cfg.isNotEmpty()) listOf(cfg) else emptyList() }
    }

    // ------------------------------------------------------------------------------------ transport helpers
    protected fun netError(e: NetworkException): LlmException = when (e.kind) {
        NetworkException.Kind.TIMEOUT -> LlmException("$label took too long to answer. Try again in a moment.", LlmErrorKind.NETWORK, name, cause = e)
        else -> LlmException("I can't reach $label. Check your internet connection.", LlmErrorKind.NETWORK, name, cause = e)
    }

    /** POST JSON; maps transport failures; one polite retry on a plain rate limit (honouring Retry-After up to the cap). */
    protected suspend fun postJson(url: String, headers: Map<String, String>, body: Map<String, Any?>, timeoutMs: Int): HttpResponse {
        val payload = Json.stringify(body)
        val hdr = headers + mapOf("Content-Type" to "application/json", "Accept" to "application/json")
        var attempt = 0
        while (true) {
            val resp = try {
                http.execute(HttpRequest(url, "POST", hdr, payload, timeoutMs))
            } catch (e: NetworkException) {
                if (e.kind == NetworkException.Kind.CANCELLED) throw CancellationException("cancelled")
                throw netError(e)
            }
            if (resp.status == 429 && attempt == 0 && LlmWire.httpError(name, resp).kind == LlmErrorKind.RATE) {
                val wait = (resp.header("Retry-After")?.trim()?.toDoubleOrNull()?.times(1000)?.toLong()) ?: tuning.rateRetryDefaultMs
                if (wait <= tuning.rateRetryMaxMs) {
                    attempt++
                    delay(wait)
                    continue
                }
            }
            return resp
        }
    }

    protected suspend fun getJson(url: String, headers: Map<String, String>, timeoutMs: Int): HttpResponse = try {
        http.execute(HttpRequest(url, "GET", headers + mapOf("Accept" to "application/json"), null, timeoutMs))
    } catch (e: NetworkException) {
        throw netError(e)
    }

    protected fun parseBody(resp: HttpResponse): Map<String, Any?> =
        Json.parseObjectOrNull(resp.text) ?: throw LlmException("$label sent an answer I couldn't read.", LlmErrorKind.ERROR, name, resp.status)

    /** Shared by Groq and OpenAI-compatible services. [force] = the model MUST call a tool. */
    protected suspend fun openAiToolsRequest(
        base: String, headers: Map<String, String>, model: String, messages: List<Map<String, Any?>>, tools: List<Map<String, Any?>>,
        temperature: Double, maxTokens: Int, timeoutMs: Int, extra: Map<String, Any?> = emptyMap(), force: Boolean = false,
    ): AiMessage {
        val body = linkedMapOf<String, Any?>(
            "model" to model, "messages" to messages, "tools" to tools, "tool_choice" to (if (force) "required" else "auto"),
            "temperature" to temperature, "max_tokens" to maxTokens,
        )
        body.putAll(extra)
        var r = postJson("$base/chat/completions", headers, body, timeoutMs)
        if (r.status == 400 && "reasoning_effort" in body && "reasoning" in LlmWire.errorMessage(r).lowercase()) {
            body.remove("reasoning_effort")
            r = postJson("$base/chat/completions", headers, body, timeoutMs)
        }
        if (r.status == 200) {
            val msg = Json.obj(Json.path(parseBody(r), "choices", 0, "message")) ?: emptyMap()
            val calls = LlmWire.callsFromOpenAi(msg)
            val text = LlmWire.stripThink(Json.str(msg["content"]))
            if (calls.isNotEmpty() || text.isNotEmpty()) return AiMessage(text, calls)
            throw LlmException("$label returned an empty answer.", LlmErrorKind.ERROR, name)
        }
        if (r.status == 400) {
            val fg = Json.str(Json.obj(Json.obj(Json.parseObjectOrNull(r.text))?.get("error"))?.get("failed_generation")).orEmpty()
            val calls = LlmWire.recoverFailedGeneration(fg)
            if (calls.isNotEmpty()) {
                log.i("LLM", "$name: recovered ${calls.size} tool call(s) from a malformed generation")
                return AiMessage("", calls)
            }
            if ("tool" in LlmWire.errorMessage(r).lowercase()) {
                throw LlmException("$label could not use tools with $model.", LlmErrorKind.MODEL, name, 400)
            }
        }
        throw LlmWire.httpError(name, r, model)
    }
}

// =============================================================================================================== Groq
internal class GroqProvider(s: Settings, h: HttpClient, c: JarvisClock, l: JarvisLog, t: LlmTuning) : LlmProvider("groq", s, h, c, l, t) {
    private val base = "https://api.groq.com/openai/v1"
    private val pref: Map<LlmRole, List<Regex>> = run {
        fun rx(vararg p: String) = p.map { Regex(it) }
        val chat = rx("gpt-oss-120b", "llama-3\\.3-70b", "llama-4-maverick", "qwen", "kimi", "gpt-oss-20b", "llama-4-scout", "llama-3\\.1-8b")
        mapOf(
            LlmRole.CHAT to chat, LlmRole.CONTENT to chat, LlmRole.REALTIME to chat,
            LlmRole.DECISION to rx("llama-3\\.3-70b", "gpt-oss-20b", "llama-3\\.1-8b", "llama-4", "gpt-oss-120b", "qwen"),
            // tool calling: models that follow function schemas most reliably first
            LlmRole.AGENT to rx("llama-3\\.3-70b", "gpt-oss-120b", "llama-4-maverick", "qwen", "kimi", "gpt-oss-20b", "llama-4-scout", "llama-3\\.1-8b"),
        )
    }

    override fun key() = Settings.cleanSecret(settings.get(Keys.GROQ_API_KEY))
    override fun configuredModel() = Settings.cleanSecret(settings.get(Keys.MODEL_NAME))
    override fun rank(model: String, role: LlmRole): Int {
        val list = pref[role] ?: pref.getValue(LlmRole.CHAT)
        val i = list.indexOfFirst { it.containsMatchIn(model) }
        return if (i >= 0) i else 50
    }

    private fun auth() = mapOf("Authorization" to "Bearer ${key()}")

    override suspend fun listModels(): List<String> {
        val r = getJson("$base/models", auth(), 8_000)
        if (r.status != 200) throw LlmWire.httpError(name, r)
        return Json.list(Json.parseObjectOrNull(r.text)?.get("data")).orEmpty().mapNotNull { Json.obj(it) }
            .filter { Json.bool(it["active"]) != false }.mapNotNull { Json.str(it["id"]) }
    }

    private fun noModels() = LlmException(
        "Groq returned no usable models for this key. Enable models at console.groq.com > Settings > Limits.", LlmErrorKind.ACCESS, name, 404,
    )

    override suspend fun chat(messages: List<Map<String, Any?>>, temperature: Double, maxTokens: Int, timeoutMs: Int, role: LlmRole): ChatResult<String> {
        val cands = candidates(role)
        if (cands.isEmpty()) throw noModels()
        var last: LlmException? = null
        for (model in cands) {
            val body = linkedMapOf<String, Any?>("model" to model, "messages" to messages, "temperature" to temperature, "max_tokens" to maxTokens)
            if ("gpt-oss" in model) { // reasoning models spend tokens thinking: keep it short so the answer isn't cut off
                body["reasoning_effort"] = "low"
                body["max_tokens"] = maxOf(maxTokens, 600)
            }
            var r = postJson("$base/chat/completions", auth(), body, timeoutMs)
            if (r.status == 400 && "reasoning_effort" in body) { // model rejected the extra field: retry plain
                body.remove("reasoning_effort")
                r = postJson("$base/chat/completions", auth(), body, timeoutMs)
            }
            if (r.status == 200) {
                val text = LlmWire.stripThink(Json.str(Json.path(parseBody(r), "choices", 0, "message", "content")))
                if (text.isNotEmpty()) return ChatResult(text, model)
                last = LlmException("Groq returned an empty answer.", LlmErrorKind.ERROR, name)
                continue
            }
            val err = LlmWire.httpError(name, r, model)
            last = err
            if (err.kind == LlmErrorKind.MODEL) {
                markBad(model)
                log.w("LLM", "Groq model $model unavailable (404); trying the next one")
                continue
            }
            throw err
        }
        if (last?.kind == LlmErrorKind.MODEL) {
            throw LlmException(
                "Groq: none of the models are accessible with this key (404 on ${cands.joinToString(", ")}). The key's project has no model access - " +
                    "open console.groq.com > Settings > Limits and enable models, or create a new key in a project that has them.",
                LlmErrorKind.ACCESS, name, 404,
            )
        }
        throw last ?: LlmException("Groq did not answer.", LlmErrorKind.ERROR, name)
    }

    override suspend fun chatTools(
        messages: List<Map<String, Any?>>, tools: List<Map<String, Any?>>, temperature: Double, maxTokens: Int, timeoutMs: Int, role: LlmRole, force: Boolean,
    ): ChatResult<AiMessage> {
        val cands = candidates(role)
        if (cands.isEmpty()) throw noModels()
        var last: LlmException? = null
        for (model in cands) {
            val extra = if ("gpt-oss" in model) mapOf("reasoning_effort" to "low") else emptyMap()
            try {
                val msg = openAiToolsRequest(base, auth(), model, messages, tools, temperature, if (extra.isNotEmpty()) maxOf(maxTokens, 700) else maxTokens, timeoutMs, extra, force)
                return ChatResult(msg, model)
            } catch (e: LlmException) {
                last = e
                if (e.kind == LlmErrorKind.MODEL) {
                    markBad(model, if (e.status == 400) 600 else 1800)
                    log.w("LLM", "Groq model $model can't be used for tools (${e.userMessage}); trying the next one")
                    continue
                }
                throw e
            }
        }
        throw last ?: LlmException("Groq did not answer.", LlmErrorKind.ERROR, name)
    }
}

// ============================================================================================================ Cohere
internal class CohereProvider(s: Settings, h: HttpClient, c: JarvisClock, l: JarvisLog, t: LlmTuning) : LlmProvider("cohere", s, h, c, l, t) {
    private val base = "https://api.cohere.com"
    private val pref = listOf("command-a-\\d", "command-r-plus", "command-r-\\d", "command-r7b", "command").map { Regex(it) }

    override fun key() = Settings.cleanSecret(settings.get(Keys.COHERE_API_KEY))
    override fun configuredModel() = Settings.cleanSecret(settings.get(Keys.COHERE_MODEL))
    override fun rank(model: String, role: LlmRole): Int {
        val i = pref.indexOfFirst { it.containsMatchIn(model) }
        return if (i >= 0) i else 50
    }

    private fun hdr() = mapOf("Authorization" to "Bearer ${key()}")

    override suspend fun listModels(): List<String> {
        val r = getJson("$base/v1/models?endpoint=chat&page_size=100", hdr(), 8_000)
        if (r.status != 200) throw LlmWire.httpError(name, r)
        return Json.list(Json.parseObjectOrNull(r.text)?.get("models")).orEmpty().mapNotNull { Json.str(Json.obj(it)?.get("name")) }
    }

    override suspend fun candidates(role: LlmRole): List<String> =
        super.candidates(role).ifEmpty { listOf("command-a-03-2025", "command-r-plus-08-2024", "command-r-08-2024") }

    private fun textOf(msg: Map<String, Any?>): String =
        LlmWire.stripThink(Json.list(msg["content"]).orEmpty().mapNotNull { Json.obj(it) }.joinToString("") { Json.str(it["text"]).orEmpty() })

    override suspend fun chat(messages: List<Map<String, Any?>>, temperature: Double, maxTokens: Int, timeoutMs: Int, role: LlmRole): ChatResult<String> {
        var last: LlmException? = null
        for (model in candidates(role)) {
            val r = postJson("$base/v2/chat", hdr(), mapOf("model" to model, "messages" to messages, "temperature" to temperature, "max_tokens" to maxTokens), timeoutMs)
            if (r.status == 200) {
                val text = textOf(Json.obj(parseBody(r)["message"]).orEmpty())
                if (text.isNotEmpty()) return ChatResult(text, model)
                last = LlmException("Cohere returned an empty answer.", LlmErrorKind.ERROR, name)
                continue
            }
            val err = LlmWire.httpError(name, r, model)
            last = err
            if (err.kind == LlmErrorKind.MODEL) { markBad(model); continue }
            throw err
        }
        throw last ?: LlmException("Cohere did not answer.", LlmErrorKind.ERROR, name)
    }

    override suspend fun chatTools(
        messages: List<Map<String, Any?>>, tools: List<Map<String, Any?>>, temperature: Double, maxTokens: Int, timeoutMs: Int, role: LlmRole, force: Boolean,
    ): ChatResult<AiMessage> {
        var last: LlmException? = null
        for (model in candidates(role)) {
            val body = linkedMapOf<String, Any?>(
                "model" to model, "messages" to LlmWire.toCohere(messages), "tools" to tools, "temperature" to temperature, "max_tokens" to maxTokens,
            )
            if (force) body["tool_choice"] = "REQUIRED"
            val r = postJson("$base/v2/chat", hdr(), body, timeoutMs)
            if (r.status == 200) {
                val msg = Json.obj(parseBody(r)["message"]).orEmpty()
                val text = textOf(msg)
                val calls = LlmWire.callsFromOpenAi(msg)
                if (calls.isNotEmpty() || text.isNotEmpty()) return ChatResult(AiMessage(text, calls, Json.str(msg["tool_plan"]).orEmpty()), model)
                last = LlmException("Cohere returned an empty answer.", LlmErrorKind.ERROR, name)
                continue
            }
            val err = LlmWire.httpError(name, r, model)
            last = err
            if (err.kind == LlmErrorKind.MODEL) { markBad(model); continue }
            throw err
        }
        throw last ?: LlmException("Cohere did not answer.", LlmErrorKind.ERROR, name)
    }
}

// ============================================================================================================ Ollama
/** A self-hosted Ollama server. The URL has no default on a phone: it must be set (HTTPS, or loopback via `adb reverse`). */
internal class OllamaProvider(s: Settings, h: HttpClient, c: JarvisClock, l: JarvisLog, t: LlmTuning) : LlmProvider("ollama", s, h, c, l, t) {
    private val pref = listOf("qwen2\\.5", "llama3\\.[123]", "gemma", "mistral", "phi", "qwen", "llama").map { Regex(it) }
    private var up = false
    private var upAtMs = 0L

    private fun url() = settings.get(Keys.OLLAMA_URL).trim().trimEnd('/')
    override fun key() = url()
    override fun configured() = url().isNotEmpty() && (upAtMs == 0L || up || clock.nowMs() - upAtMs > tuning.ollamaProbeCacheMs)
    override fun configuredModel() = settings.get(Keys.OLLAMA_MODEL).trim()
    override fun rank(model: String, role: LlmRole): Int {
        val i = pref.indexOfFirst { it.containsMatchIn(model) }
        return if (i >= 0) i else 50
    }

    override suspend fun ready(): Boolean = url().isNotEmpty() && running()

    /** Is the server answering? Cached for 15 s unless [force]. */
    suspend fun running(force: Boolean = false): Boolean {
        if (url().isEmpty()) return false
        if (!force && upAtMs != 0L && clock.nowMs() - upAtMs < tuning.ollamaProbeCacheMs) return up
        up = try {
            http.execute(HttpRequest("${url()}/api/tags", "GET", emptyMap(), null as ByteArray?, 3_000)).status == 200
        } catch (e: NetworkException) { false }
        upAtMs = clock.nowMs()
        return up
    }

    override suspend fun listModels(): List<String> {
        val r = getJson("${url()}/api/tags", emptyMap(), 4_000)
        if (r.status != 200) return emptyList()
        return Json.list(Json.parseObjectOrNull(r.text)?.get("models")).orEmpty().mapNotNull { Json.str(Json.obj(it)?.get("name")) }
    }

    private suspend fun pickModel(role: LlmRole): String {
        if (!running(true)) throw LlmException("Ollama isn't reachable. Check the address in Settings > AI > Ollama URL and that the server is running.", LlmErrorKind.NETWORK, name)
        val installed = discover(true)
        if (installed.isEmpty()) throw LlmException("Ollama has no models installed yet. Pull one on the server (for example: ollama pull llama3.1).", LlmErrorKind.MODEL, name)
        val cfg = configuredModel()
        return if (cfg in installed) cfg else installed.sortedBy { rank(it, role) }.first()
    }

    private suspend fun post(body: Map<String, Any?>, timeoutMs: Int): HttpResponse = try {
        postJson("${url()}/api/chat", emptyMap(), body, maxOf(timeoutMs, 120_000))
    } catch (e: LlmException) {
        if (e.kind == LlmErrorKind.NETWORK) throw LlmException("Local Ollama stopped responding.", LlmErrorKind.NETWORK, name, cause = e) else throw e
    }

    override suspend fun chat(messages: List<Map<String, Any?>>, temperature: Double, maxTokens: Int, timeoutMs: Int, role: LlmRole): ChatResult<String> {
        val model = pickModel(role)
        val r = post(
            mapOf("model" to model, "messages" to messages, "stream" to false, "keep_alive" to "30m",
                "options" to mapOf("temperature" to temperature, "num_predict" to maxTokens)), timeoutMs,
        )
        if (r.status != 200) throw LlmWire.httpError(name, r, model)
        val text = LlmWire.stripThink(Json.str(Json.path(parseBody(r), "message", "content")))
        if (text.isEmpty()) throw LlmException("The local model returned an empty answer.", LlmErrorKind.ERROR, name)
        return ChatResult(text, model)
    }

    override suspend fun chatTools(
        messages: List<Map<String, Any?>>, tools: List<Map<String, Any?>>, temperature: Double, maxTokens: Int, timeoutMs: Int, role: LlmRole, force: Boolean,
    ): ChatResult<AiMessage> {
        val model = pickModel(role)
        val r = post(
            mapOf("model" to model, "messages" to LlmWire.toOllama(messages), "tools" to tools, "stream" to false, "keep_alive" to "30m",
                "options" to mapOf("temperature" to temperature, "num_predict" to maxTokens)), timeoutMs,
        )
        if (r.status != 200) {
            if ("tool" in LlmWire.errorMessage(r).lowercase()) {
                throw LlmException("The local model $model can't use tools. Pull a tool-capable one (qwen2.5, llama3.1 or llama3.2).", LlmErrorKind.MODEL, name, r.status)
            }
            throw LlmWire.httpError(name, r, model)
        }
        val msg = Json.obj(parseBody(r)["message"]).orEmpty()
        val calls = Json.list(msg["tool_calls"]).orEmpty().mapNotNull { Json.obj(it) }.mapNotNull { tc ->
            val fn = Json.obj(tc["function"]) ?: return@mapNotNull null
            val n = Json.str(fn["name"])?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ToolCall(n, LlmWire.parseArgs(fn["arguments"]), LlmWire.newCallId())
        }
        val text = LlmWire.stripThink(Json.str(msg["content"]))
        if (calls.isEmpty() && text.isEmpty()) throw LlmException("The local model returned an empty answer.", LlmErrorKind.ERROR, name)
        return ChatResult(AiMessage(text, calls), model)
    }
}

// ========================================================================================================== OpenAI
/** Any OpenAI-compatible endpoint (OpenAI, OpenRouter, LM Studio behind HTTPS ...). */
internal class OpenAiProvider(s: Settings, h: HttpClient, c: JarvisClock, l: JarvisLog, t: LlmTuning) : LlmProvider("openai", s, h, c, l, t) {
    override fun key() = Settings.cleanSecret(settings.get(Keys.OPENAI_API_KEY))
    override fun configuredModel() = settings.get(Keys.OPENAI_MODEL, "gpt-4o-mini").trim()
    private fun base() = settings.get(Keys.OPENAI_BASE_URL, "https://api.openai.com/v1").trim().trimEnd('/')
    private fun auth() = mapOf("Authorization" to "Bearer ${key()}")
    override suspend fun listModels(): List<String> = emptyList()

    override suspend fun chat(messages: List<Map<String, Any?>>, temperature: Double, maxTokens: Int, timeoutMs: Int, role: LlmRole): ChatResult<String> {
        val model = configuredModel()
        val r = postJson("${base()}/chat/completions", auth(),
            mapOf("model" to model, "messages" to messages, "temperature" to temperature, "max_tokens" to maxTokens), timeoutMs)
        if (r.status != 200) throw LlmWire.httpError(name, r, model)
        val text = LlmWire.stripThink(Json.str(Json.path(parseBody(r), "choices", 0, "message", "content")))
        if (text.isEmpty()) throw LlmException("OpenAI returned an empty answer.", LlmErrorKind.ERROR, name)
        return ChatResult(text, model)
    }

    override suspend fun chatTools(
        messages: List<Map<String, Any?>>, tools: List<Map<String, Any?>>, temperature: Double, maxTokens: Int, timeoutMs: Int, role: LlmRole, force: Boolean,
    ): ChatResult<AiMessage> {
        val model = configuredModel()
        return ChatResult(openAiToolsRequest(base(), auth(), model, messages, tools, temperature, maxTokens, timeoutMs, emptyMap(), force), model)
    }
}
