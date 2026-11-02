# Background listening ("Hey JARVIS") - how it works and where Android stops it

Background listening is **off by default**. Turn it on in **JARVIS > Settings > Wake word and listening**. It runs as a foreground service of type `microphone`
(`service/AssistantForegroundService`) with a notification that is always visible while the microphone is open (Pause / Talk / Stop buttons). There is no hidden listening.

## Wake-word engines (Settings > Wake word and listening)

| Engine | What it is | Costs / limits |
|---|---|---|
| **openWakeWord** (`openwakeword`) | Small neural networks (ONNX Runtime) run on the phone over the microphone audio. The Kotlin port of the streaming pipeline (`core/wake/OpenWakeWordPipeline`) was checked against the reference Python implementation. Phrase: **"hey jarvis"** only (that is the model that exists). | Needs three model files the user downloads inside the app (see licence below). Audio never leaves the phone. |
| **Speech recogniser** (`speech`) | Android's own `SpeechRecognizer` restarted continuously, matching the custom wake words you set. | Drains battery, may beep or show UI on some phones, may stop after a minute or two of silence (restarts with back-off), may use the cloud recogniser depending on the phone. |
| **Auto** (default) | openWakeWord if its models are installed; otherwise the speech engine only if an *on-device* recogniser is available. It never silently falls back to a cloud recogniser. | If neither is possible, background listening does not start and says why. |
| **Off** | Only the push-to-talk button and the assistant gesture. | |

Once the wake phrase is heard: short tone, one request is listened for with Android's recogniser, handed to the same engine as typed text (origin `WAKE`, with the real keyguard
state so the lock-screen policy applies), the answer is spoken, then listening resumes.

### Model files and licence

The three openWakeWord model files (`melspectrogram.onnx`, `embedding_model.onnx`, `hey_jarvis_v0.1.onnx`) are **CC BY-NC-SA 4.0** (non-commercial). They are **not bundled** in this
project or the APK. **Settings > Wake word and listening > Wake word model** downloads them over HTTPS **only when the user taps Download**, checks each file against a pinned SHA-256 and rejects
a mismatch. If you plan to distribute JARVIS commercially, you must train/obtain wake models whose licence allows it.

## What Android allows (and the app's response)

| Android rule | Consequence | What JARVIS does |
|---|---|---|
| **Android 12+** blocks starting foreground services from the background | The service cannot restart itself from a random background event | It is started only from the visible app (Settings toggle / "Talk" button) |
| **Android 14+** forbids starting a `microphone` foreground service from `BOOT_COMPLETED` | Listening cannot resume by itself after a reboot | If you had it on, a **"Tap to resume listening"** notification is posted after boot (`ListenOnBootPrompt`) |
| **Doze / App Standby** | The CPU may sleep; background network is deferred | A running foreground service is exempt from most of it; reminders use exact alarms, not the listening service |
| **Battery optimisation** | Aggressive settings can still stop the service | Settings > Permissions (group "surfaces") links to the battery-optimisation list; JARVIS asks for exemption only if you choose to |
| **Microphone privacy toggle / another app recording** | Android may *silence* the microphone (audio of zeros) for a background app | Detected through `AudioRecordingCallback` plus a digital-zero check; the status shows "Android is blocking the microphone for JARVIS right now" instead of claiming to listen |
| **Phone calls** | Only one app holds the microphone | Listening pauses during calls and resumes afterwards |
| **Privacy indicator (Android 12+)** | A green dot shows whenever the mic is in use | Expected; it is the honest signal |
| **OEM battery managers** (Xiaomi, Huawei, Oppo, Vivo, OnePlus, Samsung "sleeping apps") | Can kill foreground services regardless of the rules above | Cannot be fixed in code. Allow *Auto-start*, set battery to *No restrictions*, lock JARVIS in recents (see dontkillmyapp.com) |
| **Screen off with the speech engine** | Some recognisers refuse to run | The openWakeWord engine is the dependable one |

## Honest limits

* Wake detection **accuracy, false-accept/-reject rate, latency and battery use are unmeasured**: there is no device in this environment. The Kotlin pipeline reproduces the reference
  model inputs exactly (golden test, worst difference 0.0), but the synthetic test audio contains no spoken wake word, so a real "Hey JARVIS" score is untested.
  The ONNX Runtime calls (`voice/OrtNeuralRunner.kt`) were compiled only against hand-written stubs because the real library could not be downloaded here.
* The sensitivity profiles (low/medium/high) are starting values from the reference project, not tuned on real recordings.
* Listening stops when the user stops it, when the microphone permission is revoked, or when Android kills the service. It does not restart itself after a kill unless the user opens the app.
* The long-press-Home gesture and the lock-screen shortcut do **not** need background listening; they work with it switched off.
