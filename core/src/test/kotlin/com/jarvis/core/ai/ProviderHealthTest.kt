package com.jarvis.core.ai

import com.jarvis.core.util.FixedClock
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ProviderHealthTest {
    private val clock = FixedClock(Instant.parse("2026-10-25T10:00:00Z"), ZoneId.of("UTC"))
    private val h = ProviderHealth(clock)

    @Test fun successAndFailureCounters() {
        h.ok("groq", 100.0); h.ok("groq", 200.0)
        h.fail("groq", "bad", LlmErrorKind.NETWORK)
        val s = h.get("groq")
        assertEquals(2, s.ok); assertEquals(1, s.fail); assertEquals(1, s.consecutiveFailures)
        assertEquals(LlmErrorKind.NETWORK, s.lastKind); assertEquals(130.0, s.avgMillis, 0.001)
        h.ok("groq")
        assertEquals(0, h.get("groq").consecutiveFailures)
    }

    @Test fun cooldownExpiresWithTheClock() {
        h.fail("cohere", "quota", LlmErrorKind.QUOTA, 60)
        assertTrue(h.cooling("cohere")); assertEquals(60, h.cooldownLeft("cohere"))
        clock.advanceMs(30_000); assertEquals(30, h.cooldownLeft("cohere"))
        clock.advanceMs(31_000); assertFalse(h.cooling("cohere")); assertEquals(0, h.cooldownLeft("cohere"))
    }

    @Test fun successClearsCooldownAndResetWorks() {
        h.fail("a", "x", LlmErrorKind.RATE, 45); h.fail("b", "x", LlmErrorKind.RATE, 45)
        h.ok("a"); assertFalse(h.cooling("a")); assertTrue(h.cooling("b"))
        h.resetCooldown(); assertFalse(h.cooling("b"))
    }

    @Test fun errorTextIsClippedAndListenersFire() {
        val seen = ArrayList<ProviderHealthSnapshot>()
        h.onChange { seen.add(it) }
        h.fail("x", "e".repeat(1000))
        h.fellBack("x")
        assertEquals(300, h.get("x").lastError.length)
        assertEquals(2, seen.size); assertEquals(1, seen.last().fallbacks)
        assertEquals(listOf("x"), h.snapshot().map { it.name })
    }
}
