# Python JARVIS -> Android JARVIS: where everything went

Left: the desktop project (`Backend/`, `Frontend/GUI/`, `main.py`). Right: the Android project. Detail and deliberate differences are in `porting-notes/*.md`.

## Backend

| Python | Kotlin | Notes |
|---|---|---|
| `Config.py`, `.env` | `core/settings/Settings.kt` (same key names), `app/data/AndroidSettingsStore`, `SecretStore` | `.env` is **not** used or imported. Keys are typed in the app and kept in the Android Keystore-backed store; the settings export never includes secrets |
| `LLM.py`, `Model.py`, `Chatbot.py` | `core/ai/*` (`DefaultLlmGateway`, providers Groq / Cohere / Ollama / OpenAI-compatible, `ProviderHealth`, `LlmWire`), `core/agent/Prompts.kt` | Fallback order, retries, timeouts, cancel, health tracking and key redaction ported; HTTP via `JdkHttpClient` |
| `Agent.py`, `Context.py` | `core/agent/AgentLoop.kt`, `Honesty.kt`, `ToolExecutor.kt`, `WorldContext.kt` | Honesty guards (claims of an action without a tool result are rejected), tool-result validation, step limit |
| `Tools.py`, `Skills.py` | `core/tools/impl/*` (20 tools), `core/offline/OfflineParser.kt`, `tools/impl/Calculator.kt` | Same tool path for the AI agent and the offline parser |
| `Engine.py`, `Dialog.py`, `Greeting.py` | `core/engine/*`, `core/greeting/Greeting.kt` | Pending questions, confirmations, duplicate suppression, speech policy, lock redaction |
| `Safety.py` | `core/safety/Safety.kt` | AI/voice never deletes anything |
| `TimeParse.py`, `Organizer.py`, `Notes.py`, `Scheduler.py` | `core/time/TimeParser.kt`, `core/organizer/*`, `app/data/Stores.kt`, `platform/AndroidReminderScheduler`, `AndroidTimerService` | SQLite + AlarmManager, boot / time-zone / time-change re-registration |
| `RealtimeSearchEngine.py` | `core/search/*` | Web search + answer composition |
| `ImageGeneration.py` | `core/image/*` | Hugging Face / Pollinations, quality, count, size; files saved via MediaStore |
| `Apps.py`, `Browsers.py`, `WindowControl.py`, `Automation.py` | `platform/AndroidAppLauncher`, `ActivityStarter`, `core/apps/AppMatcher.kt`, `access/AndroidSystemNavigator` | Desktop window control becomes launcher intents and accessibility *global actions* only |
| `Messenger.py`, `WhatsappMessage.py`, `TelegramMessage.py` | `platform/AndroidMessagingGateway`, `AndroidCallGateway`, `AndroidContactsGateway`, `access/*` (notification reply) | Never sends silently; WhatsApp reply only through the notification's own reply action |
| `Media.py`, `NowPlaying.py` | `platform/AndroidMediaGateway` | Media sessions via notification access; media keys otherwise |
| `SystemControl.py`, `DeviceInfo.py` | `platform/AndroidDeviceControl` | Volume, brightness, torch, DND, battery, network, clipboard; every setter re-reads |
| `TextToSpeech.py`, `Ducking.py` | `platform/AndroidSpeaker` | TextToSpeech + audio focus ducking |
| `Audio.py`, `Wake.py` | `core/wake/*` (text + openWakeWord pipeline), `app/voice/*`, `service/AssistantForegroundService` | See `porting-notes/voice-service.md` |
| `Health.py`, `Util.py`, `Result.py` | `app/data/AppLog` (redacting log), `CrashHandler`, `core/util/*`, `ports/ActionResult` | |
| `main.py` | `app/JarvisApp.kt`, `di/AppContainer.kt` | Hand-written DI |

## Frontend

| Python (PyQt) | Android | Notes |
|---|---|---|
| `main_window.py`, `home.py`, `chat*.py`, `virtualchat.py`, `cards.py`, `flow.py` | `ui/MainActivity`, `ui/chat/*`, `ui/Screens.kt` | Programmatic Views (no Compose / AndroidX) |
| `settings.py`, `customize.py`, `control.py`, `device.py`, `skills.py` | `ui/settings/*` (approximate mapping) | Settings home, appearance, AI providers, voice, wake word, island, lock, integrations, storage, permissions, capabilities (what works and what is missing), diagnostics, about. `designer.py` and `tips.py` were not ported |
| `theme.py`, `glass.py`, `widgets.py`, `dialogs.py`, `icons.py`, `appicon*.py` | `ui/kit/*`, Feather vector icons in `res/drawable` | Light / dark / system, accent, font size |
| `orbs.py`, `orbopts.py`, `dotmatrix.py` | `ui/kit/OrbView.kt` | One orb (Aura-style); the other orb families and dot-matrix animation were not ported |
| `island*.py` | `core/island/*`, `app/island/*` | See `porting-notes/surfaces.md` and `ISLAND.md` |
| `imageviewer.py`, library | `ui/library/*`, `ui/kit/ImageLoader.kt` | Browse, search, open, share, export, delete (confirmed UI only) |

## New on Android (no Python equivalent)
Capability router and permission-aware replies (`core/routing`), lock policy and lock-screen assistant (`core/lock`, `app/lock`), voice-interaction service / "Jarvis Air" (`app/assist`),
notification listener and accessibility shortcuts (`app/access`), boot / time-zone re-registration (`receivers/SystemEventsReceiver`), permission status screen, crash record, MediaStore file storage.

## Not ported
Desktop-only: window automation, global hotkeys, screen scraping, Windows audio sessions, PyQt animations and the extra orb styles, the Porcupine engine (needs a vendor access key and licence), the StreamSync desktop widgets.
