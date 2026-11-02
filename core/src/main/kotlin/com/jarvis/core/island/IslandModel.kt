package com.jarvis.core.island

import com.jarvis.core.engine.EngineEvent
import com.jarvis.core.engine.EngineState
import com.jarvis.core.ports.ImageEventType

enum class IslandMode {
    /** Nothing to show: the overlay window is removed so it cannot block touches. */
    HIDDEN,
    /** Idle state when always visible option is enabled. */
    IDLE,
    LISTENING, THINKING, SPEAKING,
    /** The latest answer. Collapses by itself. */
    REPLY,
    /** A short message (reminder, timer, activity, notice). Collapses by itself. */
    NOTICE,
    ERROR,
    /** Picture generation in progress (ring). */
    IMAGE,
}

/** What the island should currently show. Pure data: the view only draws it. */
data class IslandUiState(
    val mode: IslandMode = IslandMode.HIDDEN,
    val title: String = "",
    val text: String = "",
    val icon: String = "",
    /** Microphone level 0..1 while listening. */
    val level: Float = 0f,
    /** 0..1 task progress for [IslandMode.IMAGE]; null = unknown (spinning ring). */
    val progress: Float? = null,
    /** The user tapped to open the larger card. */
    val expanded: Boolean = false,
    val locked: Boolean = false,
    val sinceMs: Long = 0,
    /** Newest reply text, kept so the card can show it again while speaking. */
    val lastReply: String = "",
)

enum class CueKind { LISTENING, HEARD, DONE, FAILED }

sealed class IslandInput {
    data class Engine(val state: EngineState) : IslandInput()
    data class Event(val event: EngineEvent) : IslandInput()
    /** From the listening service: a wake word was heard / a request finished. [text] is already safe to show for the lock state. */
    data class Cue(val kind: CueKind, val text: String?, val locked: Boolean) : IslandInput()
    data class Level(val value: Float) : IslandInput()
    data class Lock(val locked: Boolean) : IslandInput()
    data class Preview(val text: String) : IslandInput()
    object Tap : IslandInput()
    object Close : IslandInput()
    object Tick : IslandInput()
}

/**
 * The island's state machine: engine state + events + voice-service cues in, [IslandUiState] out. No Android, no clocks (the caller passes [nowMs]), so every
 * transition is unit-tested. Personal text is never shown on a locked screen: a sensitive reply or a reminder becomes [lockedText] when [IslandUiState.locked].
 */
class IslandReducer(
    private val collapseMs: () -> Long,
    private val alwaysVisible: () -> Boolean = { false },
    private val lockedText: String = "You have a response. Unlock your phone to see it.",
    private val lockedReminderText: String = "You have a reminder.",
    /** An active mode (listening/thinking/speaking) with no input for this long is assumed stuck and hidden. */
    private val watchdogMs: Long = 90_000L,
) {
    fun reduce(s: IslandUiState, input: IslandInput, nowMs: Long): IslandUiState {
        val out = when (input) {
            is IslandInput.Engine -> engine(s, input.state, nowMs)
            is IslandInput.Event -> event(s, input.event, nowMs)
            is IslandInput.Cue -> cue(s, input, nowMs)
            is IslandInput.Level -> if (s.mode == IslandMode.LISTENING) s.copy(level = input.value.coerceIn(0f, 1f)) else s
            is IslandInput.Lock -> s.copy(locked = input.locked)
            is IslandInput.Preview -> s.copy(mode = IslandMode.REPLY, title = "JARVIS", text = input.text, icon = "check", expanded = false, sinceMs = nowMs, lastReply = input.text)
            IslandInput.Tap -> if (s.mode == IslandMode.HIDDEN) (if (alwaysVisible()) s.copy(mode = IslandMode.IDLE, title = "JARVIS", icon = "zap", sinceMs = nowMs) else s) else s.copy(expanded = !s.expanded, sinceMs = nowMs)
            IslandInput.Close -> if (alwaysVisible()) s.copy(mode = IslandMode.IDLE, title = "JARVIS", text = "", icon = "zap", expanded = false, level = 0f, progress = null) else s.copy(mode = IslandMode.HIDDEN, expanded = false, level = 0f, progress = null)
            IslandInput.Tick -> tick(s, nowMs)
        }
        return if (out.mode == IslandMode.HIDDEN && alwaysVisible()) out.copy(mode = IslandMode.IDLE, title = "JARVIS", text = "", icon = "zap", sinceMs = nowMs) else out
    }

    /** Should the caller keep ticking? (only while something can time out) */
    fun needsTick(s: IslandUiState) = s.mode != IslandMode.HIDDEN && s.mode != IslandMode.IDLE

    private fun active(m: IslandMode) = m == IslandMode.LISTENING || m == IslandMode.THINKING || m == IslandMode.SPEAKING

    private fun engine(s: IslandUiState, st: EngineState, now: Long): IslandUiState = when (st) {
        EngineState.LISTENING -> s.copy(mode = IslandMode.LISTENING, title = "Listening", text = "", icon = "mic", expanded = false, level = 0f, sinceMs = now)
        EngineState.THINKING -> s.copy(mode = IslandMode.THINKING, title = "Thinking", icon = "cpu", level = 0f, sinceMs = now)
        EngineState.SPEAKING -> s.copy(mode = IslandMode.SPEAKING, title = "Speaking", text = s.lastReply.ifBlank { s.text }, icon = "volume-2", level = 0f, sinceMs = now)
        EngineState.ERROR -> s.copy(mode = IslandMode.ERROR, title = "Problem", icon = "alert", sinceMs = now)
        EngineState.IDLE -> when {
            !active(s.mode) -> s
            s.mode != IslandMode.LISTENING && s.lastReply.isNotBlank() -> s.copy(mode = IslandMode.REPLY, title = "JARVIS", text = s.lastReply, icon = "check", sinceMs = now)
            alwaysVisible() -> s.copy(mode = IslandMode.IDLE, title = "JARVIS", text = "", icon = "zap", expanded = false, level = 0f)
            else -> s.copy(mode = IslandMode.HIDDEN, expanded = false, level = 0f)
        }
    }

    private fun event(s: IslandUiState, e: EngineEvent, now: Long): IslandUiState = when (e) {
        is EngineEvent.UserSaid -> if (s.mode == IslandMode.LISTENING || s.mode == IslandMode.THINKING) s.copy(text = if (s.locked) "" else e.text) else s
        is EngineEvent.Thinking -> s.copy(mode = IslandMode.THINKING, title = "Thinking", text = e.label, icon = "cpu", level = 0f, sinceMs = now)
        is EngineEvent.AssistantSaid -> {
            val shown = if (s.locked && e.reply.sensitive) lockedText else e.reply.text
            s.copy(mode = IslandMode.REPLY, title = "JARVIS", text = shown, icon = e.reply.icon.ifBlank { "check" }, lastReply = shown, sinceMs = now, level = 0f)
        }
        is EngineEvent.Notice -> if (active(s.mode)) s else s.copy(
            mode = if (e.level == com.jarvis.core.engine.NoticeLevel.ERROR) IslandMode.ERROR else IslandMode.NOTICE,
            title = e.title, text = if (s.locked) "" else e.message, icon = when (e.level) {
                com.jarvis.core.engine.NoticeLevel.ERROR -> "alert"; com.jarvis.core.engine.NoticeLevel.WARN -> "alert"; com.jarvis.core.engine.NoticeLevel.SUCCESS -> "check"; else -> "info"
            }, sinceMs = now,
        )
        is EngineEvent.Activity -> if (active(s.mode)) s else s.copy(mode = if (e.ok) IslandMode.NOTICE else IslandMode.ERROR, title = "JARVIS", text = if (s.locked) "" else e.text, icon = e.icon, sinceMs = now)
        is EngineEvent.ReminderFired -> s.copy(
            mode = IslandMode.NOTICE, title = if (e.isTimer) "Timer" else "Reminder", text = if (s.locked) lockedReminderText else e.text,
            icon = if (e.isTimer) "clock" else "bell", expanded = false, sinceMs = now,
        )
        is EngineEvent.Image -> when (e.event.type) {
            ImageEventType.START -> s.copy(mode = IslandMode.IMAGE, title = "Drawing", text = if (s.locked) "" else e.event.prompt, icon = "image", progress = 0.05f, sinceMs = now)
            ImageEventType.STAGE -> if (s.mode == IslandMode.IMAGE) s.copy(text = if (s.locked) "" else e.event.message.ifBlank { s.text }, progress = ((s.progress ?: 0f) + 0.2f).coerceAtMost(0.9f), sinceMs = now) else s
            ImageEventType.DONE -> s.copy(mode = IslandMode.NOTICE, title = "Image ready", text = "", icon = "image", progress = null, sinceMs = now)
            ImageEventType.ERROR -> s.copy(mode = IslandMode.ERROR, title = "Couldn't draw that", text = e.event.message, icon = "alert", progress = null, sinceMs = now)
        }
    }

    private fun cue(s: IslandUiState, c: IslandInput.Cue, now: Long): IslandUiState {
        val base = s.copy(locked = c.locked)
        return when (c.kind) {
            CueKind.LISTENING -> engine(base, EngineState.LISTENING, now)
            CueKind.HEARD -> if (base.mode == IslandMode.HIDDEN || base.mode == IslandMode.IDLE || base.mode == IslandMode.REPLY || base.mode == IslandMode.NOTICE)
                base.copy(mode = IslandMode.LISTENING, title = "Listening", icon = "mic", text = c.text.orEmpty(), expanded = false, sinceMs = now)
            else base.copy(text = c.text ?: base.text)
            CueKind.DONE -> if ((base.mode == IslandMode.HIDDEN || base.mode == IslandMode.IDLE) && !c.text.isNullOrBlank()) base.copy(mode = IslandMode.REPLY, title = "JARVIS", text = c.text, icon = "check", lastReply = c.text, sinceMs = now) else base
            CueKind.FAILED -> base.copy(mode = IslandMode.ERROR, title = "JARVIS", text = c.text.orEmpty(), icon = "alert", expanded = false, sinceMs = now)
        }
    }

    private fun tick(s: IslandUiState, now: Long): IslandUiState {
        if (s.mode == IslandMode.HIDDEN || s.mode == IslandMode.IDLE) return s
        val age = now - s.sinceMs
        val limit = when (s.mode) {
            IslandMode.REPLY, IslandMode.NOTICE, IslandMode.ERROR -> collapseMs() * (if (s.expanded) 3 else 1)
            IslandMode.IMAGE -> 10 * 60_000L
            else -> watchdogMs
        }
        return if (age >= limit) {
            if (alwaysVisible()) s.copy(mode = IslandMode.IDLE, title = "JARVIS", text = "", icon = "zap", expanded = false, level = 0f, progress = null)
            else s.copy(mode = IslandMode.HIDDEN, expanded = false, level = 0f, progress = null)
        } else s
    }
}
