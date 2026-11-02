package com.jarvis.core.offline

import com.jarvis.core.agent.Executed
import com.jarvis.core.engine.Dialog
import com.jarvis.core.greeting.Greeting
import com.jarvis.core.model.AssistantReply
import com.jarvis.core.model.Card
import com.jarvis.core.model.ReplyKind
import com.jarvis.core.organizer.Organizer
import com.jarvis.core.ports.Services
import com.jarvis.core.tools.ToolContext
import com.jarvis.core.tools.impl.Calculator
import com.jarvis.core.util.stripWake
import com.jarvis.core.time.TimeParser
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The offline command parser (port of Skills.py): understands simple commands without any AI ("set the volume to 40", "open spotify", "call mom",
 * "set a timer for five minutes") and turns them into TOOL INVOCATIONS. It does not do anything itself: the engine runs every [Step.Invoke] through the
 * same [com.jarvis.core.agent.ToolExecutor] the AI agent uses, so lock policy, permission checks and honest outcomes are identical online and offline.
 */
class OfflineParser(
    private val services: Services,
    private val organizer: Organizer,
    /** Runs a tool through the shared executor (needed by follow-up questions such as "should the volume go up or down?"). */
    private val run: suspend (String, Map<String, Any?>, ToolContext) -> Executed,
) {
    sealed class Step {
        data class Invoke(val tool: String, val args: Map<String, Any?>) : Step()
        data class Say(val reply: AssistantReply) : Step()
        /** Notes / reminders phrasing: handled by [Organizer] (multi-turn, deterministic). */
        data class Organize(val text: String) : Step()
    }

    /** [rest] = parts of a compound command that could not be understood offline (nothing was done for them). */
    class Plan(val steps: List<Step>, val rest: List<String>)

    private val I = RegexOption.IGNORE_CASE

    /** Null = not a command this parser understands (the request needs the AI). */
    fun plan(text: String, ctx: ToolContext): Plan? {
        val wake = services.settings.wakeWords()
        val raw = stripWake(text, wake)
        if (raw.isEmpty()) return null
        services.clock.now().toLocalDateTime().let { now ->
            Greeting.replyTo(text.trim(' ', '.', '!', '?', ',').lowercase(), now, services.settings.userName())?.let { g ->
                return Plan(listOf(Step.Say(AssistantReply(g, kind = ReplyKind.CHAT, icon = Greeting.icon(now)))), emptyList())
            }
        }
        val parts = split(raw)
        if (parts.size >= 2) {
            val steps = ArrayList<Step>()
            var restFrom = -1
            for ((i, p) in parts.withIndex()) {
                val one = one(p.lowercase(), p, ctx)
                if (one == null) { restFrom = i; break }
                steps += one
            }
            if (steps.isEmpty()) return null
            return Plan(steps, if (restFrom >= 0) parts.drop(restFrom) else emptyList())
        }
        val one = one(raw.lowercase(), raw, ctx) ?: return null
        return Plan(one, emptyList())
    }

    private fun inv(tool: String, vararg kv: Pair<String, Any?>) = listOf(Step.Invoke(tool, mapOf(*kv)))
    private fun say(text: String, icon: String = "message", kind: ReplyKind = ReplyKind.CHAT, card: Card? = null) =
        listOf(Step.Say(AssistantReply(text, kind = kind, icon = icon, card = card)))

    // ---------------------------------------------------------------------------------------------------- one command
    @Suppress("CyclomaticComplexMethod")
    private fun one(t: String, raw: String, ctx: ToolContext): List<Step>? {
        val now = services.clock.now().toLocalDateTime()
        // ---- time / date
        if (R_TIME.matches(t) || t == "what time is it" || t == "time please") {
            val c = TimeParser.clock12(now)
            return say("It's $c.", "clock", ReplyKind.ACTION, Card("info", c, now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH)), "clock"))
        }
        if (R_DATE.containsMatchIn(t)) {
            val d = now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH))
            return say("Today is $d.", "calendar", ReplyKind.ACTION, Card("info", now.format(DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH)), now.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)), "calendar"))
        }
        // ---- "I'm going to play GTA now": announcing something is a request to do it
        R_INTENT.matchEntire(t)?.let { m ->
            val verb = m.groupValues[1]
            val obj = m.groupValues[2].trim(' ', '.', '!', '?', ',').replace(Regex("^(?:the|my|a|some)\\s+"), "")
            if (verb == "listen to") return if (obj in setOf("music", "songs", "some music", "something", "a song", "song")) null else inv("play", "query" to obj)
            if (verb == "play" && obj.isNotEmpty() && services.apps.resolve(obj).isEmpty()) return inv("play", "query" to obj)
            if (obj.isNotEmpty() && services.apps.resolve(obj).isNotEmpty()) return inv("open", "target" to obj)
        }
        // ---- help / jokes
        if (R_HELP.containsMatchIn(t)) {
            return say("I can open apps and websites, play music, control volume, brightness, the flashlight and Do Not Disturb, set timers, alarms and reminders, keep notes, " +
                "call and message your contacts (you press send), read your notifications, check the weather, search the web, write content and make images. " +
                "I never delete anything and I never get around your lock screen.", "zap", ReplyKind.INFO)
        }
        if (R_JOKE.containsMatchIn(t)) return say(JOKES[(now.toLocalTime().toSecondOfDay() / 60) % JOKES.size], "smile")
        // ---- calculator
        try {
            Calculator.evaluateSpoken(t)?.let { return inv("calculate", "expression" to it.expression) }
        } catch (e: Calculator.CalcException) {
            return say("I can't divide by zero.", "alert", ReplyKind.ERROR)
        }
        // ---- weather
        if ((R_WEATHER.containsMatchIn(t) && !R_NOT_WEATHER.containsMatchIn(t)) || R_WEATHER2.containsMatchIn(t)) {
            val city = R_CITY.find(t)?.groupValues?.get(1)?.trim().orEmpty().takeIf { it !in setOf("the", "my area", "here", "this place") }.orEmpty()
            return inv("weather", "city" to city)
        }
        // ---- alarms (Clock app); notes/reminders phrasing below belongs to the Organizer
        R_ALARM.find(t)?.let { return inv("alarm", "time" to it.groupValues[1].ifEmpty { it.groupValues[2] }.trim()) }
        // ---- timers
        (R_TIMER.matchEntire(t) ?: R_TIMER2.matchEntire(t))?.let { return inv("timer", "action" to "start", "duration" to it.groupValues[1]) }
        if (R_TIMER_STOP.matches(t)) return inv("timer", "action" to "stop")
        if (R_TIMER_STATUS.containsMatchIn(t)) return inv("timer", "action" to "status")
        // ---- notifications
        if (R_READ_NOTIF.containsMatchIn(t)) return inv("read_notifications")
        R_REPLY.find(raw)?.let { m -> return inv("reply_notification", "app" to m.groupValues[1].trim(), "text" to m.groupValues[2].trim()) }
        // ---- reminders & notes
        if (organizer.related(raw)) return listOf(Step.Organize(raw))
        // ---- volume / brightness / mute
        if (R_TOGGLE_MUTE.matches(t)) return inv("system", "action" to "toggle_mute")
        if (R_MUTE.matches(t)) return inv("system", "action" to "mute")
        if (R_UNMUTE.matches(t)) return inv("system", "action" to "unmute")
        R_ADJUST.matchEntire(t)?.let { m -> return askAdjust(if (m.groupValues[1] == "sound") "volume" else m.groupValues[1], ctx) }
        (R_SET_LEVEL.matchEntire(t) ?: R_SET_LEVEL2.find(t))?.let { m ->
            val what = m.groupValues[1]
            return inv("system", "action" to "${if (what == "sound") "volume" else what}_set", "value" to m.groupValues[2])
        }
        R_LEVEL_STEP.matchEntire(t)?.let { m ->
            val g = m.groupValues.drop(1).filter { it.isNotEmpty() }
            val up = g.any { it in setOf("up", "increase", "raise", "louder", "brighter") }
            val bright = g.any { it in setOf("brightness", "brighter", "dimmer") }
            return inv("system", "action" to "${if (bright) "brightness" else "volume"}_${if (up) "up" else "down"}")
        }
        // ---- torch / DND
        R_TORCH.matchEntire(t)?.let { m -> val on = (m.groupValues[1].ifEmpty { m.groupValues[2] }).ifEmpty { "on" } != "off"; return inv("system", "action" to if (on) "flashlight_on" else "flashlight_off") }
        R_DND.matchEntire(t)?.let { m -> val on = (m.groupValues[1].ifEmpty { m.groupValues[2] }) != "off"; return inv("system", "action" to if (on) "dnd_on" else "dnd_off") }
        // ---- media
        if (R_NOW_PLAYING.containsMatchIn(t)) return inv("media", "action" to "now_playing")
        R_MEDIA.matchEntire(t)?.let { m ->
            val w = (m.groupValues[1].ifEmpty { m.groupValues[2] })
            return inv("media", "action" to if (t == "play pause") "toggle" else if (w == "pause") "pause" else "resume")
        }
        if (R_NEXT.matches(t)) return inv("media", "action" to "next")
        if (R_PREV.matches(t)) return inv("media", "action" to "previous")
        // ---- phone / navigation
        if (R_SCREENSHOT.containsMatchIn(t)) return inv("navigate", "action" to "screenshot")
        if (R_LOCK.matches(t)) return inv("navigate", "action" to "lock")
        if (R_HOME.matches(t)) return inv("navigate", "action" to "home")
        if (R_BACK.matches(t)) return inv("navigate", "action" to "back")
        if (R_RECENTS.matches(t)) return inv("navigate", "action" to "recents")
        if (R_SHADE.matches(t)) return inv("navigate", "action" to "notifications")
        if (R_QS.matches(t)) return inv("navigate", "action" to "quick_settings")
        if (R_CLIPBOARD.matches(t)) return inv("system", "action" to "clipboard")
        if (R_NETWORK.containsMatchIn(t)) return inv("system", "action" to "network")
        if (R_BATTERY.containsMatchIn(t)) return inv("system", "action" to "battery")
        if (R_SPECS.containsMatchIn(t)) return inv("system", "action" to "specs")
        R_SETTINGS.matchEntire(t)?.let { m -> return inv("open", "target" to "${m.groupValues[1]} settings") }
        // ---- AI mode / mic
        R_AI_MODE.matchEntire(t)?.let { return inv("jarvis", "action" to "ai_mode", "value" to it.groupValues[1]) }
        if (R_MIC_OFF.matches(t)) return inv("jarvis", "action" to "mic_off")
        if (R_MIC_ON.matches(t)) return inv("jarvis", "action" to "mic_on")
        // ---- calls & messages
        R_MSG1.matchEntire(raw)?.let { m -> return inv("message", "app" to m.groupValues[1].lowercase(), "to" to m.groupValues[2].trim(), "text" to m.groupValues[3].trim()) }
        R_MSG2.matchEntire(raw)?.let { m -> return inv("message", "app" to m.groupValues[1].lowercase(), "to" to m.groupValues[2].trim(), "text" to m.groupValues[3].trim()) }
        R_CALL.matchEntire(t)?.let { m -> val who = m.groupValues[1].trim(); if (who.isNotEmpty() && who !in setOf("me", "it", "back")) return inv("call", "contact" to who) }
        // ---- open / play / search
        R_OPEN.matchEntire(t)?.let { m ->
            val body = m.groupValues[1].trim()
            R_IN_BROWSER.matchEntire(body)?.let { b ->
                val where = b.groupValues[2].trim()
                if (services.apps.browsers().any { it.label.equals(where, true) || it.label.lowercase().contains(where) }) return inv("open", "target" to b.groupValues[1].trim(), "in_app" to where)
            }
            return inv("open", "target" to body)
        }
        R_PLAY.matchEntire(t)?.let { m ->
            val body = m.groupValues[1].trim()
            R_ON_APP.matchEntire(body)?.let { b -> if (services.apps.resolve(b.groupValues[2].trim()).isNotEmpty()) return inv("play", "query" to b.groupValues[1].trim(), "where" to b.groupValues[2].trim()) }
            return inv("play", "query" to body)
        }
        R_YT.matchEntire(t)?.let { m -> return inv("search_in_browser", "query" to (m.groupValues[1].ifEmpty { m.groupValues[2] }).trim(), "site" to "youtube") }
        R_SEARCH.matchEntire(t)?.let { m ->
            if (!R_SEARCH_NOT.containsMatchIn(t)) return inv("search_in_browser", "query" to m.groupValues[1].trim(), "site" to "google")
        }
        return null
    }

    // ---------------------------------------------------------------------------------------------------- "adjust the volume" follow-up
    private fun askAdjust(what: String, ctx: ToolContext): List<Step> {
        fun handler(tries: Int): suspend (String) -> AssistantReply? = handler@{ raw ->
            val t = raw.trim(' ', '.', '!', '?', ',').lowercase()
            val digits = Regex("\\b(\\d{1,3})\\b").find(t)?.groupValues?.get(1)
            val (action, value) = when {
                digits != null -> "${what}_set" to digits
                Regex("\\b(up|higher|more|increase|raise|louder|brighter)\\b").containsMatchIn(t) -> "${what}_up" to ""
                Regex("\\b(down|lower|less|decrease|reduce|quieter|dimmer)\\b").containsMatchIn(t) -> "${what}_down" to ""
                Regex("\\b(max|maximum|full)\\b").containsMatchIn(t) -> "${what}_set" to "100"
                Regex("\\b(half|middle)\\b").containsMatchIn(t) -> "${what}_set" to "50"
                else -> {
                    if (tries < 1) {
                        ctx.dialog.ask(what, handler = handler(tries + 1))
                        return@handler AssistantReply("Sorry, say up, down, or a number from 0 to 100.", kind = ReplyKind.CHAT, icon = "help-circle", extras = mapOf("expect_reply" to true))
                    }
                    return@handler AssistantReply("Okay, I've left it as it is.", kind = ReplyKind.CHAT, icon = "help-circle")
                }
            }
            run("system", mapOf("action" to action, "value" to value), ctx).outcome.reply
        }
        ctx.dialog.ask(what, handler = handler(0))
        return listOf(Step.Say(AssistantReply("Sure. Should I turn the $what up, down, or set it to a number?", kind = ReplyKind.CHAT,
            icon = if (what == "volume") "volume-2" else "sun", extras = mapOf("expect_reply" to true))))
    }

    // ---------------------------------------------------------------------------------------------------- compound commands
    /**
     * "open chrome and spotify, then set volume to 30 and brightness to 50" → separate commands. A piece starts a new command only if it really is one
     * (starts with a command word, names a real app after open/launch, or is "volume/brightness ..." after "set"); anything else stays with the command
     * before it, so "make a note buy milk and eggs", "play simon and garfunkel" and "open tom and jerry" are never cut in half.
     */
    fun split(raw: String): List<String> {   // public so tests can reach it (internal is not visible across the offline compile units)
        val pieces = SEP.split(raw)
        val seps = SEP.findAll(raw).map { it.value }.toList()
        if (pieces.size < 2) return listOf(raw.trim())
        val parts = arrayListOf(pieces[0].trim())
        for ((idx, nxtRaw) in pieces.drop(1).withIndex()) {
            val sep = seps.getOrElse(idx) { " " }
            val nxt = nxtRaw.trim()
            if (nxt.isEmpty()) continue
            val cur = parts.last()
            if (FREE_TEXT.containsMatchIn(cur) && !STRONG.containsMatchIn(sep)) { parts[parts.lastIndex] = cur + sep + nxt; continue }
            if (VERB_RE.containsMatchIn(nxt)) { parts += nxt; continue }
            val verb = cur.split(Regex("\\s+")).firstOrNull()?.lowercase().orEmpty()
            if (verb in OBJECT_VERBS && services.apps.resolve(nxt).isNotEmpty()) parts += "$verb $nxt"
            // "open spotify and explain quantum physics": the app is real, the rest is a sentence, not an app name → a separate (unknown) command
            else if (verb in OBJECT_VERBS && nxt.split(Regex("\\s+")).size >= 3 && services.apps.resolve(cur.substringAfter(' ', "").trim()).isNotEmpty()) parts += nxt
            else if (verb == "set" && Regex("^(?:the\\s+)?(?:volume|brightness)\\b", I).containsMatchIn(nxt)) parts += "set $nxt"
            else parts[parts.lastIndex] = cur + sep + nxt
        }
        return parts
    }

    companion object {
        private val JOKES = listOf(
            "Why do programmers prefer dark mode? Because light attracts bugs.",
            "I would tell you a UDP joke, but you might not get it.",
            "There are only 10 kinds of people: those who understand binary and those who don't.",
            "A SQL query walks into a bar, walks up to two tables and asks: can I join you?",
            "Why did the developer go broke? Because he used up all his cache.",
            "I told my phone I needed a break, and it said: no problem, I'll go to sleep.",
        )
        private val I = RegexOption.IGNORE_CASE
        private fun r(p: String) = Regex(p)
        private val R_TIME = r("""(?:what(?:'s| is) the |what time is it|tell me the |whats the )?(?:current )?time(?: is it)?(?: now| right now)?""")
        private val R_DATE = r("""\b(what(?:'s| is) (?:the )?(?:today'?s )?date|what day is (?:it|today)|today'?s date)\b""")
        private const val FILLER = """(?:(?:well|so|ok|okay|alright|actually|hmm+|umm*|yeah|yes|right|then|anyway|now)[,.\s]+)*"""
        private const val PLAN = """(?:i(?:'m| am| will|'ll)?\s*)?(?:(?:plann?ing|going|about|gonna|thinking|trying|ready|willing|plan|want|wanna|need|have|got|gotta|would like|feel like|in the mood|wish)\s*(?:to|for|of)?\s*)+|let'?s\s+|lets\s+|time to\s+|time for me to\s+|i(?:'ll| will)\s+"""
        private val R_INTENT = r("""^$FILLER(?:$PLAN)(play|open|launch|start|run|watch|use|listen to|check out|check|try|fire up|load|go to)\s+(.+?)(?:\s+(?:now|today|right now|real quick|tonight|later|for a bit|for a while|please|jarvis))*[.!?\s]*""")
        private val R_HELP = r("""\b(what can you do|what you can do|help me|show (?:me )?(?:your )?(?:commands|features|capabilities)|your capabilities)\b""")
        private val R_JOKE = r("""\btell me a joke\b|\bmake me laugh\b|^joke$""")
        private val R_WEATHER = r("""\bweather\b|\bforecast\b|\btemperature\b""")
        private val R_NOT_WEATHER = r("""\b(?:open|settings|app|widget)\b""")
        private val R_WEATHER2 = r("""^(?:is it|will it) (?:going to )?(?:rain|raining|hot|cold|sunny)""")
        private val R_CITY = r("""\b(?:in|at|for|of|near)\s+([a-z][a-z .'-]{1,40}?)(?=\s+(?:today|now|tonight|tomorrow|right now|please)\b|$)""")
        private val R_ALARM = r("""^(?:set|create|add|make)\s+(?:an?\s+|the\s+|my\s+)?alarm(?:\s+(?:for|at))?\s+(.+)$|^wake me(?: up)?\s+(?:at|by)\s+(.+)$""")
        private val R_TIMER = r("""^(?:set|start|create)\s+(?:a\s+|an\s+)?(?:timer|countdown)(?:\s+for)?\s+(.+)$""")
        private val R_TIMER2 = r("""^timer\s+(?:for\s+)?(.+)$""")
        private val R_TIMER_STOP = r("""^(?:stop|cancel|end)\s+(?:the\s+|my\s+|all\s+)?timers?$""")
        private val R_TIMER_STATUS = r("""^(?:how much time (?:is )?left|check (?:the )?timer|timer status)""")
        private val R_READ_NOTIF = r("""^(?:read|show|check)(?: me)?(?: my| the)?(?: new| latest| recent)? notifications?$|^what(?:'s| are) my notifications$|^do i have (?:any )?(?:new )?notifications$""")
        private val R_REPLY = Regex("""^reply\s+(?:to\s+(.+?)\s+)?(?:with|saying|:)\s*(.+)$""", RegexOption.IGNORE_CASE)
        private val R_TOGGLE_MUTE = r("""^(?:toggle mute|mute/unmute)$""")
        private val R_MUTE = r("""^(?:mute|silence)(?: (?:the )?(?:volume|sound|audio|phone))?$|^(?:volume|sound) mute$|^turn off (?:the )?(?:sound|volume)$""")
        private val R_UNMUTE = r("""^unmute(?: (?:the )?(?:volume|sound|audio))?$|^(?:volume|sound) unmute$|^turn on (?:the )?(?:sound|volume)$""")
        private val R_ADJUST = r("""^(?:adjust|change|modify|fix|control)\s+(?:the\s+|my\s+)?(volume|brightness|sound)$""")
        private val R_SET_LEVEL = r("""^(?:set )?(?:the )?(volume|brightness|sound)(?: to| at)?\s+(\d{1,3})\s*(?:%|percent)?$""")
        private val R_SET_LEVEL2 = r("""^(?:set|change|turn)\s+(?:the\s+)?(volume|brightness|sound)\s+(?:to|at)\s+(\d{1,3})""")
        private val R_LEVEL_STEP = r("""^(?:turn |make it |bring )?(?:the )?(?:volume|sound|brightness)\s+(up|down)$|^(increase|decrease|raise|lower|reduce)\s+(?:the )?(?:volume|sound|brightness)$|^(louder|quieter|brighter|dimmer)$""")
        private val R_TORCH = r("""^(?:turn |switch )?(on|off)(?: the)? (?:flash ?light|torch)$|^(?:flash ?light|torch)(?: (on|off))?$""")
        private val R_DND = r("""^(?:turn |switch )?(on|off)(?: the)? (?:do not disturb|dnd|silent mode)$|^(?:do not disturb|dnd) (on|off)$""")
        private val R_NOW_PLAYING = r("""^(?:what(?:'s| is) playing|which song is (?:this|playing)|what song is (?:this|playing)|what(?:'s| is) this song)""")
        private val R_MEDIA = r("""^(play|pause|resume)(?: the)?(?: music| media| song| video)?$|^play pause$|^(pause|resume) (?:music|media|song|video)$""")
        private val R_NEXT = r("""^(?:next|skip)(?: (?:track|song|video))?$|^skip (?:this )?(?:track|song)$""")
        private val R_PREV = r("""^(?:previous|last)(?: (?:track|song))?$|^previous (?:track|song)$""")
        private val R_SCREENSHOT = r("""^(?:take|capture|grab|get|make)?\s*(?:a |an |the )?(?:screen\s?shot|screen capture)\b""")
        private val R_LOCK = r("""^lock(?: (?:the |my )?(?:screen|phone))?$""")
        private val R_HOME = r("""^(?:go|take me)(?: to)?(?: the)? home(?: screen)?$|^home(?: screen)?$""")
        private val R_BACK = r("""^go back$|^press back$""")
        private val R_RECENTS = r("""^(?:show |open )?(?:the )?recent apps$|^recents$|^app switcher$""")
        private val R_SHADE = r("""^(?:pull down |open |show )?(?:the )?notification (?:shade|panel|drawer)$|^pull down (?:the )?notifications$""")
        private val R_QS = r("""^(?:open |show |pull down )?(?:the )?quick settings$""")
        private val R_CLIPBOARD = r("""^(?:what(?:'s| is) on my clipboard|read (?:my )?clipboard|what did i copy|show (?:my )?clipboard)$""")
        private val R_NETWORK = r("""\b(?:am i (?:online|connected)|internet (?:status|connection)|check (?:my )?(?:internet|connection)|wi-?fi status)\b""")
        private val R_BATTERY = r("""\b(?:battery|how much charge)\b""")
        private val R_SPECS = r("""^(?:tell me about|describe|about|show me) (?:my |this )?(?:phone|device)$|^(?:phone|device) (?:specs?|info|information|details)$|^(?:my )?(?:phone|device) (?:specs?|info)$|^device info$""")
        private val R_SETTINGS = r("""^(?:open|show|go to) (?:the )?(wi-?fi|bluetooth|display|sound|network|battery|apps|notifications|location|accessibility|storage|privacy|date and time|language|developer|default apps) settings$""")
        private val R_AI_MODE = r("""^(?:switch to|use|go|turn on|enable|set ai to)\s+(?:the\s+)?(local|offline|cloud|online|auto|automatic)(?: ai| mode| model)?$""")
        private val R_MIC_OFF = r("""^(?:turn off|switch off|disable|stop) (?:the |my )?(?:mic|microphone|listening)$|^stop listening$""")
        private val R_MIC_ON = r("""^(?:turn on|switch on|enable|start) (?:the |my )?(?:mic|microphone|listening)$|^start listening$""")
        private val R_MSG1 = Regex("""^(?:send|write)\s+(?:a\s+)?(?:(whatsapp|telegram|sms|text)\s+)?(?:message\s+)?to\s+(.+?)\s+(?:saying|that says|:)\s*(.+)$""", RegexOption.IGNORE_CASE)
        private val R_MSG2 = Regex("""^(whatsapp|telegram|text|sms)\s+(.+?)\s+(?:saying|that says|:)\s*(.+)$""", RegexOption.IGNORE_CASE)
        private val R_CALL = r("""^(?:call|phone|dial|ring)\s+(.+)$""")
        private val R_OPEN = r("""^(?:open|launch|start|run)\s+(.+)$""")
        private val R_IN_BROWSER = r("""(.+?)\s+(?:in|on|with|using)\s+(.+)""")
        private val R_PLAY = r("""^play\s+(.+)$""")
        private val R_ON_APP = r("""(.+?)\s+(?:on|in|using)\s+([a-z0-9 ]+)""")
        private val R_YT = r("""^(?:search|find|look up)\s+(.+?)\s+(?:on|in) youtube$|^youtube(?: search)?\s+(.+)$""")
        private val R_SEARCH = r("""^(?:search|google|look up|look for|find)\s+(?:for\s+)?(.+?)(?:\s+(?:on|in) google)?$""")
        private val R_SEARCH_NOT = r("""\b(?:on|in) youtube$|^find (?:my|the) """)

        private val VERBS = listOf(
            "switch to", "use", "turn", "open", "launch", "start", "close", "switch", "go to", "go", "stop", "volume", "mute", "unmute", "increase", "decrease", "lower", "raise",
            "brightness", "take", "show", "list", "lock", "play", "pause", "next", "previous", "remind", "what", "tell", "search", "google", "youtube", "read", "calculate", "make",
            "call", "text", "message", "send", "set", "reply", "flashlight", "torch",
        )
        private val VERB_RE = Regex("^(?:" + VERBS.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) + "\\b" } + ")", RegexOption.IGNORE_CASE)
        private val SEP = Regex("""(\s*(?:,\s*and then|,\s*then|\band then\b|\bthen\b|\band also\b|\balso\b|\bplus\b|;|&|\band\b|,)\s+)""", RegexOption.IGNORE_CASE)
        private val STRONG = Regex("then|also|plus|;", RegexOption.IGNORE_CASE)
        private val FREE_TEXT = Regex(
            "^(?:remind|make a note|take a note|add a note|add a reminder|set a reminder|note\\b|remember|search|google|youtube|play|write|tell|what|who|how|why|calculate|type|send|message|text\\b|whatsapp|telegram|call|reply)",
            RegexOption.IGNORE_CASE,
        )
        private val OBJECT_VERBS = setOf("open", "launch", "start", "run", "close")
    }
}
