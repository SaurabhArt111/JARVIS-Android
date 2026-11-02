package com.jarvis.core.wake

/**
 * The three neural networks of openWakeWord, behind plain float arrays so the buffering logic around them can be
 * tested on the JVM with a recorded replay and, on Android, backed by ONNX Runtime.
 *
 * All tensors are row-major and flattened.
 */
interface NeuralRunner : AutoCloseable {
    /**
     * Melspectrogram model. [samples] are the PCM16 values as floats (no scaling), one batch of N samples.
     * Returns the raw model output flattened as frames x 32 (the squeezed [time,1,frames,32] tensor).
     */
    fun melspectrogram(samples: FloatArray): FloatArray

    /** Embedding model. [windows] is [batch, 76, 32, 1]; returns [batch, 96] flattened. */
    fun embeddings(windows: FloatArray, batch: Int): FloatArray

    /** Wake-word model. [features] is [1, 16, 96]; returns the single score in 0..1. */
    fun wakeScore(features: FloatArray): Float

    override fun close() {}
}
