package com.jarvis.core.wake

import kotlin.math.sqrt

/**
 * A small energy-based voice-activity gate: adaptive noise floor, two thresholds (hysteresis) and a hangover so it does not flap.
 * It is used to skip the neural networks while the room is quiet; it never decides on its own that the wake word was said.
 *
 * Levels are RMS of PCM16 scaled to 0..1. [process] takes blocks of any size (the audio loop uses 80 ms).
 */
class EnergyVad(
    private val onRatio: Float = 3.0f,
    private val offRatio: Float = 1.8f,
    private val minSpeechRms: Float = 0.004f,
    private val hangoverMs: Int = 1200,
    private val sampleRate: Int = 16000,
) {
    /** Current estimate of the background level (RMS, 0..1). */
    var noiseFloor = INITIAL_FLOOR
        private set

    var speaking = false
        private set

    private var quietMs = 0

    fun reset() {
        noiseFloor = INITIAL_FLOOR
        speaking = false
        quietMs = 0
    }

    /** Updates the state with a block and returns whether speech (or its hangover) is active. */
    fun process(pcm: ShortArray, length: Int = pcm.size): Boolean {
        if (length <= 0) return speaking
        val rms = rms(pcm, length)
        val blockMs = (length * 1000L / sampleRate).toInt().coerceAtLeast(1)

        val onLevel = maxOf(noiseFloor * onRatio, minSpeechRms)
        val offLevel = maxOf(noiseFloor * offRatio, minSpeechRms * 0.6f)

        if (!speaking) {
            if (rms > onLevel) {
                speaking = true
                quietMs = 0
            }
        } else {
            if (rms < offLevel) {
                quietMs += blockMs
                if (quietMs >= hangoverMs) {
                    speaking = false
                    quietMs = 0
                }
            } else {
                quietMs = 0
            }
        }

        // The floor falls quickly to a quieter room and rises slowly, and more slowly still while "speech" is active: real speech keeps dipping between
        // syllables (which pulls the floor back down fast), whereas a steady hum never dips and so, after a few seconds, becomes the new floor.
        noiseFloor = when {
            rms < noiseFloor -> noiseFloor + (rms - noiseFloor) * FALL
            speaking -> noiseFloor + (rms - noiseFloor) * RISE_WHILE_SPEAKING
            else -> noiseFloor + (rms - noiseFloor) * RISE
        }.coerceIn(MIN_FLOOR, MAX_FLOOR)
        return speaking
    }

    companion object {
        private const val INITIAL_FLOOR = 0.003f
        private const val MIN_FLOOR = 0.0002f
        private const val MAX_FLOOR = 0.05f
        private const val FALL = 0.3f
        private const val RISE = 0.03f
        private const val RISE_WHILE_SPEAKING = 0.01f

        fun rms(pcm: ShortArray, length: Int = pcm.size): Float {
            var sum = 0.0
            val n = minOf(length, pcm.size)
            for (i in 0 until n) {
                val v = pcm[i] / 32768.0
                sum += v * v
            }
            return if (n == 0) 0f else sqrt(sum / n).toFloat()
        }
    }
}
