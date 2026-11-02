# Making JARVIS your phone's assistant ("Jarvis Air", long-press Home)

JARVIS ships a **voice-interaction service** (`assist/JarvisVoiceInteractionService`, label **"Jarvis Air"**). Once you choose it as the phone's *Digital assistant app*,
the assistant gesture (long-press Home, the power-button gesture or the corner swipe, depending on the phone) opens JARVIS's compact chat, already listening.

## 1. Choose it (Android does not let an app select itself)

1. Install and open JARVIS once.
2. Open **Settings > Apps > Default apps > Digital assistant app** (on some phones: *Settings > Apps > Choose default apps*, or search for "assistant").
   JARVIS can open this screen for you from **JARVIS > Settings > Default assistant > Open Android's assistant settings**.
3. Tap **Digital assistant app** and select **Jarvis Air**. Confirm the warning Android shows.
4. Optional but recommended: on that same screen turn **off** "Use text from screen" and "Use screenshot". JARVIS ignores that data (see below), but there is no reason to hand it over.

Then long-press Home (or use your phone's assistant gesture). JARVIS's chat opens as a sheet and the microphone starts.

## 2. What the manifest declares (as in the original request)

```xml
<service android:name=".assist.JarvisVoiceInteractionService"
         android:label="@string/assist_service_label"            <!-- "Jarvis Air" -->
         android:permission="android.permission.BIND_VOICE_INTERACTION"
         android:exported="true">
    <meta-data android:name="android.voice_interaction" android:resource="@xml/assistant_service" />
    <intent-filter><action android:name="android.service.voice.VoiceInteractionService" /></intent-filter>
</service>
```

`res/xml/assistant_service.xml` names the session service, the recognition service, `supportsAssist="true"` (this is what lists the app under *Digital assistant app*)
and `supportsLaunchVoiceAssistFromKeyguard="true"`. The settings activity is JARVIS's main screen.

## 3. What happens on the gesture

`JarvisVoiceInteractionSession.onShow` opens JARVIS's own screen and hides the (empty) session window:

| Phone state | Screen that opens |
|---|---|
| Unlocked, or locked with no secure lock | the compact chat (`AssistantActivity`), listening at once |
| Locked **with** a PIN/pattern/password/biometric | the lock-screen assistant (`LockScreenActivity`) - privacy rules apply, no chat history, see `LOCK_SCREEN.md` |

It first tries `Context.startActivity` (the session has a window, which Android treats as a visible window) and, if Android refuses, `VoiceInteractionSession.startAssistantActivity`.
The lock-screen shortcut ("voice assist" on phones that have one) reaches the same session through `showSession`.

## 4. Things you should know (honest limits)

* **Not verified on a device.** The classes compile against the Android 36 API and the manifest/resource checks pass, but nobody has run this on a phone or emulator. Phones differ
  (Samsung, Xiaomi, Pixel ...): some route the Home long-press elsewhere, or only list assistants that were on a vendor allow-list.
* **Screen context is not read.** Android may pass the session "assist data" (screen structure, screenshot) when the gesture is used. JARVIS does not override `onHandleAssist` /
  `onHandleScreenshot`, so it is dropped unread. Nothing in JARVIS reads other apps' screens.
* **Voice input provider.** Android requires a recognition service in the assistant metadata and may make it the phone's voice-input provider. `JarvisRecognitionService`
  therefore **forwards** requests to another recogniser installed on the phone (Google's when present) so dictation in other apps keeps working. It needs JARVIS's microphone
  permission (the other recogniser runs inside JARVIS's process while forwarding). If no other recogniser exists, it reports an error instead of pretending. This forwarder is
  the least-verified piece: if dictation in other apps misbehaves after selecting Jarvis Air, set *Settings > Apps > Default apps > Voice input* back to your usual provider.
* **Role dialog.** On phones that offer the assistant *role*, `AssistantRoleHelper.roleRequestIntent` can show Android's one-tap dialog; the settings screens above always work.
* **Quick Settings tile.** If the gesture is not available for JARVIS on your phone, add the **JARVIS** tile (edit your Quick Settings panel). It opens the same chat.
* **Google Play.** Voice-interaction services are allowed, but a microphone foreground service (background listening) and the island overlay need the declarations described in `README.md`.

## 5. Turning it off

Choose another app (or "None") under *Digital assistant app*. Uninstalling JARVIS also removes it.
