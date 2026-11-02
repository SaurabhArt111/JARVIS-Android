package com.jarvis.core.engine

import com.jarvis.core.agent.AgentLoop
import com.jarvis.core.agent.Compose
import com.jarvis.core.agent.Executed
import com.jarvis.core.agent.Honesty
import com.jarvis.core.agent.Prompts
import com.jarvis.core.agent.ToolExecutor
import com.jarvis.core.agent.WorldContext
import com.jarvis.core.ai.LlmErrorKind
import com.jarvis.core.ai.LlmEvent
import com.jarvis.core.ai.LlmException
import com.jarvis.core.ai.LlmGateway
import com.jarvis.core.ai.LlmRole
import com.jarvis.core.greeting.Greeting
import com.jarvis.core.lock.LockPolicy
import com.jarvis.core.lock.LockState
import com.jarvis.core.model.ActionType
import com.jarvis.core.model.AssistantReply
import com.jarvis.core.model.Card
import com.jarvis.core.model.CardItem
import com.jarvis.core.model.Origin
import com.jarvis.core.model.Outcome
import com.jarvis.core.model.ReplyKind
import com.jarvis.core.model.Requirement
import com.jarvis.core.model.UiAction
import com.jarvis.core.offline.OfflineParser
import com.jarvis.core.ports.ImageEvent
import com.jarvis.core.ports.ImageEventType
import com.jarvis.core.ports.PermState
import com.jarvis.core.ports.ReminderState
import com.jarvis.core.ports.Services
import com.jarvis.core.ports.SpeechChannel
import com.jarvis.core.routing.CapabilityRouter
import com.jarvis.core.safety.Safety
import com.jarvis.core.settings.Keys
import com.jarvis.core.tools.ToolContext
import com.jarvis.core.tools.impl.T
import com.jarvis.core.tools.impl.ToolRegistry
import com.jarvis.core.util.stripWake
import com.jarvis.core.wake.WakeText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/**
 * The assistant engine (port of Engine.py): one place where text / voice requests become actions.
 *
 *     safety guard → pending question → AI agent (tools) → [no AI reachable: offline command parser] → honest result → delivery (speech policy, lock redaction) → memory
 *
 * Requests never interleave (one [Mutex]), a duplicate within 1.5 s is ignored, [cancel] stops the request in flight and the speech.
 * Everything the UI needs is on [state] and [events]; nothing here touches Android.
 */
class DefaultAssistantEngine(
    private val services: Services,
    private val llm: LlmGateway,
    private val scope: CoroutineScope,
    private val registry: ToolRegistry = ToolRegistry.default(services),
    private val lockPolicy: LockPolicy = LockPolicy(services.settings, services.clock),
    private val router: CapabilityRouter = CapabilityRouter(services, registry),
    /** Live keyguard state for things the engine does on its own (reminder delivery, greeting, image results). */
    private val lockProvider: () -> LockState = { LockState.UNLOCKED },
    private val onlineProvider: () -> Boolean = { services.permissions.state(Requirement.NETWORK) != PermState.DENIED },
    private val rng: Random = Random.Default,
) : AssistantEngine {

    val dialog = Dialog(services.clock)
    val executor = ToolExecutor(registry, services, lockPolicy)
    private val agent = AgentLoop(llm, executor, services)
    private val world = WorldContext(services, registry, router, lockPolicy)
    internal val parser = OfflineParser(services, registry.organizer) { name, args, ctx -> executor.execute(name, args, ctx) }

    private val _state = MutableStateFlow(EngineState.IDLE)
    override val state: StateFlow<EngineState> = _state
    private val _events = MutableSharedFlow<EngineEvent>(replay = 0, extraBufferCapacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: SharedFlow<EngineEvent> = _events

    private val gate = Mutex()
    private val speechGate = Mutex()
    private var current: Deferred<AssistantReply>? = null
    private var lastSubmit: Pair<String, Long> = "" to 0L
    private var lastReply: AssistantReply? = null
    private val noteTimes = HashMap<String, Long>()

    init {
        scope.launch {
            llm.events.collect { ev ->
                if (ev is LlmEvent.Fallback) notice("fb:${ev.to}", NoticeLevel.INFO, "Using backup AI", "${ev.failed.joinToString()} didn't work (${ev.why.take(90)}). Answered by ${ev.to}.", 120_000)
            }
        }
        scope.launch { services.images.events.collect { onImageEvent(it) } }
    }

    // ================================================================================================ public API
    override fun aiReady(): Boolean = llm.configured()

    override fun setListening(listening: Boolean) {
        if (listening && (_state.value == EngineState.IDLE || _state.value == EngineState.ERROR)) _state.value = EngineState.LISTENING
        else if (!listening && _state.value == EngineState.LISTENING) _state.value = EngineState.IDLE
    }

    override fun cancel() {
        services.speaker.stop()
        val j = synchronized(this) { current }
        j?.cancel()
        if (_state.value != EngineState.IDLE) _state.value = EngineState.IDLE
    }

    override suspend fun submit(text: String, origin: Origin, lock: LockState, echo: Boolean): AssistantReply {
        val t = text.trim()
        if (t.isEmpty()) return AssistantReply.chat("I didn't catch that. Could you say it again?", "mic-off").copy(speak = false)
        val now = services.clock.nowMs()
        val (prevText, prevAt) = lastSubmit
        if (t.lowercase() == prevText && now - prevAt < DUPLICATE_MS) {
            services.log.i("Engine", "ignored a duplicate submit")
            return (lastReply ?: AssistantReply.chat("One moment.")).copy(speak = false, extras = (lastReply?.extras ?: emptyMap()) + ("duplicate" to true))
        }
        lastSubmit = t.lowercase() to now

        // only the name was said: wake up, start listening
        val wake = WakeText.extractCommand(t, services.settings.wakeWords())
        if (wake.matched && wake.rest.isEmpty()) {
            if (echo) _events.emit(EngineEvent.UserSaid(t, origin))
            val ack = listOf("Yes, {a}?", "I'm listening, {a}.", "Go ahead, {a}.", "At your service, {a}.")[rng.nextInt(4)].replace("{a}", Greeting.address(services.settings.userName()))
            val reply = AssistantReply(ack, kind = ReplyKind.CHAT, icon = "mic", speak = false, extras = mapOf("quiet" to true, "listen" to true, "expect_reply" to true))
            services.memory.rememberTurn(t, ack)
            _events.emit(EngineEvent.AssistantSaid(reply, origin))
            return reply
        }
        if (echo) _events.emit(EngineEvent.UserSaid(t, origin))

        val job = scope.async { gate.withLock { handle(t, origin, lock) } }
        synchronized(this) { current = job }
        try {
            return job.await()
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) { job.cancel(); _state.value = EngineState.IDLE; throw e }
            _state.value = EngineState.IDLE
            return AssistantReply("Okay, I stopped.", kind = ReplyKind.CHAT, icon = "x", speak = false)
        } finally {
            synchronized(this) { if (current === job) current = null }
        }
    }

    override suspend fun greet(speak: Boolean) {
        val nowZ = services.clock.now()
        val now = nowZ.toLocalDateTime()
        val today = registry.reminders.upcoming().filter { it.due.toLocalDate() == now.toLocalDate() }
        val text = Greeting.startupMessage(today, now, services.settings.userName(), rng)
        services.memory.remember("assistant", text)
        val reply = AssistantReply(text, kind = ReplyKind.CHAT, icon = Greeting.icon(now), speak = false, extras = mapOf("greeting" to true), sensitive = today.isNotEmpty())
        val lock = lockProvider()
        val shown = lockPolicy.redact(reply, lock)
        _events.emit(EngineEvent.AssistantSaid(shown, Origin.SCHEDULED))
        _events.emit(EngineEvent.Activity(reply.icon, shown.text.take(90), true))
        if (speak && !muted() && services.settings.flag(Keys.SPEAK_GREETING, true)) scope.launch { speak(shown.text, SpeechChannel.GREETING) }
    }

    /**
     * Called by the alarm receiver when a reminder is due ([isTimer] = false: [id] is the reminder id) or by the timer service ([isTimer] = true: [id] is the
     * timer's label). Marks the reminder fired, tells every surface, and speaks it when the user wants that (lock-screen aware).
     */
    suspend fun deliverReminder(id: String, isTimer: Boolean = false) {
        val lock = lockProvider()
        if (isTimer) {
            val label = id.ifBlank { "Timer" }
            _events.emit(EngineEvent.ReminderFired(id, label, true))
            _events.emit(EngineEvent.Activity("clock", "$label finished", true))
            if (services.settings.flag(Keys.SPEAK_REMINDERS, true) && !muted()) speak("Your ${label.lowercase()} is finished.", SpeechChannel.REMINDER)
            return
        }
        // The alarm receiver may already have marked it FIRED (to post its notification); a reminder that is still PENDING here fired early, was cancelled or is
        // a duplicate, and stays silent.
        val r = registry.reminders.onFired(id)
            ?: services.reminders.get(id)?.takeIf { it.state == ReminderState.FIRED && it.firedAt?.let { f -> services.clock.instant().epochSecond - f.epochSecond in 0..120 } == true }
            ?: run { services.log.i("Engine", "a reminder alarm was ignored (missing, early, cancelled or already delivered)"); return }
        val secret = lockPolicy.effectivelyLocked(lock)
        _events.emit(EngineEvent.ReminderFired(r.id, if (secret) "You have a reminder." else r.text, false))
        _events.emit(EngineEvent.Activity("bell", if (secret) "Reminder" else "Reminder: ${r.text}", true))
        if (services.settings.flag(Keys.SPEAK_REMINDERS, true) && !muted()) speak(if (secret) "You have a reminder. Unlock your phone to see it." else "Reminder: ${r.text}.", SpeechChannel.REMINDER)
    }

    /** Reports something that finished AFTER the reply (a finished image, a quick reply): shown, stored, and spoken when asked. */
    suspend fun announce(reply: AssistantReply) {
        val lock = lockProvider()
        val shown = lockPolicy.redact(reply, lock)
        services.memory.remember("assistant", reply.text)
        _events.emit(EngineEvent.AssistantSaid(shown, Origin.SCHEDULED))
        _events.emit(EngineEvent.Activity(shown.icon, shown.text.take(90), shown.ok))
        if (shown.speak && !muted() && shown.kind != ReplyKind.INFO && services.settings.flag(Keys.SPEAK_REPLIES, false)) speak(shown.text, SpeechChannel.REPLY)
    }

    // ================================================================================================ pipeline
    private suspend fun handle(text: String, origin: Origin, lock: LockState): AssistantReply {
        _state.value = EngineState.THINKING
        _events.emit(EngineEvent.Thinking("Thinking…"))
        val t0 = services.clock.nowMs()
        val ctx = ToolContext(services, origin, lock, dialog) { announce(it) }
        val raw = try {
            process(text, ctx)
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmException) {
            llmFailure(e)
        } catch (e: Exception) {
            services.log.e("Engine", "request failed: ${e.javaClass.simpleName}", e)
            notice("cmd", NoticeLevel.ERROR, "Command failed", e.javaClass.simpleName, 10_000)
            AssistantReply.error("Something went wrong while doing that. Nothing was changed that I know of; the details are in the app log.")
        }
        val reply = if (raw.kind != ReplyKind.ERROR && raw.text.trimEnd().endsWith("?") && raw.extras["expect_reply"] != true) raw.copy(extras = raw.extras + ("expect_reply" to true)) else raw
        services.log.i("Engine", "handled a request in ${services.clock.nowMs() - t0} ms -> kind=${reply.kind} ok=${reply.ok}")
        if (reply.kind != ReplyKind.ERROR && reply.extras["duplicate"] != true) {
            services.memory.rememberTurn(text, reply.text)
            if (reply.kind == ReplyKind.ACTION && reply.ok) dialog.noteAction(reply.text.take(140).trimEnd('.'))
        }
        lastReply = reply
        return deliver(reply, origin, lock)
    }

    private suspend fun process(text: String, ctx: ToolContext): AssistantReply {
        if (Safety.isDeleteRequest(text)) return AssistantReply(Safety.refusal(text), ok = false, kind = ReplyKind.BLOCKED, icon = "shield")
        val clean = stripWake(text, services.settings.wakeWords()).ifEmpty { text }
        if (dialog.waiting() != null) dialog.resume(clean)?.let { return it }
        val agentOn = services.settings.flag(Keys.AGENT_MODE, true)
        if (!agentOn) return route(clean, ctx)
        if (!llm.configured()) {
            // no AI at all: simple commands still work
            runOffline(clean, ctx)?.let { return it }
            return AssistantReply.error(
                "I can't answer open questions yet because no AI is set up. Simple commands like timers, volume, calls and opening apps still work — or add an AI key in Settings.",
                "key", listOf(Requirement.API_KEY), listOf(T.grantAction(Requirement.API_KEY)),
            )
        }
        return try {
            val online = onlineProvider()
            val prompt = Prompts.agent(services.settings.assistantName(), services.settings.userName(), services.clock.now(), world.block(dialog, ctx.lock, online))
            agent.run(clean, ctx, prompt, router.toolsFor(ctx.lock, online)) { label -> _events.tryEmit(EngineEvent.Thinking(label)) }
        } catch (e: LlmException) {
            if (!services.settings.flag(Keys.AGENT_FALLBACK, true)) throw e
            services.log.i("Engine", "agent unavailable (${e.kind}) — trying the offline command parser")
            val offline = runOffline(clean, ctx)
            when {
                offline != null -> offline.copy(extras = offline.extras + ("offline" to true))
                e.kind == LlmErrorKind.MODEL -> route(clean, ctx)    // the AI works but can't call tools: plain chat
                else -> throw e
            }
        }
    }

    /** AgentMode Off (or the model cannot call tools): local commands first, then plain chat. */
    private suspend fun route(clean: String, ctx: ToolContext): AssistantReply {
        runOffline(clean, ctx)?.let { return it }
        if (!llm.configured()) throw LlmException("No AI provider is set up. Add a key in Settings to chat.", LlmErrorKind.NO_KEY)
        return chat(clean, ctx)
    }

    /** Chat only: the model talks, never acts. If its answer CLAIMS an action it could not have done, re-read the request as a command or say so plainly. */
    private suspend fun chat(clean: String, ctx: ToolContext): AssistantReply {
        val online = onlineProvider()
        val sys = Prompts.chat(services.settings.assistantName(), services.settings.userName(), services.clock.now(), world.block(dialog, ctx.lock, online))
        val msgs = ArrayList<Map<String, Any?>>()
        msgs += mapOf("role" to "system", "content" to sys)
        services.memory.recent(AgentLoop.CONTEXT_TURNS).forEach { msgs += mapOf("role" to it.role, "content" to it.content) }
        msgs += mapOf("role" to "user", "content" to clean)
        val ans = Honesty.clean(llm.chat(msgs, LlmRole.CHAT, 0.7, 900))
        if (Honesty.claimsAction(ans)) {
            services.log.i("Engine", "chat answer claimed an action it can't have done")
            runOffline(clean, ctx)?.let { return it }
            return AssistantReply(Honesty.HONEST_FALLBACK, ok = false, kind = ReplyKind.CHAT, icon = "help-circle")
        }
        return AssistantReply(ans.ifEmpty { "I didn't catch that — could you say it again?" }, kind = ReplyKind.CHAT, icon = "message")
    }

    /** The offline command parser: its steps run through the very same [ToolExecutor] as the agent's tool calls. Null = not understood. */
    private suspend fun runOffline(clean: String, ctx: ToolContext): AssistantReply? {
        val plan = parser.plan(clean, ctx) ?: return null
        val steps = ArrayList<Executed>()
        for (s in plan.steps) {
            steps += when (s) {
                is OfflineParser.Step.Say -> Executed("say", emptyMap(), null, Outcome(s.reply.ok, s.reply.text, s.reply, verified = true))
                is OfflineParser.Step.Invoke -> executor.execute(s.tool, s.args, ctx)
                is OfflineParser.Step.Organize -> executor.organize(s.text, ctx)
            }
        }
        var text = steps.mapNotNull { it.outcome.reply?.text?.takeIf { t -> t.isNotBlank() } }.joinToString(" ")
        if (plan.rest.isNotEmpty()) text += (if (text.isEmpty()) "" else " ") + "I couldn't work out “${plan.rest.joinToString(" and ").take(80)}” without the AI, so I didn't do that part."
        val reply = Compose.reply(text, steps, calledAny = steps.any { it.spec != null })
        return reply.copy(extras = reply.extras + ("offline" to true))
    }

    // ================================================================================================ delivery
    private suspend fun deliver(reply: AssistantReply, origin: Origin, lock: LockState): AssistantReply {
        val shown = lockPolicy.redact(reply, lock)
        _events.emit(EngineEvent.AssistantSaid(shown, origin))
        _events.emit(EngineEvent.Activity(shown.icon, shown.text.take(90), shown.ok))
        val wantSpeech = shown.speak && !muted() && shown.kind != ReplyKind.INFO && when (origin) {
            Origin.VOICE, Origin.WAKE -> true
            Origin.ISLAND -> services.settings.flag(Keys.ISLAND_SPEAK, false) || services.settings.flag(Keys.SPEAK_REPLIES, false)
            Origin.SCHEDULED, Origin.NOTIFICATION -> false
            else -> services.settings.flag(Keys.SPEAK_REPLIES, false)
        }
        if (wantSpeech) {   // [shown] is already redacted when the phone is locked, so speaking it never reads out personal text
            _state.value = EngineState.SPEAKING
            try {
                if (!speak(shown.text, SpeechChannel.REPLY)) notice("tts", NoticeLevel.WARN, "Voice output", "I couldn't speak that reply. Check the volume and the text-to-speech settings.", 300_000)
            } finally {
                _state.value = EngineState.IDLE
            }
        } else _state.value = EngineState.IDLE
        return shown
    }

    private suspend fun speak(text: String, channel: SpeechChannel): Boolean = speechGate.withLock {
        try {
            services.speaker.speak(text, channel)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            services.log.w("Engine", "speech failed: ${e.javaClass.simpleName}")
            false
        }
    }

    private fun muted() = services.settings.flag(Keys.VOICE_MUTED, false)

    private fun notice(key: String, level: NoticeLevel, title: String, message: String, everyMs: Long) {
        val now = services.clock.nowMs()
        val last = synchronized(noteTimes) { noteTimes[key] ?: 0L }
        if (now - last >= everyMs) {
            synchronized(noteTimes) { noteTimes[key] = now }
            _events.tryEmit(EngineEvent.Notice(level, title, message))
        }
    }

    private fun llmFailure(e: LlmException): AssistantReply {
        val icon = when (e.kind) { LlmErrorKind.NETWORK -> "wifi-off"; LlmErrorKind.AUTH, LlmErrorKind.NO_KEY -> "key"; LlmErrorKind.ACCESS -> "lock"; else -> "alert" }
        val missing = if (e.kind == LlmErrorKind.NO_KEY || e.kind == LlmErrorKind.AUTH) listOf(Requirement.API_KEY) else emptyList()
        notice("ai-err", if (e.kind == LlmErrorKind.RATE || e.kind == LlmErrorKind.NETWORK) NoticeLevel.WARN else NoticeLevel.ERROR, "AI unavailable", e.userMessage, 30_000)
        return AssistantReply.error(e.userMessage, icon, missing, T.actionsFor(missing)).copy(extras = mapOf("ai_error" to e.kind.name.lowercase()))
    }

    // ================================================================================================ images finishing later
    private suspend fun onImageEvent(ev: ImageEvent) {
        _events.emit(EngineEvent.Image(ev))
        when (ev.type) {
            ImageEventType.DONE -> {
                val files = ev.files
                val text = if (files.size == 1) "Your image is ready." else "Your ${files.size} images are ready."
                val reply = AssistantReply(
                    text, icon = "image", speak = true,
                    card = Card("images", "Image ready", ev.prompt.take(100), "image", items = files.map { CardItem(it.name, it.uri) }, fields = mapOf("job" to ev.jobId)),
                    actions = files.take(1).map { UiAction("Open", ActionType.OPEN_FILE, mapOf("uri" to it.uri, "mime" to it.mime, "name" to it.name)) },
                )
                announce(reply)
            }
            ImageEventType.ERROR -> announce(AssistantReply.error(ev.message.ifBlank { "The image couldn't be created." }, "image"))
            else -> {}
        }
    }

    companion object {
        const val DUPLICATE_MS = 1500L
    }
}
