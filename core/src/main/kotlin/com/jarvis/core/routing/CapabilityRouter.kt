package com.jarvis.core.routing

import com.jarvis.core.lock.LockState
import com.jarvis.core.model.Requirement
import com.jarvis.core.ports.PermState
import com.jarvis.core.ports.Services
import com.jarvis.core.tools.Capability
import com.jarvis.core.tools.ToolSpec
import com.jarvis.core.tools.impl.ToolRegistry

/** What JARVIS can do in one area right now, what is missing, and whether it works without a connection. */
data class CapabilityStatus(
    val capability: Capability,
    val available: Boolean,
    val offlineCapable: Boolean,
    /** Hard and soft requirements that are not granted (the permission screen shows these). */
    val missing: List<Requirement>,
    val note: String,
    val allowedWhenLocked: Boolean = true,
)

/**
 * Capability-aware routing: decides which tools are offered to the model, tells the model (and the user) what is not possible right now and why,
 * and classifies a request into the capabilities it needs.
 */
class CapabilityRouter(private val services: Services, private val registry: ToolRegistry) {

    private class Rule(val hard: List<Requirement>, val soft: List<Requirement>, val offline: Boolean, val ok: String, val limited: String)

    private val rules: Map<Capability, Rule> = mapOf(
        Capability.CONVERSATION to Rule(listOf(Requirement.API_KEY), emptyList(), false, "Chat and questions use your AI provider.", "Open-ended chat needs an AI provider (add a key in Settings, or run Ollama); simple commands still work offline."),
        Capability.INFORMATION to Rule(emptyList(), listOf(Requirement.NETWORK), true, "Web search, weather and calculator.", "Only the calculator works offline; web search and weather need a connection."),
        Capability.CONTENT_GENERATION to Rule(listOf(Requirement.API_KEY), emptyList(), false, "Writing text and saving it to Documents/JARVIS.", "Writing needs an AI provider."),
        Capability.IMAGE_GENERATION to Rule(listOf(Requirement.NETWORK), emptyList(), false, "Image generation.", "Image generation needs a connection."),
        Capability.REMINDERS to Rule(emptyList(), listOf(Requirement.NOTIFICATIONS, Requirement.EXACT_ALARM), true, "Reminders work offline.", "Reminders are saved, but without notification permission Android won't show them, and without exact alarms they can arrive a few minutes late."),
        Capability.NOTES to Rule(emptyList(), emptyList(), true, "Notes work offline.", ""),
        Capability.TIMERS_ALARMS to Rule(emptyList(), listOf(Requirement.NOTIFICATIONS), true, "Timers and alarms.", "Timers may not be visible without notification permission."),
        Capability.APP_LAUNCH to Rule(emptyList(), listOf(Requirement.OVERLAY), true, "Open apps, websites and settings pages.", "From the background Android may block app launches unless 'display over other apps' is allowed."),
        Capability.DEVICE_CONTROL to Rule(emptyList(), listOf(Requirement.WRITE_SETTINGS, Requirement.DND_ACCESS), true, "Volume, flashlight, battery, network, clipboard.", "Brightness needs 'modify system settings'; Do Not Disturb needs its own access."),
        Capability.MEDIA to Rule(emptyList(), listOf(Requirement.NOTIFICATION_LISTENER), true, "Play, pause, skip and 'what's playing'.", "Without notification access I can ask a music app to play something, but I can't see or control what is playing."),
        Capability.COMMUNICATION to Rule(emptyList(), listOf(Requirement.CONTACTS, Requirement.CALL_PHONE), true, "Calls and messages (the user presses send / call when Android requires it).", "Without contacts permission I can't look up names; without phone permission I open the dialer instead of calling directly."),
        Capability.NOTIFICATIONS to Rule(listOf(Requirement.NOTIFICATION_LISTENER), emptyList(), true, "Read notifications and reply through their reply button.", "Turn on notification access to read or reply to notifications."),
        Capability.SYSTEM_NAVIGATION to Rule(listOf(Requirement.ACCESSIBILITY), emptyList(), true, "Home, back, recents, notification shade, lock, screenshot.", "Turn on the JARVIS accessibility service for system navigation."),
        Capability.FILES to Rule(emptyList(), emptyList(), true, "Save and find documents in Documents/JARVIS.", ""),
        Capability.ASSISTANT_SETTINGS to Rule(emptyList(), emptyList(), true, "Change JARVIS's own settings.", ""),
    )

    private fun granted(r: Requirement) = services.permissions.state(r) == PermState.GRANTED

    fun status(lock: LockState): List<CapabilityStatus> = Capability.entries.map { cap ->
        val rule = rules.getValue(cap)
        val hardMissing = rule.hard.filter { !granted(it) }
        val softMissing = rule.soft.filter { !granted(it) }
        val tools = registry.specs().filter { it.capability == cap }
        val noLockTools = tools.isNotEmpty() && tools.all { it.lock == com.jarvis.core.tools.LockClass.ADMIN }
        CapabilityStatus(
            capability = cap,
            available = hardMissing.isEmpty(),
            offlineCapable = rule.offline,
            missing = hardMissing + softMissing,
            note = if (hardMissing.isEmpty() && softMissing.isEmpty()) rule.ok else rule.limited.ifBlank { rule.ok },
            allowedWhenLocked = !(lock.protectPersonalData && noLockTools),
        )
    }

    private val triggers: List<Pair<Capability, Regex>> = listOf(
        Capability.REMINDERS to Regex("\\b(?:remind|reminder|reminders|remember to)\\b", RegexOption.IGNORE_CASE),
        Capability.NOTES to Regex("\\b(?:notes?|jot|write down)\\b", RegexOption.IGNORE_CASE),
        Capability.TIMERS_ALARMS to Regex("\\b(?:timer|alarm|countdown|wake me)\\b", RegexOption.IGNORE_CASE),
        Capability.MEDIA to Regex("\\b(?:play|pause|resume|skip|next song|previous song|what'?s playing|which song|music)\\b", RegexOption.IGNORE_CASE),
        Capability.COMMUNICATION to Regex("\\b(?:call|dial|phone|whatsapp|telegram|sms|text|message)\\b", RegexOption.IGNORE_CASE),
        Capability.NOTIFICATIONS to Regex("\\b(?:notifications?|reply to)\\b", RegexOption.IGNORE_CASE),
        Capability.DEVICE_CONTROL to Regex("\\b(?:volume|brightness|mute|unmute|flashlight|torch|do not disturb|dnd|battery|wifi|wi-fi|clipboard)\\b", RegexOption.IGNORE_CASE),
        Capability.SYSTEM_NAVIGATION to Regex("\\b(?:go home|go back|recent apps|recents|lock (?:the |my )?(?:screen|phone)|screenshot|quick settings)\\b", RegexOption.IGNORE_CASE),
        Capability.APP_LAUNCH to Regex("\\b(?:open|launch|start)\\b", RegexOption.IGNORE_CASE),
        Capability.IMAGE_GENERATION to Regex("\\b(?:(?:generate|create|draw|make)\\s+(?:me\\s+)?(?:an?\\s+)?(?:image|picture|photo|drawing)|image of|picture of)\\b", RegexOption.IGNORE_CASE),
        Capability.INFORMATION to Regex("\\b(?:weather|forecast|temperature|news|score|price of|search|look up|calculate|what is \\d|how much is)\\b", RegexOption.IGNORE_CASE),
        Capability.CONTENT_GENERATION to Regex("\\b(?:write|draft|compose)\\b.*\\b(?:email|essay|letter|story|poem|plan|summary|code)\\b", RegexOption.IGNORE_CASE),
        Capability.FILES to Regex("\\b(?:save (?:it|this|that)|my files|documents folder|library)\\b", RegexOption.IGNORE_CASE),
        Capability.ASSISTANT_SETTINGS to Regex("\\b(?:jarvis settings|ai mode|mute your voice|stop listening|background listening|wake word)\\b", RegexOption.IGNORE_CASE),
    )

    /** Which capabilities a request touches. Empty list = ordinary conversation. */
    fun classify(text: String): List<Capability> {
        val t = text.trim()
        if (t.isEmpty()) return emptyList()
        val found = triggers.filter { it.second.containsMatchIn(t) }.map { it.first }
        return if (found.isEmpty()) listOf(Capability.CONVERSATION) else found
    }

    /** What stops the requested capabilities from working right now (hard requirements only). */
    fun blockers(text: String): List<Requirement> {
        return classify(text).flatMap { c -> rules.getValue(c).hard.filter { !granted(it) } }.distinct()
    }

    /** Tools offered to the model: offline, tools that need a connection are left out. Tools with missing PERMISSIONS stay visible so the call reports exactly what to grant. */
    fun toolsFor(lock: LockState, online: Boolean): List<ToolSpec> =
        registry.specs().filter { spec -> online || Requirement.NETWORK !in spec.needs }

    /** A short block for the model's prompt: what is possible right now and what is not (and why). */
    fun promptBlock(lock: LockState, online: Boolean): String {
        val st = status(lock)
        val limited = st.filter { !it.available || it.missing.isNotEmpty() }
        val sb = StringBuilder()
        if (!online) sb.append("The phone is OFFLINE: web search, weather and image generation are unavailable right now.\n")
        if (lock.protectPersonalData) sb.append("The phone is LOCKED: personal details and opening other apps need the user to unlock first (the tools enforce this; say so plainly).\n")
        if (limited.isNotEmpty()) {
            sb.append("Currently limited:\n")
            limited.forEach { s ->
                sb.append("- ${s.capability.name.lowercase().replace('_', ' ')}: ${if (s.available) "works, but" else "NOT available —"} ${s.note} (missing: ${s.missing.joinToString { it.name.lowercase() }})\n")
            }
        }
        return sb.toString().trim()
    }
}
