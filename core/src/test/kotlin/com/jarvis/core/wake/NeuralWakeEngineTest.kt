package com.jarvis.core.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NeuralWakeEngineTest {
    private val block = OpenWakeWordPipeline.CHUNK

    private fun engine(
        runner: FakeRunner,
        profile: SensitivityProfile = SensitivityProfile.MEDIUM,
        useVad: Boolean = false,
        vad: EnergyVad = EnergyVad(hangoverMs = 400),
    ) = NeuralWakeEngine(OpenWakeWordPipeline(runner), profile, "hey jarvis", useVad, vad)

    @Test
    fun noDetectionWhileScoresStayLow() {
        val e = engine(FakeRunner { 0.1f })
        repeat(60) { assertNull(e.feed(tone(block, 0.1f))) }
        assertEquals(0, e.stats.detections)
    }

    @Test
    fun detectionNeedsPatienceConsecutiveHighScores() {
        // wake call numbers: high only at 10 (single spike) then 20,21 (two in a row)
        val e = engine(FakeRunner { if (it == 10 || it == 20 || it == 21) 0.9f else 0f })
        val hits = ArrayList<Int>()
        for (i in 0 until 40) if (e.feed(tone(block, 0.1f)) != null) hits.add(i)
        assertEquals("a single spike is ignored, two in a row fire once", listOf(21), hits)
    }

    @Test
    fun highSensitivityFiresOnASingleFrame() {
        val e = engine(FakeRunner { if (it == 10) 0.5f else 0f }, SensitivityProfile.HIGH)
        var hit: WakeDetection? = null
        for (i in 0 until 20) e.feed(tone(block, 0.1f))?.let { hit = it }
        assertNotNull(hit)
        assertEquals("hey jarvis", hit!!.phrase)
        assertEquals(0.5f, hit!!.score, 0f)
    }

    @Test
    fun lowSensitivityIgnoresAMiddlingScore() {
        val e = engine(FakeRunner { if (it >= 10) 0.7f else 0f }, SensitivityProfile.LOW)
        repeat(40) { assertNull(e.feed(tone(block, 0.1f))) }
    }

    @Test
    fun aSecondHitInsideTheCooldownIsSuppressed() {
        val e = engine(FakeRunner { if (it >= 10) 0.95f else 0f }, SensitivityProfile.MEDIUM)
        var hits = 0
        // 3 s cooldown = 37.5 blocks of 80 ms: a long stretch of high scores gives hit #1, then #2 only after the cooldown
        val times = ArrayList<Long>()
        for (i in 0 until 120) e.feed(tone(block, 0.1f))?.let { hits++; times.add(it.atMs) }
        assertTrue("hits: $times", hits >= 2)
        assertTrue("hits are at least 3 s apart: $times", times.zipWithNext().all { (a, b) -> b - a >= 3000 })
    }

    @Test
    fun detectionTimeIsOnTheAudioClock() {
        val e = engine(FakeRunner { if (it >= 8) 0.95f else 0f })
        var at = -1L
        var n = 0
        while (at < 0 && n < 50) { n++; e.feed(tone(block, 0.1f))?.let { at = it.atMs } }
        assertEquals(n * 80L, at)
    }

    @Test
    fun vadGateSkipsTheNetworksInSilenceAndAnalysesSpeech() {
        val r = FakeRunner()
        val e = engine(r, useVad = true)
        val wake0 = r.wakeCalls
        repeat(40) { e.feed(silence(block)) }
        assertEquals("nothing analysed while quiet", wake0, r.wakeCalls)
        assertEquals(0L, e.stats.blocksAnalysed)
        e.feed(tone(block, 0.3f))
        assertTrue("pre-roll is replayed when speech starts", r.wakeCalls - wake0 >= 15)
        val afterOnset = r.wakeCalls
        e.feed(tone(block, 0.3f))
        assertEquals(afterOnset + 1, r.wakeCalls)
        assertTrue(e.stats.speechActive)
    }

    @Test
    fun theGateClosesAgainAfterTheHangover() {
        val r = FakeRunner()
        val e = engine(r, useVad = true, vad = EnergyVad(hangoverMs = 400))
        e.feed(tone(block, 0.3f))
        repeat(10) { e.feed(silence(block)) }
        val settled = r.wakeCalls
        repeat(20) { e.feed(silence(block)) }
        assertEquals("no more inference once the hangover is over", settled, r.wakeCalls)
    }

    @Test
    fun aWakeWordSpokenAfterALongSilenceIsStillDetectedThroughThePreRoll() {
        // the (fake) wake model fires on every call after the 10th: whatever the gating does, speech must reach the model
        val r = FakeRunner { if (it >= 10) 0.95f else 0f }
        val e = engine(r, SensitivityProfile.MEDIUM, useVad = true)
        repeat(100) { assertNull(e.feed(silence(block))) }
        var hit: WakeDetection? = null
        for (i in 0 until 30) e.feed(tone(block, 0.3f))?.let { if (hit == null) hit = it }
        assertNotNull(hit)
    }

    @Test
    fun resetClearsPeakAndGate() {
        val r = FakeRunner { 0.2f }
        val e = engine(r, useVad = true)
        repeat(10) { e.feed(tone(block, 0.3f)) }
        assertTrue(e.stats.peakScore > 0f)
        e.reset()
        assertEquals(0f, e.stats.peakScore, 0f)
        assertEquals(false, e.stats.speechActive)
    }

    @Test
    fun emptyAndOversizedLengthsAreHandled() {
        val e = engine(FakeRunner())
        assertNull(e.feed(ShortArray(0)))
        assertNull(e.feed(ShortArray(100), 0))
        assertNull(e.feed(ShortArray(100), 5000)) // length is clamped to the array
        assertEquals(1L, e.stats.blocksSeen)
    }
}
