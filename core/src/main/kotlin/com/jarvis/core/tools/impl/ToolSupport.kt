package com.jarvis.core.tools.impl

import com.jarvis.core.model.ActionType
import com.jarvis.core.model.AssistantReply
import com.jarvis.core.model.Card
import com.jarvis.core.model.CardItem
import com.jarvis.core.model.Outcome
import com.jarvis.core.model.ReplyKind
import com.jarvis.core.model.Requirement
import com.jarvis.core.model.UiAction
import com.jarvis.core.ports.ActionResult
import com.jarvis.core.ports.Services
import com.jarvis.core.settings.Keys
import com.jarvis.core.tools.ParamSpec
import java.util.Locale

/** Package name of the JARVIS app itself (used so tools never act on the assistant). */
const val OWN_PACKAGE = "com.jarvis.assistant"

/** Small helpers shared by every tool implementation. */
internal object T {
    fun p(description: String, type: String = "string", enum: List<String>? = null) = ParamSpec(description, type, enum)

    fun grantAction(req: Requirement, label: String = labelFor(req)) =
        UiAction(label, ActionType.GRANT, mapOf("requirement" to req.name))

    fun labelFor(req: Requirement): String = when (req) {
        Requirement.MICROPHONE -> "Allow microphone"
        Requirement.NOTIFICATIONS -> "Allow notifications"
        Requirement.CONTACTS -> "Allow contacts"
        Requirement.CALL_PHONE -> "Allow phone calls"
        Requirement.OVERLAY -> "Allow display over other apps"
        Requirement.NOTIFICATION_LISTENER -> "Allow notification access"
        Requirement.ACCESSIBILITY -> "Turn on accessibility service"
        Requirement.EXACT_ALARM -> "Allow exact alarms"
        Requirement.WRITE_SETTINGS -> "Allow changing system settings"
        Requirement.DND_ACCESS -> "Allow Do Not Disturb access"
        Requirement.FULL_SCREEN_INTENT -> "Allow full-screen alerts"
        Requirement.BATTERY_OPTIMIZATION -> "Exclude from battery optimisation"
        Requirement.ASSISTANT_ROLE -> "Make JARVIS the default assistant"
        Requirement.UNLOCK -> "Unlock"
        Requirement.NETWORK -> "Check connection"
        Requirement.API_KEY -> "Add an AI key"
        Requirement.APP_INSTALLED -> "Install the app"
    }

    fun actionsFor(missing: List<Requirement>): List<UiAction> =
        missing.filter { it != Requirement.NETWORK && it != Requirement.APP_INSTALLED }.map { grantAction(it) }

    /** A failed [ActionResult] turned into an honest Outcome that carries the grant buttons. */
    fun failed(r: ActionResult, fallback: String, icon: String = "alert"): Outcome {
        val text = r.message.ifBlank { fallback }
        return Outcome(false, text, AssistantReply.error(text, icon, r.missing, actionsFor(r.missing)))
    }

    fun fail(text: String, icon: String = "alert", missing: List<Requirement> = emptyList()): Outcome =
        Outcome.fail(text, icon, missing, actionsFor(missing))

    fun reply(
        text: String, icon: String = "check", card: Card? = null, kind: ReplyKind = ReplyKind.ACTION, sensitive: Boolean = false,
        actions: List<UiAction> = emptyList(), extras: Map<String, Any?> = emptyMap(), missing: List<Requirement> = emptyList(),
    ) = AssistantReply(text, ok = true, kind = kind, icon = icon, card = card, sensitive = sensitive, actions = actions, extras = extras, missing = missing)

    fun info(modelNote: String, reply: AssistantReply) = Outcome(true, modelNote, reply, info = true, verified = true)

    fun items(title: String, icon: String, rows: List<Pair<String, String>>) =
        Card("items", title, icon = icon, items = rows.map { CardItem(it.first, it.second) })

    fun levelCard(kind: String, label: String, value: Int, icon: String, muted: Boolean = false) =
        Card("level", label, icon = icon, fields = mapOf("kind" to kind, "value" to value.toString(), "muted" to muted.toString()))

    fun plural(n: Int, word: String) = "$n $word" + if (n == 1) "" else "s"

    /** "1 hour 30 minutes 5 seconds" */
    fun durationText(secs: Int): String {
        val h = secs / 3600
        val m = secs % 3600 / 60
        val s = secs % 60
        return listOfNotNull(
            if (h > 0) plural(h, "hour") else null,
            if (m > 0) plural(m, "minute") else null,
            if (s > 0) plural(s, "second") else null,
        ).joinToString(" ")
    }

    fun title(s: String) = s.replaceFirstChar { it.titlecase(Locale.ENGLISH) }

    /** When personal details are not to be sent to an AI provider, the model sees [redacted] instead of [full]. */
    fun modelNote(services: Services, full: String, redacted: String): String =
        if (services.settings.flag(Keys.AI_SHARE_PERSONAL, true)) full else redacted

    val URL_SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
    val DOMAIN = Regex("^(?:www\\.)?[a-z0-9][a-z0-9-]*(?:\\.[a-z0-9-]+)*\\.(?:com|org|net|io|co|in|edu|gov|app|dev|ai|tv|me|info|uk|de|fr|us|ca|au|xyz|news|tech)(?:[/:?#].*)?$", RegexOption.IGNORE_CASE)

    /** Only web links are opened by the assistant; every other scheme (tel:, sms:, intent:, file:, javascript:, content: ...) is refused. */
    fun safeWebUrl(target: String): String? {
        val t = target.trim().trim('"', '\'', '<', '>')
        if (t.isEmpty() || t.any { it.isWhitespace() }) return null
        if (URL_SCHEME.containsMatchIn(t)) {
            val scheme = t.substringBefore(':').lowercase()
            return if ((scheme == "http" || scheme == "https") && t.length > scheme.length + 3) t else null
        }
        return if (DOMAIN.matches(t)) "https://$t" else null
    }

    val AFFIRM = Regex("^(?:yes|yeah|yep|yup|sure|ok|okay|go ahead|do it|confirm|confirmed|send it|send|please do|correct|that'?s right|affirmative)$", RegexOption.IGNORE_CASE)
    val NEGATE = Regex("^(?:no|nope|nah|don'?t|do not|negative|stop|cancel|never ?mind)$", RegexOption.IGNORE_CASE)
    fun isYes(s: String) = AFFIRM.matches(s.trim(' ', '.', '!', '?', ','))
    fun isNo(s: String) = NEGATE.matches(s.trim(' ', '.', '!', '?', ','))

    // "the second one" contains both "second" and "one": ordinals win over bare number words
    private val ORDINALS = mapOf(
        "first" to 1, "1st" to 1, "second" to 2, "2nd" to 2, "third" to 3, "3rd" to 3, "fourth" to 4, "4th" to 4, "fifth" to 5, "5th" to 5,
    )
    private val CARDINALS = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5)

    /** Which of [options] did the user pick? By number ("2", "the second one") or by a distinctive word of its label. Null = unclear. */
    fun pickIndex(answer: String, options: List<String>): Int? {
        val a = answer.trim().lowercase().trim(' ', '.', '!', '?', ',')
        a.toIntOrNull()?.let { if (it in 1..options.size) return it - 1 }
        for (table in listOf(ORDINALS, CARDINALS))
            for ((w, n) in table) if (n <= options.size && Regex("\\b${Regex.escape(w)}\\b").containsMatchIn(a)) return n - 1
        val hits = options.indices.filter { i -> val o = options[i].lowercase(); o == a || (a.length >= 2 && o.contains(a)) }
        return if (hits.size == 1) hits[0] else null
    }
}
