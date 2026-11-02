# Porting notes: Android data & device platform (`:app`, packages `data`, `platform`, `receivers`, `di`)

Python (desktop) → Kotlin (Android framework only: no AndroidX / Room / Hilt / WorkManager).

| Python | Android |
|---|---|
| `Backend/Config.py` + `.env` | `data/AndroidSettingsStore` (SharedPreferences) + `data/SecretStore` (Android Keystore AES-256-GCM). Same key names (`GroqAPIKey`, `Username` …). Secrets never touch plain prefs, export files or logs. The uploaded `.env` is **not** imported (it contained live keys). |
| `Backend/Scheduler.py`, `Notes.py` (JSON files) | `data/Db` (SQLiteOpenHelper, versioned, corrupt file moved aside not deleted) + `SqlReminderStore`, `SqlNoteStore`, `TimerStore`, `FileIndex`; logic stays in core `ReminderService` / `NoteService`. |
| scheduler thread / `threading.Timer` | `platform/AndroidReminderScheduler` (AlarmManager, immutable explicit PendingIntent per reminder, exact only when `canScheduleExactAlarms()`), `platform/AndroidTimerService` (persisted), `receivers/*` (fire, Done/Snooze, boot/zone/time-change/update re-registration). |
| `Backend/TextToSpeech.py` | `platform/AndroidSpeaker` (TextToSpeech; suspends until the utterance ends; chunks long text; cancels cleanly). |
| `Backend/Ducking.py` (Windows audio sessions) | audio focus `GAIN_TRANSIENT_MAY_DUCK`, `USAGE_ASSISTANT`, taken per utterance and released in `finally`; refused focus (a call) = not spoken, reported as `false`. |
| `Backend/SystemControl.py` | `platform/AndroidDeviceControl` (volume, mute, brightness, torch, DND, battery, network, report, clipboard), every setter re-reads the value. |
| `Backend/Apps.py`, `Backend/Browsers.py` | `platform/AndroidAppLauncher` (launcher-intent package visibility, `AppMatcher`, browsers, URL/file/settings pages) + `platform/ActivityStarter` (background-launch aware). |
| `Backend/Messenger.py`, `WhatsappMessage.py`, `TelegramMessage.py` | `platform/AndroidMessagingGateway` (wa.me deep link / share screen / `smsto:`; **never sends**), `AndroidCallGateway`, `AndroidContactsGateway` + core `ContactMatcher`, `PhoneNumbers`. |
| `Backend/Media.py`, `NowPlaying.py` | `platform/AndroidMediaGateway` (MediaSession controllers via notification-listener access, media-key fallback, voice-search intent). |
| `Backend/Audio.py` (files), `Backend/Prefs.py` | `platform/DocumentStoreAndroid` (MediaStore `Documents/JARVIS/<Category>`, private fallback) + `JarvisFileProvider` (own minimal read-only provider). |
| `Backend/Health.py` | `data/AppLog` (redacting rotating log), `CrashHandler` (local crash record, no reporting service). |
| `Backend/Context.py` (desktop context) | `data/SqlConversationRepository` / `SqlConversationMemory` (history for the UI / for the model). |
| `main.py` wiring | `di/AppContainer`, `JarvisApp`. |

## Behaviour that is kept
* One shared tool path: the AI agent and the offline parser call the same `ToolExecutor`; the Android adapters sit behind the same ports the core tests use.
* A reminder is only "created" after Android confirmed the alarm and the row was read back (core `ReminderService`); the scheduler reports `EXACT` / `INEXACT` truthfully.

## Deliberate differences (and why)
1. **Honest results instead of optimistic ones.** Adapters return `ActionResult(ok, message, missing, verified)`; `verified = true` only after a read-back (volume, brightness, torch callback, DND filter, alarm PendingIntent, media session state, file size in MediaStore). Clock-app alarms, media "play X", message and call hand-offs are never `verified`.
2. **Android blocks background app starts (10+).** `ActivityStarter` starts an activity only when JARVIS is visible (or, with "Display over other apps", when allowed). Otherwise the result is `ok = false`, says why, and a **tap-to-open notification** is posted. The assistant therefore never says "Opening Spotify" when Android would silently drop the request.
3. **Messages are never sent.** WhatsApp / Telegram / SMS open with the text typed; the user taps send. Telegram cannot pre-fill a chat from a number, so its share screen opens.
4. **USSD/MMI codes (`*…#`) are never dialed.**
5. **Do Not Disturb = "priority only"**, so an alarm is never silenced by JARVIS.
6. **Brightness** switches adaptive brightness off (and says so) because a manual value has no effect while it is on.
7. **Boot**: alarms are re-registered; reminders that passed while the phone was off are marked fired and shown as notifications, *not* spoken. Background listening cannot be restarted at boot on Android 14+, so a "tap to resume listening" notification is posted if the user had it on.
8. **Library = index table.** Deleting a file in a file manager removes its library row the next time the list is read; JARVIS never deletes a file except through an explicit, confirmed UI action (`deletePermanently`, `cleanupOlderThan`).
9. **No AndroidX `FileProvider`**: a 90-line read-only `ContentProvider` (`JarvisFileProvider`) serves only `files/documents/**` with canonical-path checks.
10. **Timers** may be inexact when exact-alarm access is off; `TimerInfo.exact` carries that and the timer tool tells the user.

## Not verifiable in this environment
* No Gradle/AGP build and no emulator: the adapters were compiled against the real `android.jar` (API 36) and the SQL was executed against real SQLite (`tools/offline-verify/check_sql.py`); behaviour on devices is untested. The instrumented tests in `app/src/androidTest` type-check (`tools/offline-verify/build_android_tests.sh`) but have never been run.
* MediaStore insertion of images into `Documents/JARVIS/Images` is expected to work for the Files collection but is device-dependent; on any failure the file is kept in private storage and the library says so.
* OEM battery managers, Keystore behaviour after a lock-screen reset, TTS engine quality, and Telegram/WhatsApp deep-link behaviour vary by device and app version.
