package com.jarvis.core.wake

/**
 * Kotlin port of the streaming half of openWakeWord 0.6.0:
 *  - `AudioFeatures._streaming_features` / `_streaming_melspectrogram` / `_get_embeddings` (utils.py), and
 *  - the single-model prediction path of `Model.predict` (model.py).
 *
 * Audio arrives in arbitrary sized pieces; every full 1280 samples (80 ms at 16 kHz) the melspectrogram model is run over the newest
 * audio, its output is transformed with `x / 10 + 2`, every 76-frame window with stride 8 goes through the embedding model, and the newest
 * 16 embeddings go through the wake-word model. The first five predictions are forced to zero (the reference does this while its
 * buffers fill).
 *
 * Equivalence with the reference is checked by `OpenWakeWordGoldenTest`, which replays calls recorded from the python implementation.
 * Not thread safe.
 *
 * @param warmupAudio audio used to prime the embedding history, like the 4 s of random noise the reference uses.
 */
class OpenWakeWordPipeline(
    private val runner: NeuralRunner,
    warmupAudio: ShortArray = defaultWarmup(),
) {
    private val raw = ShortRing(RAW_MAX)
    private val mel = RowBuffer(MEL_COLS, MEL_MAX_ROWS)
    private val features = RowBuffer(EMB, FEATURE_MAX_ROWS)
    private val initialFeatures: FloatArray
    private val initialFeatureRows: Int

    private var accumulated = 0
    private var remainder = ShortArray(0)
    private var predictionCount = 0
    private var previousScore = 0f

    /** The score returned by the latest [feed]. */
    var lastScore = 0f
        private set

    /** How many 1280-sample chunks have gone through the networks since construction or [reset]. */
    var chunksProcessed = 0L
        private set

    init {
        val (rows, flat) = embedAll(warmupAudio)
        initialFeatureRows = rows
        initialFeatures = flat
        reset()
    }

    /** Back to the state right after construction (same warm-up history, no audio, no scores). */
    fun reset() {
        raw.clear()
        mel.fillOnes(WINDOW)
        features.clear()
        features.append(initialFeatures, initialFeatureRows)
        accumulated = 0
        remainder = ShortArray(0)
        predictionCount = 0
        previousScore = 0f
        lastScore = 0f
        chunksProcessed = 0
    }

    /**
     * Feeds PCM16 samples and returns the score for them, exactly as `Model.predict(x)[name]` would:
     * the max over the chunks completed by this call, the previous score if no chunk completed, and 0 while fewer than five
     * predictions have been made.
     */
    fun feed(pcm: ShortArray, offset: Int = 0, length: Int = pcm.size - offset): Float {
        require(offset >= 0 && length >= 0 && offset + length <= pcm.size) { "bad range" }
        val prepared = streamingFeatures(pcm, offset, length)

        var score: Float
        if (prepared > CHUNK) {
            var best = Float.NEGATIVE_INFINITY
            for (i in prepared / CHUNK - 1 downTo 0) {
                val startRow = features.rows - WAKE_FRAMES - i
                if (startRow < 0) continue
                best = maxOf(best, runWake(startRow))
            }
            score = if (best == Float.NEGATIVE_INFINITY) 0f else best
        } else if (prepared == CHUNK) {
            score = runWake(features.rows - WAKE_FRAMES)
        } else {
            score = previousScore
        }
        if (predictionCount < ZERO_FIRST) score = 0f
        predictionCount = minOf(predictionCount + 1, 1000)
        previousScore = score
        lastScore = score
        return score
    }

    private fun runWake(startRow: Int): Float {
        val input = FloatArray(WAKE_FRAMES * EMB)
        features.copyRows(startRow, WAKE_FRAMES, input)
        return runner.wakeScore(input)
    }

    /** Port of `_streaming_features`: returns the number of samples that were run through the networks, or the pending count. */
    private fun streamingFeatures(pcm: ShortArray, offset: Int, length: Int): Int {
        var src = pcm
        var off = offset
        var n = length
        if (remainder.isNotEmpty()) {
            val joined = ShortArray(remainder.size + n)
            System.arraycopy(remainder, 0, joined, 0, remainder.size)
            System.arraycopy(pcm, offset, joined, remainder.size, n)
            src = joined
            off = 0
            n = joined.size
            remainder = ShortArray(0)
        }

        if (accumulated + n >= CHUNK) {
            val rest = (accumulated + n) % CHUNK
            if (rest != 0) {
                val even = n - rest
                raw.push(src, off, even)
                accumulated += even
                remainder = src.copyOfRange(off + even, off + n)
            } else {
                raw.push(src, off, n)
                accumulated += n
                remainder = ShortArray(0)
            }
        } else {
            accumulated += n
            raw.push(src, off, n)
        }

        var processed = 0
        if (accumulated >= CHUNK && accumulated % CHUNK == 0) {
            streamingMelspectrogram(accumulated)
            val window = FloatArray(WINDOW * MEL_COLS)
            for (i in accumulated / CHUNK - 1 downTo 0) {
                val end = mel.rows - STRIDE * i
                val startRow = end - WINDOW
                if (startRow >= 0) {
                    mel.copyRows(startRow, WINDOW, window)
                    val emb = runner.embeddings(window, 1)
                    features.append(emb, 1)
                }
            }
            chunksProcessed += accumulated / CHUNK
            processed = accumulated
            accumulated = 0
        }
        return if (processed != 0) processed else accumulated
    }

    /** Port of `_streaming_melspectrogram`: melspectrogram over the newest n + 3*160 samples, transformed and appended. */
    private fun streamingMelspectrogram(nSamples: Int) {
        val wanted = nSamples + 160 * 3
        val buf = FloatArray(minOf(wanted, RAW_MAX))
        val got = raw.copyLastAsFloat(wanted, buf)
        require(got >= 400) { "need at least 400 samples (25 ms) for the melspectrogram" }
        val input = if (got == buf.size) buf else buf.copyOf(got)
        val out = runner.melspectrogram(input)
        mel.append(out, out.size / MEL_COLS, ::melTransform)
    }

    /** Port of `AudioFeatures._get_embeddings` for a whole clip: returns (rows, flattened rows x 96). */
    private fun embedAll(audio: ShortArray): Pair<Int, FloatArray> {
        val spec = runner.melspectrogram(FloatArray(audio.size) { audio[it].toFloat() })
        val frames = spec.size / MEL_COLS
        val t = FloatArray(spec.size) { melTransform(spec[it]) }
        val starts = ArrayList<Int>()
        var i = 0
        while (i < frames) {
            if (i + WINDOW <= frames) starts.add(i)
            i += STRIDE
        }
        if (starts.isEmpty()) return 0 to FloatArray(0)
        val batch = FloatArray(starts.size * WINDOW * MEL_COLS)
        for ((k, s) in starts.withIndex()) System.arraycopy(t, s * MEL_COLS, batch, k * WINDOW * MEL_COLS, WINDOW * MEL_COLS)
        return starts.size to runner.embeddings(batch, starts.size)
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHUNK = 1280
        const val MEL_COLS = 32
        const val WINDOW = 76
        const val STRIDE = 8
        const val EMB = 96
        const val WAKE_FRAMES = 16
        const val MEL_MAX_ROWS = 970
        const val FEATURE_MAX_ROWS = 120
        const val RAW_MAX = SAMPLE_RATE * 10
        const val ZERO_FIRST = 5

        /** The transform the reference applies so the ONNX melspectrogram matches Google's original model. */
        fun melTransform(x: Float): Float = x / 10f + 2f

        /** 4 s of deterministic noise in [-1000, 1000): stand-in for the reference's `np.random.randint(-1000, 1000, 64000)`. */
        fun defaultWarmup(seed: Long = 0x5DEECE66DL): ShortArray {
            var s = seed
            return ShortArray(SAMPLE_RATE * 4) {
                s = s * 6364136223846793005L + 1442695040888963407L
                (((s ushr 33) % 2000).toInt() - 1000).toShort()
            }
        }
    }
}
