package com.jarvis.assistant.voice

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.SpeechRecognizer
import android.os.Build
import com.jarvis.core.model.Requirement
import com.jarvis.core.ports.PermissionChecker
import com.jarvis.core.settings.Keys
import com.jarvis.core.settings.Settings
import com.jarvis.core.util.JarvisLog
import com.jarvis.core.wake.RestartBackoff
import com.jarvis.core.wake.WakeBackend
import com.jarvis.core.wake.WakeDetection
import com.jarvis.core.wake.WakeEngineChooser
import com.jarvis.core.wake.WakeStats
import com.jarvis.core.wake.WakeWordEngine
import com.jarvis.core.wake.NeuralWakeEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

/** What the wake-word layer is really doing, for the status line in settings and the foreground notification. */
sealed class WakeStatus {
    object Off : WakeStatus()
    object NeedsModels : WakeStatus()
    object NeedsPermission : WakeStatus()
    object Starting : WakeStatus()
    data class Listening(val backend: WakeBackend, val phrase: String) : WakeStatus()
    data class Paused(val reason: String) : WakeStatus()
    /** [willRetry]: a restart is already scheduled; false means the controller gave up and the user has to do something. */
    data class Error(val message: String, val willRetry: Boolean = false) : WakeStatus()
}

/** The wake word was heard. [command] is non-empty only for the speech engine when the command followed the wake word in one breath. */
data class WakeEvent(val phrase: String, val score: Float, val command: String, val backend: WakeBackend)

/**
 * Owns the wake-word engine and the microphone loop. Everything public is main-thread; the heavy work (loading the models, the audio thread) is not.
 *
 * Life cycle: [start] picks an engine (see [WakeEngineChooser]), loads it, opens the microphone and reports [status]. On a wake word the microphone is
 * RELEASED (so SpeechRecognizer can use it) and [onWake] is called; the caller must later call [resume]. [pause]/[resume] take a reason so the call monitor
 * and the command handling cannot un-pause each other: the microphone is only taken again when no reason is left.
 * Failures restart with back-off ([RestartBackoff]); after too many the status becomes [WakeStatus.Error] instead of retrying forever.
 */
class WakeController(
    context: Context,
    private val settings: Settings,
    private val permissions: PermissionChecker,
    private val models: WakeModelStore,
    private val log: JarvisLog = JarvisLog.None,
    private val onWake: (WakeEvent) -> Unit,
) {
    private val ctx = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "jarvis-wake-init").apply { isDaemon = true } }
    private val audioManager: AudioManager = ctx.getSystemService(AudioManager::class.java)
    private val capture = AudioCapture(ctx, log)
    private val backoff = RestartBackoff()

    private val _status = MutableStateFlow<WakeStatus>(WakeStatus.Off)
    val status: StateFlow<WakeStatus> = _status.asStateFlow()

    private var wanted = false
    private var generation = 0
    private var backend = WakeBackend.NONE
    @Volatile private var neural: NeuralWakeEngine? = null
    private var speech: SpeechWakeEngine? = null
    private val pauseReasons = linkedSetOf<String>()
    private var silenced = false
    private var pausedByCall = false
    private var modelsVerified: Boolean? = null

    /** Forget the cached "models are installed and match their checksums" answer (after a download or removal). */
    fun invalidateModels() { modelsVerified = null }

    /** Counters of the neural engine for the diagnostics screen (null when another engine runs). */
    fun stats(): WakeStats? = neural?.stats

    // ------------------------------------------------------------------------------------------------ public API (main thread)
    fun start() {
        wanted = true
        pauseReasons.remove(REASON_FAILURE)
        backoff.reset()
        modelsVerified = null
        launch()
    }

    fun stop() {
        wanted = false
        generation++
        main.removeCallbacks(callPoll)
        main.removeCallbacks(retry)
        pauseReasons.clear()
        pausedByCall = false
        tearDown()
        _status.value = WakeStatus.Off
    }

    /** Releases the microphone until [resume] is called with the same [reason]. */
    fun pause(reason: String) {
        if (!wanted) return
        pauseReasons.add(reason)
        if (backend == WakeBackend.NEURAL) capture.stop() else speech?.stop()
        publishPaused()
    }

    fun resume(reason: String) {
        if (!wanted) return
        pauseReasons.remove(reason)
        if (pauseReasons.isEmpty()) launch()
    }

    // ------------------------------------------------------------------------------------------------ starting
    private fun launch() {
        if (!wanted) return
        if (pauseReasons.isNotEmpty()) { publishPaused(); return }
        if (!permissions.isGranted(Requirement.MICROPHONE)) { tearDown(); _status.value = WakeStatus.NeedsPermission; return }
        val modelsOk = modelsVerified ?: models.verified().also { modelsVerified = it }
        val choice = WakeEngineChooser.choose(
            settings.get(Keys.WAKE_ENGINE), modelsOk,
            onDeviceSpeech = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx),
            anySpeech = SpeechRecognizer.isRecognitionAvailable(ctx),
        )
        when (choice.backend) {
            WakeBackend.NONE -> {
                tearDown()
                _status.value = when {
                    choice.needsModels -> WakeStatus.NeedsModels
                    settings.get(Keys.WAKE_ENGINE).equals("off", true) -> WakeStatus.Off
                    else -> WakeStatus.Error(choice.reason)
                }
            }
            WakeBackend.NEURAL -> startNeural()
            WakeBackend.SPEECH -> startSpeech()
        }
        main.removeCallbacks(callPoll)
        main.postDelayed(callPoll, CALL_POLL_MS)
    }

    private fun startNeural() {
        val existing = neural
        if (backend == WakeBackend.NEURAL && existing != null) { // paused and resumed: keep the loaded models, just take the microphone again
            if (capture.isRunning) { publishListening(); return }
            existing.reset()
            val problem = capture.start(audioListener(generation, existing))
            if (problem != null) fail(problem) else { backoff.reset(); publishListening() }
            return
        }
        tearDown()
        backend = WakeBackend.NEURAL
        _status.value = WakeStatus.Starting
        val gen = ++generation
        worker.execute {
            val engine = try { OpenWakeWordEngine.create(models, settings) } catch (e: Throwable) {
                log.w("Wake", "could not load the wake word models (${e.javaClass.simpleName})")
                main.post { if (gen == generation) { modelsVerified = null; fail("The wake word models couldn't be loaded (${e.javaClass.simpleName}). Try removing and downloading them again.") } }
                return@execute
            }
            main.post {
                if (gen != generation || !wanted || pauseReasons.isNotEmpty()) {
                    engine.close()
                    if (gen == generation && wanted) launch() // paused meanwhile: the models are loaded again on resume
                    return@post
                }
                neural = engine
                val problem = capture.start(audioListener(gen, engine))
                if (problem != null) { fail(problem); return@post }
                backoff.reset()
                publishListening()
            }
        }
    }

    private fun audioListener(gen: Int, engine: WakeWordEngine) = object : AudioCapture.Listener {
        override fun onAudio(pcm: ShortArray, length: Int) {
            if (gen != generation) return
            val hit: WakeDetection = try { engine.feed(pcm, length) } catch (e: Throwable) {
                log.w("Wake", "detector failed (${e.javaClass.simpleName})")
                capture.requestStop()
                main.post { if (gen == generation) fail("The wake word detector crashed (${e.javaClass.simpleName}).") }
                return
            } ?: return
            // Take the microphone off the wake word BEFORE anyone else wants it, and only report once it is really free.
            capture.requestStop()
            main.post {
                if (gen != generation || !wanted) return@post
                capture.stop() // waits (briefly) until the audio thread has released the microphone
                pauseReasons.add(REASON_COMMAND)
                _status.value = WakeStatus.Paused("Heard “${hit.phrase}”")
                onWake(WakeEvent(hit.phrase, hit.score, "", WakeBackend.NEURAL))
            }
        }

        override fun onFailure(message: String) { main.post { if (gen == generation) fail(message) } }

        override fun onSilenced(silenced: Boolean) {
            main.post {
                if (gen != generation) return@post
                this@WakeController.silenced = silenced
                if (pauseReasons.isEmpty()) {
                    if (silenced) _status.value = WakeStatus.Paused("Android is blocking the microphone for JARVIS right now (it is being given silence). Open the app to restore it.")
                    else publishListening()
                }
            }
        }
    }

    private fun startSpeech() {
        tearDown()
        backend = WakeBackend.SPEECH
        _status.value = WakeStatus.Starting
        val gen = ++generation
        val engine = SpeechWakeEngine(ctx, settings, log)
        speech = engine
        engine.start(object : SpeechWakeEngine.Callback {
            override fun onWake(command: String) {
                if (gen != generation || !wanted) return
                pauseReasons.add(REASON_COMMAND)
                _status.value = WakeStatus.Paused("Heard the wake word")
                onWake(WakeEvent(settings.assistantName().lowercase(), 1f, command, WakeBackend.SPEECH))
            }
            override fun onListening(listening: Boolean) {
                if (gen != generation) return
                if (listening) { backoff.reset(); if (pauseReasons.isEmpty()) publishListening() }
            }
            override fun onFatal(message: String) { if (gen == generation) fail(message) }
        })
    }

    // ------------------------------------------------------------------------------------------------ status, failures, calls
    private fun publishListening() {
        if (!wanted) return
        _status.value = when {
            pauseReasons.isNotEmpty() -> WakeStatus.Paused(reasonText())
            silenced && backend == WakeBackend.NEURAL -> WakeStatus.Paused("Android is blocking the microphone for JARVIS right now (it is being given silence). Open the app to restore it.")
            else -> WakeStatus.Listening(backend, if (backend == WakeBackend.NEURAL) com.jarvis.core.wake.WakeModels.PHRASE else settings.assistantName().lowercase())
        }
    }

    private fun publishPaused() { _status.value = WakeStatus.Paused(reasonText()) }

    private fun reasonText() = when {
        REASON_CALL in pauseReasons -> "Paused during a phone call"
        REASON_COMMAND in pauseReasons -> "Handling a request"
        REASON_USER in pauseReasons -> "Paused"
        else -> "Paused"
    }

    private val retry = Runnable { if (wanted) { pauseReasons.remove(REASON_FAILURE); launch() } }

    private fun fail(message: String) {
        tearDown()
        if (!wanted) return
        if (backoff.exhausted) { _status.value = WakeStatus.Error(message); log.w("Wake", "giving up: $message"); return }
        val delay = backoff.nextDelayMs()
        log.i("Wake", "restarting in ${delay}ms: $message")
        _status.value = WakeStatus.Error(message, willRetry = true)
        pauseReasons.add(REASON_FAILURE)
        main.removeCallbacks(retry)
        main.postDelayed(retry, delay)
    }

    /** Pauses while a call is ringing or in progress, resumes afterwards (only if the call is what paused it). */
    private val callPoll = object : Runnable {
        override fun run() {
            if (!wanted) return
            val mode = audioManager.mode
            val inCall = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_RINGTONE || mode == AudioManager.MODE_IN_COMMUNICATION
            if (inCall && REASON_CALL !in pauseReasons) { pausedByCall = true; pause(REASON_CALL) }
            else if (!inCall && pausedByCall) { pausedByCall = false; resume(REASON_CALL) }
            main.postDelayed(this, CALL_POLL_MS)
        }
    }

    private fun tearDown() {
        capture.stop()
        speech?.stop()
        speech = null
        neural?.close()
        neural = null
        backend = WakeBackend.NONE
        silenced = false
    }

    /** Releases everything, including the worker thread. The controller cannot be used afterwards. */
    fun shutdown() {
        stop()
        worker.shutdownNow()
    }

    companion object {
        private const val REASON_COMMAND = "command"
        private const val REASON_CALL = "call"
        private const val REASON_USER = "user"
        private const val REASON_FAILURE = "failure"
        private const val CALL_POLL_MS = 2_000L
        const val REASON_FOR_COMMAND = REASON_COMMAND
        const val REASON_FOR_USER = REASON_USER
    }
}
