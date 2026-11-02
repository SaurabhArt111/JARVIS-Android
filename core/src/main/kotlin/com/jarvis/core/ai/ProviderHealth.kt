package com.jarvis.core.ai

import com.jarvis.core.util.JarvisClock

/** Read-only copy of one provider's counters (for the Settings > AI status panel). */
data class ProviderHealthSnapshot(
    val name: String,
    val ok: Int,
    val fail: Int,
    val consecutiveFailures: Int,
    val lastError: String,
    val lastKind: LlmErrorKind?,
    val lastFailMs: Long,
    val lastOkMs: Long,
    val avgMillis: Double,
    val cooldownLeftSeconds: Int,
    val fallbacks: Int,
)

/** Success/failure counters and cooldown (circuit breaker) per AI provider. Port of Backend/Health.py. Thread-safe. */
class ProviderHealth(private val clock: JarvisClock) {
    private class Counter(val name: String) {
        var ok = 0; var fail = 0; var consecutive = 0
        var lastError = ""; var lastKind: LlmErrorKind? = null
        var lastFailMs = 0L; var lastOkMs = 0L; var avgMs = 0.0; var cooldownUntilMs = 0L; var fallbacks = 0
    }

    private val counters = LinkedHashMap<String, Counter>()
    private val listeners = ArrayList<(ProviderHealthSnapshot) -> Unit>()

    private fun c(name: String) = counters.getOrPut(name) { Counter(name) }

    @Synchronized fun onChange(listener: (ProviderHealthSnapshot) -> Unit) { listeners.add(listener) }

    fun ok(name: String, millis: Double? = null) {
        val snap = synchronized(this) {
            val x = c(name)
            x.ok++; x.consecutive = 0; x.lastOkMs = clock.nowMs(); x.cooldownUntilMs = 0
            if (millis != null) x.avgMs = if (x.avgMs == 0.0) millis else x.avgMs * 0.7 + millis * 0.3
            snapshotOf(x)
        }
        emit(snap)
    }

    /** [cooldownSeconds] > 0 makes the provider be skipped for that long. [message] is clipped to 300 chars. */
    fun fail(name: String, message: String, kind: LlmErrorKind = LlmErrorKind.ERROR, cooldownSeconds: Int = 0) {
        val snap = synchronized(this) {
            val x = c(name)
            x.fail++; x.consecutive++
            x.lastError = message.take(300); x.lastKind = kind; x.lastFailMs = clock.nowMs()
            if (cooldownSeconds > 0) x.cooldownUntilMs = clock.nowMs() + cooldownSeconds * 1000L
            snapshotOf(x)
        }
        emit(snap)
    }

    fun fellBack(name: String) {
        val snap = synchronized(this) { val x = c(name); x.fallbacks++; snapshotOf(x) }
        emit(snap)
    }

    @Synchronized fun cooling(name: String): Boolean = c(name).cooldownUntilMs > clock.nowMs()

    @Synchronized fun cooldownLeft(name: String): Int = maxOf(0L, (c(name).cooldownUntilMs - clock.nowMs()) / 1000).toInt()

    @Synchronized fun resetCooldown(name: String? = null) {
        for ((k, x) in counters) if (name == null || name == k) x.cooldownUntilMs = 0
    }

    @Synchronized fun consecutive(name: String): Int = c(name).consecutive

    @Synchronized fun lastKind(name: String): LlmErrorKind? = c(name).lastKind

    @Synchronized fun get(name: String): ProviderHealthSnapshot = snapshotOf(c(name))

    @Synchronized fun snapshot(): List<ProviderHealthSnapshot> = counters.values.map(::snapshotOf)

    private fun snapshotOf(x: Counter) = ProviderHealthSnapshot(
        x.name, x.ok, x.fail, x.consecutive, x.lastError, x.lastKind, x.lastFailMs, x.lastOkMs, x.avgMs,
        maxOf(0L, (x.cooldownUntilMs - clock.nowMs()) / 1000).toInt(), x.fallbacks,
    )

    private fun emit(s: ProviderHealthSnapshot) {
        val ls = synchronized(this) { listeners.toList() }
        for (l in ls) try { l(s) } catch (_: Exception) {}
    }
}
