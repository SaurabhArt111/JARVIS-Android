package com.jarvis.assistant.ui.settings

import android.widget.LinearLayout
import com.jarvis.assistant.R
import com.jarvis.core.model.Requirement
import com.jarvis.core.ports.PermState

/** The Settings tab: a short status summary and a door to every section. Nothing here changes a setting by itself. */
class SettingsHomeScreen : SettingsPage() {
    override val id = "settings"
    override val topLevel = true
    override fun title() = s(R.string.ui_tab_settings)
    override fun onActivityResumed() { host.rebuild() }

    private fun open(id: String) { com.jarvis.assistant.ui.Screens.create(id)?.let { host.push(it) } }

    override fun fill(c: LinearLayout) {
        val p = app.permissions
        val granted = p.allRequirements().count { p.state(it) == PermState.GRANTED }
        val aiState = if (p.hasAnyProvider()) s(R.string.ui_set_ai_ready) else s(R.string.ui_set_ai_none)

        c.group(s(R.string.ui_set_setup)) {
            link(s(R.string.ui_set_ai), s(R.string.ui_set_ai_sub), "cpu", aiState) { open("settings:ai") }
            link(s(R.string.ui_perm_title), s(R.string.ui_set_perm_sub), "shield", s(R.string.ui_set_perm_value, granted, p.allRequirements().size)) { open("permissions") }
            link(s(R.string.ui_set_assistant), s(R.string.ui_set_assistant_sub), "home", if (p.state(Requirement.ASSISTANT_ROLE) == PermState.GRANTED) s(R.string.ui_yes) else s(R.string.ui_no)) { open("settings:assistant") }
        }
        c.group(s(R.string.ui_set_assistant_group)) {
            link(s(R.string.ui_set_general), null, "user") { open("settings:general") }
            link(s(R.string.ui_set_voice), s(R.string.ui_set_voice_sub), "volume-2") { open("settings:voice") }
            link(s(R.string.ui_set_wake), s(R.string.ui_set_wake_sub), "mic") { open("settings:wake") }
        }
        c.group(s(R.string.ui_set_surfaces)) {
            link(s(R.string.ui_set_island), s(R.string.ui_set_island_sub), "minimize-2") { open("settings:island") }
            link(s(R.string.ui_set_lock), s(R.string.ui_set_lock_sub), "lock") { open("settings:lock") }
            link(s(R.string.ui_set_integrations), s(R.string.ui_set_integrations_sub), "bell") { open("settings:integrations") }
        }
        c.group(s(R.string.ui_set_data)) {
            link(s(R.string.ui_set_storage), s(R.string.ui_set_storage_sub), "folder") { open("settings:storage") }
            link(s(R.string.ui_set_appearance), null, "sun") { open("settings:appearance") }
        }
        c.group(s(R.string.ui_set_help)) {
            link(s(R.string.ui_cap_title), s(R.string.ui_set_cap_sub), "help-circle") { open("capabilities") }
            link(s(R.string.ui_set_diagnostics), s(R.string.ui_set_diag_sub), "activity") { open("settings:diagnostics") }
            link(s(R.string.ui_set_about), null, "info") { open("settings:about") }
        }
    }
}
