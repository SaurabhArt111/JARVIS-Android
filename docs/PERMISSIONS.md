# Permissions: what JARVIS asks for, when, and what happens without it

Nothing is requested at install or first launch. Each permission is requested **at the moment a feature needs it**, one at a time, with a plain-language reason shown first
(`BaseActivity.grant`). Settings > Permissions shows the **real** state of each (re-read from Android every time) and a button to the right system screen. If a phone has no
such screen, the app's own settings page opens instead. `minSdk 29` (Android 10), `compileSdk/targetSdk 36` (Android 16).

## Runtime permissions (system dialog)

| Permission | Needed for | Asked | Without it |
|---|---|---|---|
| `RECORD_AUDIO` | talking to JARVIS, wake word | when you first tap the mic / enable listening | text chat only; mic buttons explain why |
| `POST_NOTIFICATIONS` (Android 13+) | reminder and timer alerts, the listening notification | when you create a reminder or turn on listening | reminders are still stored and scheduled, but Android drops the notification; JARVIS says so instead of assuming you saw it |
| `READ_CONTACTS` | "call Mom" is resolved on the phone (never uploaded) | first time a contact is needed | calls by number still work; names can't be resolved |
| `CALL_PHONE` | place a call directly | first call request | JARVIS opens the dialer with the number filled in; you press call |
| `ANSWER_PHONE_CALLS` | "answer" / "hang up" | requested together with `CALL_PHONE` | JARVIS says it can't answer calls |

## Special access (user grants on a system screen; JARVIS only opens the screen)

| Access | Needed for | Without it |
|---|---|---|
| Display over other apps (`SYSTEM_ALERT_WINDOW`) | the island overlay; lets Android open apps while JARVIS is in the background | island stays off (notification explains); apps are opened through a tap-to-open notification |
| Schedule exact alarms (`SCHEDULE_EXACT_ALARM`) | reminders and timers at the exact minute | reminders still fire but JARVIS reports them as **approximate** (not exact) |
| Full-screen intents (`USE_FULL_SCREEN_INTENT`) | alarm-style reminders and the lock-screen assistant lighting the screen | a heads-up notification you tap |
| Notification access (notification listener) | read/announce/reply to notifications, now-playing & media keys of other apps | those features report "needs notification access"; JARVIS's own switch must also be on |
| Accessibility (JARVIS shortcuts service) | press Home / Back / Recents, open the shade or quick settings, lock screen, screenshot, power menu | those commands say they need it. The service reads **nothing**: no events, no window content |
| Modify system settings (`WRITE_SETTINGS`) | set screen brightness | brightness requests explain |
| Do Not Disturb access (`ACCESS_NOTIFICATION_POLICY`) | turn Do Not Disturb on/off | the request explains |
| Battery optimisation exemption (optional) | keeps the listening service alive on strict phones | listening may be killed more often (see `BACKGROUND_LISTENING.md`) |
| Digital assistant app (role / settings) | long-press-Home | the tile and in-app mic still work (see `ASSISTANT_SETUP.md`) |

## Normal permissions (granted at install, shown for transparency)

`INTERNET`, `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED` (re-register reminders after restart), `com.android.alarm.permission.SET_ALARM` (hand an alarm to the Clock app),
`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE` (the listening service), `FOREGROUND_SERVICE_SPECIAL_USE` (the island service).

## Not requested on purpose
`QUERY_ALL_PACKAGES` (only launcher/browser/messaging queries are declared), `READ_PHONE_STATE` (incoming calls are announced from notifications), storage permissions (files go to
`Documents/JARVIS` through MediaStore, which needs none on Android 10+), `CAMERA`, location, `READ_SMS`/`SEND_SMS`, `MANAGE_EXTERNAL_STORAGE`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
(Google Play restricts it; the list screen is opened instead), `BIND_ACCESSIBILITY_SERVICE` usage beyond global actions.

## Components with a system `BIND_*` permission
`JarvisVoiceInteractionService` / `JarvisVoiceInteractionSessionService` (`BIND_VOICE_INTERACTION`), `JarvisNotificationListener` (`BIND_NOTIFICATION_LISTENER_SERVICE`),
`JarvisAccessibilityService` (`BIND_ACCESSIBILITY_SERVICE`), `JarvisTileService` (`BIND_QUICK_SETTINGS_TILE`). Only the system can bind them.
Exported without a permission: the recognition forwarder (`android.speech.RecognitionService` is how apps find it) and `MainActivity` / `AssistantActivity` (launcher/assist intents).
