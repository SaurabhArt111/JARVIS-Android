package com.jarvis.core.util

/**
 * Removes secrets and personal content from a log line BEFORE it is written anywhere. Logs are for diagnosing JARVIS, not for recording the user's life:
 * keys / tokens, e-mail addresses, phone numbers and long free text never reach Logcat or the log file.
 */
object LogRedactor {
    private const val MASK = "[redacted]"

    private val RULES: List<Pair<Regex, String>> = listOf(
        // Authorization headers and bearer tokens
        Regex("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._~+/=-]{6,}") to "$1 $MASK",
        // key=value / "key": "value" for anything that smells like a secret
        Regex("(?i)\\b((?:api[_-]?key|apikey|access[_-]?token|refresh[_-]?token|token|secret|password|passwd|authorization|x-api-key)[\"']?\\s*[:=]\\s*[\"']?)[^\\s\"'&,;}]{3,}") to "$1$MASK",
        // well-known key shapes (Groq, OpenAI/OpenRouter, Hugging Face, Google, Cohere-like long tokens, GitHub)
        Regex("\\b(?:gsk|sk|hf|ghp|gho|xox[abp])[_-][A-Za-z0-9_-]{16,}") to MASK,
        Regex("\\bAIza[0-9A-Za-z_-]{20,}") to MASK,
        Regex("\\b[A-Za-z0-9_-]{32,}\\b") to MASK,
        // e-mail addresses
        Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}") to "[email]",
        // phone numbers: optional +, then 7+ digits possibly separated by space / dash / dot / brackets
        Regex("(?<![\\w])\\+?\\(?\\d[\\d\\s().-]{6,}\\d(?![\\w])") to "[number]",
    )

    /** Free text (message bodies, notification text, reminder text) must be passed through [text] instead of being put into a log line. */
    fun text(s: String): String = "[${s.length} chars]"

    fun redact(line: String, maxLength: Int = 600): String {
        var out = line
        for ((re, rep) in RULES) out = re.replace(out, rep)
        return if (out.length > maxLength) out.take(maxLength) + "… [+${out.length - maxLength} chars]" else out
    }
}
