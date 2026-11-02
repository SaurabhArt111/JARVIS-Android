package com.jarvis.core.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebouncerAndProfileTest {
    @Test
    fun firstEventPassesThenCooldownBlocks() {
        val d = Debouncer(3000)
        assertTrue(d.accept(0))
        assertFalse(d.accept(1000))
        assertFalse(d.accept(2999))
        assertTrue(d.accept(3000))
        assertFalse(d.accept(3001))
    }

    @Test
    fun rejectedEventsDoNotExtendTheCooldown() {
        val d = Debouncer(1000)
        assertTrue(d.accept(5000))
        assertFalse(d.accept(5900))
        assertTrue("measured from the last ACCEPTED event", d.accept(6000))
    }

    @Test
    fun resetForgetsTheLastEvent() {
        val d = Debouncer(10_000)
        assertTrue(d.accept(100))
        d.reset()
        assertTrue(d.accept(101))
    }

    @Test
    fun profilesParseCaseInsensitivelyAndFallBackToMedium() {
        assertEquals(SensitivityProfile.LOW, SensitivityProfile.fromSetting("Low"))
        assertEquals(SensitivityProfile.HIGH, SensitivityProfile.fromSetting(" high "))
        assertEquals(SensitivityProfile.MEDIUM, SensitivityProfile.fromSetting(null))
        assertEquals(SensitivityProfile.MEDIUM, SensitivityProfile.fromSetting("loud"))
    }

    @Test
    fun higherSensitivityMeansLowerThreshold() {
        assertTrue(SensitivityProfile.LOW.threshold > SensitivityProfile.MEDIUM.threshold)
        assertTrue(SensitivityProfile.MEDIUM.threshold > SensitivityProfile.HIGH.threshold)
        assertTrue(SensitivityProfile.values().all { it.patience >= 1 && it.cooldownMs > 0 })
    }
}
