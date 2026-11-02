package com.jarvis.core.ai

import com.jarvis.core.json.Json
import com.jarvis.core.net.HttpResponse
import java.util.UUID

/** Pure helpers for the provider wire formats (OpenAI-shaped, Cohere v2, Ollama). Ported from Backend/LLM.py. */
internal object LlmWire {
    val NAMES = mapOf("groq" to "Groq", "cohere" to "Cohere", "ollama" to "Ollama (local)", "openai" to "OpenAI")
    fun nameOf(provider: String) = NAMES[provider] ?: provider

    private val THINK = Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL)
    private val FAILED_GEN = Regex("<function=([\\w\\-.]+)[^{<]*(\\{.*?\\})\\s*\\)?\\s*(?:</function>|$)", RegexOption.DOT_MATCHES_ALL)
    val NON_CHAT = Regex(
        "whisper|tts|guard|embed|playai|orpheus|safeguard|compound|rerank|moderation|vision-preview|classify|summarize|arabic|translate|reasoning|vision",
        RegexOption.IGNORE_CASE,
    )

    fun stripThink(t: String?): String = THINK.replace(t ?: "", "").trim()

    fun newCallId(): String = "call_" + UUID.randomUUID().toString().replace("-", "").take(10)

    /** Tool arguments arrive as a JSON string, a map, or damaged text; always return a map. */
    @Suppress("UNCHECKED_CAST")
    fun parseArgs(raw: Any?): Map<String, Any?> {
        if (raw is Map<*, *>) return raw as Map<String, Any?>
        if (raw == null) return emptyMap()
        val s = raw.toString()
        if (s.isBlank()) return emptyMap()
        return Json.parseObjectLenient(s) ?: emptyMap()
    }

    fun callsFromOpenAi(msg: Map<String, Any?>): List<ToolCall> {
        val out = ArrayList<ToolCall>()
        for (tc in Json.list(msg["tool_calls"]).orEmpty()) {
            val t = Json.obj(tc) ?: continue
            val fn = Json.obj(t["function"]) ?: continue
            val name = Json.str(fn["name"])?.takeIf { it.isNotBlank() } ?: continue
            out.add(ToolCall(name, parseArgs(fn["arguments"]), Json.str(t["id"])?.takeIf { it.isNotBlank() } ?: newCallId()))
        }
        return out
    }

    /** Groq sometimes writes a call as `<function=name{...}</function>` text and rejects it (tool_use_failed). The call is fine: read it back. */
    fun recoverFailedGeneration(text: String?): List<ToolCall> =
        FAILED_GEN.findAll(text ?: "").map { ToolCall(it.groupValues[1], parseArgs(it.groupValues[2]), newCallId()) }.toList()

    fun toCohere(messages: List<Map<String, Any?>>): List<Map<String, Any?>> = messages.map { m ->
        val role = Json.str(m["role"])
        when {
            role == "assistant" && !Json.list(m["tool_calls"]).isNullOrEmpty() -> mapOf(
                "role" to "assistant",
                "tool_plan" to (Json.str(m["content"])?.takeIf { it.isNotBlank() } ?: "Calling the tools I need."),
                "tool_calls" to m["tool_calls"],
            )
            role == "tool" -> mapOf("role" to "tool", "tool_call_id" to m["tool_call_id"], "content" to m["content"])
            else -> mapOf("role" to role, "content" to (Json.str(m["content"]) ?: ""))
        }
    }

    fun toOllama(messages: List<Map<String, Any?>>): List<Map<String, Any?>> = messages.map { m ->
        val role = Json.str(m["role"])
        when {
            role == "assistant" && !Json.list(m["tool_calls"]).isNullOrEmpty() -> mapOf(
                "role" to "assistant",
                "content" to (Json.str(m["content"]) ?: ""),
                "tool_calls" to Json.list(m["tool_calls"])!!.map { c ->
                    val fn = Json.obj(Json.obj(c)?.get("function")).orEmpty()
                    mapOf("function" to mapOf("name" to fn["name"], "arguments" to parseArgs(fn["arguments"])))
                },
            )
            role == "tool" -> mapOf("role" to "tool", "content" to m["content"], "tool_name" to (Json.str(m["name"]) ?: ""))
            else -> mapOf("role" to role, "content" to (Json.str(m["content"]) ?: ""))
        }
    }

    // ---------------------------------------------------------------------------------------------- errors
    /** The provider's own error text (never the request). */
    fun errorMessage(resp: HttpResponse): String {
        val j = Json.parseObjectOrNull(resp.text)
        if (j != null) {
            val e = j["error"] ?: j
            val msg = if (e is Map<*, *>) Json.str(e["message"]) else e.toString()
            if (!msg.isNullOrBlank()) return msg
        }
        return resp.text.take(200)
    }

    fun httpError(provider: String, resp: HttpResponse, model: String = ""): LlmException {
        val s = resp.status
        val msg = errorMessage(resp)
        val low = msg.lowercase()
        val nm = nameOf(provider)
        return when {
            s == 401 || s == 403 -> LlmException("$nm rejected the API key ($s). Re-copy the key in Settings > AI.", LlmErrorKind.AUTH, provider, s)
            s == 404 -> LlmException("$nm says model '$model' is not available to this key (404).", LlmErrorKind.MODEL, provider, s)
            s == 429 -> {
                val quota = listOf("month", "quota", "trial", "billing", "daily").any { it in low }
                LlmException("$nm is ${if (quota) "out of quota" else "rate limiting me"} (429).", if (quota) LlmErrorKind.QUOTA else LlmErrorKind.RATE, provider, s)
            }
            s >= 500 -> LlmException("$nm is having server trouble ($s).", LlmErrorKind.NETWORK, provider, s)
            else -> LlmException("$nm error $s: ${msg.take(120)}", LlmErrorKind.ERROR, provider, s)
        }
    }
}
