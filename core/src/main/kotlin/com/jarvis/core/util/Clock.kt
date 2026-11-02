package com.jarvis.core.util

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** Time source. Tests inject a fixed clock; the app uses [SystemClock]. All "now" reads go through this. */
interface JarvisClock {
    fun instant(): Instant
    fun zone(): ZoneId
    fun now(): ZonedDateTime = ZonedDateTime.ofInstant(instant(), zone())
    fun nowMs(): Long = instant().toEpochMilli()
}

object SystemClock : JarvisClock {
    override fun instant(): Instant = Instant.now()
    override fun zone(): ZoneId = ZoneId.systemDefault()
}

class FixedClock(private var at: Instant, private var zone: ZoneId) : JarvisClock {
    override fun instant(): Instant = at
    override fun zone(): ZoneId = zone
    fun set(instant: Instant) { at = instant }
    fun advanceMs(ms: Long) { at = at.plusMillis(ms) }
    fun setZone(z: ZoneId) { zone = z }
}

/** Logging seam. The app routes this to Logcat + the redacting file logger; tests collect lines. */
interface JarvisLog {
    fun d(tag: String, msg: String)
    fun i(tag: String, msg: String)
    fun w(tag: String, msg: String, t: Throwable? = null)
    fun e(tag: String, msg: String, t: Throwable? = null)

    object None : JarvisLog {
        override fun d(tag: String, msg: String) {}
        override fun i(tag: String, msg: String) {}
        override fun w(tag: String, msg: String, t: Throwable?) {}
        override fun e(tag: String, msg: String, t: Throwable?) {}
    }
}
