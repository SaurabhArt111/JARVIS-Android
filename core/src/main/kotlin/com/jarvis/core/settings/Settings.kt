package com.jarvis.core.settings

/**
 * Persistent key/value storage. The app implements this with SharedPreferences for ordinary values and an
 * Android-Keystore-encrypted store for secrets (anything where [Settings.isSecret] is true). Keys keep the names the
 * Python version used in `.env`, so the same vocabulary works everywhere.
 */
interface SettingsStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
    fun secret(key: String): String?
    fun putSecret(key: String, value: String?)
}

class InMemorySettingsStore : SettingsStore {
    private val plain = HashMap<String, String>()
    private val secrets = HashMap<String, String>()
    override fun get(key: String) = plain[key]
    override fun put(key: String, value: String?) { if (value == null) plain.remove(key) else plain[key] = value }
    override fun secret(key: String) = secrets[key]
    override fun putSecret(key: String, value: String?) { if (value == null) secrets.remove(key) else secrets[key] = value }
}

/** Typed view over a [SettingsStore]: defaults, flags, numbers, secrets. Mirrors `Backend/Config.py`. */
class Settings(private val store: SettingsStore) {

    fun isSecret(key: String): Boolean = key.endsWith("APIKey") || key.endsWith("Token") || key.endsWith("Secret")

    /** Value for [key]: stored value, else the default, else [default]. Blank stored values count as unset. */
    fun get(key: String, default: String = ""): String {
        val raw = if (isSecret(key)) store.secret(key) else store.get(key)
        if (!raw.isNullOrBlank()) return raw
        return DEFAULTS[key] ?: default
    }

    fun flag(key: String, default: Boolean = true): Boolean {
        val v = get(key, if (default) "On" else "Off").trim().lowercase()
        return v in setOf("on", "true", "1", "yes")
    }

    fun int(key: String, default: Int = 0): Int = get(key, default.toString()).trim().toIntOrNull() ?: default
    fun double(key: String, default: Double = 0.0): Double = get(key, default.toString()).trim().toDoubleOrNull() ?: default

    fun set(key: String, value: String?) {
        if (isSecret(key)) store.putSecret(key, value?.let(::cleanSecret)) else store.put(key, value)
    }

    fun setFlag(key: String, on: Boolean) = set(key, if (on) "On" else "Off")

    fun hasSecret(key: String): Boolean = !store.secret(key).isNullOrBlank()

    fun userName(): String = get(Keys.USERNAME, "Sir")
    fun assistantName(): String = get(Keys.ASSISTANT_NAME, "Jarvis")

    fun wakeWords(): List<String> {
        val words = get(Keys.WAKE_WORDS, "jarvis").split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toMutableList()
        val n = assistantName().trim().lowercase()
        if (n.isNotEmpty() && n !in words) words.add(n)
        return words
    }

    companion object {
        private val ZW = Regex("[\\u200b\\u200c\\u200d\\ufeff\\u00a0\\s]")

        /** Removes zero-width characters, spaces and stray quotes from a pasted API key. */
        fun cleanSecret(s: String): String = ZW.replace(s, "").trim('\'', '"')

        val DEFAULTS: Map<String, String> = mapOf(
            // identity
            Keys.USERNAME to "Sir",
            Keys.ASSISTANT_NAME to "Jarvis",
            // wake word / background listening (all opt-in)
            Keys.WAKE_WORDS to "jarvis",
            Keys.WAKE_ENGINE to "auto",            // auto | openwakeword | speech | off
            Keys.WAKE_SENSITIVITY to "medium",     // low | medium | high
            Keys.BACKGROUND_LISTENING to "Off",    // always-on foreground service listening for the wake word
            Keys.LISTEN_ON_BOOT_PROMPT to "On",    // after a reboot show a "tap to resume listening" notification
            // AI
            Keys.AI_MODE to "auto",                // auto | cloud | local
            Keys.CHAT_PROVIDER to "auto",
            Keys.DECISION_PROVIDER to "auto",
            Keys.MODEL_NAME to "",
            Keys.COHERE_MODEL to "",
            Keys.OLLAMA_URL to "",
            Keys.OLLAMA_MODEL to "llama3.1",
            Keys.OPENAI_BASE_URL to "https://api.openai.com/v1",
            Keys.OPENAI_MODEL to "gpt-4o-mini",
            Keys.AGENT_MODE to "On",
            Keys.AGENT_MAX_STEPS to "6",
            Keys.AGENT_FALLBACK to "On",
            // voice
            Keys.INPUT_LANGUAGE to "en",
            Keys.ASSISTANT_VOICE to "",            // empty = system default TTS voice
            Keys.SPEECH_RATE to "1.0",
            Keys.SPEAK_REPLIES to "Off",           // also speak replies to TYPED messages (voice requests are always spoken)
            Keys.SPEAK_REMINDERS to "On",
            Keys.VOICE_MUTED to "Off",
            Keys.GREET_ON_START to "On",
            Keys.SPEAK_GREETING to "Off",           // a phone assistant should not talk when the app opens unless asked to
            Keys.DUCK_ENABLED to "On",             // lower other audio while JARVIS speaks (audio focus)
            Keys.PREFER_ON_DEVICE_SPEECH to "Off", // Off = the phone's default recogniser (often cloud-backed); On = on-device only
            // island
            Keys.ISLAND_ENABLED to "Off",          // needs the overlay permission, so opt-in
            Keys.ISLAND_POSITION to "auto",        // auto (follow the display cutout) | left | center | right
            Keys.ISLAND_OFFSET_X to "0",
            Keys.ISLAND_OFFSET_Y to "0",
            Keys.ISLAND_SIZE to "100",
            Keys.ISLAND_COLLAPSE_SECONDS to "4",
            Keys.ISLAND_SPEAK to "Off",
            // lock screen
            Keys.LOCK_MODE_ENABLED to "Off",
            Keys.LOCK_PRIVACY to "strict",         // strict | balanced | open
            Keys.LOCK_READ_NOTIFICATIONS to "Off",
            Keys.LOCK_ALLOW_CONTACT_LOOKUP to "Off",
            Keys.LOCK_ALLOW_CALLS to "Off",
            Keys.LOCK_ALLOW_DICTATION to "On",
            // integrations (each needs its own system permission as well, and each is off until the user turns it on)
            Keys.NOTIF_INTEGRATION to "Off",
            Keys.WHATSAPP_QUICK_REPLY to "Off",
            Keys.READ_NOTIFICATIONS_ALOUD to "Off",
            Keys.ACCESSIBILITY_ACTIONS to "Off",
            // appearance
            Keys.THEME_MODE to "System",           // System | Light | Dark
            Keys.ACCENT to "Cyan",
            Keys.FONT_SIZE to "Medium",
            // information / images
            Keys.WEATHER_CITY to "",
            Keys.PREFERRED_BROWSER to "",
            Keys.IMAGE_PROVIDER to "auto",         // auto | huggingface | pollinations
            Keys.IMAGE_QUALITY to "best",
            Keys.IMAGE_MODEL to "",
            Keys.IMAGE_COUNT to "1",
            Keys.IMAGE_SIZE to "1024",
            // storage
            Keys.STORAGE_LOCATION to "documents",  // documents (Documents/JARVIS) | private
            Keys.KEEP_CONVERSATION_DAYS to "0",    // 0 = keep until the user clears them
            // diagnostics
            Keys.ONBOARDED to "Off",
            Keys.CRASH_REPORTING to "Off",         // optional external crash reporting is never enabled implicitly
            Keys.AI_SHARE_PERSONAL to "On",
        )
    }
}

/** Setting names. Same spelling as the Python `.env`, plus the Android-only ones. */
object Keys {
    const val USERNAME = "Username"
    const val ASSISTANT_NAME = "Assistantname"
    const val WAKE_WORDS = "WakeWords"
    const val WAKE_ENGINE = "WakeEngine"
    const val WAKE_SENSITIVITY = "WakeSensitivity"
    const val BACKGROUND_LISTENING = "BackgroundListening"
    const val LISTEN_ON_BOOT_PROMPT = "ListenOnBootPrompt"

    const val AI_MODE = "AIMode"
    const val CHAT_PROVIDER = "ChatProvider"
    const val DECISION_PROVIDER = "DecisionProvider"
    const val GROQ_API_KEY = "GroqAPIKey"
    const val MODEL_NAME = "ModelName"
    const val COHERE_API_KEY = "CohereAPIKey"
    const val COHERE_MODEL = "CohereModel"
    const val OLLAMA_URL = "OllamaURL"
    const val OLLAMA_MODEL = "OllamaModel"
    const val OPENAI_API_KEY = "OpenAIAPIKey"
    const val OPENAI_BASE_URL = "OpenAIBaseURL"
    const val OPENAI_MODEL = "OpenAIModel"
    const val SERP_API_KEY = "SerpAPIKey"
    const val HUGGINGFACE_API_KEY = "HuggingFaceAPIKey"
    const val POLLINATIONS_API_KEY = "PollinationsAPIKey"
    const val AGENT_MODE = "AgentMode"
    const val AGENT_MAX_STEPS = "AgentMaxSteps"
    const val AGENT_FALLBACK = "AgentFallback"

    const val INPUT_LANGUAGE = "InputLanguage"
    const val ASSISTANT_VOICE = "AssistantVoice"
    const val SPEECH_RATE = "SpeechRate"
    const val SPEAK_REPLIES = "SpeakReplies"
    const val SPEAK_REMINDERS = "SpeakReminders"
    const val VOICE_MUTED = "VoiceMuted"
    const val GREET_ON_START = "GreetOnStart"
    const val SPEAK_GREETING = "SpeakGreeting"
    const val DUCK_ENABLED = "DuckEnabled"
    /** On = ask Android for an on-device speech recogniser only (Android 12+); if there is none, listening says so instead of silently using the cloud. */
    const val PREFER_ON_DEVICE_SPEECH = "PreferOnDeviceSpeech"

    const val ISLAND_ENABLED = "IslandEnabled"
    const val ISLAND_POSITION = "IslandPosition"
    const val ISLAND_OFFSET_X = "IslandOffsetX"
    const val ISLAND_OFFSET_Y = "IslandOffsetY"
    const val ISLAND_SIZE = "IslandSize"
    const val ISLAND_COLLAPSE_SECONDS = "IslandCollapseSeconds"
    const val ISLAND_SPEAK = "IslandSpeak"

    const val LOCK_MODE_ENABLED = "LockModeEnabled"
    const val LOCK_PRIVACY = "LockPrivacy"
    const val LOCK_READ_NOTIFICATIONS = "LockReadNotifications"
    const val LOCK_ALLOW_CONTACT_LOOKUP = "LockAllowContactLookup"
    const val LOCK_ALLOW_CALLS = "LockAllowCalls"
    const val LOCK_ALLOW_DICTATION = "LockAllowDictation"

    /** Each integration has its own switch; none is turned on by granting another one. */
    const val NOTIF_INTEGRATION = "NotifIntegrationEnabled"
    const val WHATSAPP_QUICK_REPLY = "WhatsAppQuickReplyEnabled"
    const val READ_NOTIFICATIONS_ALOUD = "ReadNotificationsAloud"
    const val ACCESSIBILITY_ACTIONS = "AccessibilityActionsEnabled"

    const val THEME_MODE = "ThemeMode"
    const val ACCENT = "Accent"
    const val FONT_SIZE = "FontSize"

    const val WEATHER_CITY = "WeatherCity"
    const val PREFERRED_BROWSER = "PreferredBrowser"
    const val IMAGE_PROVIDER = "ImageProvider"
    const val IMAGE_QUALITY = "ImageQuality"
    const val IMAGE_MODEL = "ImageModel"
    const val IMAGE_COUNT = "ImageCount"
    const val IMAGE_SIZE = "ImageSize"

    const val STORAGE_LOCATION = "StorageLocation"
    const val KEEP_CONVERSATION_DAYS = "KeepConversationDays"
    const val CRASH_REPORTING = "CrashReporting"
    /** On once the welcome screen has been shown (or skipped). */
    const val ONBOARDED = "Onboarded"
    /** On = reminders/notes/notification text may be sent to the chosen AI provider as context for answers. Off = only counts and titles are. */
    const val AI_SHARE_PERSONAL = "AIShareContext"
}
