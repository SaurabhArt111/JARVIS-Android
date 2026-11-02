package com.jarvis.assistant.ui.settings

import android.content.ComponentName
import android.content.Intent
import android.widget.LinearLayout
import com.jarvis.assistant.R
import com.jarvis.assistant.ui.Screens
import com.jarvis.assistant.ui.kit.Ui
import com.jarvis.assistant.ui.kit.WRAP
import com.jarvis.core.model.Requirement
import com.jarvis.core.ports.PermState
import com.jarvis.core.settings.Keys

/** Make JARVIS the phone's assistant (long-press Home / assistant gesture). Android decides; JARVIS can only send you to the right screen and report what it finds. */
class AssistantSetupPage : SettingsPage() {
    override val id = "settings:assistant"
    override fun title() = s(R.string.ui_set_assistant)
    override fun onActivityResumed() { host.rebuild() }

    override fun fill(c: LinearLayout) {
        val isDefault = app.permissions.state(Requirement.ASSISTANT_ROLE) == PermState.GRANTED
        c.addView(ui.card(16, 16, 8) {
            addView(ui.row(10) {
                addView(ui.icon(if (isDefault) "check-circle" else "info", 24, if (isDefault) ui.pal.ok else ui.pal.accent))
                addView(ui.text(if (isDefault) s(R.string.ui_asst_is_default) else s(R.string.ui_asst_not_default), 16f, bold = true), LinearLayout.LayoutParams(0, WRAP, 1f))
            })
            addView(ui.muted(s(R.string.ui_asst_what), 13.5f))
            if (!isDefault) addView(ui.button(s(R.string.ui_asst_open), Ui.Kind.PRIMARY, "settings") { activity.grant(Requirement.ASSISTANT_ROLE) { host.rebuild() } })
        })
        c.addView(ui.sectionHeader(s(R.string.ui_asst_steps)))
        c.addView(ui.card(16, 14, 10) {
            listOf(R.string.ui_asst_step1, R.string.ui_asst_step2, R.string.ui_asst_step3, R.string.ui_asst_step4).forEachIndexed { i, r ->
                addView(ui.row(10, center = false) {
                    addView(ui.text("${i + 1}", 15f, ui.pal.accent, bold = true).apply { minWidth = ui.dp(18) })
                    addView(ui.text(s(r), 14.5f), LinearLayout.LayoutParams(0, WRAP, 1f))
                })
            }
        })
        c.group(s(R.string.ui_asst_try)) {
            link(s(R.string.ui_asst_open_sheet), s(R.string.ui_asst_open_sheet_sub), "message-circle") {
                startActivityByName("com.jarvis.assistant.ui.AssistantActivity")
            }
        }
        c.paragraph(s(R.string.ui_asst_limits))
    }

    private fun startActivityByName(cls: String) {
        try { activity.startActivity(Intent().setComponent(ComponentName(activity.packageName, cls))) } catch (e: Exception) { /* not installed in this build */ }
    }
}

/** First run: three optional steps, each a button that opens the real flow. Nothing is switched on for you; closing it (or "Skip") marks it as seen. */
class OnboardingScreen : SettingsPage() {
    override val id = "onboarding"
    override fun title() = s(R.string.ui_onb_title)

    override fun onClosed() { settings.setFlag(Keys.ONBOARDED, true) }

    override fun fill(c: LinearLayout) {
        c.addView(ui.title(s(R.string.ui_onb_hello, settings.assistantName()), 24f))
        c.addView(ui.muted(s(R.string.ui_onb_intro), 14.5f))
        step(c, 1, s(R.string.ui_onb_ai), s(R.string.ui_onb_ai_body), s(R.string.ui_set_ai)) { Screens.create("settings:ai")?.let { host.push(it) } }
        step(c, 2, s(R.string.ui_onb_mic), s(R.string.ui_onb_mic_body), s(R.string.ui_allow)) { activity.grant(Requirement.MICROPHONE) { host.rebuild() } }
        step(c, 3, s(R.string.ui_onb_assistant), s(R.string.ui_onb_assistant_body), s(R.string.ui_asst_open_short)) { Screens.create("settings:assistant")?.let { host.push(it) } }
        c.addView(ui.muted(s(R.string.ui_onb_more), 13f))
        c.addView(ui.button(s(R.string.ui_onb_start), Ui.Kind.PRIMARY) { host.back() })
    }

    private fun step(c: LinearLayout, n: Int, title: String, body: String, action: String, run: () -> Unit) {
        c.addView(ui.card(16, 14, 8) {
            addView(ui.row(10) {
                addView(ui.text("$n", 18f, ui.pal.accent, bold = true))
                addView(ui.text(title, 16f, bold = true), LinearLayout.LayoutParams(0, WRAP, 1f))
                addView(ui.text(s(R.string.ui_onb_optional), 12f, ui.pal.muted))
            })
            addView(ui.muted(body, 13.5f))
            addView(ui.button(action, Ui.Kind.GHOST) { run() })
        })
    }
}
