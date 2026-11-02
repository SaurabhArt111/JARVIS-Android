package com.jarvis.core.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenWakeWordPipelineTest {
    private val chunk = OpenWakeWordPipeline.CHUNK

    @Test
    fun warmupMakesOneMelCallAndOneBatchedEmbeddingCall() {
        val r = FakeRunner()
        OpenWakeWordPipeline(r)
        assertEquals(1, r.melCalls)
        assertEquals(64000, r.melInputSizes[0])
        assertEquals(1, r.embeddingCalls)
        assertEquals(0, r.wakeCalls)
    }

    @Test
    fun theFirstFivePredictionsAreZeroEvenIfTheModelScoresHigh() {
        val r = FakeRunner { 0.9f }
        val p = OpenWakeWordPipeline(r)
        val scores = (1..8).map { p.feed(ShortArray(chunk)) }
        assertEquals(listOf(0f, 0f, 0f, 0f, 0f, 0.9f, 0.9f, 0.9f), scores)
    }

    @Test
    fun aCompleteChunkRunsMelOnceEmbeddingOnceAndWakeOnce() {
        val r = FakeRunner()
        val p = OpenWakeWordPipeline(r)
        val mel0 = r.melCalls; val emb0 = r.embeddingCalls
        p.feed(ShortArray(chunk))
        assertEquals(mel0 + 1, r.melCalls)
        assertEquals(emb0 + 1, r.embeddingCalls)
        assertEquals(1, r.wakeCalls)
        // the melspectrogram sees the new 1280 samples plus 480 samples of context (the very first call only has what exists)
        assertEquals(chunk, r.melInputSizes.last())
        p.feed(ShortArray(chunk))
        assertEquals(chunk + 480, r.melInputSizes.last())
    }

    @Test
    fun partialReadsAreAccumulatedAndReusePreviousScore() {
        val r = FakeRunner { 0.7f }
        val p = OpenWakeWordPipeline(r)
        repeat(6) { p.feed(ShortArray(chunk)) } // past the 5 zeroed predictions
        val wake0 = r.wakeCalls
        val s = p.feed(ShortArray(500))
        assertEquals("no chunk completed: no wake inference", wake0, r.wakeCalls)
        assertEquals("previous score is repeated", 0.7f, s, 0f)
        p.feed(ShortArray(500))
        assertEquals(wake0, r.wakeCalls)
        p.feed(ShortArray(500)) // 1500 total >= 1280: one chunk (1280) is processed, 220 samples are held back
        assertEquals(wake0 + 1, r.wakeCalls)
        assertEquals(chunk + 480, r.melInputSizes.last())
    }

    @Test
    fun aBigReadRunsSeveralChunksAndReturnsTheMaxScore() {
        val r = FakeRunner { if (it == 6) 0.95f else 0.1f }
        val p = OpenWakeWordPipeline(r)
        repeat(6) { p.feed(ShortArray(chunk)) } // wake calls 0..5
        val emb0 = r.embeddingCalls
        val s = p.feed(ShortArray(chunk * 3)) // wake calls 6,7,8 -> max is call 6
        assertEquals(0.95f, s, 0f)
        assertEquals("one embedding per chunk", emb0 + 3, r.embeddingCalls)
        assertEquals(9, r.wakeCalls)
        assertEquals("one melspectrogram over all three chunks + context", chunk * 3 + 480, r.melInputSizes.last())
    }

    @Test
    fun embeddingsAreAppendedInOrderAndTheWakeModelSeesTheNewest16() {
        val r = FakeRunner()
        val p = OpenWakeWordPipeline(r)
        val warmRows = r.embeddingCounter // rows produced during warm-up are 0 until warmRows-1
        repeat(3) { p.feed(ShortArray(chunk)) }
        val last = r.wakeInputs.last()
        // newest row is the embedding produced by the third chunk; the dummy runner numbers embeddings sequentially
        val newestRowValue = last[(16 - 1) * 96]
        assertEquals((warmRows + 2).toFloat(), newestRowValue, 0f)
        val oldestRowValue = last[0]
        assertEquals((warmRows + 2 - 15).toFloat(), oldestRowValue, 0f)
    }

    @Test
    fun resetRestoresWarmupHistoryWithoutNeuralCalls() {
        val r = FakeRunner { 0.9f }
        val p = OpenWakeWordPipeline(r)
        repeat(10) { p.feed(ShortArray(chunk)) }
        val mel = r.melCalls; val emb = r.embeddingCalls
        p.reset()
        assertEquals(mel, r.melCalls)
        assertEquals(emb, r.embeddingCalls)
        assertEquals("the first predictions after a reset are zeroed again", 0f, p.feed(ShortArray(chunk)), 0f)
        assertEquals(1L, p.chunksProcessed)
    }

    @Test
    fun feedingASubrangeUsesOnlyThatRange() {
        val r = FakeRunner()
        val p = OpenWakeWordPipeline(r)
        val big = ShortArray(chunk * 2)
        p.feed(big, 0, chunk)
        assertEquals(1L, p.chunksProcessed)
        p.feed(big, chunk, 100)
        assertEquals(1L, p.chunksProcessed)
    }

    @Test
    fun defaultWarmupIsDeterministicAndInRange() {
        val a = OpenWakeWordPipeline.defaultWarmup()
        val b = OpenWakeWordPipeline.defaultWarmup()
        assertTrue(a.contentEquals(b))
        assertEquals(64000, a.size)
        assertTrue(a.all { it in -1000..999 })
        assertTrue("not constant", a.toSet().size > 100)
    }

    @Test
    fun rowBufferKeepsOnlyTheNewestRows() {
        val b = RowBuffer(2, 3)
        for (i in 1..10) b.append(floatArrayOf(i.toFloat(), -i.toFloat()), 1)
        assertEquals(3, b.rows)
        val out = FloatArray(6)
        b.copyRows(0, 3, out)
        assertEquals(listOf(8f, -8f, 9f, -9f, 10f, -10f), out.toList())
        b.append(FloatArray(20) { it.toFloat() }, 10) // more than maxRows at once
        b.copyRows(0, 3, out)
        assertEquals(listOf(14f, 15f, 16f, 17f, 18f, 19f), out.toList())
    }

    @Test
    fun shortRingKeepsTheNewestSamplesAndWraps() {
        val r = ShortRing(5)
        r.push(shortArrayOf(1, 2, 3), 0, 3)
        r.push(shortArrayOf(4, 5, 6, 7), 0, 4)
        val f = FloatArray(5)
        assertEquals(5, r.copyLastAsFloat(5, f))
        assertEquals(listOf(3f, 4f, 5f, 6f, 7f), f.toList())
        assertEquals(2, r.copyLastAsFloat(2, f))
        assertEquals(listOf(6f, 7f), f.take(2))
        r.push(ShortArray(8) { (it + 10).toShort() }, 0, 8) // larger than capacity
        assertEquals(listOf<Short>(13, 14, 15, 16, 17), r.drain().toList())
        assertEquals(0, r.size)
    }
}
