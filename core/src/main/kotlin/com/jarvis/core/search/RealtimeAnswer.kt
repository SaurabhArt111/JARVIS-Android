package com.jarvis.core.search

import com.jarvis.core.ai.LlmGateway
import com.jarvis.core.ai.LlmRole
import com.jarvis.core.ports.ChatTurn
import com.jarvis.core.ports.WebSearch
import com.jarvis.core.util.JarvisClock
import java.time.format.DateTimeFormatter
import java.util.Locale

data class RealtimeResult(val answer: String, val sources: List<Pair<String, String>>, val usedWeb: Boolean)

/**
 * Port of `RealtimeSearchEngine`: search the web, then ask the LLM to answer from the hits.
 * The caller records the turn in memory (the Python version did it here AND in the engine, which stored it twice).
 * Throws [com.jarvis.core.ai.LlmException] when no AI is reachable.
 */
class RealtimeAnswer(
    private val search: WebSearch,
    private val llm: LlmGateway,
    private val clock: JarvisClock,
) {
    suspend fun answer(prompt: String, systemPrompt: String, history: List<ChatTurn> = emptyList()): RealtimeResult {
        val results = search.search(prompt, 5)
        val note = if (results.isNotEmpty()) {
            "Web search results for '$prompt':\n" + results.joinToString("\n") { "- ${it.title}: ${it.snippet}" }
        } else {
            "Live web search returned nothing (offline or blocked). Answer from your own knowledge and say clearly that it may be out of date."
        }
        val now = clock.now().format(DateTimeFormatter.ofPattern("EEEE dd MMMM yyyy, hh:mm a", Locale.ENGLISH))
        val msgs = ArrayList<Map<String, Any?>>()
        msgs.add(mapOf("role" to "system", "content" to systemPrompt + "\nAnswer the question using the search results. Be concise and factual and do not read out URLs."))
        msgs.add(mapOf("role" to "system", "content" to "Now: $now\n$note"))
        history.takeLast(6).forEach { msgs.add(mapOf("role" to it.role, "content" to it.content)) }
        msgs.add(mapOf("role" to "user", "content" to prompt))
        val answer = clean(llm.chat(msgs, LlmRole.CHAT, 0.4, 700))
        return RealtimeResult(answer, results.take(4).filter { it.url.isNotEmpty() }.map { it.title to it.url }, results.isNotEmpty())
    }

    companion object {
        private val TAGS = Regex("</?s>|<br\\s*/?>")
        private val BLANKS = Regex("\\n{3,}")
        /** Same cleanup as Backend/Chatbot.clean: no stray markup or markdown asterisks. */
        fun clean(text: String): String = BLANKS.replace(TAGS.replace(text, "").replace("**", "").replace("*", ""), "\n\n").trim()
    }
}
