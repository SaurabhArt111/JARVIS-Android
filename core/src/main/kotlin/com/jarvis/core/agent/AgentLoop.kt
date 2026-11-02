package com.jarvis.core.agent

import com.jarvis.core.ai.LlmGateway
import com.jarvis.core.ai.LlmRole
import com.jarvis.core.json.Json
import com.jarvis.core.model.AssistantReply
import com.jarvis.core.model.Card
import com.jarvis.core.model.ReplyKind
import com.jarvis.core.ports.Services
import com.jarvis.core.settings.Keys
import com.jarvis.core.tools.ToolContext
import com.jarvis.core.tools.ToolSpec

/**
 * The single brain (port of Agent.py): ask the AI → run the tools it asked for → give it the results → repeat until it answers in plain words.
 * Throws [com.jarvis.core.ai.LlmException] when no AI can be reached (the engine then falls back to the offline command parser).
 */
class AgentLoop(private val llm: LlmGateway, private val executor: ToolExecutor, private val services: Services) {

    suspend fun run(text: String, ctx: ToolContext, systemPrompt: String, specs: List<ToolSpec>, onStep: (String) -> Unit = {}): AssistantReply {
        val maxSteps = services.settings.int(Keys.AGENT_MAX_STEPS, 6).coerceIn(2, 10)
        val messages = ArrayList<Map<String, Any?>>()
        messages += mapOf("role" to "system", "content" to systemPrompt)
        services.memory.recent(CONTEXT_TURNS).forEach { messages += mapOf("role" to it.role, "content" to it.content) }
        messages += mapOf("role" to "user", "content" to text)
        val schemas = specs.map { it.toSchema() }

        val steps = ArrayList<Executed>()
        val seen = HashSet<String>()
        var final = ""
        var nudged = false
        var calledAny = false
        var forced = false
        var finished = false
        val mustAct = Honesty.needsTool(text)

        for (step in 0 until maxSteps) {
            val msg = llm.chatWithTools(messages, schemas, LlmRole.AGENT, 0.3, 900, 35_000, force = forced && !calledAny)
            val calls = msg.toolCalls.filter { key(it.name, it.args) !in seen }.distinctBy { key(it.name, it.args) }

            if (calls.isEmpty()) {
                // The request obviously needs a tool ("set the volume to 40") but the AI only talked: make it act.
                if (mustAct && !calledAny && !forced) {
                    forced = true
                    services.log.i("Agent", "request needs a tool but the AI only replied in words — requiring a tool call")
                    messages += mapOf("role" to "assistant", "content" to msg.content.ifBlank { "(no tool called)" })
                    messages += mapOf("role" to "user", "content" to "(system note) That request needs a tool - nothing has happened yet. Call the right tool now.")
                    continue
                }
                final = Honesty.clean(msg.content)
                if (Honesty.garbled(final)) final = if (steps.isNotEmpty()) fallbackText(steps) else "Sorry, I didn't get a clear answer from the AI there — please try again."
                if (final.isEmpty() && steps.isNotEmpty()) final = fallbackText(steps)
                // honesty guard: it SAYS it did something, but no tool ran in this whole turn
                if (final.isNotEmpty() && !calledAny && !nudged && Honesty.claimsAction(final)) {
                    nudged = true
                    services.log.i("Agent", "answer claimed an action without a tool call — nudging once")
                    messages += mapOf("role" to "assistant", "content" to final)
                    messages += mapOf("role" to "user", "content" to "(system note) You described doing something, but you did not call any tool, so nothing happened. " +
                        "Call the right tool now, or tell the user plainly that you have not done it.")
                    final = ""
                    continue
                }
                finished = true
                break
            }

            messages += msg.canonical()
            val stepOuts = ArrayList<Executed>()
            for (c in calls) {
                seen.add(key(c.name, c.args))
                onStep(Prompts.stepLabel(c.name, c.args))
                val ex = executor.execute(c.name, c.args, ctx)
                steps += ex
                stepOuts += ex
                messages += mapOf("role" to "tool", "tool_call_id" to c.id, "name" to c.name, "content" to ex.outcome.toModel())
            }
            calledAny = true

            // A question to the user is pending (which contact? confirm?): the question itself is the answer.
            stepOuts.firstOrNull { it.outcome.reply?.extras?.get("question") == true }?.let { q ->
                final = q.outcome.reply!!.text; finished = true; return@let
            }
            if (finished) break
            // Pictures: the reply is fixed text; the picture itself is the answer.
            stepOuts.firstOrNull { it.name == "image" && it.outcome.ok && it.outcome.reply != null }?.let { final = it.outcome.reply!!.text; finished = true }
            if (finished) break
            // Fast path: the AI already worded the answer next to its tool calls and nothing needs reading back to it.
            if (msg.content.isNotBlank() && !Honesty.garbled(msg.content) && stepOuts.all { it.spec?.isAction == true && it.outcome.ok && !it.outcome.info }) {
                final = Honesty.clean(msg.content); finished = true; break
            }
        }
        if (!finished) final = final.ifEmpty { fallbackText(steps) }
        if (final.isEmpty() || Honesty.garbled(final)) final = if (steps.isNotEmpty()) fallbackText(steps) else "I didn't catch that — could you say it again?"
        return Compose.reply(final, steps, calledAny)
    }

    private fun key(name: String, args: Map<String, Any?>): String = name + "|" + Json.stringify(args.toSortedMap())

    private fun fallbackText(steps: List<Executed>): String =
        steps.takeLast(3).mapNotNull { s -> (s.outcome.reply?.text?.takeIf { it.isNotBlank() } ?: s.outcome.note).takeIf { it.isNotBlank() } }.joinToString(" ").ifEmpty { "Done." }

    companion object {
        const val CONTEXT_TURNS = 16
    }
}

/** Builds the one reply the user sees from the final words plus everything the tools produced, and checks the words against the real results. */
object Compose {
    private val CARD_PRIORITY = listOf("browse", "generating", "images", "music", "calendar", "system", "weather", "file", "items", "timer", "level", "choice", "info")
    private val SCORE = CARD_PRIORITY.withIndex().associate { (i, t) -> t to CARD_PRIORITY.size - i }

    private fun pickCard(steps: List<Executed>): Card? {
        var best: Card? = null
        var score = -1
        for (s in steps) {
            val c = s.outcome.reply?.card ?: continue
            val sc = SCORE[c.type] ?: 0
            if (sc >= score) { best = c; score = sc }     // later cards win ties
        }
        return best
    }

    fun reply(finalIn: String, steps: List<Executed>, calledAny: Boolean): AssistantReply {
        val replies = steps.mapNotNull { it.outcome.reply }
        var card = pickCard(steps)
        val extras = LinkedHashMap<String, Any?>()
        val sources = ArrayList<Any?>()
        for (r in replies) {
            for (k in listOf("goto", "widget", "job")) r.extras[k]?.let { extras[k] = it }
            (r.extras["sources"] as? List<*>)?.let { sources.addAll(it) }
        }
        if (sources.isNotEmpty()) extras["sources"] = sources.take(4)
        val browse = replies.mapNotNull { it.card }.filter { it.type == "browse" }
        if (browse.size > 1) {
            card = browse[0]
            extras["open_now"] = browse.drop(1).mapNotNull { it.url }
        }
        val others = replies.mapNotNull { it.card }.filter { it.type != "info" && it !== card }
        if (others.isNotEmpty()) extras["cards"] = others.take(3)
        if (replies.any { it.extras["expect_reply"] == true }) extras["expect_reply"] = true

        val okAll = steps.isEmpty() || steps.any { it.outcome.ok }
        val blocked = replies.any { it.kind == ReplyKind.BLOCKED }
        val kind = when {
            blocked && steps.none { it.outcome.ok } -> ReplyKind.BLOCKED
            !okAll -> ReplyKind.ERROR
            calledAny -> ReplyKind.ACTION
            else -> ReplyKind.CHAT
        }
        val icon = (if (card?.type == "info") card.icon.takeIf { it.isNotBlank() } else null)
            ?: replies.lastOrNull { it.ok }?.icon ?: if (blocked) "shield" else if (okAll) "message" else "alert"
        val final = ResultValidator.validate(finalIn, steps)
        return AssistantReply(
            text = final, ok = okAll, kind = kind, icon = icon, card = card,
            speak = replies.none { it.kind == ReplyKind.INFO } || replies.any { it.kind != ReplyKind.INFO },
            actions = replies.flatMap { it.actions }.distinctBy { Triple(it.label, it.type, it.payload) }.take(4),
            missing = replies.flatMap { it.missing }.distinct(),
            sensitive = replies.any { it.sensitive },
            extras = extras,
        )
    }
}

/**
 * Tool-result validation: the words the AI wrote must not contradict what the tools really reported.
 *  1. every tool failed but the answer claims success → say what failed instead;
 *  2. some tools failed and the answer never mentions it → append the failure;
 *  3. a tool succeeded but could not be verified ("tap send", "I can't confirm") and the answer claims completion or drops the caveat → use / add the tool's caveat.
 */
object ResultValidator {
    private val ACKNOWLEDGES = Regex("can't|cannot|couldn't|could not|didn't|did not|wasn't|isn't|not able|unable|failed|need|needs|permission|unlock|offline|don't|doesn't|won't|sorry|unfortunately|no luck", RegexOption.IGNORE_CASE)

    /** Words that say "finished" even without an "I've ..." in front ("Message sent!", "All done."): wrong when the tool could not confirm anything. */
    private val COMPLETION = Regex("\\b(?:sent|delivered|done|completed|all set|finished|successfully|messaged|texted|called|dialed|dialled|placed|posted|replied)\\b", RegexOption.IGNORE_CASE)

    fun validate(final: String, steps: List<Executed>): String {
        if (steps.isEmpty()) return final
        val failed = steps.filter { !it.outcome.ok }
        val ok = steps.filter { it.outcome.ok }
        val failText = { failed.mapNotNull { it.outcome.reply?.text?.takeIf { t -> t.isNotBlank() } }.distinct().take(2).joinToString(" ") }
        // every tool failed: the answer must say so. A confident answer ("Spotify is open.") or one that never mentions the failure is replaced.
        if (ok.isEmpty() && failed.isNotEmpty() && (Honesty.claimsAction(final) || !ACKNOWLEDGES.containsMatchIn(final))) return failText().ifEmpty { "That didn't work." }
        var out = final
        val hedged = ok.filter { it.spec?.isAction == true && !it.outcome.verified && it.outcome.reply?.text?.let(Honesty.HEDGE::containsMatchIn) == true }
        if (hedged.isNotEmpty()) {
            val caveat = hedged.mapNotNull { it.outcome.reply?.text }.distinct().joinToString(" ")
            val hedgedAlready = Honesty.HEDGE.containsMatchIn(out)
            out = if (!hedgedAlready && (Honesty.claimsAction(out) || COMPLETION.containsMatchIn(out))) (ok - hedged.toSet()).mapNotNull { it.outcome.reply?.text }.plus(caveat).distinct().joinToString(" ")
            else if (!hedgedAlready) (out + " " + hedged.mapNotNull { Honesty.hedgeSentences(it.outcome.reply?.text ?: "").takeIf { s -> s.isNotEmpty() } }.joinToString(" ")).trim()
            else out
        }
        if (failed.isNotEmpty() && ok.isNotEmpty() && !ACKNOWLEDGES.containsMatchIn(out)) out = (out + " But " + failText().replaceFirstChar { it.lowercase() }).trim()
        return out
    }
}
