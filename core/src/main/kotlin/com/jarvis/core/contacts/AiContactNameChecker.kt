package com.jarvis.core.contacts

import com.jarvis.core.ai.LlmException
import com.jarvis.core.ai.LlmGateway
import com.jarvis.core.ai.LlmRole
import com.jarvis.core.ports.ContactNameChecker
import com.jarvis.core.util.JarvisLog
import kotlinx.coroutines.CancellationException

/**
 * Asks the AI to correct a name the speech recogniser probably got wrong ("dhruv bindori" for the contact "Dhruv Indori SSL GD").
 *
 * Privacy: the prompt holds only the heard words and a handful of candidate names that already sound similar on the phone; no numbers, no other contacts, no history.
 * The answer is trusted only if it is one of the candidates, word for word, so the AI can never invent a person to call.
 */
class AiContactNameChecker(private val llm: LlmGateway, private val log: JarvisLog = JarvisLog.None) : ContactNameChecker {
    override suspend fun choose(heard: String, candidates: List<String>): List<String> {
        if (candidates.isEmpty() || heard.isBlank() || !llm.configured()) return emptyList()
        val list = candidates.joinToString("\n") { "- $it" }
        val messages = listOf(
            mapOf("role" to "system", "content" to
                "You fix speech-recognition mistakes in people's names. The user spoke a name and the phone heard it as the text below; it may be misspelled or " +
                "mis-heard (Indian names are often garbled: \"Dhruv bindori\" for \"Dhruv Indori\"). From the contact list pick the contact(s) the user most likely meant. " +
                "Reply with the exact contact name(s) from the list, one per line, nothing else. If two or more are equally likely, list them all. " +
                "If none is a plausible match, reply with exactly: NONE"),
            mapOf("role" to "user", "content" to "Heard: \"${heard.take(80)}\"\nContacts:\n$list"),
        )
        return try {
            val answer = llm.chat(messages, LlmRole.DECISION, temperature = 0.0, maxTokens = 80, timeoutMs = 9_000)
            parse(answer, candidates).also { log.i("Contacts", "AI name check: ${it.size} pick(s) among ${candidates.size} sound-alike(s)") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmException) {
            log.i("Contacts", "AI name check unavailable (${e.kind})")
            emptyList()
        }
    }

    companion object {
        /** The candidates the answer names (by exact name, ignoring case, bullets, numbering and quotes). Anything else in the answer is ignored. */
        fun parse(answer: String, candidates: List<String>): List<String> {
            val byLower = candidates.associateBy { it.trim().lowercase() }
            val out = LinkedHashSet<String>()
            for (line in answer.lines()) {
                val t = line.trim().trimStart('-', '*', '•', ' ').replace(Regex("^\\d+[.)]\\s*"), "").trim().trim('"', '\'', '“', '”', '.').lowercase()
                if (t.isEmpty() || t == "none") continue
                byLower[t]?.let { out.add(it) }
            }
            return out.toList()
        }
    }
}
