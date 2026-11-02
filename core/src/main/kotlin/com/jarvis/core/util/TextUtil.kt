package com.jarvis.core.util

/** Small shared text helpers (port of Backend/Util.py). */

private val LEAD_FILLER = Regex("^(?:please|can you|could you|would you|will you|i want you to|i need you to)\\s+", RegexOption.IGNORE_CASE)
private val TRAIL_FILLER = Regex("\\s+(?:please|for me|now)[.!?]*$", RegexOption.IGNORE_CASE)

/**
 * Removes the wake word and polite filler from the START/END of a request, KEEPING the user's capitalisation
 * (notes and reminders should read the way the user said them).
 */
fun stripWake(text: String?, wakeWords: List<String>): String {
    var t = (text ?: "").trim()
    for (w in wakeWords + listOf("hey", "ok", "okay", "yo")) {
        if (w.isBlank()) continue
        t = Regex("^(?:hey\\s+|ok\\s+|okay\\s+)?" + Regex.escape(w) + "[\\s,:.!-]*", RegexOption.IGNORE_CASE).replace(t, "")
    }
    t = LEAD_FILLER.replace(t, "")
    t = TRAIL_FILLER.replace(t, "")
    return t.trim(' ', '.', '!', '?', ',')
}
