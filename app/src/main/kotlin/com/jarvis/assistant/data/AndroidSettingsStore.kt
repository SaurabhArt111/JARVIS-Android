package com.jarvis.assistant.data

import android.content.Context
import com.jarvis.core.json.Json
import com.jarvis.core.settings.Settings
import com.jarvis.core.settings.SettingsStore

/** [SettingsStore] on SharedPreferences (ordinary values) + [SecretStore] (keys and tokens). */
class AndroidSettingsStore(context: Context, val secrets: SecretStore) : SettingsStore {
    private val prefs = context.applicationContext.getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(String) -> Unit>()

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
        listeners.forEach { it(key) }
    }

    override fun secret(key: String): String? = secrets.get(key)
    override fun putSecret(key: String, value: String?) { secrets.put(key, value); listeners.forEach { it(key) } }

    /** Called with the key name after every change (the island and the wake-word service react to settings immediately). */
    fun onChange(l: (String) -> Unit) { listeners.add(l) }
    fun removeListener(l: (String) -> Unit) { listeners.remove(l) }

    /** Non-secret settings as JSON. Keys, tokens and secrets are NEVER included. */
    fun export(): String {
        val m = linkedMapOf<String, Any?>("app" to "jarvis", "version" to 1)
        val values = java.util.TreeMap<String, String>()
        for ((k, v) in prefs.all) if (v is String && !isSecretName(k)) values[k] = v
        m["settings"] = values
        return Json.stringify(m, pretty = true)
    }

    /** Applies settings from [export]. Unknown keys and anything secret-looking are ignored. Returns how many values were imported. */
    fun import(json: String): Int {
        val root = Json.parseObjectOrNull(json) ?: return 0
        if (Json.str(root["app"]) != "jarvis") return 0
        val values = Json.obj(root["settings"]) ?: return 0
        var n = 0
        val edit = prefs.edit()
        for ((k, v) in values) {
            val s = Json.str(v) ?: continue
            if (isSecretName(k) || k.length > 64 || s.length > 4000) continue
            edit.putString(k, s); n++
        }
        edit.apply()
        return n
    }

    private val rules by lazy { Settings(this) }   // the same rule set that routes secrets decides what is exported
    private fun isSecretName(k: String) = rules.isSecret(k) || k.contains("Password", true)
}
