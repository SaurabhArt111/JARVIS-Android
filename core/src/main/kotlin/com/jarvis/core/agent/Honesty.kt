package com.jarvis.core.agent

import java.util.regex.Pattern

/**
 * The honesty guards (ports of Context.claims_action, Tools.needs_tool, Agent.garbled, Chatbot.clean).
 *
 * The chat model can only TALK. If its answer says it opened / set / sent something but no tool ran in the turn, that is a false claim
 * and the agent loop either forces a real tool call or tells the user plainly that nothing happened.
 */
object Honesty {
    private fun re(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private const val VERBS = "set|changed|raised|lowered|increased|decreased|turned|muted|unmuted|opened|closed|started|stopped|paused|resumed|played|skipped|moved|snapped|" +
        "saved|sent|created|scheduled|added|updated|locked|adjusted|switched|launched|cancelled|canceled|removed|written|generated|called|dialed|dialled|messaged|texted|replied|" +
        "enabled|disabled|dimmed|brightened"

    private val CLAIM = re(
        "\\b(?:i(?:'ve| have| had|'ll| will)?\\s+(?:just\\s+|now\\s+|already\\s+)?(?:added|created|set|scheduled|rescheduled|updated|saved|noted|moved|changed|edited|marked|cancell?ed|booked)\\b[^.\\n]{0,80}\\b(?:reminders?|notes?|alarms?|timers?|schedule)\\b" +
            "|(?:reminders?|notes?|alarms?)\\b[^.\\n]{0,40}\\b(?:has been|have been|is now|was|were)\\s+(?:added|set|created|saved|updated|rescheduled|moved|scheduled))",
    )
    private val DONE = re(
        "\\bi(?:'ve| have|'m| am|'ll| will)\\s+(?:just\\s+|now\\s+|already\\s+|currently\\s+)?(?:opened|opening|launched|launching|started|starting|closed|closing|muted|muting|unmuted|locked|locking|" +
            "played|playing|turned|turning|switched|switching|sent|sending|ran|running|taken|taking|searched|searching|called|calling|dialed|dialling|dialing|messaged|messaging|texted|replied|replying)\\b",
    )
    private val DOING = re(
        "^\\W*(?:(?:sure|okay|ok|alright|certainly|of course|got it|right away|absolutely|done|yes|yep)[,!.\\s]+)*" +
            "(?:opening|launching|starting|closing|playing|muting|unmuting|locking|turning|switching|setting|sending|running|taking|searching|calling|dialing|messaging|" +
            "opened|launched|closed|muted|unmuted|locked|switched|started)\\b",
    )
    private val TALK = re("\\b(?:is|are|was|were|can|could|helps?|requires?|means?|makes?|would|should|may|might|usually|often)\\b")
    private val PASSIVE = re(
        "\\b(?:has|have|had|is|are|was|were)\\s+(?:now\\s+|been\\s+|just\\s+|successfully\\s+|already\\s+)*(?:$VERBS)\\b|" +
            "\\bI(?:'ve|\\s+have|\\s+just|'ll\\s+have)?\\s+(?:now\\s+|just\\s+)?(?:$VERBS)\\b|\\ball set\\b|\\bnow (?:at|set to) \\d+|" +
            "\\b(?:volume|brightness|timer|reminder|alarm|note|flashlight)\\s+(?:is\\s+)?(?:$VERBS)\\b",
    )

    /** True when a chat answer says it changed something or did something on the phone. A plain answer can do none of that, so such a claim is false. */
    fun claimsAction(answer: String?): Boolean {
        val a = (answer ?: "").trim()
        if (a.isEmpty()) return false
        // A clause that denies ("nothing was opened", "I haven't set it") is the opposite of a claim.
        val clauses = CLAUSES.split(a).filter { it.isNotBlank() && !NEGATED.containsMatchIn(it) }
        if (clauses.isEmpty()) return false
        if (clauses.any { CLAIM.containsMatchIn(it) || DONE.containsMatchIn(it) || PASSIVE.containsMatchIn(it) }) return true
        return a.length <= 100 && DOING.containsMatchIn(a) && !TALK.containsMatchIn(a)   // short "Opening Spotify. Enjoy." - but not "Playing the piano is relaxing."
    }

    private val CLAUSES = Regex("(?<=[.!?;\\n])\\s+|,?\\s+(?:but|although|though|however)\\s+|\\s+[-—–]\\s+")
    private val NEGATED = re("\\b(?:nothing|not|never|none|neither|nor|no longer)\\b|n't\\b")

    const val HONEST_FALLBACK =
        "I haven't actually done that — I can't change anything from a chat answer, and nothing was opened or changed. " +
            "Tell me as a command: 'open Spotify', 'set the volume to 40', 'remind me to call mom at 5 pm', 'make a note …'."

    // ------------------------------------------------------------------------------------------------ needs a tool
    private const val LEAD = "^\\W*(?:(?:hey|ok|okay|hi|yo)[\\s,]+)?(?:jarvis[\\s,]+)?(?:please[\\s,]+)?(?:(?:can|could|would|will) you[\\s,]+(?:please[\\s,]+)?)?(?:go ahead and[\\s,]+)?"
    private val DO = re(
        LEAD + "(?:open|launch|close|quit|play|pause|resume|skip|next|previous|mute|unmute|set|turn|raise|lower|increase|decrease|remind|take|lock|put|switch|" +
            "send|call|dial|text|message|whatsapp|start|save|generate|draw|add|stop|show|go (?:home|back)|read (?:my |the )?(?:notifications?|messages|notes|reminders|clipboard)|reply)\\b|" +
            "^\\W*(?:make|create)\\s+(?:me\\s+)?(?:a|an|the)?\\s*(?:note|image|picture|photo|reminder|timer|alarm|screenshot)\\b",
    )
    private val ASK = re(
        "\\b(?:my|the)\\s+(?:reminders?|notes?|timers?|battery|clipboard|notifications?|phone|device|system)\\b|" +
            "\\b(?:which|what)(?:'s|\\s+is|\\s+song|\\s+music)?\\s+(?:song|music|track|playing)\\b|\\bwhat(?:'s|\\s+is)\\s+(?:playing|on my)\\b|\\bweather\\b|" +
            "\\b(?:is|are) there (?:any|a)\\s+(?:reminders?|alarm|notes?)\\b|\\bany (?:reminders?|alarms?|notifications?)\\b",
    )

    /** True when the request can only be done (or truthfully answered) by a tool: "set the volume to 40", "what are my reminders?". */
    fun needsTool(text: String?): Boolean {
        val t = (text ?: "").trim()
        if (t.isEmpty()) return false
        return DO.containsMatchIn(t) || ASK.containsMatchIn(t)
    }

    // ------------------------------------------------------------------------------------------------ output hygiene
    /** Degenerate model output (a wall of dots, one word repeated, mostly punctuation): never show or speak it. */
    fun garbled(text: String?): Boolean {
        val t = (text ?: "").trim()
        if (t.length < 30) return false
        val alnum = t.count { it.isLetterOrDigit() }
        if (alnum.toDouble() / t.length < 0.45) return true
        if (Pattern.compile("(?:\\.{2,}\\s*){6,}|(.)\\1{14,}").matcher(t).find()) return true
        val words = Regex("\\w+").findAll(t.lowercase()).map { it.value }.toList()
        return words.size >= 12 && words.toSet().size.toDouble() / words.size < 0.2
    }

    private val CLEAN_TAGS = Regex("</?s>|<br\\s*/?>")
    private val BLANKS = Regex("\\n{3,}")

    fun clean(text: String?): String = BLANKS.replace(CLEAN_TAGS.replace(text ?: "", "").replace("**", "").replace("*", ""), "\n\n").trim()

    /** Sentences in a tool reply that are caveats ("I can't confirm", "tap send ..."). */
    val HEDGE = re("\\b(?:can'?t confirm|can'?t tell|can'?t see|doesn'?t confirm|didn'?t confirm|not confirmed|not sent|has not been sent|tap send|tap call|check the|check it|please check|isn'?t confirmed|may be late|a few minutes late)\\b")

    fun hedgeSentences(text: String): String =
        text.split(Regex("(?<=[.!?])\\s+")).filter { HEDGE.containsMatchIn(it) }.joinToString(" ").trim()
}
