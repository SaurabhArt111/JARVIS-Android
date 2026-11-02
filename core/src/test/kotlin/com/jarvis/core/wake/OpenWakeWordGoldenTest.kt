package com.jarvis.core.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.util.zip.GZIPInputStream
import kotlin.math.abs

/**
 * Replays calls recorded from the reference python openWakeWord (tools/wake-models/make_golden.py) through the Kotlin pipeline.
 *
 * Verified here: every tensor the Kotlin pipeline hands to the melspectrogram, embedding and wake models equals what the reference
 * handed them (|diff| <= 1e-4), in the same order, and the per-feed scores equal the reference scores.
 * NOT verified here: ONNX Runtime numerics, real-speech detection, timing. (The recorded audio is synthetic and contains no wake word,
 * so the recorded scores are small; the check that matters is the equality of the neural inputs.)
 */
class OpenWakeWordGoldenTest {
    private class Call(val kind: Int, val input: FloatArray, val output: FloatArray)
    private class Feed(val pcm: ShortArray, val score: Float, val callsAfter: Int)
    private class Scenario(val name: String, val warmup: ShortArray, val feeds: List<Feed>, val calls: List<Call>)

    private fun load(): List<Scenario> {
        val stream = javaClass.getResourceAsStream("/wake/oww_golden.bin.gz") ?: error("golden fixture missing from the test classpath")
        DataInputStream(GZIPInputStream(stream).buffered()).use { d ->
            val magic = ByteArray(4).also { d.readFully(it) }
            assertEquals("OWW1", String(magic))
            val scenarios = ArrayList<Scenario>()
            repeat(d.readInt()) {
                val nameBytes = ByteArray(d.readUnsignedShort()).also { d.readFully(it) }
                val warm = ShortArray(d.readInt()) { d.readShort() }
                val feeds = List(d.readInt()) {
                    val pcm = ShortArray(d.readInt()) { d.readShort() }
                    val score = d.readFloat()
                    Feed(pcm, score, d.readInt())
                }
                val calls = List(d.readInt()) {
                    val kind = d.readByte().toInt()
                    val input = FloatArray(d.readInt()) { d.readFloat() }
                    val output = FloatArray(d.readInt()) { d.readFloat() }
                    Call(kind, input, output)
                }
                scenarios.add(Scenario(String(nameBytes), warm, feeds, calls))
            }
            return scenarios
        }
    }

    private class Replay(val calls: List<Call>) : NeuralRunner {
        var pos = 0
        var worst = 0.0
        var compared = 0L
        private fun take(kind: Int, input: FloatArray): Call {
            assertTrue("pipeline made more neural calls than the reference (call #$pos)", pos < calls.size)
            val c = calls[pos]
            assertEquals("call #$pos is for a different model", c.kind, kind)
            assertEquals("call #$pos input size", c.input.size, input.size)
            for (i in input.indices) {
                val diff = abs((c.input[i] - input[i]).toDouble())
                if (diff > worst) worst = diff
                assertTrue("call #$pos kind $kind input[$i]: recorded=${c.input[i]} kotlin=${input[i]}", diff <= 1e-4)
            }
            compared += input.size
            pos++
            return c
        }
        override fun melspectrogram(samples: FloatArray) = take(0, samples).output
        override fun embeddings(windows: FloatArray, batch: Int): FloatArray {
            val out = take(1, windows).output
            assertEquals("embedding batch/output size", batch * 96, out.size)
            return out
        }
        override fun wakeScore(features: FloatArray) = take(2, features).output.also { assertEquals(1, it.size) }[0]
    }

    @Test
    fun fixtureHasBothScenarios() {
        val s = load()
        assertEquals(listOf("aligned", "irregular"), s.map { it.name })
        assertTrue(s.all { it.calls.size > 50 })
    }

    @Test
    fun kotlinPipelineMatchesTheReferenceNeuralInputsAndScores() {
        for (sc in load()) {
            val replay = Replay(sc.calls)
            val pipeline = OpenWakeWordPipeline(replay, sc.warmup)
            val warmupCalls = replay.pos
            assertEquals("${sc.name}: warm-up should be one melspectrogram and one embedding call", 2, warmupCalls)
            for ((i, f) in sc.feeds.withIndex()) {
                val score = pipeline.feed(f.pcm)
                assertEquals("${sc.name}: neural call count after feed #$i", f.callsAfter, replay.pos)
                assertEquals("${sc.name}: score of feed #$i", f.score.toDouble(), score.toDouble(), 1e-4)
            }
            assertEquals("${sc.name}: every recorded call was consumed", sc.calls.size, replay.pos)
            assertNotNull(pipeline)
            println("golden ${sc.name}: ${sc.feeds.size} feeds, ${replay.pos} neural calls replayed, ${replay.compared} input values compared, worst |diff| = ${replay.worst}")
        }
    }

    @Test
    fun resetReturnsToTheWarmedUpStateWithoutRecomputingTheWarmup() {
        val sc = load().first()
        val replay = Replay(sc.calls)
        val pipeline = OpenWakeWordPipeline(replay, sc.warmup)
        for (f in sc.feeds.take(10)) pipeline.feed(f.pcm)
        // after reset the whole recorded run should replay again from the first streaming call (warm-up calls are not repeated)
        pipeline.reset()
        replay.pos = 2
        for ((i, f) in sc.feeds.withIndex()) {
            val score = pipeline.feed(f.pcm)
            assertEquals("feed #$i after reset", f.score.toDouble(), score.toDouble(), 1e-4)
        }
        assertEquals(sc.calls.size, replay.pos)
    }
}
