package com.jarvis.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.jarvis.core.settings.Keys
import com.jarvis.core.settings.Settings
import com.jarvis.core.util.JarvisLog
import com.jarvis.core.wake.RestartBackoff
import com.jarvis.core.wake.WakeText

/**
 * Fallback wake word that runs Android's SpeechRecognizer in a loop and checks the transcript for the wake word ([WakeText]). It exists for phones where the
 * neural models aren't downloaded; it is clearly worse than the neural engine:
 *  - battery: the recogniser is running (and transcribing) all the time;
 *  - privacy: unless an ON-DEVICE recogniser is used, the audio goes to the phone's speech service, which may be cloud based;
 *  - some recogniser services beep or show UI each time they restart, and some stop after a minute or two of silence (it restarts with back-off);
 *  - the microphone is held by the recogniser, so nothing else can record meanwhile.
 * "auto" therefore only picks it when an on-device recogniser exists.
 *
 * All methods must be called on the main thread (SpeechRecognizer requires it).
 */
class SpeechWakeEngine(context: Context, private val settings: Settings, private val log: JarvisLog = JarvisLog.None) {
    interface Callback {
        /** The wake word was heard. [command] is what followed it in the same utterance ("jarvis open spotify" -> "open spotify"), or empty. The recogniser is already stopped. */
        fun onWake(command: String)
        /** The recogniser is running again (true) or between restarts (false). */
        fun onListening(listening: Boolean)
        /** Gave up (permission lost, no recogniser, too many failures). */
        fun onFatal(message: String)
    }

    private val ctx = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val backoff = RestartBackoff()
    private var recognizer: SpeechRecognizer? = null
    private var callback: Callback? = null
    private var active = false
    private var delivered = false
    /** Set when the on-device recogniser reported "language not supported/available" (error 12/13): from then on use the standard recogniser. */
    private var onDeviceUnusable = false
    private val restart = Runnable { startRecognizer() }

    val isActive: Boolean get() = active

    fun start(cb: Callback) {
        stop()
        callback = cb
        active = true
        backoff.reset()
        onDeviceUnusable = false
        startRecognizer()
    }

    fun stop() {
        active = false
        handler.removeCallbacks(restart)
        destroyRecognizer()
        callback?.onListening(false)
        callback = null
    }

    private fun destroyRecognizer() {
        val r = recognizer ?: return
        recognizer = null
        try { r.setRecognitionListener(null); r.cancel(); r.destroy() } catch (_: Exception) { }
    }

    private fun wakeWords(): List<String> = settings.wakeWords()

    private fun startRecognizer() {
        if (!active) return
        destroyRecognizer()
        delivered = false
        val preferOnDevice = !onDeviceUnusable && Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)
        val r = try {
            when {
                preferOnDevice -> SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
                SpeechRecognizer.isRecognitionAvailable(ctx) -> SpeechRecognizer.createSpeechRecognizer(ctx)
                else -> null
            }
        } catch (e: Exception) { null }
        if (r == null) { fatal("No speech recogniser is available for the wake word."); return }
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { backoff.reset(); callback?.onListening(true) }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) { check(partialResults) }
            override fun onResults(results: Bundle?) { if (!check(results)) scheduleRestart(failed = false) }
            override fun onError(error: Int) {
                when (error) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> fatal("Microphone permission was withdrawn.")
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> scheduleRestart(failed = false)
                    // 12 = language not supported, 13 = language model not downloaded (on-device recogniser, Android 12+). Retrying the same recogniser can never
                    // succeed, which is what left the wake word dead on phones whose on-device pack is missing: switch to the standard recogniser instead.
                    12, 13 -> {
                        log.i("WakeSpeech", "on-device recogniser cannot use this language (error $error); switching to the standard recogniser")
                        if (preferOnDevice && !onDeviceUnusable) { onDeviceUnusable = true; scheduleRestart(failed = false) }
                        else scheduleRestart(failed = true)
                    }
                    else -> { log.i("WakeSpeech", "recogniser error $error"); scheduleRestart(failed = true) }
                }
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
        settings.get(Keys.INPUT_LANGUAGE).takeIf { it.isNotBlank() }?.let { intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
        try { r.startListening(intent) } catch (e: Exception) { log.w("WakeSpeech", "startListening failed (${e.javaClass.simpleName})"); scheduleRestart(failed = true) }
    }

    /** True if the wake word was found (and delivered). */
    private fun check(bundle: Bundle?): Boolean {
        if (delivered || !active) return false
        val heard = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        for (h in heard) {
            val m = WakeText.extractCommand(h, wakeWords())
            if (m.matched) {
                delivered = true
                val cb = callback
                // stop the recogniser BEFORE telling anyone, so the next consumer of the microphone finds it free
                active = false
                handler.removeCallbacks(restart)
                destroyRecognizer()
                cb?.onListening(false)
                callback = null
                cb?.onWake(m.rest)
                return true
            }
        }
        return false
    }

    private fun scheduleRestart(failed: Boolean) {
        if (!active) return
        callback?.onListening(false)
        if (!failed) backoff.reset()
        if (backoff.exhausted) { fatal("The speech recogniser kept failing, so I stopped listening for the wake word."); return }
        val delay = if (failed) backoff.nextDelayMs() else 250L
        handler.removeCallbacks(restart)
        handler.postDelayed(restart, delay)
    }

    private fun fatal(message: String) {
        val cb = callback
        active = false
        handler.removeCallbacks(restart)
        destroyRecognizer()
        callback = null
        cb?.onListening(false)
        cb?.onFatal(message)
    }
}
