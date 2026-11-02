package com.jarvis.assistant.service

import com.jarvis.core.model.Origin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

enum class ShowState {
    /** The microphone is open: show "listening". */
    LISTENING,
    /** What was heard so far (partial) or in full ([ShowRequest.text]). */
    HEARD,
    /** The request finished; the reply itself arrives through the engine's events. */
    DONE,
    /** Nothing was understood or something failed; [ShowRequest.text] says what. */
    FAILED,
}

/**
 * A request from the voice layer to every visible surface (island, lock screen, in-app sheet): "something is happening, show it".
 * [locked] is the keyguard state when the request was made. [text] is already safe to show for that state (never personal text while locked).
 */
data class ShowRequest(val locked: Boolean, val origin: Origin, val state: ShowState, val text: String? = null)

/** Process-wide hand-off between the listening service and the surfaces. Fire-and-forget: if nobody is collecting, requests are dropped (oldest first). */
object AssistantBus {
    private val _requests = MutableSharedFlow<ShowRequest>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val requests: SharedFlow<ShowRequest> = _requests.asSharedFlow()

    fun tryShow(request: ShowRequest): Boolean = _requests.tryEmit(request)

    private val _levels = MutableSharedFlow<Float>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Microphone loudness 0..1 while the voice service is listening for a command, for the island's waveform. */
    val levels: SharedFlow<Float> = _levels.asSharedFlow()

    fun tryLevel(value: Float): Boolean = _levels.tryEmit(value)
}
