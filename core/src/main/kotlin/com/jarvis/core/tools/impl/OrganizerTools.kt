package com.jarvis.core.tools.impl

import com.jarvis.core.model.ActionType
import com.jarvis.core.model.AssistantReply
import com.jarvis.core.model.Card
import com.jarvis.core.model.CardItem
import com.jarvis.core.model.Outcome
import com.jarvis.core.model.Requirement
import com.jarvis.core.model.UiAction
import com.jarvis.core.organizer.AddResult
import com.jarvis.core.organizer.NoteService
import com.jarvis.core.organizer.ReminderService
import com.jarvis.core.ports.Note
import com.jarvis.core.ports.Reminder
import com.jarvis.core.ports.ReminderState
import com.jarvis.core.time.TimeParser
import com.jarvis.core.tools.Args
import com.jarvis.core.tools.Capability
import com.jarvis.core.tools.LockClass
import com.jarvis.core.tools.Tool
import com.jarvis.core.tools.ToolContext
import com.jarvis.core.tools.ToolSpec
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val VAGUE = setOf("it", "last", "latest", "that", "this", "the reminder", "the note")
private val HHMM = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

// ===================================================================================================== reminder
class ReminderTool(private val reminders: ReminderService, private val notes: NoteService) : Tool {
    override val spec = ToolSpec(
        "reminder",
        "Reminders. add (text, when); list (filters: day today|tomorrow|YYYY-MM-DD|all, time HH:MM, query, include_done) — use list to check whether a reminder exists at a time; " +
            "update (id or query; new when and/or text); done; cancel. Times: ISO like 2026-10-16T15:30 or plain words like '5 pm'.",
        mapOf(
            "action" to T.p("add | list | update | done | cancel", enum = listOf("add", "list", "update", "done", "cancel")),
            "text" to T.p("What to remind (add) or new wording (update)"), "when" to T.p("Time for add/update"),
            "id" to T.p("Reminder id from a previous list"), "query" to T.p("Words from the reminder"), "day" to T.p("Filter for list"),
            "time" to T.p("HH:MM filter for list"), "include_done" to T.p("Also show reminders that already went off", "boolean"),
        ),
        listOf("action"), Capability.REMINDERS, LockClass.PERSONAL, needs = emptyList(),
    )

    private fun line(r: Reminder, now: LocalDateTime) = "id=${r.id} | ${r.text} | ${TimeParser.nice(r.due, now)} | ${r.state.name.lowercase()}"

    private fun card(rems: List<Reminder>, title: String, now: LocalDateTime, calendar: Boolean = false): Card {
        val items = rems.take(6).map { CardItem(it.text, TimeParser.nice(it.due, now) + if (it.state == ReminderState.DONE) " · done" else "") }
        return if (calendar) Card("calendar", title, icon = "bell", items = items, fields = mapOf("date" to now.toLocalDate().toString(), "marks" to rems.map { it.due.toLocalDate().toString() }.distinct().sorted().joinToString(",")))
        else Card("items", title, icon = "bell", items = items)
    }

    private suspend fun find(args: Args): Pair<Reminder?, String?> {
        val id = args.str("id", "reminder_id")
        if (id.isNotEmpty()) reminders.get(id)?.let { return it to null }
        val pend = reminders.upcoming()
        val q = args.str("query", "text_match", "which")
        if (q.isNotEmpty() && q.lowercase() !in VAGUE) {
            val qw = NoteService.words(q)
            val scored = (pend + reminders.recentDone(10)).map { (qw intersect NoteService.words(it.text)).size to it }.sortedByDescending { it.first }
            return if (scored.isNotEmpty() && scored[0].first > 0) scored[0].second to null else null to "I couldn't find a reminder matching “$q”."
        }
        if (pend.size == 1) return pend[0] to null
        if (pend.isNotEmpty()) return pend.maxByOrNull { it.createdAt }!! to null
        reminders.recentDone(1).firstOrNull()?.let { return it to null }
        return null to "You don't have any reminders."
    }

    private fun inexact(a: AddResult): Pair<String, List<UiAction>> =
        if (a.inexact) " Android may deliver it a few minutes late because exact alarms aren't allowed for JARVIS yet." to listOf(T.grantAction(Requirement.EXACT_ALARM))
        else "" to emptyList()

    override suspend fun execute(args: Args, ctx: ToolContext): Outcome {
        val act = args.str("action", default = "list").lowercase()
        val nowZ = ctx.now
        val now = nowZ.toLocalDateTime()
        when (act) {
            "add" -> {
                val text = args.str("text", "message", "what").replace(Regex("^(?:to|that)\\s+", RegexOption.IGNORE_CASE), "").trim()
                if (text.isEmpty()) return T.fail("What should I remind you about?", "bell")
                val (due, why) = TimeParser.parseWhen(args.str("when", "time", "at"), nowZ)
                if (due == null) return T.fail(why ?: "I need a time for that.", "bell")
                reminders.upcoming().firstOrNull { it.text.equals(text, true) && it.due == due.withSecond(0).withNano(0) }?.let { r ->
                    return Outcome(true, "That reminder already exists (id=${r.id}): $text, ${TimeParser.nice(due, now)}.",
                        T.reply("Reminder is already set for ${TimeParser.nice(due, now)}.", "bell", card(listOf(r), "Reminder", now), sensitive = true), verified = true)
                }
                val res = reminders.add(text, due)
                val r = res.reminder ?: return T.fail(res.error ?: "I couldn't create that reminder.", "bell")
                ctx.dialog.remember("reminder", r.id, "set reminder '$text' for ${TimeParser.nice(due, now)}")
                val (late, actions) = inexact(res)
                return Outcome(true, "Reminder saved (id=${r.id}): $text, ${TimeParser.nice(due, now)}.$late",
                    T.reply("Reminder set for ${TimeParser.nice(due, now)}.$late", "bell", card(listOf(r), "Reminder set", now), sensitive = true, actions = actions), verified = true)
            }
            "update", "reschedule", "edit", "move", "snooze" -> {
                val (found, why) = find(args)
                var r = found ?: return T.fail(why ?: "I couldn't find that reminder.", "bell")
                val did = ArrayList<String>()
                var late = ""
                var actions = emptyList<UiAction>()
                if (args.has("when", "time", "at")) {
                    val (due, w) = TimeParser.parseWhen(args.str("when", "time", "at"), nowZ)
                    if (due == null) return T.fail(w ?: "I need a time for that.", "bell")
                    val res = reminders.rescheduleChecked(r.id, due)
                    r = res.reminder ?: return T.fail(res.error ?: "I couldn't move that reminder.", "bell")
                    did.add("moved to ${TimeParser.nice(due, now)}")
                    inexact(res).let { late = it.first; actions = it.second }
                }
                if (args.has("text", "new_text")) {
                    r = reminders.setText(r.id, args.str("text", "new_text")) ?: r
                    did.add("text changed")
                }
                if (did.isEmpty()) return T.fail("Tell me the new time or the new wording.", "bell")
                ctx.dialog.remember("reminder", r.id, "updated reminder '${r.text}'")
                return Outcome(true, "Reminder “${r.text}” ${did.joinToString(" and ")}.$late",
                    T.reply("Done — ${did.joinToString(" and ")}.$late", "bell", card(listOf(r), "Reminder updated", now), sensitive = true, actions = actions), verified = true)
            }
            "done", "complete", "finish" -> {
                val (found, why) = find(args)
                val r = found ?: return T.fail(why ?: "I couldn't find that reminder.", "bell")
                reminders.markDone(r.id)
                return Outcome(true, "Marked “${r.text}” as done.", T.reply("Marked “${r.text}” as done.", "check", sensitive = true), verified = true)
            }
            "cancel", "remove", "delete" -> {
                val (found, why) = find(args)
                val r = found ?: return T.fail(why ?: "I couldn't find that reminder.", "bell")
                reminders.cancel(r.id)
                return Outcome(true, "Cancelled “${r.text}” (it stays in your reminder history; nothing was erased).", T.reply("Cancelled “${r.text}”.", "bell", sensitive = true), verified = true)
            }
        }
        // ---- list / check
        val day: LocalDate? = TimeParser.parseDay(args.str("day", "date"), now)
        val tm = TimeParser.parseClock(args.str("time"), now)
        val q = args.str("query")
        val includeDone = args.bool("include_done")
        val all = reminders.all()
        var rems = all.filter { it.state != ReminderState.CANCELLED && (includeDone || it.state == ReminderState.PENDING) }
        val doneToday = all.filter { (it.state == ReminderState.DONE || it.state == ReminderState.FIRED) && day != null && it.due.toLocalDate() == day }
        if (day != null) rems = rems.filter { it.due.toLocalDate() == day }
        if (q.isNotEmpty()) {
            val qw = NoteService.words(q)
            if (qw.isNotEmpty()) rems = rems.filter { (qw intersect NoteService.words(it.text)).isNotEmpty() }
        }
        val whenTxt = (if (day != null && day != now.toLocalDate()) " on ${TimeParser.dayPhrase(day, now)}" else if (day != null) " today" else "") +
            (if (tm != null) " at ${HHMM.format(java.time.LocalTime.of(tm.first, tm.second))}" else "")
        val share = { full: String, redacted: String -> T.modelNote(ctx.services, full, redacted) }
        if (tm != null) {
            val exact = rems.filter { it.due.hour == tm.first && it.due.minute == tm.second }
            if (exact.isNotEmpty()) {
                return T.info(share("YES - ${exact.size} reminder(s)$whenTxt:\n" + exact.joinToString("\n") { line(it, now) }, "YES - ${exact.size} reminder(s)$whenTxt (shown on screen; their text is private)."),
                    T.reply("Yes — ${exact[0].text} at ${HHMM.format(java.time.LocalTime.of(tm.first, tm.second))}.", "bell", card(exact, "Reminder", now), sensitive = true))
            }
            val near = rems.take(6)
            var msg = "NO reminder$whenTxt." + if (near.isNotEmpty()) " Other reminders that day:\n" + near.joinToString("\n") { line(it, now) } else " No other reminders that day."
            if (doneToday.isNotEmpty()) msg += "\n(${doneToday.size} reminder(s) that day already fired.)"
            return T.info(share(msg, "NO reminder$whenTxt."), T.reply("No reminder$whenTxt.", "bell", near.takeIf { it.isNotEmpty() }?.let { card(it, "Other reminders", now) }, sensitive = near.isNotEmpty()))
        }
        if (rems.isEmpty()) {
            val extra = if (doneToday.isNotEmpty()) " (${doneToday.size} already fired.)" else ""
            return T.info("No ${if (q.isNotEmpty()) "matching " else ""}reminders$whenTxt.$extra", T.reply("No reminders$whenTxt.", "bell"))
        }
        val top = rems.take(12)
        return T.info(share("${rems.size} reminder(s)$whenTxt:\n" + top.joinToString("\n") { line(it, now) }, "${rems.size} reminder(s)$whenTxt — shown on screen; their text is private, so don't repeat it."),
            T.reply("You have ${T.plural(rems.size, "reminder")}$whenTxt.", "bell", card(top, "Reminders", now, calendar = true), sensitive = true))
    }
}

// ===================================================================================================== note
class NoteTool(private val notes: NoteService, private val reminders: ReminderService) : Tool {
    override val spec = ToolSpec(
        "note",
        "Notes. add (text; remind_at optional makes it a reminder too); list (query optional to search); edit (replace text); append (add to a note); " +
            "remind (turn a note into a reminder). Without id the newest note is used.",
        mapOf(
            "action" to T.p("add | list | edit | append | remind", enum = listOf("add", "list", "edit", "append", "remind")),
            "text" to T.p("Note text"), "id" to T.p("Note id"), "query" to T.p("Search words"), "remind_at" to T.p("Time for a reminder"),
        ),
        listOf("action"), Capability.NOTES, LockClass.PERSONAL,
    )

    private suspend fun find(args: Args): Pair<Note?, String?> {
        val id = args.str("id", "note_id")
        if (id.isNotEmpty()) notes.get(id)?.let { return it to null }
        val q = args.str("query")
        if (q.isNotEmpty() && q.lowercase() !in VAGUE) {
            val hits = notes.find(q)
            return if (hits.isNotEmpty()) hits[0] to null else null to "I couldn't find a note about “$q”."
        }
        val last = notes.latest(1)
        return if (last.isNotEmpty()) last[0] to null else null to "You don't have any notes yet."
    }

    private fun noteLine(n: Note, zone: java.time.ZoneId): String {
        val t = n.createdAt.atZone(zone).format(DateTimeFormatter.ofPattern("d MMM h:mm a", Locale.ENGLISH))
        return "id=${n.id} | ${n.text} | saved $t" + (n.reminderId?.let { " | linked reminder $it" } ?: "")
    }

    private fun noteCard(title: String, text: String, sub: String) = Card("items", title, icon = "file-text", items = listOf(CardItem(text, sub)))

    override suspend fun execute(args: Args, ctx: ToolContext): Outcome {
        val act = args.str("action", default = "list").lowercase()
        val nowZ = ctx.now
        val now = nowZ.toLocalDateTime()
        when (act) {
            "add" -> {
                val text = args.str("text", "content")
                if (text.isEmpty()) return T.fail("What should the note say?", "file-text")
                var due: LocalDateTime? = null
                if (args.has("remind_at", "when")) {
                    val (d, why) = TimeParser.parseWhen(args.str("remind_at", "when"), nowZ)
                    if (d == null) return T.fail(why ?: "I need a time for that.", "bell")
                    due = d
                }
                val n = notes.add(text)
                ctx.dialog.remember("note", n.id, "saved note '${text.take(60)}'")
                if (due == null) return Outcome(true, "Note saved (id=${n.id}): $text", T.reply("Note saved.", "file-text", noteCard("Note saved", text, "just now"), sensitive = true), verified = true)
                val res = reminders.add(text, due, noteId = n.id)
                val r = res.reminder
                if (r == null) return Outcome(true, "Note saved (id=${n.id}) but the reminder could NOT be created: ${res.error}",
                    T.reply("Note saved, but I couldn't set the reminder: ${res.error}", "file-text", noteCard("Note saved", text, "no reminder"), sensitive = true), verified = true)
                notes.linkReminder(n.id, r.id)
                ctx.dialog.remember("reminder", r.id)
                val nice = TimeParser.nice(due, now)
                return Outcome(true, "Note saved (id=${n.id}) and a reminder for $nice: $text",
                    T.reply("Note saved and a reminder set for $nice.", "file-text", noteCard("Note saved", text, "reminder $nice"), sensitive = true), verified = true)
            }
            "edit", "replace", "update" -> {
                val (n, why) = find(args)
                n ?: return T.fail(why ?: "I couldn't find that note.", "file-text")
                val new = args.str("text", "content")
                if (new.isEmpty()) return T.fail("What should the note say now?", "file-text")
                notes.edit(n.id, new) ?: return T.fail("I couldn't update that note.", "file-text")
                ctx.dialog.remember("note", n.id)
                return Outcome(true, "Note updated (the old wording is kept in its history): $new", T.reply("Note updated.", "file-text", noteCard("Note updated", new, "edited just now"), sensitive = true), verified = true)
            }
            "append", "add_to" -> {
                val (n, why) = find(args)
                n ?: return T.fail(why ?: "I couldn't find that note.", "file-text")
                val extra = args.str("text", "content")
                if (extra.isEmpty()) return T.fail("What should I add to the note?", "file-text")
                val n2 = notes.append(n.id, extra) ?: return T.fail("I couldn't update that note.", "file-text")
                ctx.dialog.remember("note", n.id)
                return Outcome(true, "Added to the note: ${n2.text}", T.reply("Added to the note.", "file-text", noteCard("Note updated", n2.text, "edited just now"), sensitive = true), verified = true)
            }
            "remind", "to_reminder" -> {
                val (n, why) = find(args)
                n ?: return T.fail(why ?: "I couldn't find that note.", "file-text")
                val (due, w) = TimeParser.parseWhen(args.str("remind_at", "when"), nowZ)
                if (due == null) return T.fail(w ?: "I need a time for that.", "bell")
                val res = reminders.add(n.text, due, noteId = n.id)
                val r = res.reminder ?: return T.fail(res.error ?: "I couldn't set that reminder.", "bell")
                notes.linkReminder(n.id, r.id)
                ctx.dialog.remember("reminder", r.id)
                return Outcome(true, "Reminder set for that note at ${TimeParser.nice(due, now)}.", T.reply("Reminder set for ${TimeParser.nice(due, now)}.", "bell", sensitive = true), verified = true)
            }
        }
        val q = args.str("query")
        val limit = (args.int("limit") ?: 8).coerceIn(1, 12)
        val list = if (q.isNotEmpty()) notes.find(q) else notes.latest(limit)
        if (list.isEmpty()) return T.info(if (q.isEmpty()) "No notes." else "No notes about “$q”.", T.reply(if (q.isEmpty()) "You have no notes yet." else "No notes about $q.", "file-text"))
        val total = notes.all().size
        val zone = ctx.services.clock.zone()
        return T.info(
            T.modelNote(ctx.services, "${list.size} note(s) shown, $total in total (newest first):\n" + list.take(12).joinToString("\n") { noteLine(it, zone) },
                "${list.size} note(s) shown on screen, $total in total. Their text is private: don't repeat it."),
            T.reply("You have ${T.plural(total, "note")}.", "file-text", T.items("Notes", "file-text", list.take(6).map { it.text to "" }), sensitive = true),
        )
    }
}

// ===================================================================================================== timer
class TimerTool : Tool {
    override val spec = ToolSpec(
        "timer", "Countdown timer: start (duration like '10 minutes', or seconds), stop, status. use_clock_app=true hands the timer to the system Clock app instead.",
        mapOf(
            "action" to T.p("start | stop | status", enum = listOf("start", "stop", "status")), "duration" to T.p("e.g. 10 minutes, 1 hour 30 minutes"),
            "seconds" to T.p("Length in seconds", "integer"), "label" to T.p("Optional name"), "use_clock_app" to T.p("Use the Clock app's timer", "boolean"),
        ),
        listOf("action"), Capability.TIMERS_ALARMS, LockClass.OPEN,
    )

    override suspend fun execute(args: Args, ctx: ToolContext): Outcome {
        val svc = ctx.services
        val act = args.str("action", default = "start").lowercase()
        when (act) {
            "start" -> {
                var secs = args.int("seconds")?.takeIf { it > 0 }
                if (secs == null) {
                    val d = args.str("duration", "time", "for")
                    secs = if (d.isNotEmpty()) TimeParser.parseDurationSeconds(d) else null
                    if (secs == null && d.isNotEmpty() && d.all { it.isDigit() }) secs = d.toInt() * 60
                }
                if (secs == null || secs <= 0) return T.fail("For how long? Say something like five minutes.", "clock")
                if (secs > 24 * 3600) return T.fail("Timers can be up to 24 hours. For something later, I can set a reminder.", "clock")
                val label = args.str("label", default = "Timer")
                val nice = T.durationText(secs)
                if (args.bool("use_clock_app")) {
                    val r = svc.clockApp.setTimer(secs, label)
                    return if (r.ok) Outcome(true, "Sent a $nice timer to the Clock app. Android doesn't confirm that the Clock app accepted it.", T.reply("Asked the Clock app for a $nice timer.", "clock"), verified = false)
                    else T.failed(r, "The Clock app didn't take the timer.", "clock")
                }
                val tm = svc.timers.start(secs, label) ?: return T.fail("I couldn't start the timer.", "clock")
                val verified = svc.timers.active().any { it.id == tm.id }
                if (!verified) return T.fail("I asked for the timer, but it isn't listed as running, so I can't promise it will ring.", "clock")
                val late = if (tm.exact) "" else " Android only allows an approximate alarm for JARVIS right now, so it may ring a little late (allow \"Alarms & reminders\" in settings for an exact one)."
                return Outcome(true, "$label started for $nice.$late", T.reply("Timer set for $nice.$late", "clock", Card("timer", label, nice, "clock", fields = mapOf("seconds" to secs.toString(), "endsAtMs" to tm.endsAtMs.toString(), "label" to label))), verified = true)
            }
            "stop", "cancel" -> {
                val n = svc.timers.stopAll()
                val left = svc.timers.active().size
                if (left > 0) return T.fail("I tried to stop the timer, but $left still ${if (left == 1) "shows" else "show"} as running.", "clock")
                return Outcome(true, if (n > 0) "Timer stopped." else "No timer was running.", T.reply(if (n > 0) "Timer stopped." else "There's no timer running.", "clock"), verified = true)
            }
        }
        val ts = svc.timers.active()
        if (ts.isEmpty()) return T.info("No timers are running.", T.reply("No timers are running.", "clock"))
        val now = svc.clock.nowMs()
        val t = ts[0]
        val left = ((t.endsAtMs - now) / 1000).toInt().coerceAtLeast(0)
        val txt = "${t.label}: ${left / 60} minutes and ${left % 60} seconds left."
        return T.info(txt, T.reply("${left / 60} minutes and ${left % 60} seconds left.", "clock", Card("timer", t.label, "", "clock", fields = mapOf("seconds" to t.seconds.toString(), "endsAtMs" to t.endsAtMs.toString(), "label" to t.label))))
    }
}

// ===================================================================================================== alarm
class AlarmTool(private val reminders: ReminderService) : Tool {
    override val spec = ToolSpec(
        "alarm", "Set a wake-up style alarm for a clock time (uses the system Clock app, which rings loudly). For a dated alarm more than a day away it creates a JARVIS reminder alarm instead.",
        mapOf("time" to T.p("When, e.g. 7 am, tomorrow at 6:30"), "label" to T.p("Optional label")),
        listOf("time"), Capability.TIMERS_ALARMS, LockClass.OPEN,
    )

    override suspend fun execute(args: Args, ctx: ToolContext): Outcome {
        val svc = ctx.services
        val nowZ = ctx.now
        val now = nowZ.toLocalDateTime()
        val (due, why) = TimeParser.parseWhen(args.str("time", "when", "at"), nowZ)
        if (due == null) return T.fail(why ?: "What time should the alarm be?", "bell")
        val label = args.str("label", default = "JARVIS alarm")
        val nice = TimeParser.nice(due, now)
        val within = java.time.Duration.between(now, due).toHours() < 24
        if (within) {
            val r = svc.clockApp.setAlarm(due.hour, due.minute, label)
            if (r.ok) {
                ctx.dialog.noteAction("asked the Clock app for an alarm at $nice")
                return Outcome(true, "Sent an alarm for $nice to the Clock app. Android doesn't tell me whether the Clock app accepted it, so tell the user to check the Clock app if it matters.",
                    T.reply("Asked the Clock app to set an alarm for $nice. Please check the Clock app if it matters — Android doesn't confirm it.", "bell"), verified = false)
            }
            // fall through to a JARVIS alarm when there is no clock app
        }
        val res = reminders.add(label, due, isAlarm = true)
        val rem = res.reminder ?: return T.fail(res.error ?: "I couldn't set that alarm.", "bell")
        ctx.dialog.remember("reminder", rem.id, "set alarm for $nice")
        val late = if (res.inexact) " Android may deliver it a few minutes late." else ""
        return Outcome(true, "Alarm saved as a JARVIS reminder alarm (id=${rem.id}) for $nice.$late",
            T.reply("Alarm set for $nice.$late", "bell", sensitive = true, actions = if (res.inexact) listOf(T.grantAction(Requirement.EXACT_ALARM)) else emptyList()), verified = true)
    }
}
