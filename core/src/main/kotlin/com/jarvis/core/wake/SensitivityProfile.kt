package com.jarvis.core.wake

/**
 * How eager the neural detector is. A hit needs [patience] consecutive 80 ms scores at or above [threshold];
 * after a hit nothing else fires for [cooldownMs]. Lower threshold = more detections AND more false triggers.
 * The numbers are starting points taken from openWakeWord's guidance (0.5 default), not tuned on this app's audio.
 */
enum class SensitivityProfile(val key: String, val threshold: Float, val patience: Int, val cooldownMs: Long) {
    LOW("low", 0.80f, 2, 4000),
    MEDIUM("medium", 0.60f, 2, 3000),
    HIGH("high", 0.40f, 1, 2500);

    companion object {
        fun fromSetting(value: String?): SensitivityProfile =
            values().firstOrNull { it.key.equals(value?.trim(), ignoreCase = true) } ?: MEDIUM
    }
}
