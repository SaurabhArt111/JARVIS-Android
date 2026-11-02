package com.jarvis.assistant.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Process
import com.jarvis.core.util.JarvisLog

/**
 * Microphone capture for the neural wake word: 16 kHz, mono, PCM16, read in 80 ms blocks (1280 samples) on one dedicated thread.
 *
 * Honest limits: from Android 11 a background app that is not running a foreground service of type "microphone" is given SILENCE instead of audio
 * (and Android 12+ shows the privacy indicator whenever this is recording). So [Listener.onSilenced] fires both when the system says the client is
 * silenced (AudioRecordingCallback) and when the audio is digital zeros for several seconds.
 */
class AudioCapture(context: Context, private val log: JarvisLog = JarvisLog.None) {
    interface Listener {
        /** Called on the capture thread with [length] valid samples in [pcm] (the array is reused: copy what you keep). */
        fun onAudio(pcm: ShortArray, length: Int)
        /** The recorder broke (device removed, another app took the microphone...). Capture has stopped. */
        fun onFailure(message: String)
        /** Android is giving this app silence instead of microphone audio (true) or has stopped doing so (false). */
        fun onSilenced(silenced: Boolean)
    }

    private val ctx = context.applicationContext
    private val lock = Any()
    @Volatile private var stopFlag = false
    @Volatile private var thread: Thread? = null
    @Volatile private var record: AudioRecord? = null
    @Volatile private var systemSilenced = false
    @Volatile private var zeroSilenced = false

    val isRunning: Boolean get() = thread?.isAlive == true

    /** Opens the microphone and starts the thread. Returns null on success, or a plain-language reason. */
    @SuppressLint("MissingPermission")
    fun start(listener: Listener, onReleased: (() -> Unit)? = null): String? = synchronized(lock) {
        if (isRunning) return null
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return "Microphone permission isn't granted."
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return "This phone can't record 16 kHz audio for the wake word."
        val rec = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setBufferSizeInBytes(maxOf(min, BLOCK * 2 * 4))
                .build()
        } catch (e: Exception) {
            log.w("Audio", "AudioRecord could not be built (${e.javaClass.simpleName})")
            return "The microphone couldn't be opened."
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return "The microphone couldn't be opened (is another app using it?)." }
        try { rec.startRecording() } catch (e: Exception) {
            rec.release(); log.w("Audio", "startRecording failed (${e.javaClass.simpleName})"); return "The microphone refused to start."
        }
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) { rec.release(); return "The microphone didn't start recording (another app may be holding it)." }

        systemSilenced = false; zeroSilenced = false; stopFlag = false
        record = rec
        val cb = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
                val mine = configs?.firstOrNull { it.clientAudioSessionId == rec.audioSessionId } ?: return
                val silenced = mine.isClientSilenced
                if (silenced != systemSilenced) { systemSilenced = silenced; listener.onSilenced(silenced || zeroSilenced) }
            }
        }
        try { rec.registerAudioRecordingCallback(ctx.mainExecutor, cb) } catch (e: Exception) { log.w("Audio", "recording callback not registered (${e.javaClass.simpleName})") }

        val t = Thread({ loop(rec, cb, listener, onReleased) }, "jarvis-wake-audio")
        thread = t
        t.start()
        null
    }

    /** Stops the thread and releases the microphone; waits (briefly) until that happened. Safe to call twice. */
    fun stop() {
        val t = synchronized(lock) { stopFlag = true; try { record?.stop() } catch (_: Exception) { }; thread }
        if (t != null && t !== Thread.currentThread()) try { t.join(1500) } catch (_: InterruptedException) { }
    }

    /** Asks the thread to stop without waiting (use from the audio thread itself); [onReleased] passed to [start] runs once the microphone is free. */
    fun requestStop() {
        stopFlag = true
        try { record?.stop() } catch (_: Exception) { }
    }

    private fun loop(rec: AudioRecord, cb: AudioManager.AudioRecordingCallback, listener: Listener, onReleased: (() -> Unit)?) {
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) } catch (_: Exception) { }
        val buf = ShortArray(BLOCK)
        var zeroBlocks = 0
        var failure: String? = null
        try {
            while (!stopFlag) {
                val n = rec.read(buf, 0, buf.size)
                if (stopFlag) break
                if (n < 0) { failure = "The microphone stopped delivering audio (error $n)."; break }
                if (n == 0) { Thread.sleep(10); continue }
                // digital silence: what Android hands a background app it has silenced
                var allZero = true
                for (i in 0 until n) if (buf[i].toInt() != 0) { allZero = false; break }
                if (allZero) {
                    zeroBlocks++
                    if (zeroBlocks == ZERO_BLOCKS_BEFORE_SILENCED && !zeroSilenced) { zeroSilenced = true; listener.onSilenced(true) }
                } else {
                    zeroBlocks = 0
                    if (zeroSilenced) { zeroSilenced = false; listener.onSilenced(systemSilenced) }
                }
                listener.onAudio(buf, n)
            }
        } catch (e: InterruptedException) {
            // stop requested
        } catch (e: Throwable) {
            log.w("Audio", "capture loop failed (${e.javaClass.simpleName})")
            failure = "Listening stopped unexpectedly (${e.javaClass.simpleName})."
        } finally {
            try { rec.unregisterAudioRecordingCallback(cb) } catch (_: Exception) { }
            try { rec.stop() } catch (_: Exception) { }
            try { rec.release() } catch (_: Exception) { }
            synchronized(lock) { if (record === rec) record = null; thread = null }
            try { onReleased?.invoke() } catch (e: Throwable) { log.w("Audio", "release callback failed (${e.javaClass.simpleName})") }
            if (failure != null && !stopFlag) try { listener.onFailure(failure) } catch (_: Throwable) { }
        }
    }

    companion object {
        const val RATE = 16_000
        const val BLOCK = 1_280
        private const val ZERO_BLOCKS_BEFORE_SILENCED = 50 // 4 s of exact zeros
    }
}
