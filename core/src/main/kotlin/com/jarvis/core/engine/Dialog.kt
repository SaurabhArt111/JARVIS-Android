package com.jarvis.core.engine

import com.jarvis.core.model.AssistantReply
import com.jarvis.core.util.JarvisClock

/**
 * Conversation state every part of JARVIS shares (port of Backend/Dialog.py):
 *  1. FOLLOW-UP QUESTIONS: after "What should I remind you about?" the next thing the user says is the ANSWER, not a new command.
 *  2. WHAT WE WERE JUST TALKING ABOUT: after "remind me to call mom at 5", "reschedule it to 6" must know what "it" is.
 */
class Dialog(private val clock: JarvisClock) {

    private class Pending(val handler: suspend (String) -> AssistantReply?, val expiresMs: Long, val label: String, val answers: Regex?)

    private var pending: Pending? = null

    /** {"kind": "reminder"|"note", "reminder": id, "note": id} */
    var last: Map<String, String> = emptyMap()
        private set
    private var lastTs = 0L

    /** One plain sentence about the last thing JARVIS DID (fed to every AI call). */
    var lastAction: String = ""
        private set

    @Synchronized
    fun ask(label: String = "", ttlMs: Long = TTL_MS, answers: Regex? = null, handler: suspend (String) -> AssistantReply?) {
        pending = Pending(handler, clock.nowMs() + ttlMs, label, answers)
    }

    @Synchronized
    fun clear() { pending = null }

    /** Label of the question JARVIS is waiting on (null when nothing is pending). */
    @Synchronized
    fun waiting(): String? = pending?.takeIf { it.expiresMs > clock.nowMs() }?.label

    /** Returns a reply if [raw] answered the pending question; null when it did not (it is a new command, or nothing was pending). */
    suspend fun resume(raw: String): AssistantReply? {
        val p = synchronized(this) { val x = pending; pending = null; x } ?: return null
        if (p.expiresMs < clock.nowMs()) return null
        val t = raw.trim(' ', '.', '!', '?', ',').lowercase()
        if (CANCEL.matches(t)) return AssistantReply.chat("Okay, I've dropped that.", icon = "x")
        // [answers] lets a question accept replies that would otherwise look like a new command ("send it" to "send this reply?")
        if (p.answers?.matches(t) == true) return p.handler(raw)
        if (FRESH.containsMatchIn(t)) return null
        return p.handler(raw)
    }

    @Synchronized
    fun fresh(): Boolean = last["kind"] != null && clock.nowMs() - lastTs < FRESH_MS

    @Synchronized
    fun remember(kind: String, itemId: String, action: String = "") {
        last = last + mapOf("kind" to kind, kind to itemId)
        lastTs = clock.nowMs()
        if (action.isNotEmpty()) lastAction = action
    }

    @Synchronized
    fun noteAction(sentence: String) { lastAction = sentence }

    companion object {
        const val TTL_MS = 120_000L
        const val FRESH_MS = 15 * 60_000L

        val CANCEL = Regex(
            "^(?:never ?mind|nevermind|cancel(?: that| it)?|forget (?:it|that)|stop|no thanks|no thank you|skip(?: it)?|leave it|drop it|abort|nothing)$",
            RegexOption.IGNORE_CASE,
        )

        /** Things that are clearly a NEW command, so a pending question must not swallow them. */
        val FRESH = Regex(
            "^(?:(?:hey |ok |okay )?(?:jarvis)\\b|open\\b|launch\\b|close\\b|play\\b|pause\\b|resume\\b|search\\b|google\\b|youtube\\b|volume\\b|mute\\b|unmute\\b|" +
                "brightness\\b|screenshot\\b|take a (?:screenshot|note)\\b|set (?:a |an |the )?(?:timer|reminder|volume|brightness|alarm)\\b|remind me\\b|" +
                "what(?:'s| is| are)\\b|whats\\b|which\\b|who\\b|how\\b|when\\b|where\\b|why\\b|show\\b|list\\b|switch to\\b|move\\b|turn (?:on|off|up|down)\\b|" +
                "tell me\\b|make a note\\b|add a (?:note|reminder)\\b|read\\b|lock\\b|do i\\b|did i\\b|have i\\b|is there\\b|are there\\b|check\\b|reschedule\\b|postpone\\b|snooze\\b|" +
                "call\\b|message\\b|text\\b|send\\b)",
            RegexOption.IGNORE_CASE,
        )
    }
}
