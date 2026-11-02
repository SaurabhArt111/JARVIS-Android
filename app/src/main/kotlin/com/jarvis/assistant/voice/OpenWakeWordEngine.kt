package com.jarvis.assistant.voice

import com.jarvis.core.settings.Keys
import com.jarvis.core.settings.Settings
import com.jarvis.core.wake.NeuralWakeEngine
import com.jarvis.core.wake.OpenWakeWordPipeline
import com.jarvis.core.wake.SensitivityProfile
import com.jarvis.core.wake.WakeModels

/**
 * Builds the neural wake-word engine from the downloaded models: ONNX Runtime sessions ([OrtNeuralRunner]) feeding openWakeWord's streaming pipeline
 * (core), wrapped with the voice-activity gate, the sensitivity profile and the debounce. Creating it runs the models once over 4 s of warm-up noise
 * (a few hundred ms), so call it off the main thread.
 */
object OpenWakeWordEngine {
    fun create(store: WakeModelStore, settings: Settings): NeuralWakeEngine {
        val runner = OrtNeuralRunner.open(store.dir)
        try {
            val pipeline = OpenWakeWordPipeline(runner)
            return NeuralWakeEngine(
                pipeline = pipeline,
                profile = SensitivityProfile.fromSetting(settings.get(Keys.WAKE_SENSITIVITY)),
                phrase = WakeModels.PHRASE,
                useVad = true,
                owned = runner,
            )
        } catch (e: Exception) {
            runner.close()
            throw e
        }
    }
}
