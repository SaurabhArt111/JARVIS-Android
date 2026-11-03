package com.jarvis.core.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoGainTest {
    private fun block(level: Int) = ShortArray(1280) { if (it % 2 == 0) level.toShort() else (-level).toShort() }
    private fun peakOf(b: ShortArray) = b.maxOf { kotlin.math.abs(it.toInt()) }

    @Test fun quietVoiceIsLiftedButNotBeyondTheCap() {
        val g = AutoGain()
        var last = block(1000)
        repeat(200) { last = block(1000); g.apply(last, last.size) }
        assertTrue("gain ${g.gain}", g.gain > 5.0 && g.gain <= AutoGain.MAX_GAIN)
        assertTrue(peakOf(last) in 5000..6000)
    }

    @Test fun loudAudioIsLeftAlone() {
        val g = AutoGain()
        repeat(100) { val b = block(20000); g.apply(b, b.size); assertEquals(20000, peakOf(b)) }
        assertEquals(1.0, g.gain, 0.0)
    }

    @Test fun roomHissIsNotAmplified() {
        val g = AutoGain()
        repeat(200) { val b = block(120); g.apply(b, b.size); assertEquals(120, peakOf(b)) }
    }

    @Test fun digitalSilenceStaysSilent() {
        val g = AutoGain()
        val b = ShortArray(1280); g.apply(b, b.size); assertEquals(0, peakOf(b))
    }

    @Test fun aSuddenLoudSoundNeverWraps() {
        val g = AutoGain()
        repeat(200) { val b = block(1000); g.apply(b, b.size) }
        val loud = block(30000); g.apply(loud, loud.size)
        assertTrue(loud.all { it.toInt() in -32768..32767 })
        assertTrue(peakOf(loud) >= 30000)
        val after = block(30000); g.apply(after, after.size)
        assertTrue(g.gain < 1.5)
    }
}
