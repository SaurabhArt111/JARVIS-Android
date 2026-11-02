# Feature status

Legend: **T** = logic covered by JVM unit tests that pass (699 tests, `tools/offline-verify/build_core.sh`) - **C** = Android code that compiles against the real API 36 `android.jar` and passes the resource/manifest checks
but has **never run** - **P** = partial / limited, see note - **N** = not implemented (and why).
Nothing in this project has been run on a device or emulator, and Gradle has never built it. "T" therefore means "the decision logic is tested", not "it works on a phone".

## 1. Agent, routing, tools
| Feature | Status | Notes |
|---|---|---|
| Capability-aware routing (what is possible *now*: permissions, network, keys, lock state) | T | `core/routing`; tells the user which requirement is missing instead of failing silently |
| Agent loop with tools, step limit, tool-result validation, honesty guard (no "done" without a tool result) | T | `core/agent`; the AI path was tested with scripted fake providers only, never a live model |
| 20 tools (open, play, media, system, navigate, reminder, note, timer, alarm, web search, browser search, weather, message, call incl. answer/hang up, read/reply notification, save document, image, calculate, jarvis settings) | T / C | tool logic T; the Android adapters behind them C |
| Offline command parser (works with no AI provider) | T | reminders, timers, notes, apps, device control, calculator, time |
| Deleting by AI or voice | N (deliberately) | `Safety` blocks it; delete exists only as a confirmed UI action |

## 2. UI
| Feature | Status | Notes |
|---|---|---|
| Chat (history, cards, confirmations, voice orb), compact assistant sheet | C | rendering never seen |
| Light / dark / system theme, accent, text size, edge-to-edge, rotation/fold handling | C | programmatic Views, no Compose |
| Settings: AI, voice, wake word, island, lock screen, integrations, storage, permissions, capabilities, diagnostics, about | C | |
| Permissions status screen (real state, per-permission request, system screens) | C | |
| History, library (browse / search / open / share / export / delete) | T / C | list/sort/name rules T; screens C |
| Translations | N | English only |

## 3. Voice, wake word, background
| Feature | Status | Notes |
|---|---|---|
| openWakeWord pipeline in Kotlin | T | model inputs identical to the reference (worst diff 0.0); **real "hey jarvis" detection untested** |
| ONNX Runtime wrapper (`OrtNeuralRunner`) | C (against stubs only) | the real library API was not available offline |
| Model download with pinned SHA-256, not bundled | C | CC BY-NC-SA 4.0 licence notice shown |
| Speech-recogniser wake engine, auto selection, restart back-off, silenced-mic detection | T (logic) / C | accuracy, battery, beeping unverified |
| Foreground service (microphone type), notification with Pause/Talk/Stop | C | |
| Resume after reboot | P | Android 14+ forbids it: a "tap to resume" notification is shown instead |
| Custom neural wake phrase | N | only "hey jarvis" exists; custom words use the speech engine |
| Porcupine engine | N | needs a vendor key and licence |
| Speech-to-text | P | Android `SpeechRecognizer` (may be cloud-backed; on-device-only option); no bundled offline recogniser |
| Text-to-speech | C | Android `TextToSpeech` with audio-focus ducking; no cloud voices |

## 4. Island overlay
| Feature | Status | Notes |
|---|---|---|
| Placement from cutout rectangles (centre / left / right / wide notch / none / landscape / tablet / foldable / bad data), user overrides | T | 28 tests incl. 3000 random cases |
| State machine (listening / thinking / speaking / reply / notice / error) | T | 17 tests |
| Overlay window, animations, drag, expand, in-app fallback, boot restart | C | needs "Display over other apps" |
| Detecting the physical camera | N (deliberately) | never claimed; only the cutout Android reports |

## 5. Storage
| Feature | Status | Notes |
|---|---|---|
| `Documents/JARVIS/<Category>` through MediaStore, private fallback, safe unique names, type validation, index, cleanup of old files | T (names, types) / C (MediaStore) | after reinstall old files can't be listed (scoped storage) |
| Library open / share / export / delete | C | |

## 6. Lock screen
| Feature | Status | Notes |
|---|---|---|
| Policy matrix (allow / confirm / unlock / deny) and privacy levels, redaction | T | `LockPolicy` matrix + `LockScreenLogic` (31 tests in `core/lock`) |
| Unlock-then-retry (only after the system prompt succeeds), button mapping, typing rule, idle timeout | T | |
| `LockScreenActivity`, `KeyguardManager.requestDismissKeyguard`, show-when-locked, full-screen / heads-up launch, notification "Type a request" box | C | OEM behaviour unknown; see `LOCK_SCREEN.md` |
| Bypassing or weakening the keyguard | N (deliberately) | never |

## 7. Contacts, calls, apps
| Feature | Status | Notes |
|---|---|---|
| Fuzzy contact match, ambiguity questions, several numbers, number formatting | T | |
| Call (direct with `CALL_PHONE`, else dialer), answer / hang up | T (flow) / C | call connection is never "verified" |
| App matching, launching, background-start fallback (tap-to-open notification), browsers | T (matching) / C | |
| Messages | P | opened pre-filled in WhatsApp / Telegram / SMS; **JARVIS never sends** |

## 8. Notifications, accessibility, WhatsApp
| Feature | Status | Notes |
|---|---|---|
| Categorisation, read-aloud rules (only messages/calls, rate limit, lock privacy) | T | 13 tests |
| Notification listener, in-memory store (60), quick reply through `RemoteInput`, dismiss, media sessions | C | WhatsApp reply only via its own notification action; delivery never verified |
| Accessibility service (global actions only, no events, no content) | C | |
| Independent switches (integration, read aloud, WhatsApp reply, accessibility) | C | each also needs its system permission |
| Reading other apps' screens, driving WhatsApp's UI | N (deliberately) | |

## 9. AI, speech, images
| Feature | Status | Notes |
|---|---|---|
| Groq, Cohere, OpenAI-compatible, Ollama; fallback order, cooldown, model discovery, retries, timeouts, cancel, key redaction | T | against fake HTTP; **no live provider call was ever made** |
| Secure key storage (Keystore AES-GCM), no keys in exports/logs | C (+ instrumented test, not run) / T (redaction) | |
| Image generation (Hugging Face / Pollinations), quality / count / size, saved to the library | T (prompts, requests) / C | never run live |
| Web search (SerpAPI when a key is saved, else DuckDuckGo HTML) and weather (Open-Meteo) | T (parsing, fakes) / C | never run live |

## 10. Reminders, notes, timers, alarms
| Feature | Status | Notes |
|---|---|---|
| Natural-language times, time zones, floating vs fixed, durations | T | 24 time + 79 organizer tests incl. a golden replay of the Python parser |
| Persistent store (SQLite), schema and 38 queries | T (real SQLite via `check_sql.py`) / C | |
| Exact alarms, inexact fallback reporting, re-registration after boot / time / zone change, overdue summary | C | alarms never fired on a device |
| Alarms in the Clock app | P | handed over via intent; Android gives no read-back, so never "verified" |
| Cancel reminder | P | marks it cancelled in JARVIS's data |

## 11. Errors, logging, security
| Feature | Status | Notes |
|---|---|---|
| Typed errors with user-readable messages, honest "not done" results | T | |
| Redacting rotating log, redaction rules | T (redactor) / C | |
| Crash handler: local record shown at next start | C | **no crash-reporting service** |
| Secrets: none in source, `.env` never read, export excludes secrets, backups off, cleartext only for loopback | C | secrets scan of the project is part of the release check |

## 12. Manifest, SDK levels, permissions
| Feature | Status |
|---|---|
| minSdk 29 / compile + targetSdk 36, per-permission requests, status screen | C (manifest and resources cross-checked statically by `check_res.py`) |
| Foreground service types (microphone, specialUse + property), `BIND_*` services, queries instead of `QUERY_ALL_PACKAGES` | C |
| Google Play declarations (specialUse, microphone FGS, overlay, full-screen intent, listener, accessibility) | N | must be completed by the publisher |

## 13. Tests
| Layer | Status |
|---|---|
| JVM unit tests (`:core`) | **699 pass** |
| Offline compile of the Android code against API 36 | **passes** |
| Instrumented tests (`app/src/androidTest`, 21 tests) | type-check only; never executed |
| UI / interaction tests, wake-word accuracy, battery, live providers | N |

## 14. Packaging
Source, Gradle files with version catalog and wrapper (generated offline), manifest, resources, icons, tests, config templates, and docs are in the ZIP. **No secrets and no wake-word models are included.**
