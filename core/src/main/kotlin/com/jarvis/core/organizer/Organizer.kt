package com.jarvis.core.organizer

import com.jarvis.core.model.ActionType
import com.jarvis.core.model.AssistantReply
import com.jarvis.core.model.Card
import com.jarvis.core.model.CardItem
import com.jarvis.core.model.ReplyKind
import com.jarvis.core.model.Requirement
import com.jarvis.core.model.UiAction
import com.jarvis.core.ports.Note
import com.jarvis.core.ports.Reminder
import com.jarvis.core.ports.Services
import com.jarvis.core.time.Parsed
import com.jarvis.core.time.TimeParser
import com.jarvis.core.tools.ToolContext
import kotlinx.coroutines.CancellationException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Notes & reminders: ONE place that understands everything you can say about them (port of Backend/Organizer.py).
 *
 *     add / list / reschedule / edit / complete / cancel a reminder     add / list / edit / append to a note
 *     "make a note ... at 5 pm" (the note ALSO becomes a reminder)       "make that note a reminder"
 *     "reschedule it", "push it by 10 minutes", "make it 5 am"           "reschedule the note", "reschedule again"
 *
 * Works offline and deterministically: the AI is never asked to guess what is in the notes or reminders, and nothing here claims
 * to have done something that was not really written (the services read back what they store and verify the alarm).
 *
 * [handle] returns a reply, or null when the text is not about notes / reminders. Follow-up questions ("What time?") are
 * registered on the shared [com.jarvis.core.engine.Dialog] so the next utterance is treated as the answer.
 * Replies that quote the user's own notes / reminders are marked `sensitive` (the lock-screen policy hides them).
 */
class Organizer(
    private val services: Services,
    private val reminders: ReminderService = ReminderService(services),
    private val notes: NoteService = NoteService(services),
) {
    // ===================================================================================================== public API
    /** Does this text talk about notes / reminders? (a safety net so such requests never reach the chat AI blindly) */
    fun related(text: String): Boolean = RELATED.containsMatchIn(text)

    suspend fun handle(raw: String, ctx: ToolContext): AssistantReply? {
        val r = raw.trim()
        if (r.isEmpty() || (!related(r) && !ENTRY.containsMatchIn(r))) return null
        val t = r.lowercase().trim(' ', '.', '!', '?', ',')
        for (h in ORDER) {
            val reply = try {
                h(this, r, t, ctx)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                services.log.e("Organizer", "handler failed on a request: ${e.javaClass.simpleName}: ${e.message}", e)
                return AssistantReply.error("Sorry, I hit a problem with that. Nothing was changed.")
            }
            if (reply != null) return reply
        }
        return null
    }

    // ===================================================================================================== tiny helpers
    private fun nowZ(): ZonedDateTime = services.clock.now()
    private fun zone(): ZoneId = services.clock.zone()

    private fun chat(text: String, icon: String = "message", card: Card? = null, display: String? = null, expect: Boolean = false, sensitive: Boolean = false) =
        AssistantReply(
            text, kind = ReplyKind.CHAT, icon = icon, card = card, sensitive = sensitive,
            extras = buildMap {
                if (display != null) put("display_text", display)
                if (expect) put("expect_reply", true)
            },
        )

    private fun done(text: String, icon: String, title: String, subtitle: String, extraActions: List<UiAction> = emptyList(), missing: List<Requirement> = emptyList()) =
        AssistantReply(
            text, kind = ReplyKind.ACTION, icon = icon, card = Card("info", title, subtitle, icon), sensitive = true,
            actions = extraActions, missing = missing,
        )

    private fun askUser(ctx: ToolContext, text: String, label: String, icon: String = "help-circle", handler: suspend (String) -> AssistantReply?): AssistantReply {
        ctx.dialog.ask(label, handler = handler)
        return chat(text, icon, expect = true)
    }

    private fun itemsCard(title: String, icon: String, rows: List<Pair<String, String>>) =
        Card("items", title, icon = icon, items = rows.map { CardItem(it.first, it.second) })

    private class Item(val kind: String, val id: String, val text: String, val rem: Reminder? = null, val note: Note? = null) {
        val noteReminderId: String? get() = note?.reminderId
    }

    private fun itemOf(r: Reminder) = Item("reminder", r.id, r.text, rem = r)
    private fun itemOf(n: Note) = Item("note", n.id, n.text, note = n)

    private fun short(i: Item, now: LocalDateTime): String =
        if (i.kind == "note") (if (i.text.length <= 60) i.text else i.text.take(58) + "…") else "${i.text} (${TimeParser.nice(i.rem!!.due, now)})"

    private fun inexactAdvice(a: AddResult): Pair<String, List<UiAction>> =
        if (a.inexact) {
            " Android may deliver it a few minutes late because exact alarms aren't allowed for JARVIS yet." to
                listOf(UiAction("Allow exact alarms", ActionType.GRANT, mapOf("requirement" to Requirement.EXACT_ALARM.name)))
        } else "" to emptyList()

    // ----------------------------------------------------------------------------------------- item selection
    private class Sel(val item: Item?, val cands: List<Item> = emptyList(), val missing: String = "")

    private suspend fun pool(kind: String): List<Item> =
        if (kind == "note") notes.all().map(::itemOf) else reminders.upcoming().map(::itemOf)

    private fun subjectOf(s: String?): String {
        var x = (s ?: "").trim()
        repeat(3) { x = LEAD_SUBJECT.replace(x, "") }
        x = REMINDER_WORD.replace(x, "")
        x = WS.replace(x, " ").trim(' ', ',', '.', ';', ':', '-')
        return if (x.lowercase() in EMPTY_SUBJECTS) "" else x
    }

    /** 'call mom' -> 'to call mom'; 'the meeting' -> 'about the meeting'. */
    private fun phrase(text: String): String {
        val t = text.trim()
        return if (ABOUT_START.containsMatchIn(t)) "about " + ABOUT_PREFIX.replace(t, "") else "to $t"
    }

    /** The words that NAME an item ('the reminder about milk' -> 'milk'), without verbs, ordinals and time words. */
    private fun query(t: String): String {
        val p = TimeParser.parse(t, nowZ())
        val base = if (p.hasTime || p.dateOnly) p.rest else t
        return WORD_Q.findAll(base.lowercase()).map { it.value }.filter { it !in NOISE && !it.all(Char::isDigit) }.joinToString(" ")
    }

    private fun ordinal(t: String): Any? {
        if (LAST.containsMatchIn(t)) return "last"
        val m = ORD_RX.find(t) ?: return null
        return ORD[m.groupValues[1]]
    }

    private fun words(s: String?): Set<String> = WORD.findAll((s ?: "").lowercase()).map { it.value }.filter { it !in NOISE && it.length > 1 }.toSet()

    private fun match(pool: List<Item>, query: String): List<Item> {
        val q = words(query)
        if (q.isEmpty()) return emptyList()
        val scored = pool.map { (q intersect words(it.text)).size.toDouble() / q.size to it }.filter { it.first > 0 }.sortedByDescending { it.first }
        if (scored.isEmpty()) return emptyList()
        val top = scored.first().first
        return scored.filter { it.first == top }.map { it.second }
    }

    /** Work out WHICH note / reminder is meant. `missing` is the name the user gave when nothing matches it: we then say so instead of guessing. */
    private suspend fun select(ctx: ToolContext, kind: String, t: String): Sel {
        val pool = pool(kind)
        val lastId = if (ctx.dialog.fresh()) ctx.dialog.last[kind] else null
        val o = ordinal(t)
        if (o == "last") {
            if (kind == "reminder") {
                val all = reminders.all()
                return Sel(all.maxByOrNull { it.createdAt }?.let(::itemOf))
            }
            return Sel(pool.lastOrNull())
        }
        if (o is Int && pool.isNotEmpty()) return if (o < pool.size) Sel(pool[o]) else Sel(null, missing = TOO_FEW)
        val q = query(t)
        if (q.isNotEmpty()) {
            var hit = match(pool, q)
            if (hit.isEmpty() && kind == "reminder") hit = match(reminders.all().map(::itemOf), q)
            if (hit.size == 1) return Sel(hit[0])
            if (hit.isNotEmpty()) return Sel(null, hit.take(4))
            return Sel(null, missing = q)
        }
        if (lastId != null) {
            val it = if (kind == "note") notes.get(lastId)?.let(::itemOf) else reminders.get(lastId)?.let(::itemOf)
            if (it != null) return Sel(it)
        }
        if (pool.size == 1) return Sel(pool[0])
        return Sel(null, pool.take(4))
    }

    private fun kindOf(t: String, ctx: ToolContext, default: String = "reminder"): String {
        if (NOTE_WORD.containsMatchIn(t) && !REM_WORD.containsMatchIn(t)) return "note"
        if (REM_WORD.containsMatchIn(t)) return "reminder"
        return if (ctx.dialog.fresh()) ctx.dialog.last["kind"] ?: default else default
    }

    private fun choose(ctx: ToolContext, kind: String, cands: List<Item>, cont: suspend (Item) -> AssistantReply): AssistantReply {
        val now = nowZ().toLocalDateTime()
        val lines = cands.take(4).mapIndexed { i, c -> "${i + 1}, ${short(c, now)}" }.joinToString("; ")
        return askUser(ctx, "Which $kind do you mean? $lines.", "which $kind", handler = chooseHandler(ctx, kind, cands, cont, 0))
    }

    private fun chooseHandler(ctx: ToolContext, kind: String, cands: List<Item>, cont: suspend (Item) -> AssistantReply, tries: Int): suspend (String) -> AssistantReply? = { raw ->
        val t = raw.trim(' ', '.', '!', '?', ',').lowercase()
        val m = PICK.find(t)
        var pick: Item? = null
        if (m != null && ORD.getValue(m.groupValues[1]) < cands.size) {
            pick = cands[ORD.getValue(m.groupValues[1])]
        } else {
            val hit = match(cands, t)
            pick = if (hit.size == 1) hit[0] else null
        }
        when {
            pick != null -> cont(pick)
            tries < 1 -> {
                ctx.dialog.ask("which $kind", handler = chooseHandler(ctx, kind, cands, cont, 1))
                chat("Sorry, which one? Say the number, like 'the first one'.", "help-circle", expect = true)
            }
            else -> chat("I'm not sure which one you mean, so I've left everything as it is.", "help-circle")
        }
    }

    private fun noItems(kind: String, missing: String = ""): AssistantReply = when {
        missing == TOO_FEW -> chat("You don't have that many ${if (kind == "reminder") "upcoming reminders" else "notes"}. Nothing was changed.", "help-circle")
        missing.isNotEmpty() -> chat("I couldn't find a $kind about $missing. Nothing was changed.", "help-circle", sensitive = true)
        else -> chat("You don't have any ${if (kind == "reminder") "upcoming reminders" else "notes"} to change.", if (kind == "reminder") "bell" else "file-text")
    }

    // ----------------------------------------------------------------------------------------- time resolution + asking
    /** state: ok | past | date | none */
    private class When(val state: String, val at: LocalDateTime?, val p: Parsed)

    private fun whenFrom(text: String, now: ZonedDateTime, old: LocalDateTime?): When {
        val p = TimeParser.parse(text, now)
        val nowL = now.toLocalDateTime()
        val shift = SHIFT.find(text.trim())
        if (shift != null && old != null && !p.relative) {          // "by 10 minutes" / "for 15 minutes" / "10 minutes" (not "in 10 minutes")
            val secs = TimeParser.parseDurationSeconds(shift.groups[1]?.value ?: shift.groups[2]?.value)
            if (secs != null && secs > 0) {
                val base = if (old.isAfter(nowL)) old else nowL
                val w = base.atZone(now.zone).plusSeconds(secs.toLong()).toLocalDateTime()
                return When("ok", w, p.copy(whenAt = w, hasTime = true, relative = true))
            }
        }
        if (p.pastToday) return When("past", (p.whenAt ?: nowL).plusDays(1), p)
        if (p.dateOnly) return When("date", p.whenAt, p)
        if (p.ok && p.hasTime) {
            var w = p.whenAt!!
            if (old != null && !p.hasDate && !p.relative && old.toLocalDate().isAfter(nowL.toLocalDate())) {
                w = LocalDateTime.of(old.toLocalDate(), w.toLocalTime())      // "make it 5 am" keeps the reminder's own day
                if (!w.isAfter(nowL)) w = p.whenAt!!
            }
            return When("ok", w, p)
        }
        return When("none", null, p)
    }

    private suspend fun withTime(
        ctx: ToolContext, text: String, onOk: suspend (LocalDateTime, Parsed) -> AssistantReply, askText: String, label: String,
        old: LocalDateTime? = null, tries: Int = 0, date: LocalDate? = null,
    ): AssistantReply {
        val now = nowZ()
        val nowL = now.toLocalDateTime()
        val w = whenFrom(text, now, old)
        when (w.state) {
            "ok" -> {
                val at = if (date != null) LocalDateTime.of(date, w.at!!.toLocalTime()) else w.at!!
                return onOk(at, w.p)
            }
            "past" -> {
                val at = w.at!!
                val said = TimeParser.nice(at.minusDays(1), nowL).replace(" today", "")      // (the Python passed now-1day here and said "9:00 AM tomorrow has already passed today")
                return askUser(ctx, "$said has already passed today. Should I set it for tomorrow instead?", "confirm tomorrow") { raw ->
                    when {
                        YES.containsMatchIn(raw.trim()) -> onOk(at, w.p)
                        NO.containsMatchIn(raw.trim()) ->
                            askUser(ctx, "Okay. What time should I use instead?", label) { r2 -> withTime(ctx, r2, onOk, askText, label, old) }
                        else -> withTime(ctx, raw, onOk, askText, label, old)
                    }
                }
            }
            "date" -> {
                val day = TimeParser.dayPhrase(w.at!!.toLocalDate(), nowL)
                val d = w.at.toLocalDate()
                return askUser(ctx, "What time on $day?", label) { r2 -> withTime(ctx, r2, onOk, askText, label, old, date = d) }
            }
        }
        return askUser(ctx, askText, label) { raw ->
            val again = whenFrom(raw, nowZ(), old)
            if (again.state == "none" && tries >= 1) {
                chat("I still couldn't catch a time, so I've left it. Try again with something like 'remind me to call mom at 5 pm'.", "help-circle")
            } else {
                withTime(ctx, raw, onOk, "Sorry, what time? For example 5 pm, or in 20 minutes.", label, old, tries + 1, date)
            }
        }
    }

    // ===================================================================================================== reminders: create
    private suspend fun create(ctx: ToolContext, at: LocalDateTime, text: String, noteId: String? = null, assumed: List<String> = emptyList(), isAlarm: Boolean = false): AssistantReply {
        val now = nowZ().toLocalDateTime()
        val dup = reminders.upcoming().firstOrNull { sameMinute(it.due, at) && it.text.trim().equals(text.trim(), ignoreCase = true) }
        val nice = TimeParser.nice(at, now)
        if (dup != null) {
            ctx.dialog.remember("reminder", dup.id)
            return chat("You already have that reminder, $nice.", "bell", sensitive = true)
        }
        val res = reminders.add(text, at, isAlarm, noteId)
        val rem = res.reminder
        if (!res.ok || rem == null) return AssistantReply.error("I couldn't set that reminder. ${res.error ?: ""} Nothing was created.".replace("  ", " "), "bell")
        ctx.dialog.remember("reminder", rem.id, "Set a reminder ${phrase(text)} for $nice")
        var msg = if (isAlarm && text in ALARM_TEXTS) "Okay, I've set an alarm for $nice." else "Okay, I'll remind you ${phrase(text)} at $nice."
        if ("tomorrow" in assumed) msg += " That time had already passed today, so it's for tomorrow."
        else if (assumed.isNotEmpty()) msg += " I took that as ${assumed[0].split(" ").last()}. Say 'make it ${if (assumed[0].endsWith("PM")) "AM" else "PM"}' to change it."
        val (advice, actions) = inexactAdvice(res)
        return done(msg + advice, "bell", "Reminder set", "$text  ·  $nice", actions, if (res.inexact) listOf(Requirement.EXACT_ALARM) else emptyList())
    }

    private fun sameMinute(a: LocalDateTime, b: LocalDateTime) = a.truncatedTo(ChronoUnit.MINUTES) == b.truncatedTo(ChronoUnit.MINUTES)

    // ===================================================================================================== handlers
    private suspend fun hAddReminder(raw: String, t: String, ctx: ToolContext): AssistantReply? {
        val alarm = ALARM.find(raw)
        var body: String
        val fallback: String
        if (alarm != null) {
            body = alarm.groups["rest"]?.value ?: ""
            fallback = if (t.startsWith("wake")) "Wake up" else "Alarm"
        } else {
            val m = ADD_REM.find(raw) ?: return null
            body = listOf("a", "pre", "post", "b").mapNotNull { m.groups[it]?.value }.filter { it.isNotEmpty() }.joinToString(" ")
            fallback = ""
        }
        val isAlarm = alarm != null
        var p = TimeParser.parse(body, nowZ())
        if (fallback.isNotEmpty() && p.hour12 != null && !MERIDIEM_WORD.containsMatchIn(body)) {
            body = body.trimEnd() + " am"                           // "wake me up at 7" means 7 in the morning
            p = TimeParser.parse(body, nowZ())
        }
        val subject = subjectOf(p.rest).ifEmpty { fallback }
        fun make(subj: String): suspend (LocalDateTime, Parsed) -> AssistantReply = { at, pp -> create(ctx, at, subj, assumed = pp.assumed, isAlarm = isAlarm) }
        if (subject.isEmpty()) return askSubject(ctx, body, 0, ::make)
        return withTime(ctx, body, make(subject), "When should I remind you ${phrase(subject)}? For example at 5 pm, or in 20 minutes.", "reminder time")
    }

    private fun askSubject(ctx: ToolContext, knownBody: String, tries: Int, make: (String) -> suspend (LocalDateTime, Parsed) -> AssistantReply): AssistantReply =
        askUser(ctx, if (tries == 0) "What should I remind you about?" else "Sorry, what should I remind you about?", "reminder subject") { ans ->
            val sp = TimeParser.parse(ans, nowZ())
            val hasWhen = sp.hasTime || sp.dateOnly
            val subj = subjectOf(if (hasWhen) sp.rest else ans)
            val src = if (hasWhen) ans else knownBody
            if (subj.isEmpty()) {
                if (tries >= 1) chat("I still don't know what to remind you about, so I've left it. Try 'remind me to call mom at 5 pm'.", "help-circle")
                else askSubject(ctx, src, tries + 1, make)          // keep the time they already gave
            } else {
                withTime(ctx, src, make(subj), "When should I remind you ${phrase(subj)}?", "reminder time")
            }
        }

    private suspend fun hListReminders(raw: String, t: String, ctx: ToolContext): AssistantReply? {
        if (!REMINDERS_WORD.containsMatchIn(t)) return null
        if (!LIST_START.containsMatchIn(t)) return null
        val nowZ = nowZ()
        val now = nowZ.toLocalDateTime()
        val doneQ = DONE_WORDS.containsMatchIn(t)
        var items = if (doneQ) reminders.recentDone(6) else reminders.upcoming()
        var day: LocalDate? = null
        if (TODAY.containsMatchIn(t)) day = now.toLocalDate() else if (TOMORROW.containsMatchIn(t)) day = now.toLocalDate().plusDays(1)
        if (day != null && !doneQ) items = items.filter { it.due.toLocalDate() == day }
        val label = if (day == now.toLocalDate()) "today" else if (day != null) "tomorrow" else ""
        val kind = if (doneQ) "completed" else "upcoming"
        val pt = TimeParser.parse(t, nowZ)          // "is there any reminder at 3:30 pm today?" -> answer yes / no for THAT time
        if (pt.ok && pt.hasTime && !pt.relative && !doneQ) {
            val at = pt.whenAt!!
            val hit = items.filter { it.due.hour == at.hour && it.due.minute == at.minute }
            val atText = TimeParser.clock12(at)
            val sfx = if (label.isNotEmpty()) " $label" else ""
            if (hit.isNotEmpty()) {
                val rows = hit.take(4).map { it.text to TimeParser.nice(it.due, now) }
                return chat("Yes - ${rows.joinToString("; ") { it.first }}, at $atText$sfx.", "bell", itemsCard("Reminder", "bell", rows), sensitive = true)
            }
            return chat("No, you have no reminder at $atText$sfx.", "bell")
        }
        if (items.isEmpty()) {
            return chat("You have no $kind reminders${if (label.isNotEmpty()) " for $label" else ""}." + (if (doneQ) "" else " Say 'remind me to ... at ...' to add one."), "bell")
        }
        val rows = items.take(6).map { it.text to TimeParser.nice(it.due, now) }
        if (items.size == 1) ctx.dialog.remember("reminder", items[0].id)
        val spoken = rows.take(4).joinToString("; ") { "${it.first}, ${it.second}" }
        val n = items.size
        val head = "You have $n $kind reminder${if (n != 1) "s" else ""}${if (label.isNotEmpty()) " for $label" else ""}."
        return chat(
            "${head.dropLast(1)}: $spoken." + (if (n > 4) " And more." else ""), "bell",
            itemsCard("Reminders" + (if (label.isNotEmpty()) " · $label" else ""), "bell", rows), display = head, sensitive = true,
        )
    }

    private suspend fun applyResched(ctx: ToolContext, kind: String, item: Item, at: LocalDateTime, p: Parsed?): AssistantReply {
        val now = nowZ().toLocalDateTime()
        if (kind == "note") {
            val note = item.note!!
            val rem = note.reminderId?.let { reminders.get(it) }
            if (rem != null) {
                val res = reminders.rescheduleChecked(rem.id, at)
                if (!res.ok) return failedMove(res)
                ctx.dialog.remember("reminder", rem.id); ctx.dialog.remember("note", note.id)
                return reschedResult(rem.text, rem.due, at, now, p, res)
            }
            val res = reminders.add(note.text, at, noteId = note.id)
            val created = res.reminder
            if (!res.ok || created == null) return failedMove(res)
            notes.linkReminder(note.id, created.id)
            ctx.dialog.remember("reminder", created.id); ctx.dialog.remember("note", note.id, "Made a reminder for a note at ${TimeParser.nice(at, now)}")
            val (advice, actions) = inexactAdvice(res)
            return done("That note had no reminder yet, so I made one for ${TimeParser.nice(at, now)}.$advice", "bell", "Reminder set",
                "${note.text.take(70)}  ·  ${TimeParser.nice(at, now)}", actions)
        }
        val rem = item.rem!!
        val res = reminders.rescheduleChecked(rem.id, at)
        if (!res.ok) return failedMove(res)
        ctx.dialog.remember("reminder", rem.id)
        return reschedResult(rem.text, rem.due, at, now, p, res)
    }

    private fun failedMove(res: AddResult) = AssistantReply.error("I couldn't do that. ${res.error ?: ""} Nothing was changed.".replace("  ", " "), "bell")

    private fun reschedResult(text: String, old: LocalDateTime, new: LocalDateTime, now: LocalDateTime, p: Parsed?, res: AddResult): AssistantReply {
        var msg = "Done. I moved '$text' from ${TimeParser.nice(old, now)} to ${TimeParser.nice(new, now)}."
        if (p != null && p.assumed.isNotEmpty() && "tomorrow" !in p.assumed) msg += " I took that as ${p.assumed[0].split(" ").last()}."
        val (advice, actions) = inexactAdvice(res)
        return done(msg + advice, "bell", "Rescheduled", "$text  ·  ${TimeParser.nice(new, now)}", actions)
    }

    private suspend fun hReschedule(raw: String, t0: String, ctx: ToolContext): AssistantReply? {
        val t = RESCHEDULED_ONE.replace(t0, "it")
        val m = RESCHED_VERB.find(t) ?: return null
        val verb = m.groupValues[1].split(" ")[0]
        val rest = m.groupValues[2]
        val noun = NOUN.containsMatchIn(rest)
        val pron = PRON.containsMatchIn(rest)
        val hasLast = ctx.dialog.fresh()
        val nowZ = nowZ()
        val timed = TimeParser.parse(rest, nowZ)
        val shifty = SHIFTY.containsMatchIn(rest)
        val hasPayload = timed.hasTime || timed.dateOnly || shifty
        val definite = DEFINITE.containsMatchIn(rest)
        val indefinite = INDEFINITE.containsMatchIn(rest)
        if (APP_WORDS.containsMatchIn(rest)) return null            // "snooze the alarm clock app" is not about a reminder
        when (verb) {
            in RESCHED_VERBS -> if (!(noun || pron || rest.isBlank() || hasPayload)) return null
            in MOVE_VERBS -> if (!(noun || (pron && hasLast && hasPayload))) return null
            "change", "update", "edit", "make" ->
                if (!((noun && (hasPayload || verb != "make") && (definite || verb != "make")) || (pron && hasLast && hasPayload))) return null
            else -> if (!(pron && hasLast && hasPayload)) return null   // "set": only the pronoun form ("set it to 6 pm") - "set a reminder ..." is new
        }
        if (indefinite && !definite) return null                    // "make a note ..." / "set a reminder ..." create something new
        val kind = kindOf(rest, ctx)
        val sel = select(ctx, kind, rest)
        val item = sel.item

        if (kind == "note" && !hasPayload && verb in setOf("change", "update", "edit") && (item != null || sel.cands.isNotEmpty())) {
            val reword: suspend (Item) -> AssistantReply = { it ->
                askUser(ctx, "What should the note say instead?", "note new text", "file-text") { ans ->
                    notes.edit(it.id, ans.trim())
                    ctx.dialog.remember("note", it.id, "Edited a note")
                    done("Updated the note: ${ans.trim()}", "file-text", "Note updated", ans.trim())
                }
            }
            return if (item != null) reword(item) else choose(ctx, "note", sel.cands, reword)
        }

        val go: suspend (Item) -> AssistantReply = { it ->
            val old: LocalDateTime? = if (kind == "note") it.noteReminderId?.let { id -> reminders.get(id) }?.due else it.rem?.due
            val pay = if (verb == "snooze" && !hasPayload) "10 minutes" else rest
            withTime(ctx, pay, { w, pp -> applyResched(ctx, kind, it, w, pp) },
                "What time should I move ${if (kind == "note") "that $kind" else "it"} to?", "$kind new time", old = old)
        }
        if (item != null) return go(item)
        if (sel.cands.isNotEmpty()) return choose(ctx, kind, sel.cands, go)
        return noItems(kind, sel.missing)
    }

    private suspend fun hComplete(raw: String, t: String, ctx: ToolContext): AssistantReply? {
        if (INDEFINITE_REM.containsMatchIn(t)) return null
        val cancel = CANCEL_START.containsMatchIn(t)
        val doneIt = (MARK_START.containsMatchIn(t) && DONE_WORD.containsMatchIn(t)) || COMPLETE_START.containsMatchIn(t) || DONE_REM.containsMatchIn(t)
        if (!(cancel || doneIt)) return null
        if (TIMER_WORD.containsMatchIn(t)) return null
        val noun = REMINDER_OR_ALARM.containsMatchIn(t)
        val pron = IT_WORD.containsMatchIn(t)
        if (!(noun || (pron && ctx.dialog.fresh() && ctx.dialog.last["kind"] == "reminder"))) return null
        val sel = select(ctx, "reminder", t)
        val go: suspend (Item) -> AssistantReply = { it ->
            val rem = it.rem!!
            if (cancel) {
                val r = reminders.cancel(rem.id)
                ctx.dialog.remember("reminder", rem.id)
                if (r == null) AssistantReply.error("I couldn't cancel '${rem.text}'. Nothing was changed.")
                else done("Cancelled the reminder '${rem.text}'. It stays in your reminders list in case you want it back.", "x-circle", "Reminder cancelled", rem.text)
            } else {
                val r = reminders.markDone(rem.id)
                ctx.dialog.remember("reminder", rem.id)
                if (r == null) AssistantReply.error("I couldn't update '${rem.text}'. Nothing was changed.")
                else done("Marked '${rem.text}' as done.", "check-circle", "Reminder done", rem.text)
            }
        }
        if (sel.item != null) return go(sel.item)
        if (sel.cands.isNotEmpty()) return choose(ctx, "reminder", sel.cands, go)
        return noItems("reminder", sel.missing)
    }

    private suspend fun hEditText(raw: String, t: String, ctx: ToolContext): AssistantReply? {
        val m = EDIT_TEXT.find(raw) ?: return null
        val kind = m.groupValues[1].lowercase()
        val rest = m.groups["rest"]?.value ?: ""
        val mk = NEW_TEXT.find(rest) ?: return null
        val explicit = EXPLICIT_NEW.containsMatchIn(rest)
        val new = (mk.groups["new"]?.value ?: mk.groups["new2"]?.value ?: mk.groups["new3"]?.value ?: "").trim()
        if (new.isEmpty()) return null
        if (!explicit) {
            val np = TimeParser.parse(new, nowZ())
            if (kind == "reminder" && (np.hasTime || np.dateOnly)) return null     // "change the reminder to 5 pm" is a reschedule
            if (kind == "note" && REMIND_RESCHED.containsMatchIn(rest)) return null
        }
        val which = m.value.dropLast(rest.length) + rest.substring(0, mk.range.first)
        val sel = select(ctx, kind, which.lowercase())
        val go: suspend (Item) -> AssistantReply = { it ->
            if (kind == "note") {
                if (notes.edit(it.id, new) == null) AssistantReply.error("I couldn't update that note. Nothing was changed.")
                else {
                    ctx.dialog.remember("note", it.id, "Edited a note")
                    done("Updated the note: $new", "file-text", "Note updated", new)
                }
            } else {
                if (reminders.setText(it.id, new) == null) AssistantReply.error("I couldn't update that reminder. Nothing was changed.")
                else {
                    ctx.dialog.remember("reminder", it.id, "Edited a reminder")
                    done("Updated the reminder. It now says: $new", "bell", "Reminder updated", new)
                }
            }
        }
        if (sel.item != null) return go(sel.item)
        if (sel.cands.isNotEmpty()) return choose(ctx, kind, sel.cands, go)
        return noItems(kind, sel.missing)
    }

    // ===================================================================================================== notes
    private suspend fun saveNote(ctx: ToolContext, bodyIn: String): AssistantReply {
        val body = bodyIn.trim()
        if (body.isEmpty()) return chat("Sure. What should the note say?", "file-text")
        val nowZ = nowZ()
        val now = nowZ.toLocalDateTime()
        val p = TimeParser.parse(body, nowZ)
        val note = notes.add(body)
        ctx.dialog.remember("note", note.id, "Saved a note: ${body.take(80)}")
        var extra = ""
        var cardSub = body
        var withReminder = false
        var actions: List<UiAction> = emptyList()
        if (p.ok && p.hasTime && !p.dateOnly) {
            val at = p.whenAt!!
            if (p.pastToday) {
                extra = " The time in it has already passed today, so I didn't set a reminder."
            } else {
                val subj = subjectOf(p.rest).ifEmpty { body }
                val dup = reminders.upcoming().firstOrNull { sameMinute(it.due, at) && it.text.trim().equals(subj.trim(), ignoreCase = true) }
                val res = if (dup != null) AddResult(dup, null, null) else reminders.add(subj, at, noteId = note.id)
                val rem = res.reminder
                if (!res.ok || rem == null) {
                    extra = " I couldn't set a reminder for it: ${res.error ?: "unknown problem"}"
                } else {
                    notes.linkReminder(note.id, rem.id)
                    ctx.dialog.remember("reminder", rem.id); ctx.dialog.remember("note", note.id, "Saved a note and set a reminder for ${TimeParser.nice(at, now)}")
                    extra = " I also set a reminder for ${TimeParser.nice(at, now)}."
                    withReminder = true
                    if ("tomorrow" in p.assumed) extra += " That time had already passed today."
                    else if (p.assumed.isNotEmpty()) extra += " I took it as ${p.assumed[0].split(" ").last()}."
                    val (advice, acts) = inexactAdvice(res)
                    extra += advice; actions = acts
                    cardSub = "$body  ·  reminder ${TimeParser.nice(at, now)}"
                }
            }
        }
        return done("Noted: $body.$extra", "file-text", "Note saved" + (if (withReminder) " + reminder" else ""), cardSub, actions)
    }

    private suspend fun hAddNote(raw: String, t: String, ctx: ToolContext): AssistantReply? {
        var body: String? = null
        for (rx in ADD_NOTE) {
            val m = rx.find(raw)
            if (m != null) { body = (m.groups["body"]?.value ?: "").trim(); break }
        }
        if (body == null) return null
        if (body.lowercase() in setOf("", "it", "this")) {
            return askUser(ctx, "Sure. What should the note say?", "note text", "file-text") { ans -> saveNote(ctx, ans) }
        }
        return saveNote(ctx, body)
    }

    private suspend fun hAppendNote(raw: String, t: String, ctx: ToolContext): AssistantReply? {
        val m = APPEND_NOTE.find(raw) ?: return null
        val extra = (m.groups["extra"]?.value ?: "").trim()
        val q = m.groups["q"]?.value
        val sel = select(ctx, "note", (if (q == null) "last note " else "note ") + (q ?: "").lowercase())
        if (sel.item == null && sel.cands.isEmpty()) return noItems("note", sel.missing)
        val go: suspend (Item) -> AssistantReply = { it ->
            val n = notes.append(it.id, extra)
            if (n == null) AssistantReply.error("I couldn't add to that note. Nothing was changed.")
            else {
                ctx.dialog.remember("note", it.id, "Added to a note")
                done("Added that to your note: ${n.text}", "file-text", "Note updated", n.text)
            }
        }
        return if (sel.item != null) go(sel.item) else choose(ctx, "note", sel.cands, go)
    }

    private suspend fun hNoteToReminder(raw: String, t: String, ctx: ToolContext): AssistantReply? {
        val m = NOTE_TO_REM.find(t) ?: return null
        val sel = select(ctx, "note", t.replace("reminder", ""))
        val rest = m.groups["rest"]?.value ?: ""
        val go: suspend (Item) -> AssistantReply = { it ->
            val rp = TimeParser.parse(rest, nowZ())
            val src = if (rp.hasTime || rp.dateOnly) rest else it.text
            withTime(ctx, src, { w, pp -> applyResched(ctx, "note", it, w, pp) }, "When should I remind you about that note?", "note reminder time")
        }
        if (sel.item != null) return go(sel.item)
        return if (sel.cands.isNotEmpty()) choose(ctx, "note", sel.cands, go) else noItems("note", sel.missing)
    }

    private suspend fun hListNotes(raw: String, t: String, ctx: ToolContext): AssistantReply? {
        if (!NOTES_WORD.containsMatchIn(t)) return null
        if (!NOTE_LIST_START.containsMatchIn(t)) return null
        // "what is a note in music?" is a general question; the user's own notes are named with my/the/any/latest... (or "show notes")
        if (!(OWN_NOTES.containsMatchIn(t) || NOTES_DO_I.containsMatchIn(t) || SHOW_NOTES.containsMatchIn(t))) return null
        val all = notes.all()
        if (all.isEmpty()) return chat("You don't have any notes yet. Say 'make a note' followed by what to write, and I'll save it.", "file-text")
        val q = query(NOTES_WORD_ANY.replace(t, "")).split(" ").filter { it.isNotEmpty() && it !in LIST_NOISE }.joinToString(" ")
        val found = if (q.isNotEmpty()) notes.find(q) else emptyList()
        val onlyLast = LAST_NOTE.containsMatchIn(t) && !PLURAL_NOTES.containsMatchIn(t)
        if (q.isNotEmpty() && found.isEmpty()) {
            return chat("I couldn't find a note about $q. You have ${all.size} note${if (all.size != 1) "s" else ""} in total.", "file-text", sensitive = true)
        }
        val shown = found.ifEmpty { notes.latest(if (onlyLast) 1 else 5) }
        val fmt = DateTimeFormatter.ofPattern("dd MMM, hh:mm a", Locale.ENGLISH)
        val rows = ArrayList<Pair<String, String>>()
        for (n in shown.take(6)) {
            var sub = fmt.format(n.createdAt.atZone(zone())).trimStart('0')
            val r = n.reminderId?.let { reminders.get(it) }
            if (r != null) sub += "  ·  reminder ${TimeParser.nice(r.due, nowZ().toLocalDateTime())}"
            rows.add(n.text to sub)
        }
        if (shown.size == 1) ctx.dialog.remember("note", shown[0].id)
        if (onlyLast) return chat("Your last note: ${rows[0].first}", "file-text", itemsCard("Notes", "file-text", rows), display = "Your last note:", sensitive = true)
        val spoken = rows.take(4).mapIndexed { i, r -> "${i + 1}, ${r.first}" }.joinToString(" ... ")
        val total = all.size
        val text = if (q.isNotEmpty()) "Here ${if (shown.size == 1) "is" else "are"} what I found about $q: $spoken."
        else "You have $total note${if (total != 1) "s" else ""}. ${if (total > 1) "The latest" else "It says"}: $spoken."
        val head = if (":" in text) text.substringBefore(":") + ":" else text
        return chat(text, "file-text", itemsCard("Notes", "file-text", rows), display = head, sensitive = true)
    }

    companion object {
        private val I = RegexOption.IGNORE_CASE
        private val IS = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)

        val YES = Regex("^(?:yes|yeah|yep|yup|ya|haan|han|sure|ok|okay|please|do it|go ahead|correct|right|that's right|tomorrow|fine|alright)\\b", I)
        val NO = Regex("^(?:no|nope|nah|na|nahi|don't|do not|not)\\b", I)

        private val NOISE = ("""reschedule rescheduled re-schedule postpone snooze delay push back move shift change update edit make set the my that this it again
reminder reminders alarm note notes to by for about regarding of called named first last next latest newest recent previous second third
one please minutes minute mins min hours hour hrs seconds later more another extra from now at on in and with a an is was as into turn convert mark save
done complete completed finished cancel skip dismiss drop tick check off""").split(Regex("\\s+")).toSet()
        private val LIST_NOISE = "what whats what's which show list tell read check see open give do does did i have has any how many me you are there on all of when is my the latest last recent".split(" ").toSet()
        private val ORD = mapOf(
            "first" to 0, "1st" to 0, "earliest" to 0, "soonest" to 0, "next" to 0, "second" to 1, "2nd" to 1, "third" to 2, "3rd" to 2, "fourth" to 3, "4th" to 3,
            "one" to 0, "two" to 1, "three" to 2, "four" to 3, "1" to 0, "2" to 1, "3" to 2, "4" to 3,
        )
        private const val TOO_FEW = "\u0000too-few"
        private val ALARM_TEXTS = setOf("Alarm", "Wake up")

        private val RELATED = Regex("\\b(?:remind(?:er|ers|me)?|notes?|alarm|re-?schedul\\w*|postpone|snooze)\\b", I)
        private val ENTRY = Regex("\\b(?:remember|jot|write down|wake me|snooze|postpone)\\b|^(?:reschedule|re-schedule|push|move|shift|make it|change it|set it)\\b", I)
        private val LAST = Regex("\\b(?:last|latest|newest|most recent|recent|previous|just)\\b", I)
        private val ORD_RX = Regex("\\b(first|1st|earliest|soonest|next|second|2nd|third|3rd|fourth|4th)\\b")
        private val PICK = Regex("\\b(first|1st|one|1|second|2nd|two|2|third|3rd|three|3|fourth|4th|four|4)\\b")
        private val WORD = Regex("[a-z0-9]+")
        private val WORD_Q = Regex("[a-z0-9']+")
        private val WS = Regex("\\s+")
        private val LEAD_SUBJECT = Regex("^(?:me to|me|to|that|about|for|of|on|regarding|and)\\s+", I)
        private val REMINDER_WORD = Regex("\\b(?:a |the |my )?reminders?\\b", I)
        private val EMPTY_SUBJECTS = setOf("", "it", "something", "me", "that", "this", "alarm", "a", "an", "the")
        private val ABOUT_START = Regex("^(?:the|a|an|my|our|your|about)\\b", I)
        private val ABOUT_PREFIX = Regex("^about\\s+", I)
        private val NOTE_WORD = Regex("\\bnotes?\\b")
        private val REM_WORD = Regex("\\b(?:reminders?|alarm)\\b")
        private val SHIFT = Regex("\\b(?:by|for|another|extra|more)\\s+(${TimeParser.DURATION})|^\\s*(${TimeParser.DURATION})\\s*$", I)
        private val SHIFTY = Regex("\\b(?:by|for)\\s+${TimeParser.DURATION}", I)

        private val ADD_REM = Regex(
            "^(?:(?:remind me|remind us)\\b(?<a>.*)|(?:set|add|create|make|schedule|new|put|save)\\s+(?:(?:an?|new|another|one|my)\\s+)?(?<pre>[^,]*?)\\breminder\\b(?<post>.*)|reminder\\b[:,]?(?<b>.*))$",
            IS,
        )
        private val ALARM = Regex("^(?:(?:set|create|make)\\s+(?:an?\\s+)?alarm\\b|wake me(?: up)?\\b)(?<rest>.*)$", I)
        private val MERIDIEM_WORD = Regex("\\b(?:a\\.?m|p\\.?m|morning|afternoon|evening|night|noon)\\b", I)
        private val REMINDERS_WORD = Regex("\\breminders?\\b")
        private val LIST_START = Regex(
            "^(?:what|whats|what's|which|show|list|tell|read|check|any|do i have|have i|how many|see|give|when|is (?:there|their|they're|it)|are (?:there|their)|my|the|upcoming|today|next|open)\\b",
        )
        private val DONE_WORDS = Regex("\\b(?:done|completed|finished|past|missed|earlier|old|fired|went off)\\b")
        private val TODAY = Regex("\\btoday\\b|\\btonight\\b")
        private val TOMORROW = Regex("\\btomorrow\\b")

        private val RESCHEDULED_ONE = Regex("\\bthe re-?scheduled(?: one| reminder| note)?\\b|\\bthe reschedule\\b")
        private val RESCHED_VERB = Regex("^(?:please\\s+)?(reschedule|re-schedule|postpone|snooze|delay|push(?: back)?|move|shift|change|update|edit|make|set)\\b(.*)$")
        private val NOUN = Regex("\\b(reminder|alarm|note)s?\\b")
        private val PRON = Regex("\\b(?:it|that|this|again|one)\\b")
        private val DEFINITE = Regex("\\b(?:the|that|this|last|next|first|latest|same|second|third|my)\\s+(?:\\w+\\s+)?(?:reminder|alarm|note)\\b")
        private val INDEFINITE = Regex("\\b(?:an?|new|another)\\s+(?:\\w+\\s+)?(?:reminder|alarm|note)\\b")
        private val APP_WORDS = Regex("\\b(?:app|apps|application|download|downloads|window|windows|clock app)\\b")
        private val RESCHED_VERBS = setOf("reschedule", "re-schedule", "postpone", "snooze", "delay")
        private val MOVE_VERBS = setOf("push", "move", "shift")

        private val INDEFINITE_REM = Regex("\\b(?:an?|new|another)\\s+(?:\\w+\\s+)?(?:reminder|alarm)\\b")
        private val CANCEL_START = Regex("^(?:please\\s+)?(?:cancel|skip|dismiss|drop)\\b")
        private val MARK_START = Regex("^(?:please\\s+)?(?:mark|set)\\b")
        private val DONE_WORD = Regex("\\b(?:as\\s+)?(?:done|complete|completed|finished)\\b")
        private val COMPLETE_START = Regex("^(?:please\\s+)?(?:complete|finish|tick off|check off|done with)\\b")
        private val DONE_REM = Regex("^(?:i(?:'ve| have)?\\s+)?(?:done|finished|completed)\\b.*\\breminders?\\b")
        private val TIMER_WORD = Regex("\\btimers?\\b")
        private val REMINDER_OR_ALARM = Regex("\\b(?:reminders?|alarm)\\b")
        private val IT_WORD = Regex("\\b(?:it|that|this)\\b")

        private val EDIT_TEXT = Regex(
            "^(?:please\\s+)?(?:edit|change|update|rename|modify|correct|rewrite|fix)\\s+(?:the\\s+|my\\s+|that\\s+|this\\s+)?(?:(?:last|latest|first|next|previous|recent|second|third)\\s+)?(reminder|note)\\b(?<rest>.*)$",
            IS,
        )
        private val NEW_TEXT = Regex("\\b(?:to say|to read|saying|says|to be|as)\\b[\\s:]*(?<new>.+)$|:\\s*(?<new2>.+)$|\\bto\\s+(?<new3>.+)$", IS)
        private val EXPLICIT_NEW = Regex("\\b(?:to say|to read|saying|says)\\b|:", I)
        private val REMIND_RESCHED = Regex("\\b(?:remind|reschedule)\\b", I)

        private val ADD_NOTE = listOf(
            Regex("^(?:take|make|add|create|write|save|jot|start|new)\\s+(?:a\\s+|an\\s+|one\\s+|new\\s+)?(?:quick\\s+)?note\\b\\s*(?:that|to|:|about|saying|says|-|,|for)?\\s*(?<body>.*)$", IS),
            Regex("^note\\s+(?:down\\s+)?(?:that\\s+)?(?<body>.+)$", IS),
            Regex("^(?:write|jot|put|note)\\s+down\\s+(?:that\\s+)?(?<body>.+)$", IS),
            Regex("^remember\\s+(?:that\\s+)?(?<body>.+)$", IS),
            Regex("^notes?\\s*:\\s*(?<body>.+)$", IS),
        )
        private val APPEND_NOTE = Regex(
            "^(?:add|append|put)\\s+(?<extra>.+?)\\s+(?:to|in|into|onto)\\s+(?:the\\s+|my\\s+)?(?:(?:last|latest|previous|recent)\\s+)?note(?:\\s+(?:about|for)\\s+(?<q>.+))?$", IS,
        )
        private val NOTE_TO_REM = Regex(
            "^(?:make|mark|set|turn|convert|save)\\s+(?:the\\s+|my\\s+|this\\s+|that\\s+)?(?:(?:last|latest|previous|recent|first)\\s+)?note\\s*(?:about\\s+(?<q>.+?)\\s+)?(?:as\\s+|into\\s+|to\\s+be\\s+|in to\\s+)?(?:a\\s+)?reminder(?<rest>.*)$",
        )
        private val NOTES_WORD = Regex("\\bnotes?\\b")
        private val NOTES_WORD_ANY = Regex("\\bnotes?\\b")
        private val NOTE_LIST_START = Regex("^(?:what|whats|what's|which|show|list|tell|read|check|see|open|give|do i have|have i|any|my|the|latest|last|recent|how many|did i)\\b")
        private val OWN_NOTES = Regex("\\b(?:my|the|any|all|latest|last|recent|saved|current|these|those|which|what)\\s+(?:\\w+\\s+)?notes?\\b")
        private val NOTES_DO_I = Regex("\\bnotes? do i have\\b")
        private val SHOW_NOTES = Regex("^(?:show|list|read|open|check|see)\\s+(?:me\\s+)?(?:all\\s+)?notes?$")
        private val LAST_NOTE = Regex("\\b(?:last|latest|recent|previous)\\s+note\\b")
        private val PLURAL_NOTES = Regex("\\bnotes\\b")

        private val ORDER: List<suspend Organizer.(String, String, ToolContext) -> AssistantReply?> = listOf(
            { r, t, c -> hAppendNote(r, t, c) },
            { r, t, c -> hNoteToReminder(r, t, c) },
            { r, t, c -> hEditText(r, t, c) },
            { r, t, c -> hReschedule(r, t, c) },
            { r, t, c -> hComplete(r, t, c) },
            { r, t, c -> hAddReminder(r, t, c) },
            { r, t, c -> hAddNote(r, t, c) },
            { r, t, c -> hListReminders(r, t, c) },
            { r, t, c -> hListNotes(r, t, c) },
        )
    }
}
