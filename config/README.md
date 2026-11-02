# Configuration templates

JARVIS has **no config file that holds secrets**, and this repository must never contain one.

* `jarvis-settings.example.json` — *non-secret* preferences in the exact format the app exports and imports
  (**Settings > Storage > Export / Import settings**). Copy it to the phone and import it to pre-set provider choices, theme and similar. It contains no keys.
* **API keys** (Groq, Cohere, OpenAI-compatible, SerpAPI, Hugging Face, Pollinations) are typed into **Settings > AI providers** on the phone. The app stores them in
  the Android Keystore-backed secret store, never in the settings export, never in logs (they are redacted), and never in the backup (`allowBackup` is off).
* `local.properties.example` (project root) — only the Android SDK path for building. Android Studio writes the real `local.properties`; it is git-ignored.

If you ever had keys in a `.env` file (the original desktop project used one), do **not** put it in this project, do not commit it, and treat any key that was
copied into chats, zip files or screenshots as exposed: **revoke it at the provider and create a new one.**
