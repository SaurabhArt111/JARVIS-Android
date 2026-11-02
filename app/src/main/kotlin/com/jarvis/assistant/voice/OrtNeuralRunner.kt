package com.jarvis.assistant.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.jarvis.core.wake.NeuralRunner
import com.jarvis.core.wake.WakeModels
import java.io.File
import java.nio.FloatBuffer

/**
 * The ONLY file that touches ONNX Runtime. Runs openWakeWord's three networks (melspectrogram, embedding, wake word) on the CPU with one thread each,
 * like the python reference. Input/output tensor names are read from the model files instead of being hard-coded.
 *
 * Calls come from the single audio thread; [close] must not race with them (the controller stops the thread first).
 */
class OrtNeuralRunner private constructor(
    private val env: OrtEnvironment,
    private val mel: OrtSession,
    private val embedding: OrtSession,
    private val wake: OrtSession,
) : NeuralRunner {
    private val melInput = mel.inputNames.first()
    private val embeddingInput = embedding.inputNames.first()
    private val wakeInput = wake.inputNames.first()

    private fun run(session: OrtSession, inputName: String, data: FloatArray, shape: LongArray): FloatArray {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                val out = result.get(0) as OnnxTensor
                val fb = out.floatBuffer
                val arr = FloatArray(fb.remaining())
                fb.get(arr)
                return arr
            }
        }
    }

    override fun melspectrogram(samples: FloatArray): FloatArray =
        run(mel, melInput, samples, longArrayOf(1, samples.size.toLong()))

    override fun embeddings(windows: FloatArray, batch: Int): FloatArray =
        run(embedding, embeddingInput, windows, longArrayOf(batch.toLong(), 76, 32, 1))

    override fun wakeScore(features: FloatArray): Float =
        run(wake, wakeInput, features, longArrayOf(1, 16, 96))[0]

    override fun close() {
        for (s in listOf(mel, embedding, wake)) try { s.close() } catch (_: Exception) { }
    }

    companion object {
        /** Opens the three sessions from [dir] (file names from [WakeModels]). Throws if a file is missing or unreadable. */
        fun open(dir: File): OrtNeuralRunner {
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions()
            opts.setIntraOpNumThreads(1)
            opts.setInterOpNumThreads(1)
            val opened = ArrayList<OrtSession>()
            try {
                for (spec in listOf(WakeModels.MELSPEC, WakeModels.EMBEDDING, WakeModels.WAKE)) {
                    val f = File(dir, spec.fileName)
                    if (!f.isFile) throw IllegalStateException("model file missing: ${spec.fileName}")
                    opened.add(env.createSession(f.absolutePath, opts))
                }
            } catch (e: Exception) {
                opened.forEach { try { it.close() } catch (_: Exception) { } }
                throw e
            } finally {
                try { opts.close() } catch (_: Exception) { }
            }
            return OrtNeuralRunner(env, opened[0], opened[1], opened[2])
        }
    }
}
