# This round: what changed and why

Verified here: `:core` compiles and all **738** unit tests pass (`bash tools/offline-verify/build_core.sh`).
NOT verified here: the `:app` module (no Android SDK in the sandbox). Build it in Android Studio and report any compile error.

## Fixed
- **Image previews blank** - `ImageLoader.decode` treated the `null` that `BitmapFactory` always returns with `inJustDecodeBounds` as "cannot decode". It now reads `outWidth/outHeight` instead.
- **"call Dhruv Indori on speaker"** - the tail is parsed as a speaker request (`CommTools`), the contact is searched without it, and the call is placed with `EXTRA_START_CALL_WITH_SPEAKERPHONE` (plus `AudioManager.isSpeakerphoneOn` once the call is active).
- **Misheard contact names** ("Dhruv bindori", "Dhruv indoor") - `NamePhonetics` (consonant skeletons) + per-word fuzzy matching find them offline. If the match is not confident, the AI picks from a short list of similar contact names (`AiContactNameChecker`; names only, no numbers; its answer must be one of the candidates). Two or three equally good matches -> JARVIS asks "Which one?". Setting: AI -> "AI name check for contacts".
- **Wake word** - more phonetic variants of "hey jarvis"/"jarvis", a "Maximum" sensitivity level, software auto-gain for quiet voices (`AutoGain`), and a fallback from the on-device recogniser to the standard one when it reports error 12/13 (language pack missing).
- **Cohere** - "aya" models reject tools; JARVIS now falls back to tool-capable Command models automatically.
- **WhatsApp Business** is matched by name and package.
- **Swipe from recents** - both services arm a one-shot system alarm that restarts them if a phone's task killer took the process (`KeepAlive`). It cannot beat an OEM *force stop* - see "Manual steps".

## New
- **Island: pull down to chat** - drag the pill down (fast or slow) and a small chat opens in an overlay: messages, text box, mic, send; answer buttons (confirm / choose a contact) work in it; locked-phone privacy rules apply. Setting: Island -> "Pull down to chat".
- **Island hides while JARVIS is open** (Island -> "Hide while JARVIS is open"; the settings preview still shows).
- **Permissions screen** explains Android 13's "Restricted setting" for sideloaded installs and opens App info.
- **Security** - Android 12+ `dataExtractionRules` (no cloud/transfer copy of any app data), explicit `usesCleartextTraffic=false`. Already in place: HTTPS-only network config, encrypted API keys, redacting logs, no `QUERY_ALL_PACKAGES`, `allowBackup=false`, non-exported lock activity.

## Manual steps on the phone (cannot be done from code)
1. Settings -> Apps -> JARVIS -> ⋮ -> **Allow restricted settings** (Android 13+, sideloaded APK), then grant Overlay / Accessibility.
2. Xiaomi/MIUI/HyperOS: Autostart ON, Battery saver -> **No restrictions**, and **lock JARVIS in the recents screen**; otherwise swiping it away kills it.
3. Groq "429 / quota" in the log is your free-tier limit; add a Cohere/OpenAI key or wait - JARVIS already falls back to other providers.
