package com.jarvis.core.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnergyVadTest {
    private val block = 1280 // 80 ms

    @Test
    fun silenceIsNotSpeech() {
        val v = EnergyVad()
        repeat(50) { assertFalse(v.process(silence(block))) }
    }

    @Test
    fun aLoudToneAfterSilenceStartsSpeechImmediately() {
        val v = EnergyVad()
        repeat(10) { v.process(silence(block)) }
        assertTrue(v.process(tone(block, 0.2f)))
        assertTrue(v.speaking)
    }

    @Test
    fun speechEndsOnlyAfterTheHangover() {
        val v = EnergyVad(hangoverMs = 800)
        repeat(5) { v.process(silence(block)) }
        assertTrue(v.process(tone(block, 0.2f)))
        // 9 quiet blocks = 720 ms: still speaking; the 10th (800 ms) ends it
        repeat(9) { assertTrue("block $it", v.process(silence(block))) }
        assertFalse(v.process(silence(block)))
    }

    @Test
    fun aBurstDuringTheHangoverRestartsTheHangover() {
        val v = EnergyVad(hangoverMs = 800)
        v.process(tone(block, 0.2f))
        repeat(8) { v.process(silence(block)) } // 640 ms quiet
        assertTrue(v.process(tone(block, 0.2f)))
        repeat(9) { assertTrue(v.process(silence(block))) }
        assertFalse(v.process(silence(block)))
    }

    @Test
    fun steadyBackgroundNoiseIsLearnedAndNoLongerCountsAsSpeech() {
        val v = EnergyVad()
        val noise = tone(block, 0.02f, 200.0) // a constant fan-like hum about -37 dBFS
        var firstQuiet = -1
        repeat(300) { i ->
            val s = v.process(noise)
            if (!s && firstQuiet < 0 && i > 0) firstQuiet = i
        }
        assertFalse("the hum must stop counting as speech once the floor has adapted", v.speaking)
        assertTrue("noise floor approaches the hum level", v.noiseFloor > 0.008f)
        // a clearly louder voice on top of the hum is still detected
        assertTrue(v.process(tone(block, 0.3f, 300.0)))
    }

    @Test
    fun levelsBetweenTheTwoThresholdsDoNotFlap() {
        val v = EnergyVad(hangoverMs = 80)
        repeat(20) { v.process(silence(block)) }
        val floor = v.noiseFloor
        // above the off-level but below the on-level: must not start speech
        val amp = floor * 2.2f * 1.41421f // rms = amp/sqrt2
        assertFalse(v.process(tone(block, maxOf(amp, 0.001f))))
        // start properly, then hold at the in-between level: stays speaking (no flapping)
        assertTrue(v.process(tone(block, 0.3f)))
        repeat(10) { assertTrue(v.process(tone(block, 0.3f * 0.12f))) }
    }

    @Test
    fun theFloorDoesNotRiseMuchWhileSomeoneTalksWithNaturalPauses() {
        val v = EnergyVad()
        repeat(20) { v.process(silence(block)) }
        // 4.8 s of "speech": three loud blocks then one quiet block (syllable gaps), repeated
        repeat(15) {
            repeat(3) { v.process(tone(block, 0.3f)) }
            v.process(tone(block, 0.003f))
        }
        assertTrue("floor ${v.noiseFloor} should stay low", v.noiseFloor < 0.03f)
        assertTrue(v.speaking)
    }

    @Test
    fun emptyBlocksChangeNothingAndResetRestoresDefaults() {
        val v = EnergyVad()
        v.process(tone(block, 0.3f))
        assertTrue(v.process(ShortArray(0), 0))
        v.reset()
        assertFalse(v.speaking)
        assertEquals(0.003f, v.noiseFloor, 1e-6f)
    }

    @Test
    fun rmsOfAFullScaleSquareIsOne() {
        val s = ShortArray(100) { if (it % 2 == 0) 32767 else -32768 }
        assertEquals(1f, EnergyVad.rms(s), 0.001f)
        assertEquals(0f, EnergyVad.rms(ShortArray(0)), 0f)
    }
}
