# Lock-screen assistant

**Status: designed and compiled, not verified on a device.** The decision logic is unit-tested on the JVM; the Android behaviour (keyguard prompt, drawing over the lock screen,
full-screen notifications, OEM differences) has never run on a phone or emulator.

## The rule that does not move

**JARVIS never unlocks the phone, skips the keyguard, or weakens it.** Everything personal waits for the user's own PIN / pattern / password / biometric, entered in
Android's own prompt. There is no code path that calls `FLAG_DISMISS_KEYGUARD`, `KeyguardManager.disableKeyguard`, or stores/guesses a credential.

## Screens that can show over the lock screen

`lock/LockScreenActivity` (`setShowWhenLocked(true)`; the keyguard stays in place underneath). One orb, the latest answer, a few buttons, a microphone button and (if allowed) a text box.
It **never shows chat history**. It closes itself when the screen turns off, when it is left, and after 45 s without interaction (not while listening, working or speaking).

It is opened by: the assistant gesture / lock-screen voice-assist shortcut (`assist/JarvisVoiceInteractionSession`), the Quick Settings tile (after Android's own unlock prompt),
a full-screen or heads-up notification (`LockScreenLauncher`, used by the wake-word service when a reply needs buttons), and the listening notification's "Talk" action.

## What happens to a request while locked (`core/lock/LockPolicy`, applied by the engine to the real keyguard state)

Settings > Lock screen: *Allow JARVIS on the lock screen* (off by default), *Privacy level* (Strict default / Balanced / Open), and per-capability switches.

| Request (tool class) | Locked, no secure lock (swipe only) | Locked, secure lock | Notes |
|---|---|---|---|
| Timers, alarms, volume / brightness / flashlight / Do Not Disturb, media play-pause-next, calculator, weather, web search (**open**) | works | **works** | harmless; nothing personal is read. Web search and weather send the query to the provider like anywhere else. |
| Dictate a note / reminder, save a document, generate an image (**personal, write**) | works | **works** if "Allow JARVIS on the lock screen" is on and "Dictation" is on (default on); otherwise needs unlock. The reply is redacted. | write-only; reading it back needs unlock |
| Read notes / reminders / clipboard (**personal, read**) | works | **needs unlock**; Privacy = Open **and** lock-screen mode on allows it | the reply and speech are replaced by "Done. Unlock your phone to see the details." when the content is marked sensitive |
| Read notifications aloud (**personal**) | works | needs unlock; Balanced/Open + mode on + "Read notifications on the lock screen" allows it | the notification listener must also be enabled |
| Call a contact (**consequential**) | needs a **confirmation** | Strict: needs unlock. Balanced/Open + mode on + "Allow calls": **confirmation** (Yes/No); looking up a contact by name also needs "Allow contact lookup" | a spoken or tapped "yes" is needed every time |
| Send a message (**consequential**) | unlock | unlock | JARVIS never says a message "was sent" - only that it was handed to the app |
| Reply to a notification (**consequential**) | its own Yes/No confirmation | unlock, unless Privacy = Open **and** mode on (then its own confirmation) | goes through the notification's reply action; delivery is not confirmed by Android |
| Open apps, search in a media app, open settings, screenshot, power menu, Home/Back/Recents (**admin**) | unlock | **needs unlock** | Android shows other apps only after unlocking |
| Delete anything | **not possible** | **not possible** | AI/voice can never delete (`Safety`) |

### "Needs unlock" flow
The reply carries an **Unlock** button. It calls `KeyguardManager.requestDismissKeyguard`, which shows the system prompt. Only if that **succeeds** is the refused request run
again, once (`UnlockRetry`). Cancel or error drops it. The request is never run on a still-locked phone as a fallback.

### Privacy details
* Replies marked sensitive are redacted by the engine before they are shown, spoken or posted as notifications. Notifications show a public version on the lock screen unless Privacy is Open.
* What you said is shown on screen only when nothing is protected (unlocked or no secure lock); otherwise the screen shows "Listening..."/"Working on it...".
* Typing is offered on a secure locked phone only when lock-screen mode is on (a keyboard app may learn from or suggest text). Voice is always offered.
* Wake-word notification readout (`READ_NOTIFICATIONS_ALOUD`) stays silent when the phone is in silent/vibrate or Do Not Disturb, never reads ongoing or JARVIS's own notifications, and reads only the sender while locked unless Privacy is Open.
* Incoming calls: announced from the **notification listener's** call notification (category `call`) rather than `READ_PHONE_STATE`, so JARVIS asks for fewer permissions. Without notification access it simply doesn't announce calls.

## Limits you should expect
* **Background starts.** Android 10+ forbids opening an activity from the background. `LockScreenLauncher` therefore tries (1) a direct start if JARVIS already has a visible window (its own activity or the island overlay with the overlay permission), (2) a notification with a full-screen intent, (3) a heads-up notification you tap. Full-screen intents need `USE_FULL_SCREEN_INTENT`, which Android 14+ grants by default only to calling and alarm apps; the user can allow it in settings and the app shows the state. Google Play restricts that permission.
* **Notification buttons on the lock screen** ("Talk", "Type a request") follow the system's *sensitive notification content* setting; on many phones they appear only after unlocking.
* **Screen-on.** The screen is lit by the assistant only after a deliberate hardware gesture (lock-screen voice-assist shortcut), never by a background event.
* **OEMs** (Samsung, Xiaomi, Oppo, Huawei...) change the keyguard and may not honour show-when-locked for some surfaces, or may block the lock-screen shortcut for third-party assistants.
* **Voice requests while locked** use the same speech recogniser as everywhere: it may send audio to the cloud unless "prefer on-device recognition" is on.
* **Background microphone.** The lock-screen button records only while the activity is visible. The always-on "Hey JARVIS" path is the foreground service described in `BACKGROUND_LISTENING.md`.

## Tests
`core/src/test/.../lock/LockPolicyTest` (policy matrix), `LockScreenLogicTest` (buttons, unlock-retry-only-after-success, typing rules, idle timeout), `notify/NotificationRulesTest`
(what may be read aloud while locked). Not tested: everything that needs a keyguard.
