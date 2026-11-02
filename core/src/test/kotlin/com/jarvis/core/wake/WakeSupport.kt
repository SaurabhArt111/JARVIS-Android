package com.jarvis.core.wake

/** Test double: counts calls and returns scripted wake scores; melspectrogram/embedding outputs have the right shapes but dummy values. */
class FakeRunner(private val scores: (Int) -> Float = { 0f }) : NeuralRunner {
    var melCalls = 0
    var embeddingCalls = 0
    var wakeCalls = 0
    val melInputSizes = ArrayList<Int>()
    val wakeInputs = ArrayList<FloatArray>()
    var embeddingCounter = 0

    override fun melspectrogram(samples: FloatArray): FloatArray {
        melCalls++
        melInputSizes.add(samples.size)
        // the real model yields (n - 400) / 160 + 1 frames for n samples; the exact value does not matter for these tests
        val frames = if (samples.size >= 400) (samples.size - 400) / 160 + 1 else 1
        return FloatArray(frames * OpenWakeWordPipeline.MEL_COLS)
    }

    override fun embeddings(windows: FloatArray, batch: Int): FloatArray {
        embeddingCalls++
        return FloatArray(batch * OpenWakeWordPipeline.EMB) { (embeddingCounter + it / OpenWakeWordPipeline.EMB).toFloat() }.also { embeddingCounter += batch }
    }

    override fun wakeScore(features: FloatArray): Float {
        wakeInputs.add(features.copyOf())
        return scores(wakeCalls++)
    }
}

/** A sine tone of the given amplitude (0..1) as PCM16. */
fun tone(samples: Int, amplitude: Float, freq: Double = 440.0, rate: Int = 16000, phase: Int = 0): ShortArray =
    ShortArray(samples) { (Math.sin(2 * Math.PI * freq * (it + phase) / rate) * amplitude * 32767).toInt().toShort() }

fun silence(samples: Int) = ShortArray(samples)
