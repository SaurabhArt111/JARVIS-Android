package com.jarvis.core.wake

/** Allows one event per [cooldownMs]. Times come from the caller (the audio clock), so it is deterministic. */
class Debouncer(private val cooldownMs: Long) {
    private var last: Long? = null

    /** True (and starts a new cooldown) when at least [cooldownMs] passed since the last accepted event. */
    fun accept(nowMs: Long): Boolean {
        val l = last
        if (l != null && nowMs - l < cooldownMs) return false
        last = nowMs
        return true
    }

    fun reset() {
        last = null
    }
}
