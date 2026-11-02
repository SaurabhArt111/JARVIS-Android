package com.jarvis.core.wake

/**
 * Delay schedule for restarting a listener that keeps failing (microphone busy, recogniser errors): 0.3 s, 1 s, 2 s, 5 s, 10 s, 20 s, then 30 s.
 * [reset] after something worked. [exhausted] after [maxAttempts] consecutive failures so the caller can stop and say so instead of retrying forever.
 */
class RestartBackoff(private val delaysMs: LongArray = longArrayOf(300, 1_000, 2_000, 5_000, 10_000, 20_000, 30_000), val maxAttempts: Int = 12) {
    private var failures = 0

    val consecutiveFailures: Int get() = failures
    val exhausted: Boolean get() = failures >= maxAttempts

    /** Records a failure and returns how long to wait before the next try. */
    fun nextDelayMs(): Long {
        val d = delaysMs[minOf(failures, delaysMs.size - 1)]
        failures++
        return d
    }

    fun reset() { failures = 0 }
}
