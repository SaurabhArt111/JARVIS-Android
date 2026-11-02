package com.jarvis.core.engine

import com.jarvis.core.lock.LockState
import com.jarvis.core.model.AssistantReply
import com.jarvis.core.model.Origin
import com.jarvis.core.ports.ImageEvent
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class EngineState { IDLE, LISTENING, THINKING, SPEAKING, ERROR }

enum class NoticeLevel { INFO, SUCCESS, WARN, ERROR }

/** Everything a UI surface (chat, island, lock screen, notification) can observe. */
sealed class EngineEvent {
    data class UserSaid(val text: String, val origin: Origin) : EngineEvent()
    data class AssistantSaid(val reply: AssistantReply, val origin: Origin) : EngineEvent()
    /** Progress label while a tool runs ("Setting a reminder…"). */
    data class Thinking(val label: String) : EngineEvent()
    data class Notice(val level: NoticeLevel, val title: String, val message: String) : EngineEvent()
    /** A short line for the island / activity strip: icon, text, ok. */
    data class Activity(val icon: String, val text: String, val ok: Boolean) : EngineEvent()
    data class Image(val event: ImageEvent) : EngineEvent()
    data class ReminderFired(val id: String, val text: String, val isTimer: Boolean) : EngineEvent()
}

/** What the UI layer talks to. Implemented by [com.jarvis.core.engine.DefaultAssistantEngine]. */
interface AssistantEngine {
    val state: StateFlow<EngineState>
    val events: SharedFlow<EngineEvent>

    /**
     * Runs one request end to end: safety guard, pending question, agent loop or offline parser, delivery (speech policy), memory.
     * Never throws for ordinary failures: errors come back as an error [AssistantReply]. Cancellation is honoured.
     */
    suspend fun submit(text: String, origin: Origin, lock: LockState = LockState.UNLOCKED, echo: Boolean = true): AssistantReply

    /** Stops speech and cancels the request in flight. */
    fun cancel()

    /** Greeting by the clock, plus today's reminders. Offline, instant. */
    suspend fun greet(speak: Boolean)

    /** The microphone layer tells the engine it started / stopped listening so every surface shows the same state. */
    fun setListening(listening: Boolean)

    fun aiReady(): Boolean
}
