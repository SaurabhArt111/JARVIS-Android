# JARVIS for Android

A native Android (Kotlin) rebuild of the desktop Python JARVIS assistant: an agent loop with tools (reminders, notes, timers, alarms, calls, messages, apps, media, device control, web search, weather,
image generation, calculator, saved documents), several AI providers with fallback, honest results, an optional wake word, a Dynamic-Island-style overlay, a lock-screen assistant, and the long-press-Home
assistant gesture ("Jarvis Air").

> **Read this first - what has and has not been verified.** This project was written and checked in a sandbox with **no Android SDK, no Gradle plugin downloads and no phone or emulator**.
> It has **never been built with Gradle** and **never run on a device**. What *has* been checked is listed under [Verification](#verification). Treat the first Android Studio build as the real
> first build: fix-ups (a dependency version, a lint error) are possible. `docs/FEATURE_STATUS.md` says, feature by feature, what is tested, compiled only, or unverified.

## Requirements
* Android Studio with AGP 8.10.1 support (Narwhal 2025.1.1 or newer), JDK 17, Android SDK Platform 36.
* Device/emulator with Android 10 (API 29) or newer. `targetSdk 36`.
* Gradle 8.13 through the included wrapper (`gradlew`, `gradlew.bat`). The wrapper jar was generated offline; its download URL is not checksum-pinned.

## Open, build, run
1. Open the folder in Android Studio (let it create `local.properties` with `sdk.dir`; see `local.properties.example`).
2. Sync. Dependencies are `kotlinx-coroutines` (core + android) and `com.microsoft.onnxruntime:onnxruntime-android:1.22.0` (optional wake word), plus test libraries; there is **no** AndroidX, Compose,
   Material, Hilt, Room or WorkManager. If `1.22.0` does not resolve, set `onnxruntime` in `gradle/libs.versions.toml` to the newest published version (use 1.22.0 or later: earlier builds are not 16 KB page-size aligned).
3. Run the unit tests: `./gradlew :core:test`. Build: `./gradlew :app:assembleDebug`. Install: run the `app` configuration, or `./gradlew :app:installDebug`.
4. Instrumented tests (`app/src/androidTest`, 21 tests: SQL stores, secrets/log redaction, platform adapters): `./gradlew :app:connectedDebugAndroidTest` on a device/emulator. They have never been run.

## First run
Open **Settings** and:
1. **AI providers** - paste at least one key (Groq, Cohere, or any OpenAI-compatible endpoint) or point to an Ollama server. Without any provider JARVIS still works through the offline command parser (reminders, timers, notes, apps, device control, calculator, time) but cannot chat.
2. **Permissions** - shows the real state of every permission and opens the right system screen. Nothing is requested up front; each permission is asked for when a feature needs it (`docs/PERMISSIONS.md`).
3. Optional: **Wake word and listening**, **Dynamic Island**, **Lock screen**, **Default assistant**, **Integrations** (notifications, WhatsApp reply, accessibility shortcuts). Everything here is **off by default**.

### Providers and keys
Automatic order for chat: Groq, Cohere, OpenAI-compatible, Ollama (the tool-decision step tries Cohere first). You can pin a provider per role; "cloud" mode skips Ollama and "local" mode uses only Ollama.

| Provider | What you need | Notes |
|---|---|---|
| Groq | API key | first in the automatic order for chat |
| Cohere | API key | fallback; tried first for the tool-decision step |
| OpenAI-compatible | key + base URL + model | any compatible server |
| Ollama | server URL + model | a LAN `http://` address is **refused** (cleartext is allowed only for `localhost`, `127.0.0.1`, `10.0.2.2`); use HTTPS, or `adb reverse tcp:11434 tcp:11434` and `http://127.0.0.1:11434` |
| SerpAPI / Hugging Face / Pollinations | optional keys | web search, image generation |

Keys are stored with the Android Keystore (AES-256-GCM), never in the settings export, never written to logs (redacted), and `allowBackup` is off. `config/jarvis-settings.example.json` is an importable template of
**non-secret** preferences. **Never put real keys in this repository, and never commit an `.env`.** If a key ever ended up in a chat, zip or screenshot, revoke it and create a new one.

### What is sent to AI providers
Your request, recent conversation, and a short "state" block (current time, upcoming reminders, recent notes, running timers, what is playing) so answers are grounded. Turn off
**Settings > AI providers > share personal details** to send only counts instead of reminder/note text; the lock screen does that automatically while the phone is locked. Contacts, call logs, notification contents and files are
not added to the AI prompt unless a tool result is needed to answer. Speech recognition is Android's own: by default it may use the phone's cloud recogniser (turn on "prefer on-device recognition" to forbid that).

## Features, in one paragraph each (details in `docs/`)
* **Assistant gesture** - choose "Jarvis Air" under *Settings > Apps > Default apps > Digital assistant app*; long-press Home opens the compact chat, listening. `docs/ASSISTANT_SETUP.md`.
* **Background wake word ("hey jarvis")** - opt-in foreground service with a permanent notification; on-device openWakeWord models (downloaded in-app, **not bundled**, non-commercial licence), or Android's recogniser. `docs/BACKGROUND_LISTENING.md`.
* **Island overlay** - status pill near the camera cutout as Android reports it; needs "Display over other apps". `docs/ISLAND.md`.
* **Lock screen** - what JARVIS may do while locked, with an unlock-then-retry flow; never bypasses the keyguard. `docs/LOCK_SCREEN.md`.
* **Storage** - files JARVIS creates go to **Documents/JARVIS/<Category>** through MediaStore (no storage permission), with a private-folder fallback; the Library screen browses, searches, opens, shares, exports and deletes (deletion only from a confirmed UI action - never by AI or voice). Names are sanitised and de-duplicated.
* **Reminders, timers, alarms, notes** - SQLite + `AlarmManager`; natural-language times with time zones; re-registered after reboot, time or zone change; the app says when a reminder is only approximate (no exact-alarm access) and never claims one was created unless it read it back.
* **Contacts, calls, apps** - names resolved on the phone; ambiguity is asked about; calls open the dialer unless `CALL_PHONE` is granted; messages are opened with the text filled in and **never sent by JARVIS**.
* **Notifications and accessibility** - notification reading/reply through the listener only (WhatsApp reply uses the notification's own reply action); an accessibility service limited to global actions that reads nothing.
* **Honesty** - every action returns `ok / message / missing requirement / verified`; replies that claim something was done without a verified tool result are rejected by the honesty guard.

## Verification
What was actually run, in this sandbox:
| Check | Command | Result when last run |
|---|---|---|
| JVM unit tests of `:core` (routing, agent, tools, time, organizer, AI gateway with fake HTTP, safety, lock policy, notification rules, island geometry/state, wake pipeline, storage names) | `tools/offline-verify/build_core.sh` (stand-in for `./gradlew :core:test`) | **699 tests pass** with a plain JUnit runner. Gradle itself was not run |
| Android code compiles against the real SDK API | `tools/offline-verify/build_app.sh` (core + app against `android.jar` API 36, stub `R`) | **passes**, then resource and manifest cross-checks (`check_res.py`) pass |
| SQL schema and 38 queries on real SQLite | `tools/offline-verify/check_sql.py app/src/main/kotlin/com/jarvis/assistant/data/Sql.kt` | **passes** |
| Instrumented tests compile | `tools/offline-verify/build_android_tests.sh` | **compiles** (androidx test stubbed); never executed |
| Wake pipeline equals the reference openWakeWord | golden fixture from `tools/wake-models/make_golden.py` | model inputs identical (worst diff 0.0 over 315,936 values) |

The offline scripts need local JDK/Kotlin jars and the AOSP `android.jar` and are provided for transparency; on your machine use Gradle.

**Not verified (needs a device, Gradle or credentials):** the Gradle/AGP build itself; any UI rendering; alarms, notifications, TTS, contacts, MediaStore, overlay, lock-screen, assistant-gesture and foreground-service behaviour on real phones and OEM skins;
the real ONNX Runtime API (compiled only against stubs); wake-word accuracy, latency and battery; live provider calls; the instrumented tests.

## Known limits (also in the docs)
* Android 12+/14+ block starting foreground services (and microphone services after boot) from the background: listening is started from the visible app and, after a reboot, from a "tap to resume" notification.
* OEM battery managers can kill the listening service; nothing in code can prevent that.
* Neural wake phrase is fixed to "hey jarvis"; custom wake words need the speech engine, which costs battery and may beep.
* Google Play: the island's `specialUse` foreground service, microphone foreground service, `SYSTEM_ALERT_WINDOW`, full-screen intents, the notification listener and the accessibility service each need a Play Console declaration/justification.
* After an uninstall/reinstall, scoped storage lets the user see old `Documents/JARVIS` files but the new install cannot list or modify them.
* "Cancel reminder" marks it cancelled in JARVIS's own data; alarms handed to the Clock app are never reported as verified (Android gives no read-back).
* No crash-reporting service: a crash is recorded locally and shown on next start (Settings > Diagnostics).
* UI text is English only.

## Project layout
```
core/                  pure Kotlin/JVM (no Android): routing, agent loop, tools, time, organizer, AI gateway, safety, lock policy, island geometry, wake pipeline  (+ 699 tests)
app/                   Android app (com.jarvis.assistant): data, platform adapters, services, ui, island, lock, access, assist, voice, di
app/src/androidTest/   instrumented tests (not run)
config/                non-secret settings template + notes about keys
docs/                  ARCHITECTURE, ASSISTANT_SETUP, BACKGROUND_LISTENING, ISLAND, LOCK_SCREEN, PERMISSIONS, PORTING_MAP, FEATURE_STATUS, porting-notes/
tools/                 offline-verify scripts, wake-model fixture generator, icon converter
```

## Licences and attributions
* Feather icons (MIT) - `tools/icons/LICENSE-feather.txt`.
* openWakeWord models: **CC BY-NC-SA 4.0**, not included; downloaded by the user. openWakeWord code: Apache-2.0.
* No licence has been chosen for this project's own code: add one before distributing.
