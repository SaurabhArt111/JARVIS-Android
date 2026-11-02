package com.jarvis.core.agent

import com.jarvis.core.lock.LockDecision
import com.jarvis.core.lock.LockPolicy
import com.jarvis.core.lock.LockVerdict
import com.jarvis.core.model.ActionType
import com.jarvis.core.model.AssistantReply
import com.jarvis.core.model.Outcome
import com.jarvis.core.model.ReplyKind
import com.jarvis.core.model.Requirement
import com.jarvis.core.model.UiAction
import com.jarvis.core.ports.PermState
import com.jarvis.core.ports.Services
import com.jarvis.core.tools.Args
import com.jarvis.core.tools.Tool
import com.jarvis.core.tools.ToolContext
import com.jarvis.core.tools.ToolSpec
import com.jarvis.core.tools.impl.T
import com.jarvis.core.tools.impl.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** One tool run: what was asked, what really happened. */
data class Executed(val name: String, val args: Map<String, Any?>, val spec: ToolSpec?, val outcome: Outcome, val decision: LockDecision? = null)

/**
 * THE single path every tool call takes (agent loop AND offline command parser):
 * unknown tool → lock policy → required permissions → run (with a timeout, exceptions become honest failures) → result.
 */
class ToolExecutor(
    private val registry: ToolRegistry,
    private val services: Services,
    private val lockPolicy: LockPolicy,
    private val timeoutMs: Long = 45_000L,
) {
    suspend fun execute(name: String, rawArgs: Map<String, Any?>, ctx: ToolContext): Executed {
        val tool = registry.get(name)
            ?: return Executed(name, rawArgs, null, Outcome(false, "There is no tool called $name. Available: ${registry.names().joinToString()}."))
        val args = Args(rawArgs)
        val spec = tool.spec
        val decision = lockPolicy.decide(spec, args, ctx.lock, ctx.origin)
        val outcome = when (decision.verdict) {
            LockVerdict.ALLOW -> runChecked(tool, args, ctx)
            LockVerdict.REQUIRE_UNLOCK -> Outcome(
                false, "NOT DONE: the phone is locked and this ${decision.reason.ifBlank { "needs the user to unlock first" }}. Tell the user to unlock the phone and ask again.",
                AssistantReply.error("Please unlock your phone first — ${decision.reason.ifBlank { "this needs you to be present" }}.", "lock", listOf(Requirement.UNLOCK), listOf(UiAction("Unlock", ActionType.UNLOCK))),
            )
            LockVerdict.DENY -> Outcome.fail("That isn't allowed from the lock screen.", "lock")
            LockVerdict.CONFIRM -> askToConfirm(tool, args, ctx, decision)
        }
        services.log.i("Tool", "$name keys=${rawArgs.keys.sorted()} lock=${decision.verdict} -> ok=${outcome.ok} verified=${outcome.verified} noteLen=${outcome.note.length}")
        return Executed(name, rawArgs, spec, outcome, decision)
    }

    /** Required permissions, then the tool itself. Never throws except for cancellation. */
    private suspend fun runChecked(tool: Tool, args: Args, ctx: ToolContext): Outcome {
        val missing = tool.spec.needs.filter { services.permissions.state(it) != PermState.GRANTED }
        if (missing.isNotEmpty()) {
            val offline = Requirement.NETWORK in missing
            val parts = missing.map { r ->
                when (r) {
                    Requirement.NETWORK -> "an internet connection"
                    Requirement.NOTIFICATION_LISTENER -> "notification access"
                    Requirement.ACCESSIBILITY -> "the JARVIS accessibility service turned on"
                    Requirement.API_KEY -> "an AI key"
                    else -> r.name.lowercase().replace('_', ' ') + " permission"
                }
            }
            val text = (if (offline && missing.size == 1) "You're offline, so I can't do that right now." else "I can't do that yet — it needs ${parts.joinToString(" and ")}.")
            return Outcome(false, "NOT DONE: ${text}", AssistantReply.error(text, "alert", missing, T.actionsFor(missing)))
        }
        return try {
            withTimeout(timeoutMs) { tool.execute(args, ctx) }
        } catch (e: TimeoutCancellationException) {
            services.log.w("Tool", "${tool.spec.name} timed out")
            Outcome.fail("That took too long, so I stopped. It may or may not have happened — please check.", "clock")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            services.log.e("Tool", "${tool.spec.name} crashed: ${e.javaClass.simpleName}", e)
            Outcome(false, "The ${tool.spec.name} tool hit an error (${e.javaClass.simpleName}).", AssistantReply.error("That didn't work (${e.javaClass.simpleName}).", "alert"))
        }
    }

    /** Lock policy said CONFIRM: ask once; run only after an explicit yes. */
    private fun askToConfirm(tool: Tool, args: Args, ctx: ToolContext, decision: LockDecision): Outcome {
        val what = describe(tool.spec, args)
        ctx.dialog.ask("lock-confirm", answers = T.AFFIRM) { answer ->
            if (T.isYes(answer)) runChecked(tool, args, ctx).reply else AssistantReply.chat("Okay, I won't $what.", "x")
        }
        return Outcome(
            true, "ASKED THE USER to confirm ($what) because the phone is locked; nothing has been done yet. Do not say it was done.",
            AssistantReply(
                "Your phone is locked. Do you want me to $what? Say yes to confirm.", kind = ReplyKind.CHAT, icon = "shield",
                actions = listOf(UiAction("Yes", ActionType.CONFIRM, mapOf("answer" to "yes")), UiAction("No", ActionType.CANCEL, mapOf("answer" to "no"))),
                extras = mapOf("expect_reply" to true, "question" to true, "pending" to "lock-confirm", "reason" to decision.reason),
            ),
        )
    }

    private val WRITE_PHRASE = Regex("\\b(?:remind me|make a note|take a note|add a (?:note|reminder)|note that|set a reminder|remember to|jot)\\b", RegexOption.IGNORE_CASE)

    /**
     * Notes / reminders phrasing handled by the [com.jarvis.core.organizer.Organizer] (offline path). It is not a tool call, but it obeys the same lock policy
     * (judged as the `reminder` / `note` tool with a read or write action) and returns the same [Executed] shape.
     */
    suspend fun organize(text: String, ctx: ToolContext): Executed {
        val low = text.lowercase()
        val tool = registry.get(if (low.contains("note") && !low.contains("remind")) "note" else "reminder")!!
        val args = Args(mapOf("action" to if (WRITE_PHRASE.containsMatchIn(text)) "add" else "list"))
        val decision = lockPolicy.decide(tool.spec, args, ctx.lock, ctx.origin)
        if (decision.verdict != LockVerdict.ALLOW) {
            val o = Outcome(false, "NOT DONE: the phone is locked.", AssistantReply.error("Please unlock your phone first — ${decision.reason.ifBlank { "this shows personal information" }}.", "lock", listOf(Requirement.UNLOCK), listOf(UiAction("Unlock", ActionType.UNLOCK))))
            return Executed("organizer", mapOf("text" to "(private)"), tool.spec, o, decision)
        }
        val reply = try {
            registry.organizer.handle(text, ctx)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            services.log.e("Tool", "organizer crashed: ${e.javaClass.simpleName}", e)
            AssistantReply.error("Sorry, I hit a problem with that. Nothing was changed.")
        }
        val r = reply ?: AssistantReply("I couldn't work out that one. Try 'remind me to call mom at 5 pm', 'make a note buy milk', 'what's on my notes' or 'reschedule it to 6 pm'.",
            ok = false, kind = ReplyKind.CHAT, icon = "help-circle")
        return Executed("organizer", mapOf("text" to "(private)"), tool.spec, Outcome(r.ok, r.text, r, verified = r.ok && r.kind == ReplyKind.ACTION), decision)
    }

    private fun describe(spec: ToolSpec, args: Args): String = when (spec.name) {
        "call" -> when (com.jarvis.core.tools.impl.callControl(args)) {
            "answer" -> "answer the call"
            "hang_up" -> "end the call"
            else -> "call ${args.str("contact", "to", "person", "number", "name", default = "that number")}"
        }
        "message" -> "prepare a message to ${args.str("to", "contact", default = "that contact")}"
        "reply_notification" -> "send that reply"
        else -> "run ${spec.name}${args.str("action").let { if (it.isNotEmpty()) " ($it)" else "" }}"
    }
}
