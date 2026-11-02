package com.jarvis.core.wake

/** One wake-word hit. [atMs] is measured on the audio clock (samples fed so far / 16), not the wall clock. */
data class WakeDetection(val score: Float, val atMs: Long, val phrase: String)

/**
 * A wake-word detector fed with raw audio: 16 kHz, mono, signed 16-bit PCM.
 *
 * Implementations are NOT thread safe: feed them from one thread (the capture thread) and call [reset] / [close]
 * from that thread or after it has stopped.
 */
interface WakeWordEngine : AutoCloseable {
    /** Human readable phrase the engine listens for, for status text. */
    val phrase: String

    /** Feeds the first [length] samples of [pcm]; returns a detection when the wake word was just heard. */
    fun feed(pcm: ShortArray, length: Int = pcm.size): WakeDetection?

    /** Forgets buffered audio and scores (call after the microphone was taken away and given back). */
    fun reset()

    override fun close()
}

/** Counters an engine keeps so a diagnostics screen can show something real instead of "listening...". */
data class WakeStats(
    val blocksSeen: Long = 0,
    val blocksAnalysed: Long = 0,
    val lastScore: Float = 0f,
    val peakScore: Float = 0f,
    val detections: Int = 0,
    val noiseFloor: Float = 0f,
    val speechActive: Boolean = false,
)
