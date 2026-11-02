# Porting notes: wake word, audio, speech (`Backend/Wake.py`, `Audio.py`, `Ducking.py`, `TextToSpeech.py`)

| Python | Android |
|---|---|
| `Wake.extract_command`, `is_dangling` | `core/wake/WakeText.kt` (JVM-tested) |
| `Wake.Porcupine` (Picovoice, needs an access key and licence) | **Dropped.** A vendor key/licence cannot be shipped in an open project |
| `Wake.OpenWakeWord` (Python, onnxruntime) | `core/wake/OpenWakeWordPipeline.kt` (pure Kotlin streaming feature extraction and prediction logic) + `app/voice/OrtNeuralRunner.kt` (the only file that touches ONNX Runtime) + `OpenWakeWordEngine` |
| `Wake.make_engine` | `core/wake/WakeWordEngine.kt` + `app/voice/WakeController` (engine selection: openWakeWord, speech, auto, off; restart back-off; pause for calls / user / command) |
| `Audio.AudioHub`, `_PyAudioSource` (PortAudio, VAD) | `app/voice/AudioCapture` (`AudioRecord` 16 kHz mono, silenced-microphone detection) + `core/wake/EnergyVad` |
| `Audio.transcribe` | `app/voice/VoiceInput` (Android `SpeechRecognizer`; optional on-device only) |
| `Ducking.py` | audio focus in `platform/AndroidSpeaker` |
| `TextToSpeech.py` | `platform/AndroidSpeaker` (system TextToSpeech) |
| background thread | foreground service `AssistantForegroundService` (type microphone, visible notification) |

## What was verified
* **Pipeline equivalence.** `tools/wake-models/make_golden.py` runs the reference openWakeWord (0.6.0) on seeded synthetic audio and records every call to its three ONNX models. The Kotlin test
  `OpenWakeWordGoldenTest` feeds the same audio and checks that the pipeline builds **the same neural inputs** (315,936 values compared across two scenarios, worst absolute difference 0.0) and
  reproduces the per-feed scores when the models are replaced by the recorded answers.
* Debounce, VAD, sensitivity profiles, restart back-off, wake-text parsing: JVM unit tests.

## What was not verified
* **Real detection.** The synthetic audio contains no spoken wake word (scores stay near 0.007). False accepts / rejects, latency and battery use on real phones are unmeasured.
* **ONNX Runtime on Android.** `OrtNeuralRunner` was compiled against hand-written stubs of the ORT classes (Maven Central is not reachable here). A real Gradle build will resolve the real library;
  a signature mismatch, if any, would show as a compile error there. ORT numerics were not exercised (the golden test replaces the networks).
* Background behaviour on real devices (Doze, OEM killers, silenced microphone, speech recogniser from a foreground service with the screen off).

## Licence
The openWakeWord **models** are CC BY-NC-SA 4.0 (non-commercial) and are not bundled; they are downloaded by the user in-app with pinned SHA-256 checks. The openWakeWord code is Apache-2.0.
