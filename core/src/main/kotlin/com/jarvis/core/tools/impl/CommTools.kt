package com.jarvis.core.tools.impl

import com.jarvis.core.model.ActionType
import com.jarvis.core.model.AssistantReply
import com.jarvis.core.model.Card
import com.jarvis.core.model.CardItem
import com.jarvis.core.model.Outcome
import com.jarvis.core.model.ReplyKind
import com.jarvis.core.model.Requirement
import com.jarvis.core.model.UiAction
import com.jarvis.core.ports.ContactMatch
import com.jarvis.core.ports.MessagingApp
import com.jarvis.core.ports.NotificationInfo
import com.jarvis.core.ports.Services
import com.jarvis.core.tools.Args
import com.jarvis.core.tools.Capability
import com.jarvis.core.tools.LockClass
import com.jarvis.core.tools.Tool
import com.jarvis.core.tools.ToolContext
import com.jarvis.core.tools.ToolSpec

/** Finds who the user means (contact name or a number) and, when it's ambiguous, asks through [com.jarvis.core.engine.Dialog] instead of guessing. */
internal object ContactFlow {
    private val NUMBER = Regex("^\\+?[\\d\\s().-]{3,}$")

    fun looksLikeNumber(s: String) = NUMBER.matches(s.trim()) && s.count { it.isDigit() } >= 3

    private fun question(text: String, labels: List<String>, kind: String): AssistantReply =
        AssistantReply(
            text, kind = ReplyKind.CHAT, icon = "help-circle",
            card = Card("choice", "Which one?", icon = "users", items = labels.mapIndexed { i, l -> CardItem("${i + 1}. $l") }),
            actions = labels.mapIndexed { i, l -> UiAction(l, ActionType.CHOOSE, mapOf("answer" to (i + 1).toString())) },
            sensitive = true, extras = mapOf("expect_reply" to true, "question" to true, "pending" to kind),
        )

    /** A local match this sure is used as it is; anything weaker may get the optional AI name check and a "did you mean" question. */
    private const val CONFIDENT = 0.90

    /**
     * Resolves [who] and calls [onChosen] with (display name, number).
     *  1. The phone's own matching (spelling, nicknames, and SOUND: "Dhruv bindori" finds "Dhruv Indori").
     *  2. If that is not sure, an optional AI second opinion picks among a few similar-sounding names (nothing else is sent; see [com.jarvis.core.ports.ContactNameChecker]).
     *  3. Several equally good contacts, or only sound-alikes: ask which one instead of guessing.
     */
    suspend fun resolve(who: String, ctx: ToolContext, verbLabel: String, onChosen: suspend (String, String) -> Outcome): Outcome {
        val svc = ctx.services
        if (looksLikeNumber(who)) return onChosen(who.trim(), who.filter { it.isDigit() || it == '+' })
        val res = svc.contacts.search(who, 5)
        if (res.missing.isNotEmpty()) return T.failed(com.jarvis.core.ports.ActionResult(false, "I need permission to read your contacts before I can find $who.", res.missing), "", "users")
        res.error?.let { return T.fail(it, "users") }

        var matches = res.matches
        var didYouMean = false
        if ((matches.isEmpty() || res.bestScore < CONFIDENT) && res.suggestions.isNotEmpty()) {
            val picked = aiPick(who, res.suggestions, ctx)
            if (picked.isNotEmpty()) matches = picked
            else if (matches.isEmpty()) { matches = res.suggestions.take(3); didYouMean = true }
        }
        if (matches.isEmpty()) return T.fail("I couldn't find “$who” in your contacts, so I didn't $verbLabel anyone.", "users")
        if (matches.size == 1 && !didYouMean) return pickNumber(matches[0], ctx, onChosen)
        val labels = matches.map { it.displayName }
        ctx.dialog.ask("contact") { answer -> choose(answer, matches, labels, ctx, onChosen, retry = true).reply }
        val q = if (didYouMean) "I couldn't find “$who” exactly. Did you mean ${labels.mapIndexed { i, l -> "${i + 1}) $l" }.joinToString(", ")}? Say the number."
        else "I found ${matches.size} contacts named like that: ${labels.mapIndexed { i, l -> "${i + 1}) $l" }.joinToString(", ")}. Which one?"
        return Outcome(true, "ASKED THE USER which contact they mean; nothing has been done yet. Do not say anything was $verbLabel.",
            question(q, labels, "contact"), verified = false)
    }

    /** The AI's pick among [candidates] (names only), or empty when the check is off, not allowed right now, fails or finds nobody. */
    private suspend fun aiPick(who: String, candidates: List<ContactMatch>, ctx: ToolContext): List<ContactMatch> {
        val svc = ctx.services
        val checker = svc.contactChecker ?: return emptyList()
        if (!svc.settings.flag(com.jarvis.core.settings.Keys.CONTACT_AI_CHECK, true)) return emptyList()
        if (!svc.settings.flag(com.jarvis.core.settings.Keys.AI_SHARE_PERSONAL, true)) return emptyList()   // the user asked that personal details stay on the phone
        if (ctx.lock.protectPersonalData) return emptyList()                                               // locked phone: nothing leaves it
        val names = candidates.map { it.displayName }.distinct().take(8)
        val chosen = try { checker.choose(who, names) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { return emptyList() }
        val keep = chosen.map { it.trim().lowercase() }.toSet()
        return candidates.filter { it.displayName.trim().lowercase() in keep }.distinctBy { it.id }
    }

    private suspend fun choose(
        answer: String, matches: List<ContactMatch>, labels: List<String>, ctx: ToolContext, onChosen: suspend (String, String) -> Outcome, retry: Boolean,
    ): Outcome {
        val i = T.pickIndex(answer, labels)
        if (i == null) {
            if (retry) {
                ctx.dialog.ask("contact") { a2 -> choose(a2, matches, labels, ctx, onChosen, retry = false).reply }
                return Outcome(true, "asked again", question("Sorry, which one? Say the number: ${labels.mapIndexed { n, l -> "${n + 1}) $l" }.joinToString(", ")}.", labels, "contact"))
            }
            return Outcome(true, "dropped", AssistantReply.chat("Okay, I've left it. Nothing was done.", "x"))
        }
        return pickNumber(matches[i], ctx, onChosen)
    }

    private suspend fun pickNumber(c: ContactMatch, ctx: ToolContext, onChosen: suspend (String, String) -> Outcome): Outcome {
        val nums = c.numbers.distinctBy { it.number.filter { ch -> ch.isDigit() } }
        if (nums.isEmpty()) return T.fail("${c.displayName} has no phone number saved.", "users")
        if (nums.size == 1) return onChosen(c.displayName, nums[0].number)
        nums.firstOrNull { it.primary }?.let { return onChosen(c.displayName, it.number) }
        val labels = nums.map { "${it.label.ifBlank { "Number" }}: ${it.number}" }
        ctx.dialog.ask("number") { answer ->
            val i = T.pickIndex(answer, labels + nums.map { it.label })?.let { it % nums.size }
            if (i == null) AssistantReply.chat("Okay, I've left it. Nothing was done.", "x") else onChosen(c.displayName, nums[i].number).reply
        }
        return Outcome(true, "ASKED THE USER which of ${c.displayName}'s numbers to use; nothing has been done yet.",
            question("${c.displayName} has ${nums.size} numbers: ${labels.mapIndexed { i, l -> "${i + 1}) $l" }.joinToString(", ")}. Which one?", labels, "number"))
    }
}

// ===================================================================================================== message
class MessageTool : Tool {
    override val spec = ToolSpec(
        "message",
        "Prepare a WhatsApp, Telegram or SMS message: opens the chat with the text already typed. The user presses send — nothing is sent automatically. " +
            "Only when the user clearly asked, with who and what.",
        mapOf("app" to T.p("whatsapp | telegram | sms", enum = listOf("whatsapp", "telegram", "sms")), "to" to T.p("Contact name or phone number"), "text" to T.p("Message")),
        listOf("to", "text"), Capability.COMMUNICATION, LockClass.CONSEQUENTIAL, needs = emptyList(),
    )

    override suspend fun execute(args: Args, ctx: ToolContext): Outcome {
        val svc = ctx.services
        val appName = args.str("app", "service", default = "whatsapp").lowercase()
        val app = when {
            appName.contains("tele") -> MessagingApp.TELEGRAM
            appName.contains("sms") || appName.contains("text") || appName.contains("message") -> MessagingApp.SMS
            else -> MessagingApp.WHATSAPP
        }
        val who = args.str("to", "contact", "recipient")
        val text = args.str("text", "message")
        if (who.isEmpty() || text.isEmpty()) return T.fail("Tell me who to message and what to say.", "send")
        val label = when (app) { MessagingApp.WHATSAPP -> "WhatsApp"; MessagingApp.TELEGRAM -> "Telegram"; MessagingApp.SMS -> "your messages app" }
        if (!svc.messaging.isAvailable(app)) return T.fail("$label isn't installed on this phone, so I can't prepare that message.", "send", listOf())
        return ContactFlow.resolve(who, ctx, "message") { name, number ->
            val r = svc.messaging.openChat(app, number, text)
            if (!r.ok) T.failed(r, "I couldn't open $label.", "send")
            else {
                ctx.dialog.noteAction("opened $label with a message to $name")
                Outcome(true, "Opened $label with the message to $name typed in. It has NOT been sent: the user must tap send. Never say it was sent.",
                    AssistantReply("Opened $label with your message to $name ready — tap send to send it.", icon = "send", sensitive = true,
                        card = Card("info", "$label to $name", text, "send")), verified = false)
            }
        }
    }
}

// ===================================================================================================== call
/** "answer" / "hang_up" when the model asked to control a call instead of placing one; null for a normal call. */
internal fun callControl(args: Args): String? = when (args.str("action").lowercase().replace(' ', '_').replace('-', '_')) {
    "answer", "answer_call", "pick_up", "accept" -> "answer"
    "hang_up", "hangup", "end", "end_call", "decline", "reject" -> "hang_up"
    else -> null
}

/** The ways "speaker" is said or misheard: speaker, speakers, speakerphone, loud speaker, speak, speaking, spiker. */
private const val SPK = """(?:loud\s*speakers?|speaker\s*phone|speakers?|speak|speaking|spiker|speeker)"""

/** "...on speaker", "with the loud speaker", "in speakerphone mode", "on loudspeaker" at the end of what was heard as a name. */
internal val SPEAKER_TAIL = Regex("""\s*[,.]?\s*\b(?:(?:on|in|with|using|over|through)\s+(?:the\s+|my\s+)?(?:$SPK|loud)(?:\s+mode)?|(?:loud\s*speakers?|speaker\s*phone)(?:\s+mode)?)\s*$""", RegexOption.IGNORE_CASE)

/** "call on speaker Mom" */
internal val SPEAKER_HEAD = Regex("""^(?:(?:on|in|with|using)\s+(?:the\s+|my\s+)?$SPK(?:\s+mode)?|(?:loud\s*speakers?|speaker\s*phone))\s*,?\s+(?:to\s+)?""", RegexOption.IGNORE_CASE)

class CallTool : Tool {
    override val spec = ToolSpec(
        "call", "Phone call to a contact or number. Places the call directly when JARVIS has phone permission, otherwise opens the dialer with the number ready. " +
            "service=meet opens a new Google Meet video call in the browser. action=answer answers the ringing call, action=hang_up ends the current call.",
        mapOf(
            "contact" to T.p("Contact name or phone number"), "service" to T.p("phone | meet (optional)"),
            "action" to T.p("call (default) | answer | hang_up", enum = listOf("call", "answer", "hang_up")),
            "speaker" to T.p("true to start the call on the loudspeaker (\"call Mom on speaker\")", "boolean"),
        ),
        emptyList(), Capability.COMMUNICATION, LockClass.CONSEQUENTIAL,
    )

    override suspend fun execute(args: Args, ctx: ToolContext): Outcome {
        val svc = ctx.services
        when (callControl(args)) {
            "answer" -> {
                val r = svc.calls.answerRingingCall()
                return if (r.ok) Outcome(true, r.message.ifBlank { "Asked the phone to answer the call." } + " Android does not confirm that it connected.",
                    T.reply(r.message.ifBlank { "Answering the call." }, "phone"), verified = false)
                else T.failed(r, "I couldn't answer the call.", "phone")
            }
            "hang_up" -> {
                val r = svc.calls.endCall()
                return if (r.ok) Outcome(true, r.message.ifBlank { "Asked the phone to end the call." } + " Android does not confirm that it ended.",
                    T.reply(r.message.ifBlank { "Ending the call." }, "phone"), verified = false)
                else T.failed(r, "I couldn't end the call.", "phone")
            }
        }
        val service = args.str("service", "app", default = "phone").lowercase()
        if (service.contains("meet")) {
            val r = svc.apps.openUrl("https://meet.google.com/new")
            return if (r.ok) Outcome(true, "Opened Google Meet to start a new video call. Share the link it shows.", T.reply("Opening Google Meet.", "video"), verified = r.verified)
            else T.failed(r, "I couldn't open Google Meet.", "video")
        }
        var who = args.str("contact", "to", "person", "number", "name")
        if (who.isEmpty()) return T.fail("Who should I call?", "phone")
        // "call Mom on speaker" must not look for a contact called "Mom on speaker"
        var speaker = args.bool("speaker", "speakerphone", "loudspeaker")
        SPEAKER_TAIL.find(who)?.let { who = who.removeRange(it.range).trim(' ', ',', '.'); speaker = true }
        SPEAKER_HEAD.find(who)?.let { who = who.removeRange(it.range).trim(' ', ',', '.'); speaker = true }
        if (who.isEmpty()) return T.fail("Who should I call?", "phone")
        return ContactFlow.resolve(who, ctx, "call") { name, number ->
            val r = svc.calls.call(number, name, speaker)
            if (!r.ok) T.failed(r, "I couldn't start the call.", "phone")
            else {
                ctx.dialog.noteAction("called $name")
                val tail = if (speaker) " It was asked to start on speaker; Android does not confirm that." else ""
                Outcome(true, r.message.ifBlank { "Started the call to $name." } + " (If this only opened the dialer, the user must tap call.)" + tail,
                    AssistantReply(r.message.ifBlank { "Calling $name." }, icon = "phone", sensitive = true), verified = false)
            }
        }
    }
}

// ===================================================================================================== read_notifications
class ReadNotificationsTool : Tool {
    override val spec = ToolSpec(
        "read_notifications", "Read the notifications currently on the phone (newest first). Filter by app or words. Needs notification access.",
        mapOf("app" to T.p("Only this app (optional)"), "query" to T.p("Only notifications containing these words (optional)"), "limit" to T.p("How many (default 5)", "integer")),
        emptyList(), Capability.NOTIFICATIONS, LockClass.PERSONAL, isAction = false, needs = listOf(Requirement.NOTIFICATION_LISTENER),
    )

    private fun ago(ms: Long, now: Long): String {
        val m = ((now - ms) / 60_000).coerceAtLeast(0)
        return when { m < 1 -> "just now"; m < 60 -> "$m min ago"; m < 24 * 60 -> "${m / 60} h ago"; else -> "${m / (24 * 60)} d ago" }
    }

    override suspend fun execute(args: Args, ctx: ToolContext): Outcome {
        val svc = ctx.services
        if (!svc.notifications.isAccessGranted()) return T.fail("Notification access is off, so I can't see your notifications.", "bell", listOf(Requirement.NOTIFICATION_LISTENER))
        val app = args.str("app").lowercase()
        val q = args.str("query").lowercase()
        val limit = (args.int("limit") ?: 5).coerceIn(1, 10)
        val all = svc.notifications.active().filter { !it.ongoing && it.packageName != OWN_PACKAGE }
            .filter { app.isEmpty() || it.appLabel.lowercase().contains(app) }
            .filter { q.isEmpty() || (it.title + " " + it.text).lowercase().contains(q) }
            .sortedByDescending { it.postedAtMs }
        if (all.isEmpty()) return T.info("There are no matching notifications.", T.reply("You have no ${if (app.isNotEmpty() || q.isNotEmpty()) "matching " else ""}notifications.", "bell"))
        val shown = all.take(limit)
        val now = svc.clock.nowMs()
        val perApp = all.groupingBy { it.appLabel }.eachCount().entries.sortedByDescending { it.value }
        val summary = "You have ${T.plural(all.size, "notification")}: " + perApp.take(4).joinToString(", ") { "${it.value} from ${it.key}" } + "."
        fun line(i: Int, n: NotificationInfo) = "${i + 1}) ${n.appLabel} — ${n.conversation ?: n.title}: ${n.text.take(160)} (${ago(n.postedAtMs, now)}${if (n.canReply) ", can reply" else ""}) [key=${n.key}]"
        val full = summary + "\n" + shown.mapIndexed(::line).joinToString("\n")
        val redacted = "$summary They are shown on screen and are private: do not repeat their content."
        return T.info(T.modelNote(svc, full, redacted),
            T.reply(summary, "bell", Card("items", "Notifications", icon = "bell", items = shown.map { CardItem("${it.appLabel}: ${it.conversation ?: it.title}", it.text.take(120)) }), sensitive = true))
    }
}

// ===================================================================================================== reply_notification
class ReplyNotificationTool : Tool {
    override val spec = ToolSpec(
        "reply_notification",
        "Reply to a message notification through its own reply button (works for WhatsApp, SMS, Telegram ... when they offer one). The user is always asked to confirm the exact text first.",
        mapOf("text" to T.p("The reply to send"), "key" to T.p("Notification key from read_notifications (optional)"), "app" to T.p("App or sender (optional): picks the newest repliable notification from it")),
        listOf("text"), Capability.NOTIFICATIONS, LockClass.CONSEQUENTIAL, needs = listOf(Requirement.NOTIFICATION_LISTENER),
    )

    override suspend fun execute(args: Args, ctx: ToolContext): Outcome {
        val svc = ctx.services
        val text = args.str("text", "message", "reply")
        if (text.isEmpty()) return T.fail("What should the reply say?", "send")
        if (!svc.notifications.isAccessGranted()) return T.fail("Notification access is off, so I can't reply to notifications.", "bell", listOf(Requirement.NOTIFICATION_LISTENER))
        val key = args.str("key")
        val who = args.str("app", "to", "sender").lowercase()
        val candidates = svc.notifications.active().filter { it.canReply }.sortedByDescending { it.postedAtMs }
        val target = when {
            key.isNotEmpty() -> candidates.firstOrNull { it.key == key }
            who.isNotEmpty() -> candidates.firstOrNull { it.appLabel.lowercase().contains(who) || (it.conversation ?: it.title).lowercase().contains(who) }
            else -> candidates.firstOrNull()
        } ?: return T.fail("I can't see a notification that offers a quick reply${if (who.isNotEmpty()) " from $who" else ""}.", "send")
        val name = target.conversation ?: target.title
        val q = "Send this reply to $name on ${target.appLabel}: “$text”? Say yes to send it."
        ctx.dialog.ask("confirm-reply", answers = T.AFFIRM) { answer ->
            when {
                T.isYes(answer) -> {
                    val r = svc.notifications.quickReply(target.key, text)
                    if (r.ok) {
                        ctx.dialog.noteAction("replied to $name on ${target.appLabel}")
                        AssistantReply("I handed your reply to ${target.appLabel}'s quick-reply button. I can't see a delivery confirmation, so check the chat if it matters.", icon = "send", sensitive = true)
                    } else AssistantReply.error(r.message.ifBlank { "That reply didn't go through." }, "send", r.missing, T.actionsFor(r.missing))
                }
                else -> AssistantReply.chat("Okay, I didn't send anything.", "x")
            }
        }
        return Outcome(true, "ASKED THE USER to confirm the reply to $name; nothing has been sent yet. Do not say it was sent.",
            AssistantReply(q, kind = ReplyKind.CHAT, icon = "send", sensitive = true,
                card = Card("info", "Reply to $name", text, "send"),
                actions = listOf(UiAction("Send", ActionType.CONFIRM, mapOf("answer" to "yes")), UiAction("Don't send", ActionType.CANCEL, mapOf("answer" to "no"))),
                extras = mapOf("expect_reply" to true, "question" to true, "pending" to "confirm-reply")))
    }
}
