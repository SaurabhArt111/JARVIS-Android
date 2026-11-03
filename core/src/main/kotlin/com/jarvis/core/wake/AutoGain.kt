package com.jarvis.core.wake

import kotlin.math.abs

/**
 * Slow software gain for the wake-word microphone. Many phones record a voice from a few metres away at a peak of only ~1000/32768, which the wake models
 * hear as a mumble ("jarvis" is not picked up unless you are close). This lifts a quiet signal towards [TARGET_PEAK] and never touches a loud one.
 *
 *  - The level is tracked as a decaying peak (about 3 s), so one cough does not pull the gain down and one quiet minute does not push it up at once.
 *  - Below [NOISE_FLOOR] (a silent room, or Android's digital silence) the gain eases back to 1, so hiss is not amplified into false wake-ups.
 *  - Gain is capped at [MAX_GAIN], moves smoothly (no clicks), and samples are clipped, never wrapped.
 * Pure Kotlin; one instance per capture session, single thread.
 */
class AutoGain(private val maxGain: Double = MAX_GAIN) {
    private var peak = 0.0
    var gain = 1.0
        private set

    /** Scales the first [length] samples of [pcm] in place. */
    fun apply(pcm: ShortArray, length: Int) {
        if (length <= 0) return
        var blockPeak = 0
        for (i in 0 until length) { val a = abs(pcm[i].toInt()); if (a > blockPeak) blockPeak = a }
        peak = if (blockPeak > peak) blockPeak.toDouble() else peak * DECAY
        val want = when {
            peak < NOISE_FLOOR -> 1.0
            else -> (TARGET_PEAK / peak).coerceIn(1.0, maxGain)
        }
        // rise slowly, fall quickly: a sudden loud sound must never be clipped by a gain that was set for a whisper
        gain += (want - gain) * (if (want < gain) 0.5 else 0.05)
        // never amplify this very block into clipping, whatever the gain was a moment ago
        if (blockPeak > 0) { val ceiling = 32_000.0 / blockPeak; if (gain > ceiling) gain = maxOf(1.0, ceiling) }
        if (gain <= 1.001) { gain = 1.0; return }
        for (i in 0 until length) {
            val v = (pcm[i] * gain).toInt()
            pcm[i] = (if (v > 32767) 32767 else if (v < -32768) -32768 else v).toShort()
        }
    }

    companion object {
        const val TARGET_PEAK = 9_000.0
        const val NOISE_FLOOR = 350.0
        const val MAX_GAIN = 6.0
        /** Per 80 ms block: 0.98^37 is about 0.5, i.e. the tracked peak halves in roughly 3 s. */
        private const val DECAY = 0.98
    }
}
