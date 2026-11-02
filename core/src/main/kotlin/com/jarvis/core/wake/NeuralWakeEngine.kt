package com.jarvis.core.wake

/**
 * openWakeWord pipeline + voice-activity gate + patience + debounce, as a [WakeWordEngine].
 *
 * Battery: while the [EnergyVad] says the room is quiet the networks are NOT run; the audio is kept in a short pre-roll ring instead.
 * When speech starts, the pre-roll is replayed through the pipeline first (so the start of the phrase is not lost) and the engine stays
 * active until the VAD's hangover ends. With [useVad] = false every block is analysed (more CPU, no gating risk).
 *
 * Time is measured in samples fed, so behaviour is identical in tests and on a device.
 */
class NeuralWakeEngine(
    private val pipeline: OpenWakeWordPipeline,
    private val profile: SensitivityProfile = SensitivityProfile.MEDIUM,
    override val phrase: String = "hey jarvis",
    private val useVad: Boolean = true,
    private val vad: EnergyVad = EnergyVad(),
    private val preRollMs: Int = 1600,
    /** Closed with the engine (the neural runner and its native sessions). */
    private val owned: AutoCloseable? = null,
) : WakeWordEngine {
    private val debouncer = Debouncer(profile.cooldownMs)
    private val preRoll = ShortRing(OpenWakeWordPipeline.SAMPLE_RATE * preRollMs / 1000)
    private var samplesSeen = 0L
    private var consecutive = 0
    private var active = !useVad
    private var blocksSeen = 0L
    private var blocksAnalysed = 0L
    private var peak = 0f
    private var detections = 0

    /** Latest counters, for a diagnostics screen. */
    val stats: WakeStats
        get() = WakeStats(blocksSeen, blocksAnalysed, pipeline.lastScore, peak, detections, vad.noiseFloor, active)

    override fun feed(pcm: ShortArray, length: Int): WakeDetection? {
        val n = minOf(length, pcm.size)
        if (n <= 0) return null
        blocksSeen++
        samplesSeen += n
        val nowMs = samplesSeen * 1000L / OpenWakeWordPipeline.SAMPLE_RATE

        if (!useVad) return analyse(pcm, 0, n, nowMs)

        val speaking = vad.process(pcm, n)
        if (!speaking) {
            if (active) { // the hangover ended: go back to saving power
                active = false
                consecutive = 0
            }
            preRoll.push(pcm, 0, n)
            return null
        }
        if (!active) { // speech just started: replay what we kept, then this block
            active = true
            preRoll.push(pcm, 0, n)
            val kept = preRoll.drain()
            return analyseInPieces(kept, nowMs)
        }
        return analyse(pcm, 0, n, nowMs)
    }

    private fun analyseInPieces(audio: ShortArray, nowMs: Long): WakeDetection? {
        var hit: WakeDetection? = null
        var off = 0
        while (off < audio.size) {
            val len = minOf(OpenWakeWordPipeline.CHUNK, audio.size - off)
            val d = analyse(audio, off, len, nowMs)
            if (hit == null) hit = d
            off += len
        }
        return hit
    }

    private fun analyse(pcm: ShortArray, off: Int, len: Int, nowMs: Long): WakeDetection? {
        blocksAnalysed++
        val score = pipeline.feed(pcm, off, len)
        if (score > peak) peak = score
        if (score >= profile.threshold) consecutive++ else consecutive = 0
        if (consecutive >= profile.patience) {
            consecutive = 0
            if (debouncer.accept(nowMs)) {
                detections++
                return WakeDetection(score, nowMs, phrase)
            }
        }
        return null
    }

    override fun reset() {
        pipeline.reset()
        vad.reset()
        debouncer.reset()
        preRoll.clear()
        consecutive = 0
        active = !useVad
        peak = 0f
    }

    override fun close() {
        owned?.close()
    }
}
