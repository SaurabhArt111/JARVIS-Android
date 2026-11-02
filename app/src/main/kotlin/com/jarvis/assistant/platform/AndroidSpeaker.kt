package com.jarvis.assistant.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.jarvis.core.ports.SpeechChannel
import com.jarvis.core.ports.Speaker
import com.jarvis.core.settings.Keys
import com.jarvis.core.settings.Settings
import com.jarvis.core.util.JarvisLog
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Text-to-speech on Android's TextToSpeech engine. [speak] suspends until the utterance is finished (or stopped) and returns false only if speech FAILED (no engine,
 * no focus because a call is active, engine error) so callers never assume the user heard something.
 * Audio ducking: with the setting on, other audio is lowered (AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, USAGE_ASSISTANT) while speaking and restored afterwards.
 * Cancelling the coroutine stops the speech immediately.
 */
class AndroidSpeaker(context: Context, private val settings: Settings, private val log: JarvisLog = JarvisLog.None) : Speaker {
    private val ctx = context.applicationContext
    private val audio: AudioManager = ctx.getSystemService(AudioManager::class.java)

    private var tts: TextToSpeech? = null
    private var ready = false
    private var lastInitFailureMs = 0L
    private val initLock = Any()
    private val waiters = ConcurrentHashMap<String, CancellableContinuation<Boolean>>()

    @Volatile private var speaking = false

    private val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) { speaking = true }
        override fun onDone(utteranceId: String) { waiters.remove(utteranceId)?.resume(true) }
        @Deprecated("Deprecated in Java") override fun onError(utteranceId: String) { waiters.remove(utteranceId)?.resume(false) }
        override fun onError(utteranceId: String, errorCode: Int) { log.w("Speech", "TTS error $errorCode"); waiters.remove(utteranceId)?.resume(false) }
        override fun onStop(utteranceId: String, interrupted: Boolean) { waiters.remove(utteranceId)?.resume(true) }   // stopped on purpose: not a failure
    }

    /** Starts the engine (once) and waits up to 4 s for it. Null = no usable engine; a failed engine is retried at most every 30 s. */
    private suspend fun engine(): TextToSpeech? {
        tts?.takeIf { ready }?.let { return it }
        val now = System.currentTimeMillis()
        if (!ready && lastInitFailureMs != 0L && now - lastInitFailureMs < 30_000) return null
        val done = CompletableDeferred<Boolean>()
        synchronized(initLock) {
            if (tts == null) {
                tts = TextToSpeech(ctx) { status -> done.complete(status == TextToSpeech.SUCCESS) }
            } else if (ready) done.complete(true)
        }
        val ok = withTimeoutOrNull(4_000) { done.await() } ?: false
        val t = tts
        if (!ok || t == null) {
            log.w("Speech", "text-to-speech engine is not available")
            synchronized(initLock) { try { tts?.shutdown() } catch (_: Exception) { }; tts = null; ready = false; lastInitFailureMs = System.currentTimeMillis() }
            return null
        }
        t.setAudioAttributes(attrs)
        t.setOnUtteranceProgressListener(progress)
        ready = true; lastInitFailureMs = 0L
        return t
    }

    private fun applySettings(t: TextToSpeech) {
        val rate = settings.double(Keys.SPEECH_RATE, 1.0).coerceIn(0.5, 2.0).toFloat()
        t.setSpeechRate(rate)
        val lang = settings.get(Keys.INPUT_LANGUAGE).ifBlank { "en" }
        val loc = Locale.forLanguageTag(lang)
        val r = try { t.setLanguage(loc) } catch (_: Exception) { TextToSpeech.ERROR }
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) log.i("Speech", "voice data for the language isn't installed — using the default voice")
        val voiceName = settings.get(Keys.ASSISTANT_VOICE)
        if (voiceName.isNotBlank()) try { t.voices?.firstOrNull { it.name == voiceName }?.let { t.voice = it } } catch (_: Exception) { }
    }

    /** Splits long text at sentence ends so each piece is under the engine's input limit. */
    private fun chunks(text: String, t: TextToSpeech): List<String> {
        val max = (TextToSpeech.getMaxSpeechInputLength() - 100).coerceIn(300, 3500)
        if (text.length <= max) return listOf(text)
        val out = ArrayList<String>()
        var rest = text.trim()
        while (rest.length > max) {
            var cut = rest.lastIndexOfAny(charArrayOf('.', '!', '?', '\n'), max)
            if (cut < max / 3) cut = rest.lastIndexOf(' ', max)
            if (cut < 1) cut = max
            out.add(rest.substring(0, cut + 1).trim()); rest = rest.substring(cut + 1).trim()
        }
        if (rest.isNotEmpty()) out.add(rest)
        return out
    }

    private var focus: AudioFocusRequest? = null

    /** True when speaking may go ahead. A refused focus request means a call (or another exclusive app) is active: speech is skipped, not forced over it. */
    private fun takeFocus(): Boolean {
        if (!settings.flag(Keys.DUCK_ENABLED, true)) return true
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).setOnAudioFocusChangeListener { }.build()
        val r = audio.requestAudioFocus(req)
        focus = req
        return r != AudioManager.AUDIOFOCUS_REQUEST_FAILED
    }

    private fun dropFocus() { focus?.let { audio.abandonAudioFocusRequest(it) }; focus = null }

    override suspend fun speak(text: String, channel: SpeechChannel): Boolean {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return true
        val t = engine() ?: return false
        if (!takeFocus()) { log.i("Speech", "audio focus refused (call or exclusive audio active) — not speaking"); return false }
        try {
            applySettings(t)
            var ok = true
            for (part in chunks(clean, t)) {
                val id = UUID.randomUUID().toString()
                val result = suspendCancellableCoroutine<Boolean> { cont ->
                    waiters[id] = cont
                    cont.invokeOnCancellation { waiters.remove(id); try { t.stop() } catch (_: Exception) { }; speaking = false }
                    val params = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC.toString()) }
                    if (t.speak(part, TextToSpeech.QUEUE_FLUSH, params, id) == TextToSpeech.ERROR) { waiters.remove(id); cont.resume(false) }
                }
                if (!result) { ok = false; break }
            }
            return ok
        } finally {
            speaking = false
            dropFocus()
        }
    }

    override fun stop() {
        try { tts?.stop() } catch (_: Exception) { }
        speaking = false
        // any caller still waiting is released by onStop; make sure none is left hanging if the engine stayed silent
        waiters.keys.toList().forEach { waiters.remove(it)?.resume(true) }
    }

    override fun isSpeaking(): Boolean = speaking || (try { tts?.isSpeaking == true } catch (_: Exception) { false })

    /** Releases the engine (the app does not need to call this; Android reclaims it with the process). */
    fun shutdown() { synchronized(initLock) { try { tts?.shutdown() } catch (_: Exception) { }; tts = null; ready = false } }
}
