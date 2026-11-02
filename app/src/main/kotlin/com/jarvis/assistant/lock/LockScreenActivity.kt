package com.jarvis.assistant.lock

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.jarvis.assistant.R
import com.jarvis.assistant.service.AssistantBus
import com.jarvis.assistant.service.ShowState
import com.jarvis.assistant.platform.Intents
import com.jarvis.assistant.ui.AssistantActivity
import com.jarvis.assistant.ui.kit.BaseActivity
import com.jarvis.assistant.ui.kit.MATCH
import com.jarvis.assistant.ui.kit.MainThread
import com.jarvis.assistant.ui.kit.OrbView
import com.jarvis.assistant.ui.kit.Ui
import com.jarvis.assistant.ui.kit.WRAP
import com.jarvis.assistant.ui.kit.add
import com.jarvis.assistant.voice.VoiceEvent
import com.jarvis.core.engine.EngineEvent
import com.jarvis.core.engine.EngineState
import com.jarvis.core.lock.LockButton
import com.jarvis.core.lock.LockScreenLogic
import com.jarvis.core.lock.UnlockRetry
import com.jarvis.core.model.AssistantReply
import com.jarvis.core.model.Origin
import com.jarvis.core.model.Requirement
import com.jarvis.core.ports.PermState
import com.jarvis.core.settings.Keys
import com.jarvis.core.util.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The assistant as it appears over the lock screen: one orb, one answer, a few buttons, a microphone, and (if allowed) a text box. It never shows chat history.
 *
 * It never unlocks, skips or weakens the keyguard. Everything is decided by the lock policy in :core, which the engine applies to the lock state passed with each
 * request: harmless things run, personal things come back redacted ("Done. Unlock your phone to see the details."), and anything that needs the user present is
 * refused with an "Unlock" button. That button calls KeyguardManager.requestDismissKeyguard, which shows the system's own PIN / pattern / biometric prompt; only
 * if that succeeds is the refused request run again (exactly once). Cancelling drops it.
 *
 * Opened by the lock-screen assistant gesture, the Quick Settings tile, the listening notification's "Talk" action and (when allowed) a full-screen notification. It closes itself
 * when the screen turns off, when it is left, and after [LockScreenLogic.IDLE_TIMEOUT_MS] of no interaction. Designed against the Android API; not verified on a device (see docs/LOCK_SCREEN.md).
 */
class LockScreenActivity : BaseActivity() {
    private val scope = CoroutineScope(SupervisorJob() + MainThread)
    private val retry = UnlockRetry()
    private var voiceJob: Job? = null
    private var submitJob: Job? = null
    private var micOpen = false
    private var busy = false
    private var unlocking = false
    private var lastInteractionMs = SystemClock.nowMs()

    private lateinit var orb: OrbView
    private lateinit var status: TextView
    private lateinit var heard: TextView
    private lateinit var answer: TextView
    private lateinit var buttons: LinearLayout
    private lateinit var field: EditText
    private var micButton: LinearLayout? = null

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)                                   // draw over the keyguard; the keyguard itself stays in place
        if (intent.getBooleanExtra(Intents.EXTRA_TURN_SCREEN_ON, false)) setTurnScreenOn(true)
        buildUi()
        setBackEnabled(true)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
        observe()
        if (intent.getBooleanExtra(AssistantActivity.EXTRA_LISTEN, false)) window.decorView.post { startMic() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        touch()
        if (intent.getBooleanExtra(Intents.EXTRA_TURN_SCREEN_ON, false)) setTurnScreenOn(true)
        if (intent.getBooleanExtra(AssistantActivity.EXTRA_LISTEN, false)) startMic()
    }

    // ------------------------------------------------------------------------------------------------ UI
    private fun s(id: Int) = getString(id)

    private fun buildUi() {
        val lock = container.lockState.read()
        val modeOn = container.settings.flag(Keys.LOCK_MODE_ENABLED, false)
        val typing = LockScreenLogic.typingAllowed(lock, modeOn)

        orb = OrbView(this).apply { setColors(ui.pal.accent, ui.pal.accent2, ui.pal.err) }
        status = ui.text(s(R.string.lock_idle), 15f, ui.pal.muted, center = true).apply { accessibilityLiveRegion = TextView.ACCESSIBILITY_LIVE_REGION_POLITE }
        heard = ui.text("", 15f, ui.pal.muted, maxLines = 3, center = true)
        answer = ui.text("", 22f, ui.pal.text, bold = true, center = true).apply { accessibilityLiveRegion = TextView.ACCESSIBILITY_LIVE_REGION_POLITE }
        buttons = ui.row(10, center = true) { gravity = Gravity.CENTER }
        field = ui.field(s(R.string.lock_type_hint), imeSend = true).apply {
            isEnabled = typing
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { submitTyped(); true } else false }
        }

        val micBtn = ui.button(s(R.string.lock_mic), Ui.Kind.PRIMARY, "mic") { toggleMic() }
        micButton = micBtn

        val root = FrameLayout(this)
        root.setBackgroundColor(ui.pal.bg)
        val column = ui.column(padH = 24, padV = 16, gap = 12).apply { gravity = Gravity.CENTER_HORIZONTAL }

        column.add(ui.row(0) {
            add(ui.flexSpace(), 0, WRAP, 1f)
            add(ui.iconButton("x", s(R.string.lock_close), ui.pal.muted) { finish() }, WRAP, WRAP)
        })
        column.add(orb, ui.dp(160), ui.dp(160), 0f, ui = ui).also { (it.layoutParams as LinearLayout.LayoutParams).gravity = Gravity.CENTER_HORIZONTAL }
        column.add(status)
        column.add(heard)
        column.add(ui.scroll(ui.column(gap = 14) { gravity = Gravity.CENTER_HORIZONTAL; addView(answer); addView(buttons) }), MATCH, 0, 1f)
        if (lock.protectPersonalData) column.add(ui.muted(s(R.string.lock_privacy_note), 12.5f).apply { gravity = Gravity.CENTER })
        if (typing) {
            column.add(ui.row(8) { add(field, 0, WRAP, 1f) })
        } else {
            column.add(ui.muted(s(R.string.lock_typing_off), 12.5f).apply { gravity = Gravity.CENTER })
        }
        column.add(micBtn)
        column.add(ui.button(s(R.string.lock_unlock_open), Ui.Kind.TEXT, "unlock") { requestUnlock(openFullApp = true) })

        root.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))
        com.jarvis.assistant.ui.kit.Edge.listen(root) { ins -> column.setPadding(ui.dp(24) + ins.left, ins.top + ui.dp(16), ui.dp(24) + ins.right, maxOf(ins.bottom, ins.imeBottom) + ui.dp(16)) }
        setContentView(root)
    }

    private fun setStatus(id: Int) { status.text = s(id) }

    private fun clearAnswer() { answer.text = ""; buttons.removeAllViews() }

    private fun showMessage(text: String, error: Boolean = false, offerUnlock: Boolean = false) {
        answer.text = text
        answer.setTextColor(if (error) ui.pal.err else ui.pal.text)
        buttons.removeAllViews()
        if (offerUnlock) buttons.addView(ui.button(s(R.string.lock_unlock), Ui.Kind.PRIMARY, "unlock") { requestUnlock(openFullApp = false) })
        answer.announceForAccessibility(text)
    }

    /** What the engine returned (already redacted for the lock state) as text plus the few buttons a locked screen supports. No cards, no history. */
    private fun showReply(reply: AssistantReply) {
        answer.text = reply.text
        answer.setTextColor(if (reply.ok) ui.pal.text else ui.pal.err)
        buttons.removeAllViews()
        for (b in LockScreenLogic.buttonsFor(reply)) {
            when (b) {
                is LockButton.Answer -> buttons.addView(ui.button(b.label, if (b.primary) Ui.Kind.PRIMARY else Ui.Kind.GHOST) { send(b.answer, Origin.LOCK_SCREEN) })
                is LockButton.Unlock -> buttons.addView(ui.button(b.label, Ui.Kind.PRIMARY, "unlock") { requestUnlock(openFullApp = false) })
                is LockButton.OpenFullApp -> buttons.addView(ui.button(b.label, Ui.Kind.GHOST, "unlock") { requestUnlock(openFullApp = true) })
            }
        }
        setStatus(R.string.lock_idle)
        answer.announceForAccessibility(reply.text)
    }

    // ------------------------------------------------------------------------------------------------ what other parts of the app are doing
    private fun observe() {
        scope.launch { container.engine.state.collect { st -> orb.mode = if (micOpen) EngineState.LISTENING else st; if (st == EngineState.SPEAKING) touch() } }
        scope.launch {
            // Requests handled by the wake-word service ("Hey JARVIS ..." while locked) are shown here too. Their text is already safe for the lock state.
            container.engine.events.collect { ev ->
                if (ev is EngineEvent.AssistantSaid && ev.origin == Origin.WAKE) { touch(); showReply(ev.reply) }
            }
        }
        scope.launch {
            AssistantBus.requests.collect { r ->
                touch()
                when (r.state) {
                    ShowState.LISTENING -> { clearAnswer(); heard.text = ""; setStatus(R.string.lock_listening) }
                    ShowState.HEARD -> { r.text?.let { heard.text = it }; setStatus(R.string.lock_thinking) }
                    ShowState.DONE -> { r.text?.let { showMessage(it) }; setStatus(R.string.lock_idle) }
                    ShowState.FAILED -> { r.text?.let { showMessage(it, error = true) }; setStatus(R.string.lock_idle) }
                }
            }
        }
        scope.launch { AssistantBus.levels.collect { if (!micOpen) orb.level = it } }
        scope.launch {
            while (isActive) {
                delay(1_000)
                if (LockScreenLogic.shouldClose(SystemClock.nowMs(), lastInteractionMs, busy || micOpen || unlocking || container.speaker.isSpeaking())) finish()
            }
        }
    }

    private fun touch() { lastInteractionMs = SystemClock.nowMs() }

    // ------------------------------------------------------------------------------------------------ talking
    private fun toggleMic() { if (micOpen) stopMic() else startMic() }

    private fun stopMic() { voiceJob?.cancel() }

    private fun startMic() {
        touch()
        if (container.permissions.state(Requirement.MICROPHONE) != PermState.GRANTED) {
            showMessage(s(R.string.lock_mic_denied), error = true, offerUnlock = true); return
        }
        if (micOpen) return
        container.speaker.stop()
        submitJob?.cancel(); busy = false
        voiceJob?.cancel()
        voiceJob = scope.launch {
            val showWords = !container.lockState.read().protectPersonalData   // what was heard is shown only when nothing needs protecting
            micOpen = true
            clearAnswer(); heard.text = ""
            micButton?.contentDescription = s(R.string.lock_mic_stop)
            container.engine.setListening(true)
            setStatus(R.string.lock_listening)
            var finalText: String? = null
            try {
                container.voice.listenOnce().collect { ev ->
                    touch()
                    when (ev) {
                        VoiceEvent.Ready -> setStatus(R.string.lock_listening)
                        VoiceEvent.Speaking -> setStatus(R.string.lock_hearing)
                        is VoiceEvent.Level -> orb.level = ev.value
                        is VoiceEvent.Partial -> if (showWords) heard.text = ev.text
                        is VoiceEvent.Final -> finalText = ev.text
                        is VoiceEvent.Failed -> showMessage(ev.message, error = true, offerUnlock = ev.error == com.jarvis.assistant.voice.VoiceError.NO_PERMISSION)
                    }
                }
            } finally {
                container.engine.setListening(false)
                orb.level = 0f
                micOpen = false
                micButton?.contentDescription = s(R.string.lock_mic)
                if (finalText == null) setStatus(R.string.lock_idle)
            }
            finalText?.let { send(it, Origin.VOICE) }
        }
    }

    private fun submitTyped() {
        val t = field.text.toString().trim()
        if (t.isEmpty()) return
        field.setText("")
        send(t, Origin.LOCK_SCREEN)
    }

    /** One request through the engine with the lock state read right now. A reply that asks for an unlock is remembered for [retry]. */
    private fun send(text: String, origin: Origin) {
        val t = text.trim()
        if (t.isEmpty()) return
        touch()
        submitJob?.cancel()
        clearAnswer()
        heard.text = if (container.lockState.read().protectPersonalData) "" else t
        setStatus(R.string.lock_thinking)
        busy = true
        submitJob = scope.launch {
            val reply = try {
                container.engine.submit(t, origin, container.lockState.read(), echo = true)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                container.appLog.w("Lock", "request failed (${e.javaClass.simpleName})")
                AssistantReply.error(s(R.string.lock_failed))
            } finally { busy = false }
            retry.onReply(t, reply)
            touch()
            showReply(reply)
        }
    }

    // ------------------------------------------------------------------------------------------------ unlocking (always by the system)
    private fun requestUnlock(openFullApp: Boolean) {
        touch()
        val km = getSystemService(KeyguardManager::class.java)
        if (km == null || !km.isKeyguardLocked) { afterUnlock(true, openFullApp); return }
        unlocking = true
        try {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() { unlocking = false; afterUnlock(true, openFullApp) }
                override fun onDismissCancelled() { unlocking = false; afterUnlock(false, openFullApp) }
                override fun onDismissError() { unlocking = false; retry.clear(); showMessage(s(R.string.lock_unlock_error), error = true) }
            })
        } catch (e: Exception) {
            unlocking = false; retry.clear()
            container.appLog.w("Lock", "keyguard prompt failed (${e.javaClass.simpleName})")
            showMessage(s(R.string.lock_unlock_error), error = true)
        }
    }

    private fun afterUnlock(succeeded: Boolean, openFullApp: Boolean) {
        touch()
        if (!succeeded) { retry.onUnlockResult(false); showMessage(s(R.string.lock_unlock_cancelled)); return }
        container.lockState.authenticatedAtMs = SystemClock.nowMs()        // the user just passed the system's own authentication
        val again = retry.onUnlockResult(true)
        if (openFullApp) {
            startActivity(Intent().setClassName(packageName, Intents.MAIN_ACTIVITY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish(); return
        }
        if (again != null) send(again, Origin.LOCK_SCREEN) else showMessage(s(R.string.lock_unlocked))
    }

    // ------------------------------------------------------------------------------------------------ leaving
    override fun onBackRequested(): Boolean = false
    override fun onBackAtRoot() { finish() }

    override fun onStop() {
        super.onStop()
        // Leaving closes the lock-screen assistant so nothing stays on screen for someone else; the system's PIN prompt covers the activity too, which is not "leaving".
        if (!isChangingConfigurations && !unlocking) finish()
    }

    override fun onDestroy() {
        try { unregisterReceiver(screenOff) } catch (_: Exception) { }
        voiceJob?.cancel()
        scope.cancel()
        container.engine.setListening(false)
        super.onDestroy()
    }
}
