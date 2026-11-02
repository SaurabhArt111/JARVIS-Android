package com.jarvis.assistant.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.jarvis.assistant.ui.kit.MainThread
import com.jarvis.core.settings.Keys
import com.jarvis.core.settings.Settings
import com.jarvis.core.util.JarvisLog
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn

enum class VoiceError { NO_PERMISSION, UNAVAILABLE, NETWORK, NO_MATCH, BUSY, AUDIO, OTHER }

sealed class VoiceEvent {
    /** The recogniser is listening: show the "speak now" state. */
    object Ready : VoiceEvent()
    /** The user started talking. */
    object Speaking : VoiceEvent()
    /** 0..1 loudness, for the orb. */
    data class Level(val value: Float) : VoiceEvent()
    data class Partial(val text: String) : VoiceEvent()
    data class Final(val text: String) : VoiceEvent()
    data class Failed(val error: VoiceError, val message: String) : VoiceEvent()
}

/**
 * One spoken request through Android's SpeechRecognizer. [listenOnce] is a cold Flow: collecting starts the microphone, cancelling the collection stops it and
 * releases the recogniser. It always ends with either [VoiceEvent.Final] or [VoiceEvent.Failed] (never silently), so the UI can always leave its "listening" state.
 *
 * Privacy: by default Android hands the audio to the device's speech service (on most phones Google's), which may process it in the cloud. With the
 * "prefer on-device recognition" setting JARVIS asks for an on-device recogniser (Android 12+) and reports UNAVAILABLE if there isn't one, instead of silently going online.
 * JARVIS itself stores no audio.
 */
class VoiceInput(context: Context, private val settings: Settings, private val log: JarvisLog = JarvisLog.None) {
    private val ctx = context.applicationContext

    companion object {
        fun errorText(e: VoiceError): String = when (e) {
            VoiceError.NO_PERMISSION -> "I need microphone permission to listen."
            VoiceError.UNAVAILABLE -> "Speech recognition isn't available on this phone (or the language isn't installed)."
            VoiceError.NETWORK -> "Speech recognition needs an internet connection right now."
            VoiceError.NO_MATCH -> "I didn't catch that."
            VoiceError.BUSY -> "The microphone is busy. Try again in a moment."
            VoiceError.AUDIO -> "There was a problem with the microphone."
            VoiceError.OTHER -> "Speech recognition failed."
        }
    }

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(ctx)

    fun listenOnce(): Flow<VoiceEvent> = callbackFlow {
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            trySend(VoiceEvent.Failed(VoiceError.NO_PERMISSION, errorText(VoiceError.NO_PERMISSION))); close(); return@callbackFlow
        }
        val onDevice = settings.flag(Keys.PREFER_ON_DEVICE_SPEECH, false)
        val recognizer: SpeechRecognizer? = try {
            when {
                onDevice && Build.VERSION.SDK_INT >= 31 ->
                    if (SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx) else null
                SpeechRecognizer.isRecognitionAvailable(ctx) -> SpeechRecognizer.createSpeechRecognizer(ctx)
                else -> null
            }
        } catch (e: Exception) { log.w("Voice", "could not create the recogniser (${e.javaClass.simpleName})"); null }
        if (recognizer == null) {
            trySend(VoiceEvent.Failed(VoiceError.UNAVAILABLE, errorText(VoiceError.UNAVAILABLE))); close(); return@callbackFlow
        }
        var finished = false
        fun finish(ev: VoiceEvent) { if (!finished) { finished = true; trySend(ev); close() } }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { trySend(VoiceEvent.Ready) }
            override fun onBeginningOfSpeech() { trySend(VoiceEvent.Speaking) }
            override fun onRmsChanged(rmsdB: Float) { trySend(VoiceEvent.Level(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { trySend(VoiceEvent.Partial(it)) }
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                if (text.isEmpty()) finish(VoiceEvent.Failed(VoiceError.NO_MATCH, errorText(VoiceError.NO_MATCH))) else finish(VoiceEvent.Final(text))
            }
            override fun onError(error: Int) {
                val kind = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> VoiceError.NO_MATCH
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> VoiceError.NETWORK
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> VoiceError.NO_PERMISSION
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> VoiceError.BUSY
                    SpeechRecognizer.ERROR_AUDIO -> VoiceError.AUDIO
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> VoiceError.UNAVAILABLE
                    else -> VoiceError.OTHER
                }
                log.i("Voice", "recogniser error code $error -> $kind")
                finish(VoiceEvent.Failed(kind, errorText(kind)))
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
        settings.get(Keys.INPUT_LANGUAGE).takeIf { it.isNotBlank() }?.let { intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
        try { recognizer.startListening(intent) } catch (e: Exception) { finish(VoiceEvent.Failed(VoiceError.OTHER, errorText(VoiceError.OTHER))) }
        awaitClose { try { recognizer.cancel(); recognizer.destroy() } catch (_: Exception) { } }
    }.flowOn(MainThread)
}
