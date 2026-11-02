package com.jarvis.assistant.ui.chat

import com.jarvis.assistant.R
import com.jarvis.assistant.di.AppContainer
import com.jarvis.assistant.ui.kit.ViewModel
import com.jarvis.assistant.voice.VoiceError
import com.jarvis.assistant.voice.VoiceEvent
import com.jarvis.core.engine.EngineEvent
import com.jarvis.core.engine.EngineState
import com.jarvis.core.engine.NoticeLevel
import com.jarvis.core.format.ChatTimeline
import com.jarvis.core.format.DayWords
import com.jarvis.core.format.LiveMessage
import com.jarvis.core.format.TimelineItem
import com.jarvis.core.model.AssistantReply
import com.jarvis.core.model.Origin
import com.jarvis.core.model.Requirement
import com.jarvis.core.ports.ImageEventType
import com.jarvis.core.ports.PermState
import com.jarvis.core.ports.StoredFile
import com.jarvis.core.ports.StoredMessage
import com.jarvis.core.settings.Keys
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.util.Locale

/**
 * State of the chat screen. Lives in [com.jarvis.assistant.ui.kit.ViewModels], so a rotation, a theme change or switching tabs does not lose the conversation, a
 * running request or an open microphone.
 *
 * What is on screen = the stored history (SQLite, paged) + a "live tail" built from the engine's events, so a message appears the moment it is said and error
 * replies (which are not stored) stay visible. [ChatTimeline] merges the two without duplicates.
 */
class ChatViewModel(private val app: AppContainer) : ViewModel({ tag, e -> app.appLog.e(tag, "unexpected ${e.javaClass.simpleName}", e) }) {

    enum class Mic { OFF, STARTING, LISTENING, HEARING }
    data class Banner(val text: String, val level: NoticeLevel, val action: com.jarvis.core.model.UiAction? = null)

    data class State(
        val items: List<TimelineItem> = emptyList(),
        val loading: Boolean = true,
        val loadFailed: Boolean = false,
        val hasMore: Boolean = false,
        val loadingMore: Boolean = false,
        /** Requests in flight (the engine runs them one at a time). */
        val busy: Boolean = false,
        /** Progress line: "Thinking…", "Setting a reminder…", "Creating your image…". */
        val progress: String? = null,
        val engine: EngineState = EngineState.IDLE,
        val mic: Mic = Mic.OFF,
        /** Words recognised so far while the user is still speaking. */
        val partial: String = "",
        val muted: Boolean = false,
        val aiReady: Boolean = true,
        val banner: Banner? = null,
        val searchOpen: Boolean = false,
        val searchResults: List<StoredMessage>? = null,
        val searchQuery: String = "",
    )

    sealed class Effect {
        data class NeedPermission(val req: Requirement) : Effect()
        data class Saved(val file: StoredFile) : Effect()
        data class Toast(val text: String) : Effect()
    }

    private val _state = MutableStateFlow(State(muted = app.settings.flag(Keys.VOICE_MUTED, false)))
    val state: StateFlow<State> get() = _state
    private val _level = MutableStateFlow(0f)
    /** Microphone loudness 0..1 for the orb (separate so the list is not rebuilt 20 times a second). */
    val micLevel: StateFlow<Float> get() = _level
    private val _effects = MutableSharedFlow<Effect>(extraBufferCapacity = 16)
    val effects: SharedFlow<Effect> get() = _effects

    private val ctx get() = app.app
    private val pageSize = 60
    private var conversationId = 0L
    private var history: List<StoredMessage> = emptyList()
    private val live = ArrayList<LiveMessage>()
    private var seq = 0L
    private var inFlight = 0
    private var voiceJob: Job? = null
    private var reloadJob: Job? = null
    private var bannerJob: Job? = null
    private var greeted = false

    private fun update(f: State.() -> State) { _state.value = _state.value.f() }

    init {
        scope.launch { load() }
        scope.launch { app.engine.events.collect { onEvent(it) } }
        scope.launch { app.engine.state.collect { s -> update { copy(engine = s) } } }
        scope.launch { app.conversations.changes.collect { scheduleReload() } }
        refreshFlags()
    }

    // ------------------------------------------------------------------------------------------------ history
    private suspend fun load() {
        try {
            conversationId = app.conversations.currentConversationId()
            history = app.conversations.messages(conversationId, pageSize)
            val more = history.size >= pageSize
            publish(loading = false, hasMore = more)
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            app.appLog.w("Chat", "could not load the history (${e.javaClass.simpleName})")
            update { copy(loading = false, loadFailed = true) }
        }
    }

    fun retryLoad() { update { copy(loading = true, loadFailed = false) }; scope.launch { load() } }

    private fun publish(loading: Boolean = false, hasMore: Boolean = _state.value.hasMore) {
        val now = System.currentTimeMillis()
        val words = DayWords(ctx.getString(R.string.ui_today), ctx.getString(R.string.ui_yesterday))
        val items = ChatTimeline.merge(history, live, now, ZoneId.systemDefault(), words, Locale.getDefault())
        update { copy(items = items, loading = loading, hasMore = hasMore, loadFailed = false) }
    }

    private fun scheduleReload() {
        reloadJob?.cancel()
        reloadJob = scope.launch {
            delay(120)
            try {
                val cid = app.conversations.currentConversationId()
                if (cid != conversationId) { conversationId = cid; history = emptyList(); live.clear() }
                history = app.conversations.messages(cid, maxOf(history.size + 6, pageSize))
                // live messages that are now stored are dropped by the merge; forget them for good so the list stays small
                val keep = ChatTimeline.unmatchedLive(history, live).map { it.seq }.toSet()
                live.removeAll { it.seq !in keep }
                publish(hasMore = _state.value.hasMore || history.size >= pageSize)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { app.appLog.w("Chat", "could not refresh the history (${e.javaClass.simpleName})") }
        }
    }

    fun loadOlder() {
        val s = _state.value
        if (!s.hasMore || s.loadingMore || history.isEmpty()) return
        update { copy(loadingMore = true) }
        scope.launch {
            try {
                val older = app.conversations.messages(conversationId, pageSize, history.first().id)
                history = older + history
                publish(hasMore = older.size >= pageSize)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { app.appLog.w("Chat", "could not load older messages (${e.javaClass.simpleName})") }
            update { copy(loadingMore = false) }
        }
    }

    // ------------------------------------------------------------------------------------------------ engine events
    private fun addLive(role: String, text: String, reply: AssistantReply?) {
        live += LiveMessage(++seq, history.lastOrNull()?.id ?: 0L, role, text, System.currentTimeMillis(), reply)
        publish()
    }

    private fun onEvent(ev: EngineEvent) {
        when (ev) {
            is EngineEvent.UserSaid -> addLive("user", ev.text, null)
            is EngineEvent.AssistantSaid -> { addLive("assistant", ev.reply.text, ev.reply); update { copy(progress = null) } }
            is EngineEvent.Thinking -> update { copy(progress = ev.label) }
            is EngineEvent.Notice -> if (ev.level == NoticeLevel.WARN || ev.level == NoticeLevel.ERROR) showBanner(Banner(ev.message.ifBlank { ev.title }, ev.level))
            is EngineEvent.Image -> when (ev.event.type) {
                ImageEventType.START, ImageEventType.STAGE -> update { copy(progress = ev.event.message.ifBlank { ctx.getString(R.string.ui_creating_image) }) }
                ImageEventType.DONE, ImageEventType.ERROR -> update { copy(progress = null) }
            }
            else -> {}
        }
    }

    private fun showBanner(b: Banner, forMs: Long = 9_000) {
        update { copy(banner = b) }
        bannerJob?.cancel()
        bannerJob = scope.launch { delay(forMs); update { copy(banner = null) } }
    }

    fun dismissBanner() { bannerJob?.cancel(); update { copy(banner = null) } }

    // ------------------------------------------------------------------------------------------------ sending
    fun send(text: String, origin: Origin = Origin.TEXT) {
        val t = text.trim()
        if (t.isEmpty()) return
        inFlight++
        update { copy(busy = true, banner = null) }
        scope.launch {
            try {
                app.engine.submit(t, origin)
            } catch (e: CancellationException) {
                // stopped on purpose
            } catch (e: Exception) {
                app.appLog.e("Chat", "request failed: ${e.javaClass.simpleName}", e)
                addLive("assistant", ctx.getString(R.string.ui_err_send_failed), AssistantReply.error(ctx.getString(R.string.ui_err_send_failed)))
            } finally {
                inFlight = (inFlight - 1).coerceAtLeast(0)
                if (inFlight == 0) update { copy(busy = false, progress = null) }
            }
        }
    }

    /** Stops speech and the request in flight. */
    fun stop() { app.engine.cancel(); app.speaker.stop(); stopListening() }

    // ------------------------------------------------------------------------------------------------ voice
    fun toggleListening() { if (voiceJob?.isActive == true) stopListening() else startListening() }

    fun stopListening() { voiceJob?.cancel() }

    private fun startListening() {
        if (app.permissions.state(Requirement.MICROPHONE) != PermState.GRANTED) { _effects.tryEmit(Effect.NeedPermission(Requirement.MICROPHONE)); return }
        app.speaker.stop()
        voiceJob = scope.launch {
            app.engine.setListening(true)
            update { copy(mic = Mic.STARTING, partial = "", banner = null) }
            var finalText: String? = null
            try {
                app.voice.listenOnce().collect { ev ->
                    when (ev) {
                        VoiceEvent.Ready -> update { copy(mic = Mic.LISTENING) }
                        VoiceEvent.Speaking -> update { copy(mic = Mic.HEARING) }
                        is VoiceEvent.Level -> _level.value = ev.value
                        is VoiceEvent.Partial -> update { copy(partial = ev.text) }
                        is VoiceEvent.Final -> finalText = ev.text
                        is VoiceEvent.Failed -> onVoiceFailed(ev)
                    }
                }
            } finally {
                app.engine.setListening(false)
                _level.value = 0f
                update { copy(mic = Mic.OFF, partial = "") }
            }
            finalText?.let { send(it, Origin.VOICE) }
        }
    }

    private fun onVoiceFailed(ev: VoiceEvent.Failed) {
        when (ev.error) {
            VoiceError.NO_PERMISSION -> _effects.tryEmit(Effect.NeedPermission(Requirement.MICROPHONE))
            VoiceError.NO_MATCH -> showBanner(Banner(ev.message, NoticeLevel.INFO), 4_000)
            else -> showBanner(Banner(ev.message, NoticeLevel.WARN))
        }
    }

    // ------------------------------------------------------------------------------------------------ misc actions
    fun refreshFlags() {
        update { copy(muted = app.settings.flag(Keys.VOICE_MUTED, false), aiReady = app.engine.aiReady()) }
    }

    fun toggleMute() {
        val now = !app.settings.flag(Keys.VOICE_MUTED, false)
        app.settings.setFlag(Keys.VOICE_MUTED, now)
        if (now) app.speaker.stop()
        update { copy(muted = now) }
    }

    /** Greets once per cold start, and only when the last message is old, so the history is not filled with "Good morning". Speaks only if the user turned that on. */
    fun greetIfDue() {
        if (greeted || !app.settings.flag(Keys.GREET_ON_START, true)) return
        scope.launch {
            // wait for the history so "last message" is known
            var waited = 0
            while (_state.value.loading && waited < 40) { delay(50); waited++ }
            if (greeted) return@launch
            greeted = true
            val last = history.lastOrNull()?.atMs ?: 0L
            if (System.currentTimeMillis() - last < 3 * 3_600_000L) return@launch
            try { app.engine.greet(speak = app.settings.flag(Keys.SPEAK_GREETING, false) && !app.settings.flag(Keys.VOICE_MUTED, false)) } catch (e: CancellationException) { throw e } catch (e: Exception) { app.appLog.w("Chat", "greeting failed (${e.javaClass.simpleName})") }
        }
    }

    fun newConversation() {
        scope.launch {
            try {
                conversationId = app.conversations.newConversation(); history = emptyList(); live.clear()
                publish(hasMore = false)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { _effects.tryEmit(Effect.Toast(ctx.getString(R.string.ui_err_generic))) }
        }
    }

    /** Permanent. Only called after the user confirmed in a dialog. */
    fun deleteConversation() {
        scope.launch {
            try {
                app.conversations.deleteConversation(conversationId)
                conversationId = app.conversations.currentConversationId(); history = emptyList(); live.clear()
                publish(hasMore = false)
                _effects.tryEmit(Effect.Toast(ctx.getString(R.string.ui_chat_deleted)))
            } catch (e: CancellationException) { throw e } catch (e: Exception) { _effects.tryEmit(Effect.Toast(ctx.getString(R.string.ui_err_generic))) }
        }
    }

    fun exportConversation() {
        scope.launch {
            try { _effects.tryEmit(Effect.Saved(app.conversations.export(conversationId))) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { app.appLog.w("Chat", "export failed (${e.javaClass.simpleName})"); _effects.tryEmit(Effect.Toast(ctx.getString(R.string.ui_err_export))) }
        }
    }

    fun search(q: String) {
        val query = q.trim()
        if (query.length < 2) { update { copy(searchResults = null, searchQuery = query) }; return }
        update { copy(searchQuery = query) }
        scope.launch {
            try { val r = app.conversations.search(query, 50); if (_state.value.searchQuery == query) update { copy(searchResults = r) } }
            catch (e: CancellationException) { throw e } catch (e: Exception) { update { copy(searchResults = emptyList()) } }
        }
    }

    fun openSearch() { update { copy(searchOpen = true) } }

    fun closeSearch() { update { copy(searchOpen = false, searchResults = null, searchQuery = "") } }

    override fun onCleared() { voiceJob?.cancel() }
}
