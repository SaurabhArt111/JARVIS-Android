package com.jarvis.assistant.ui.settings

import android.content.Intent
import android.widget.LinearLayout
import com.jarvis.assistant.R
import com.jarvis.assistant.ui.kit.Themes
import com.jarvis.core.settings.Keys
import kotlinx.coroutines.launch

class GeneralPage : SettingsPage() {
    override val id = "settings:general"
    override fun title() = s(R.string.ui_set_general)
    override fun fill(c: LinearLayout) {
        c.group(s(R.string.ui_gen_names)) {
            textRow(s(R.string.ui_gen_your_name), Keys.USERNAME, s(R.string.ui_gen_your_name_hint), s(R.string.ui_gen_your_name_sub))
            textRow(s(R.string.ui_gen_assistant_name), Keys.ASSISTANT_NAME, s(R.string.ui_gen_assistant_name_hint), s(R.string.ui_gen_assistant_name_sub))
        }
        c.group(s(R.string.ui_gen_place)) {
            textRow(s(R.string.ui_gen_city), Keys.WEATHER_CITY, s(R.string.ui_gen_city_hint), s(R.string.ui_gen_city_sub))
            choice(s(R.string.ui_gen_language), Keys.INPUT_LANGUAGE, LANGUAGES, "en", s(R.string.ui_gen_language_sub))
            textRow(s(R.string.ui_gen_browser), Keys.PREFERRED_BROWSER, s(R.string.ui_gen_browser_hint), s(R.string.ui_gen_browser_sub))
        }
        c.group(s(R.string.ui_gen_start)) {
            flag(s(R.string.ui_gen_greet), s(R.string.ui_gen_greet_sub), Keys.GREET_ON_START, true)
        }
    }

    companion object {
        val LANGUAGES = listOf("en" to "English", "en-IN" to "English (India)", "en-GB" to "English (UK)", "hi" to "हिन्दी (Hindi)", "es" to "Español", "fr" to "Français",
            "de" to "Deutsch", "it" to "Italiano", "pt" to "Português", "ja" to "日本語", "ko" to "한국어", "zh" to "中文", "ar" to "العربية", "ru" to "Русский")
    }
}

class AppearancePage : SettingsPage() {
    override val id = "settings:appearance"
    override fun title() = s(R.string.ui_set_appearance)

    private fun apply() { activity.refreshUi(); host.rebuild() }

    override fun fill(c: LinearLayout) {
        c.group(s(R.string.ui_app_theme)) {
            choice(s(R.string.ui_app_mode), Keys.THEME_MODE, listOf("System" to s(R.string.ui_app_mode_system), "Light" to s(R.string.ui_app_mode_light), "Dark" to s(R.string.ui_app_mode_dark)), "System") { apply() }
            choice(s(R.string.ui_app_size), Keys.FONT_SIZE, listOf("Small" to s(R.string.ui_app_size_small), "Medium" to s(R.string.ui_app_size_medium), "Large" to s(R.string.ui_app_size_large)), "Medium", s(R.string.ui_app_size_sub)) { apply() }
        }
        // accent swatches
        c.addView(ui.sectionHeader(s(R.string.ui_app_accent)))
        val current = settings.get(Keys.ACCENT, "Cyan")
        val flow = com.jarvis.assistant.ui.kit.FlowLayout(activity, ui.dp(10))
        Themes.ACCENTS.forEach { (name, tri) ->
            val color = android.graphics.Color.parseColor(if (ui.pal.dark) tri.first else tri.second)
            val sw = android.widget.FrameLayout(activity).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(ui.dp(56), ui.dp(56))
                background = ui.rounded(color, 28, if (name == current) ui.pal.text else null, 3)
                isClickable = true; isFocusable = true; contentDescription = name + if (name == current) ", " + s(R.string.ui_selected) else ""
                if (name == current) addView(ui.icon("check", 24, ui.pal.onAccent).apply { layoutParams = android.widget.FrameLayout.LayoutParams(ui.dp(24), ui.dp(24), android.view.Gravity.CENTER) })
                setOnClickListener { settings.set(Keys.ACCENT, name); apply() }
            }
            flow.addView(sw, android.view.ViewGroup.LayoutParams(ui.dp(56), ui.dp(56)))
        }
        c.addView(flow)
        c.paragraph(s(R.string.ui_app_note))
    }
}

class VoicePage : SettingsPage() {
    override val id = "settings:voice"
    override fun title() = s(R.string.ui_set_voice)
    override fun fill(c: LinearLayout) {
        c.group(s(R.string.ui_voice_speaking), s(R.string.ui_voice_speaking_note)) {
            flag(s(R.string.ui_voice_muted), s(R.string.ui_voice_muted_sub), Keys.VOICE_MUTED, false) { if (it) app.speaker.stop() }
            flag(s(R.string.ui_voice_speak_typed), s(R.string.ui_voice_speak_typed_sub), Keys.SPEAK_REPLIES, false)
            flag(s(R.string.ui_voice_speak_reminders), s(R.string.ui_voice_speak_reminders_sub), Keys.SPEAK_REMINDERS, true)
            flag(s(R.string.ui_voice_speak_greeting), s(R.string.ui_voice_speak_greeting_sub), Keys.SPEAK_GREETING, false)
            flag(s(R.string.ui_voice_duck), s(R.string.ui_voice_duck_sub), Keys.DUCK_ENABLED, true)
            choice(s(R.string.ui_voice_rate), Keys.SPEECH_RATE, listOf("0.75" to "0.75×", "0.9" to "0.9×", "1.0" to "1×", "1.15" to "1.15×", "1.3" to "1.3×", "1.5" to "1.5×"), "1.0")
            link(s(R.string.ui_voice_engine), s(R.string.ui_voice_engine_sub), "volume-2") { openTtsSettings() }
            link(s(R.string.ui_voice_test), null, "play") { testVoice() }
        }
        c.group(s(R.string.ui_voice_listening), s(R.string.ui_voice_listening_note)) {
            choice(s(R.string.ui_gen_language), Keys.INPUT_LANGUAGE, GeneralPage.LANGUAGES, "en", s(R.string.ui_gen_language_sub))
            flag(s(R.string.ui_voice_on_device), s(R.string.ui_voice_on_device_sub), Keys.PREFER_ON_DEVICE_SPEECH, false)
        }
    }

    private fun openTtsSettings() {
        val tries = listOf(Intent("com.android.settings.TTS_SETTINGS"), Intent(android.provider.Settings.ACTION_SETTINGS))
        for (i in tries) { try { activity.startActivity(i); return } catch (_: Exception) { } }
    }

    private fun testVoice() {
        val text = s(R.string.ui_voice_test_phrase, settings.userName())
        viewScope.launch {
            if (!app.speaker.speak(text)) android.widget.Toast.makeText(activity, s(R.string.ui_voice_test_failed), android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
