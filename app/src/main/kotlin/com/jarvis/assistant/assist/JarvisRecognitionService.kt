package com.jarvis.assistant.assist

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import com.jarvis.assistant.JarvisApp

/**
 * Android's voice-interaction metadata requires the assistant to name a recognition service, and Android may make the chosen assistant's recognition service the
 * phone's voice-input provider. JARVIS has no speech recogniser of its own (it uses the phone's), so this service FORWARDS each request to another recognition
 * service installed on the phone (the Google one when present) and relays its callbacks. That way, becoming the default assistant never breaks dictation in
 * other apps, and JARVIS's own SpeechRecognizer calls, which may be routed here, keep working.
 *
 * It never forwards to itself. If no other recogniser exists it reports ERROR_CLIENT instead of pretending to listen. Audio is handled entirely by the other
 * service; JARVIS never sees or stores it. The recogniser runs inside JARVIS's process, so it needs JARVIS's own microphone permission: without it the caller gets
 * ERROR_INSUFFICIENT_PERMISSIONS.
 */
class JarvisRecognitionService : RecognitionService() {
    private var delegate: SpeechRecognizer? = null
    private var active: Callback? = null

    private fun pickDelegate(): ComponentName? {
        val found = packageManager.queryIntentServices(Intent(SERVICE_INTERFACE), 0).mapNotNull { it.serviceInfo }.filter { it.packageName != packageName }
        val best = found.firstOrNull { it.packageName == "com.google.android.googlequicksearchbox" } ?: found.firstOrNull() ?: return null
        return ComponentName(best.packageName, best.name)
    }

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        if (active != null) { try { listener.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY) } catch (_: Exception) { }; return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            try { listener.error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) } catch (_: Exception) { }; return
        }
        val target = pickDelegate()
        if (target == null) {
            JarvisApp.container.appLog.w("Assist", "no other speech recogniser installed to forward to")
            try { listener.error(SpeechRecognizer.ERROR_CLIENT) } catch (_: Exception) { }; return
        }
        val r = try { SpeechRecognizer.createSpeechRecognizer(this, target) } catch (e: Exception) { null }
        if (r == null) { try { listener.error(SpeechRecognizer.ERROR_CLIENT) } catch (_: Exception) { }; return }
        delegate = r
        active = listener
        r.setRecognitionListener(object : RecognitionListener {
            private fun relay(block: Callback.() -> Unit) { try { active?.block() } catch (_: Exception) { } }
            override fun onReadyForSpeech(params: Bundle?) = relay { readyForSpeech(params ?: Bundle()) }
            override fun onBeginningOfSpeech() = relay { beginningOfSpeech() }
            override fun onRmsChanged(rmsdB: Float) = relay { rmsChanged(rmsdB) }
            override fun onBufferReceived(buffer: ByteArray?) { if (buffer != null) relay { bufferReceived(buffer) } }
            override fun onEndOfSpeech() = relay { endOfSpeech() }
            override fun onError(error: Int) { relay { error(error) }; release() }
            override fun onResults(results: Bundle?) { relay { results(results ?: Bundle()) }; release() }
            override fun onPartialResults(partialResults: Bundle?) = relay { partialResults(partialResults ?: Bundle()) }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        try { r.startListening(recognizerIntent) } catch (e: Exception) {
            try { listener.error(SpeechRecognizer.ERROR_CLIENT) } catch (_: Exception) { }
            release()
        }
    }

    override fun onStopListening(listener: Callback) { try { delegate?.stopListening() } catch (_: Exception) { } }

    override fun onCancel(listener: Callback) { try { delegate?.cancel() } catch (_: Exception) { }; release() }

    private fun release() {
        try { delegate?.destroy() } catch (_: Exception) { }
        delegate = null
        active = null
    }

    override fun onDestroy() { release(); super.onDestroy() }
}
