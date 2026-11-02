package com.jarvis.core.ai

import com.jarvis.core.net.HttpClient
import com.jarvis.core.settings.Keys
import com.jarvis.core.settings.Settings
import com.jarvis.core.util.JarvisClock
import com.jarvis.core.util.JarvisLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableSharedFlow as MSF

/** Result of testing one provider from Settings > AI. */
data class ProviderDiagnosis(
    val provider: String,
    val label: String,
    val configured: Boolean,
    val ok: Boolean,
    val detail: String,
    val models: List<String>,
    val millis: Long,
)

/**
 * Provider order, fallback, cooldowns and model discovery (port of the module-level functions in Backend/LLM.py).
 * Secrets are read from [Settings] on every call and are never logged. Cancellation propagates untouched.
 */
class DefaultLlmGateway(
    private val settings: Settings,
    http: HttpClient,
    private val clock: JarvisClock,
    private val log: JarvisLog = JarvisLog.None,
    tuning: LlmTuning = LlmTuning(),
) : LlmGateway {

    private val providers: Map<String, LlmProvider> = listOf(
        GroqProvider(settings, http, clock, log, tuning),
        CohereProvider(settings, http, clock, log, tuning),
        OllamaProvider(settings, http, clock, log, tuning),
        OpenAiProvider(settings, http, clock, log, tuning),
    ).associateBy { it.name }

    val providerHealth = ProviderHealth(clock)
    private val _events = MSF<LlmEvent>(extraBufferCapacity = 32, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    override val events: SharedFlow<LlmEvent> = _events.asSharedFlow()

    @Volatile private var last: LlmCallMeta? = null
    override fun lastCall(): LlmCallMeta? = last

    // ------------------------------------------------------------------------------------------ routing
    fun mode(): String {
        val m = settings.get(Keys.AI_MODE).lowercase()
        return if (m in setOf("auto", "cloud", "local")) m else "auto"
    }

    /** Provider ids to try, in order, for [role]. */
    fun order(role: LlmRole = LlmRole.CHAT): List<String> {
        var base = if (role == LlmRole.DECISION) listOf("cohere", "groq", "openai", "ollama") else listOf("groq", "cohere", "openai", "ollama")
        val pref = settings.get(if (role == LlmRole.DECISION) Keys.DECISION_PROVIDER else Keys.CHAT_PROVIDER, "auto").lowercase()
        if (pref in providers) base = listOf(pref) + base.filter { it != pref }
        return when (mode()) {
            "local" -> listOf("ollama")
            "cloud" -> base.filter { it != "ollama" }
            else -> base
        }
    }

    override fun configured(): Boolean = order(LlmRole.CHAT).any { providers.getValue(it).configured() }

    fun health(): List<ProviderHealthSnapshot> = providerHealth.snapshot()

    /** Call after a key, address or model setting changed: forgets cached model lists and failure cool-downs, so the new value is tried at once instead of after the old wait. */
    fun resetAfterSettingsChange() {
        providerHealth.resetCooldown()
        providers.values.forEach { it.reset() }
    }

    // ------------------------------------------------------------------------------------------ calls
    override suspend fun chat(messages: List<Map<String, Any?>>, role: LlmRole, temperature: Double, maxTokens: Int, timeoutMs: Int): String =
        dispatch(role) { it.chat(messages, temperature, maxTokens, timeoutMs, role) }

    override suspend fun chatWithTools(
        messages: List<Map<String, Any?>>, tools: List<Map<String, Any?>>, role: LlmRole, temperature: Double, maxTokens: Int, timeoutMs: Int, force: Boolean,
    ): AiMessage = dispatch(role) { it.chatTools(messages, tools, temperature, maxTokens, timeoutMs, role, force) }

    private suspend fun <T> dispatch(role: LlmRole, call: suspend (LlmProvider) -> ChatResult<T>): T {
        val tAll = clock.nowMs()
        val tried = ArrayList<String>()
        val failures = ArrayList<Pair<String, String>>() // provider to message
        val order = order(role)
        for (ignoreCooldown in listOf(false, true)) {
            for (name in order) {
                val p = providers.getValue(name)
                if (!p.ready()) continue
                if (providerHealth.cooling(name) && !ignoreCooldown) {
                    failures.add(name to "cooling down (${providerHealth.cooldownLeft(name)}s)")
                    continue
                }
                if (name in tried) continue
                tried.add(name)
                val t0 = clock.nowMs()
                try {
                    val res = call(p)
                    val ms = (clock.nowMs() - t0).toDouble()
                    providerHealth.ok(name, ms)
                    val fallback = failures.isNotEmpty() && name != order.first()
                    if (fallback) {
                        providerHealth.fellBack(name)
                        val real = failures.filter { "cooling" !in it.second }
                        _events.tryEmit(LlmEvent.Fallback(name, real.map { it.first }, real.firstOrNull()?.second.orEmpty()))
                    }
                    last = LlmCallMeta(name, res.model, fallback, ms.toLong())
                    return res.value
                } catch (e: CancellationException) {
                    throw e
                } catch (e: LlmException) {
                    val n = providerHealth.consecutive(name) + 1
                    val cd = if (e.kind in COOLDOWN_ALWAYS || n >= 2) COOLDOWN_SECONDS[e.kind] ?: 0 else 0
                    providerHealth.fail(name, e.userMessage, e.kind, cd)
                    failures.add(name to e.userMessage)
                    log.w("LLM", "AI $name failed (${e.kind}): ${e.userMessage}")
                    _events.tryEmit(LlmEvent.Failure(name, e.kind, e.userMessage))
                } catch (e: Exception) { // never leak raw exceptions
                    providerHealth.fail(name, e.javaClass.simpleName)
                    failures.add(name to "unexpected error: ${e.javaClass.simpleName}")
                    log.e("LLM", "AI $name crashed", e)
                }
            }
            if (tried.isNotEmpty()) break
        }
        last = null
        if (failures.isNotEmpty()) {
            val real = failures.filter { "cooling" !in it.second }.ifEmpty { failures }
            val kinds = real.mapNotNull { providerHealth.lastKind(it.first) }.toSet()
            val kind = when {
                kinds == setOf(LlmErrorKind.NETWORK) -> LlmErrorKind.NETWORK
                LlmErrorKind.AUTH in kinds -> LlmErrorKind.AUTH
                else -> LlmErrorKind.ERROR
            }
            val msg = real.take(3).joinToString(" ") { it.second } + (if (real.size < 2) " No other AI is available." else "")
            throw LlmException(msg, kind, real.first().first)
        }
        throw LlmException(
            if (mode() == "local") "Local AI mode is on, but no Ollama server is set up or reachable. Open Settings > AI."
            else "No AI is connected yet. Add a free Groq or Cohere key in Settings > AI.",
            LlmErrorKind.NO_KEY,
        ).also { log.i("LLM", "no provider configured after ${clock.nowMs() - tAll} ms") }
    }

    // ------------------------------------------------------------------------------------------ diagnostics
    /** Tests every configured provider (or just [only]) and reports what happened, for Settings > AI. */
    suspend fun diagnose(only: String? = null): List<ProviderDiagnosis> {
        val out = ArrayList<ProviderDiagnosis>()
        for ((name, p) in providers) {
            if (only != null && name != only) continue
            val label = LlmWire.nameOf(name)
            if (!p.configured()) {
                out.add(ProviderDiagnosis(name, label, false, false, if (name == "ollama") "No Ollama address set." else "No API key set.", emptyList(), 0))
                continue
            }
            p.reset()
            val t0 = clock.nowMs()
            val models = try {
                p.discover(true)
            } catch (e: LlmException) {
                providerHealth.fail(name, e.userMessage, e.kind, COOLDOWN_SECONDS[e.kind] ?: 0)
                out.add(ProviderDiagnosis(name, label, true, false, e.userMessage, emptyList(), clock.nowMs() - t0))
                continue
            }
            try {
                val r = p.chat(listOf(mapOf("role" to "user", "content" to "Reply with the single word: ready")), 0.0, 8, 20_000, LlmRole.CHAT)
                providerHealth.ok(name, (clock.nowMs() - t0).toDouble())
                providerHealth.resetCooldown(name)
                out.add(ProviderDiagnosis(name, label, true, true, "OK - answered with ${r.model.ifEmpty { "default model" }}", models, clock.nowMs() - t0))
            } catch (e: CancellationException) {
                throw e
            } catch (e: LlmException) {
                providerHealth.fail(name, e.userMessage, e.kind, COOLDOWN_SECONDS[e.kind] ?: 0)
                out.add(ProviderDiagnosis(name, label, true, false, e.userMessage, models, clock.nowMs() - t0))
            }
        }
        return out
    }

    /** Models installed on the configured Ollama server (empty if unreachable). */
    suspend fun ollamaModels(): List<String> {
        val p = providers.getValue("ollama") as OllamaProvider
        return if (p.running(true)) p.discover(true) else emptyList()
    }

    private companion object {
        val COOLDOWN_SECONDS = mapOf(
            LlmErrorKind.AUTH to 600, LlmErrorKind.ACCESS to 600, LlmErrorKind.QUOTA to 1800,
            LlmErrorKind.RATE to 45, LlmErrorKind.NETWORK to 20, LlmErrorKind.MODEL to 120,
        )
        /** Kinds that start a cooldown on the first failure (others only after two in a row). */
        val COOLDOWN_ALWAYS = setOf(LlmErrorKind.AUTH, LlmErrorKind.ACCESS, LlmErrorKind.QUOTA, LlmErrorKind.RATE)
    }
}
