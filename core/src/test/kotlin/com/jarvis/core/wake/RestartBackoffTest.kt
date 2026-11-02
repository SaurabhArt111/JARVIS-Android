package com.jarvis.core.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestartBackoffTest {
    @Test
    fun delaysGrowThenStayAtTheCap() {
        val b = RestartBackoff()
        val seq = List(9) { b.nextDelayMs() }
        assertEquals(listOf(300L, 1000L, 2000L, 5000L, 10_000L, 20_000L, 30_000L, 30_000L, 30_000L), seq)
    }

    @Test
    fun resetStartsOver() {
        val b = RestartBackoff()
        repeat(4) { b.nextDelayMs() }
        b.reset()
        assertEquals(0, b.consecutiveFailures)
        assertEquals(300L, b.nextDelayMs())
    }

    @Test
    fun exhaustedAfterMaxAttempts() {
        val b = RestartBackoff(maxAttempts = 3)
        assertFalse(b.exhausted)
        repeat(3) { b.nextDelayMs() }
        assertTrue(b.exhausted)
    }

    @Test
    fun closingTheEngineClosesWhatItOwns() {
        var closed = 0
        val runner = FakeRunner()
        val e = NeuralWakeEngine(OpenWakeWordPipeline(runner), owned = AutoCloseable { closed++ })
        e.close()
        assertEquals(1, closed)
    }
}
