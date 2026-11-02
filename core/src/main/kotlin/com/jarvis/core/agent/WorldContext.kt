package com.jarvis.core.agent

import com.jarvis.core.engine.Dialog
import com.jarvis.core.lock.LockPolicy
import com.jarvis.core.lock.LockState
import com.jarvis.core.ports.ReminderState
import com.jarvis.core.ports.Services
import com.jarvis.core.routing.CapabilityRouter
import com.jarvis.core.settings.Keys
import com.jarvis.core.time.TimeParser
import com.jarvis.core.tools.impl.ToolRegistry

/**
 * The shared picture of the world every AI call receives (port of Context.py): CAPABILITIES (what is really possible right now),
 * STATE (real time, reminders, notes, timers, what is playing, the last thing JARVIS did) and RULES.
 *
 * Privacy: when the device is locked, or the user switched off "share personal details with the AI", reminders and notes appear only as counts.
 */
class WorldContext(
    private val services: Services,
    private val registry: ToolRegistry,
    private val router: CapabilityRouter,
    private val lockPolicy: LockPolicy,
) {
    suspend fun block(dialog: Dialog, lock: LockState, online: Boolean): String {
        val caps = router.promptBlock(lock, online)
        return Prompts.CAPABILITIES + (if (caps.isNotEmpty()) "\n\nRIGHT NOW:\n$caps" else "") + "\n\nSTATE (live, real data):\n" + state(dialog, lock) + "\n\n" + Prompts.RULES
    }

    suspend fun state(dialog: Dialog, lock: LockState): String {
        val nowZ = services.clock.now()
        val now = nowZ.toLocalDateTime()
        val personal = services.settings.flag(Keys.AI_SHARE_PERSONAL, true) && !lockPolicy.effectivelyLocked(lock)
        val lines = ArrayList<String>()
        lines += "Now: ${Prompts.nowText(nowZ)}."
        try {
            val all = registry.reminders.all()
            val up = all.filter { it.state == ReminderState.PENDING }
            if (personal) {
                val shown = up.take(6)
                lines += "Upcoming reminders: " + (if (shown.isEmpty()) "none" else shown.mapIndexed { i, r -> "${i + 1}) ${r.text} (${TimeParser.nice(r.due, now)}, id=${r.id})" }.joinToString("; ")) + "."
                val done = all.filter { it.state == ReminderState.DONE || it.state == ReminderState.FIRED }.sortedByDescending { it.due }.take(3)
                if (done.isNotEmpty()) lines += "Recently finished reminders: " + done.joinToString("; ") { it.text } + "."
            } else {
                lines += "Upcoming reminders: ${up.size} (details are private right now — use the reminder tool to read them)."
            }
            val timers = services.timers.active()
            if (timers.isNotEmpty()) lines += "Running timers: " + timers.take(3).joinToString("; ") { "${it.label} (${((it.endsAtMs - services.clock.nowMs()) / 60_000).coerceAtLeast(0)} min left)" } + "."
            val notes = registry.notes.all()
            if (personal) {
                val latest = notes.takeLast(5).reversed()
                lines += "Notes (${notes.size} total), newest first: " + (if (latest.isEmpty()) "none" else latest.mapIndexed { i, n -> "${i + 1}) ${n.text.take(90)} (id=${n.id})" }.joinToString("; ")) + "."
            } else lines += "Notes: ${notes.size} (details are private right now — use the note tool to read them)."
            services.media.nowPlaying()?.let { t -> if (personal) lines += "Now playing: ${t.title}${if (t.artist.isNotBlank()) " by ${t.artist}" else ""} in ${t.appLabel} (${t.state.name.lowercase()})." }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            services.log.w("World", "state block incomplete: ${e.javaClass.simpleName}")
        }
        if (dialog.lastAction.isNotEmpty()) lines += "Last thing JARVIS did: ${dialog.lastAction}."
        dialog.waiting()?.let { lines += "JARVIS just asked the user a question ($it) and is waiting for the answer." }
        return lines.joinToString("\n")
    }
}
