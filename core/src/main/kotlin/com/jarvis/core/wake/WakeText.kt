package com.jarvis.core.wake

/**
 * Text-side wake-word helpers (port of Wake.extract_command / Wake.is_dangling). The audio side (openWakeWord / SpeechRecognizer)
 * lives in `:app`; this file only decides what was SAID: "hey jarvis open spotify" → (true, "open spotify").
 */
object WakeText {
    /** Speech recognisers mis-hear names; each wake word lists the spellings they tend to produce. */
    private val PHONETIC = mapOf(
        "jarvis" to listOf(
            "jarvis", "jarvas", "jervis", "gervis", "jarvice", "jarvus", "jarviss", "jarvish", "jarwis", "jarvez", "jarves", "jervas", "gervas", "charvis",
            "jarvi", "jarvik", "jarvi's", "jar vis", "jar viss", "jar was", "jar viz", "jarvys",
        ),
        "null" to listOf("null", "nul", "nol", "knull", "noll"),
    )

    /**
     * Mishearings that are ordinary words or other names ("service", "Travis"): accepted ONLY right after "hey / ok / hi ..." so that talking about a
     * service or a friend called Travis does not wake the assistant.
     */
    private val LOOSE = mapOf(
        "jarvis" to listOf("service", "services", "harvis", "harvest", "travis", "jervice", "jarvic", "darvis", "garvis", "narvis", "yarvis", "davis", "jervais", "jervais", "gar vis"),
    )
    private const val GREETING = "(?:hey|hay|hei|he|hi|hello|ok|okay|yo|hey there|a)"
    private const val GREETING_LOOSE = "(?:hey|hay|hei|hi|hello|ok|okay|yo)"

    private val DANGLING = setOf(
        "the", "a", "an", "to", "my", "of", "for", "and", "in", "on", "at", "with", "what's", "whats", "is", "are", "from", "it", "this", "that", "me", "your",
        "his", "her", "their", "then", "or", "into", "set", "open", "close", "move", "switch", "play", "search", "tell", "what", "how", "who", "where", "when",
        "can", "could", "please",
    )

    data class Match(val matched: Boolean, val rest: String)

    private fun variants(wakeWords: List<String>): List<String> =
        wakeWords.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            .flatMap { w -> PHONETIC[w] ?: listOf(w) }
            .distinct().sortedByDescending { it.length }

    /** If [heard] starts with a wake word → (true, remainder). Handles the usual mishearings ("hey service"), a bare "jarvis", and "hey, Jarvis!". */
    fun extractCommand(heard: String?, wakeWords: List<String>): Match {
        val h = (heard ?: "").lowercase().replace(Regex("^[\\s\\p{Punct}]+"), "").trim()
        val alt = variants(wakeWords).joinToString("|") { Regex.escape(it) }
        if (alt.isEmpty()) return Match(false, "")
        Regex("^(?:$GREETING[\\s,]+)?(?:$alt)(?!\\w)[\\s,.:!?-]*(.*)$").find(h)?.let { return Match(true, it.groupValues[1].trim()) }
        val loose = wakeWords.map { it.trim().lowercase() }.flatMap { LOOSE[it].orEmpty() }.distinct().sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
        if (loose.isNotEmpty()) {
            Regex("^$GREETING_LOOSE[\\s,]+(?:$loose)(?!\\w)[\\s,.:!?-]*(.*)$").find(h)?.let { return Match(true, it.groupValues[1].trim()) }
        }
        return Match(false, "")
    }

    /** True if a command looks cut off mid-sentence ("close the", "what's the"): the voice layer keeps listening a little longer. */
    fun isDangling(text: String?): Boolean {
        val t = (text ?: "").lowercase()
        val words = Regex("[\\w']+").findAll(t).map { it.value }.toList()
        // Deviation from the Python original: "what time is it" is a complete question, not a cut-off sentence.
        if (Regex("\\b(?:is|was|isn't|wasn't|do|does|did|are|were) (?:it|that|this)\\W*$").containsMatchIn(t)) return false
        return words.isNotEmpty() && words.last() in DANGLING
    }
}
