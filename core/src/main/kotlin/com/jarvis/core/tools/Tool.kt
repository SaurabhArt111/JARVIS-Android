package com.jarvis.core.tools

import com.jarvis.core.engine.Dialog
import com.jarvis.core.model.AssistantReply
import com.jarvis.core.model.Origin
import com.jarvis.core.model.Outcome
import com.jarvis.core.model.Requirement
import com.jarvis.core.ports.Services

/** The domain a request or tool belongs to. Used for capability-aware routing, lock-screen policy and the "what can I do now" screen. */
enum class Capability {
    CONVERSATION, INFORMATION, CONTENT_GENERATION, IMAGE_GENERATION, REMINDERS, NOTES, TIMERS_ALARMS,
    APP_LAUNCH, DEVICE_CONTROL, MEDIA, COMMUNICATION, NOTIFICATIONS, SYSTEM_NAVIGATION, FILES, ASSISTANT_SETTINGS
}

/**
 * How a tool may behave on a locked device (see [com.jarvis.core.lock.LockPolicy]).
 *  - OPEN: harmless, no personal data (time, timers, flashlight, volume, general questions)
 *  - PERSONAL: reveals or writes personal data (contacts, notifications, reminders text, notes)
 *  - CONSEQUENTIAL: has effects outside the device or costs money/privacy (calls, messages, sending replies)
 *  - ADMIN: changes JARVIS or system settings, opens arbitrary apps/files
 */
enum class LockClass { OPEN, PERSONAL, CONSEQUENTIAL, ADMIN }

data class ParamSpec(val description: String, val type: String = "string", val enum: List<String>? = null)

data class ToolSpec(
    val name: String,
    val description: String,
    val params: Map<String, ParamSpec>,
    val required: List<String> = emptyList(),
    val capability: Capability,
    val lock: LockClass,
    /** Does this tool change something in the world (true) or only read (false)? Action tools get the "must really run" honesty treatment. */
    val isAction: Boolean = true,
    /** Requirements that must be satisfied for the tool to be offered at all (e.g. API_KEY for images). Missing ones are reported, not hidden. */
    val needs: List<Requirement> = emptyList(),
) {
    /** OpenAI/Groq/Cohere/Ollama "function" schema. */
    fun toSchema(): Map<String, Any?> = mapOf(
        "type" to "function",
        "function" to mapOf(
            "name" to name,
            "description" to description,
            "parameters" to mapOf(
                "type" to "object",
                "properties" to params.mapValues { (_, p) ->
                    val m = linkedMapOf<String, Any?>("type" to p.type, "description" to p.description)
                    p.enum?.let { m["enum"] = it }
                    m
                },
                "required" to required,
            ),
        ),
    )
}

/** Arguments of a tool call. Tolerant of the many names a model may use for the same thing (mirrors Python's `_a`). */
class Args(val raw: Map<String, Any?>) {
    fun str(vararg names: String, default: String = ""): String {
        for (n in names) {
            val v = raw[n] ?: continue
            when (v) {
                is String -> if (v.isNotBlank()) return v.trim()
                is Number, is Boolean -> return v.toString()
                else -> {}
            }
        }
        return default
    }

    fun int(vararg names: String): Int? {
        for (n in names) {
            val v = raw[n] ?: continue
            when (v) {
                is Number -> return v.toInt()
                is String -> v.trim().toDoubleOrNull()?.let { return it.toInt() }
                else -> {}
            }
        }
        return null
    }

    fun bool(vararg names: String, default: Boolean = false): Boolean {
        for (n in names) {
            val v = raw[n] ?: continue
            when (v) {
                is Boolean -> return v
                is String -> return v.trim().lowercase() in setOf("true", "1", "yes", "on")
                is Number -> return v.toInt() != 0
                else -> {}
            }
        }
        return default
    }

    fun has(vararg names: String): Boolean = names.any { n -> raw[n].let { it != null && it.toString().isNotBlank() } }

    companion object {
        val EMPTY = Args(emptyMap())
    }
}

/** What a tool run may touch. One per request. */
class ToolContext(
    val services: Services,
    val origin: Origin,
    val lock: com.jarvis.core.lock.LockState,
    val dialog: Dialog,
    /** Reports something that finishes AFTER the reply (e.g. an image, a quick reply): shown, stored and optionally spoken. */
    val announce: suspend (AssistantReply) -> Unit = {},
) {
    val now get() = services.clock.now()
}

interface Tool {
    val spec: ToolSpec
    suspend fun execute(args: Args, ctx: ToolContext): Outcome
}
