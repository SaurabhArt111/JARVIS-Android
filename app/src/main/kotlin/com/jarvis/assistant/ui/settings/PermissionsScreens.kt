package com.jarvis.assistant.ui.settings

import android.view.View
import android.widget.LinearLayout
import com.jarvis.assistant.R
import com.jarvis.assistant.ui.kit.Themes
import com.jarvis.assistant.ui.kit.Ui
import com.jarvis.assistant.ui.kit.WRAP
import com.jarvis.assistant.ui.kit.add
import com.jarvis.core.model.Requirement
import com.jarvis.core.ports.PermState
import com.jarvis.core.routing.CapabilityStatus
import com.jarvis.core.tools.Capability

/**
 * Every permission JARVIS can use, with its state, why it is wanted, and a button that asks for it the way Android requires. Nothing is requested until the user
 * taps here (or uses a feature that needs it, where the reason is explained first). Everything is optional: JARVIS works, with fewer abilities, without any of them.
 */
class PermissionsScreen : SettingsPage() {
    override val id = "permissions"
    override fun title() = s(R.string.ui_perm_title)
    override fun onActivityResumed() { host.rebuild() }

    private val groups: List<Pair<Int, List<Requirement>>> = listOf(
        R.string.ui_perm_g_talk to listOf(Requirement.MICROPHONE),
        R.string.ui_perm_g_alerts to listOf(Requirement.NOTIFICATIONS, Requirement.EXACT_ALARM, Requirement.FULL_SCREEN_INTENT),
        R.string.ui_perm_g_people to listOf(Requirement.CONTACTS, Requirement.CALL_PHONE),
        R.string.ui_perm_g_surfaces to listOf(Requirement.ASSISTANT_ROLE, Requirement.OVERLAY, Requirement.BATTERY_OPTIMIZATION),
        R.string.ui_perm_g_integrations to listOf(Requirement.NOTIFICATION_LISTENER, Requirement.ACCESSIBILITY),
        R.string.ui_perm_g_device to listOf(Requirement.WRITE_SETTINGS, Requirement.DND_ACCESS),
    )

    override fun fill(c: LinearLayout) {
        val p = app.permissions
        c.paragraph(s(R.string.ui_perm_intro))
        if (restrictedSettingsLikely()) {
            c.addView(ui.banner(s(R.string.ui_perm_restricted), ui.pal.warn, "alert-triangle"))
            c.addView(ui.button(s(R.string.ui_perm_open_app_info), com.jarvis.assistant.ui.kit.Ui.Kind.GHOST) { openAppInfo() })
        }
        groups.forEach { (titleRes, reqs) ->
            c.addView(ui.sectionHeader(s(titleRes)))
            reqs.forEach { c.addView(card(it)) }
        }
        c.addView(ui.sectionHeader(s(R.string.ui_perm_g_conditions)))
        c.addView(conditionRow(s(R.string.ui_perm_internet), if (p.isOnline()) s(R.string.ui_perm_online) else s(R.string.ui_perm_offline), p.isOnline(), null))
        c.addView(conditionRow(s(R.string.ui_perm_ai), if (p.hasAnyProvider()) s(R.string.ui_set_ai_ready) else s(R.string.ui_set_ai_none), p.hasAnyProvider()) { com.jarvis.assistant.ui.Screens.create("settings:ai")?.let { host.push(it) } })
    }

    /**
     * Android 13+ greys out "Display over other apps", Accessibility and Notification access for apps installed from a file (not from a store) until the user
     * picks "Allow restricted settings" in the app's info page. Nothing in an app can do that for them, so say it plainly and take them to the page.
     */
    private fun restrictedSettingsLikely(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 33) return false
        val installer = try {
            if (android.os.Build.VERSION.SDK_INT >= 30) activity.packageManager.getInstallSourceInfo(activity.packageName).installingPackageName
            else @Suppress("DEPRECATION") activity.packageManager.getInstallerPackageName(activity.packageName)
        } catch (_: Exception) { null }
        return installer != "com.android.vending"
    }

    private fun openAppInfo() {
        try {
            activity.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", activity.packageName, null)))
        } catch (_: Exception) {
            android.widget.Toast.makeText(activity, s(R.string.ui_perm_restricted_manual), android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun chip(text: String, color: Int): View = ui.text(text, 12f, color, bold = true).apply {
        setPadding(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(4)); background = ui.rounded(Themes.withAlpha(color, 36), 12)
    }

    private fun card(req: Requirement): View {
        val p = app.permissions
        val st = p.state(req)
        val pal = ui.pal
        val color = when (st) { PermState.GRANTED -> pal.ok; PermState.DENIED -> pal.warn; PermState.UNAVAILABLE -> pal.muted }
        return ui.card(16, 14, 8) {
            addView(ui.row(10) {
                addView(ui.text(p.title(req), 16f, bold = true), LinearLayout.LayoutParams(0, WRAP, 1f))
                addView(chip(p.statusText(st), color))
            })
            addView(ui.muted(p.rationale(req), 13.5f))
            when (st) {
                PermState.DENIED -> addView(ui.button(s(R.string.ui_allow), Ui.Kind.PRIMARY) { activity.grant(req) { host.rebuild() } })
                PermState.GRANTED -> addView(ui.button(s(R.string.ui_perm_manage), Ui.Kind.TEXT) { if (!p.open(activity, req)) android.widget.Toast.makeText(activity, s(R.string.ui_perm_cant_open), android.widget.Toast.LENGTH_SHORT).show() })
                PermState.UNAVAILABLE -> {}
            }
        }
    }

    private fun conditionRow(title: String, value: String, ok: Boolean, onClick: (() -> Unit)?): View =
        ui.card(16, 12, 4) {
            addView(ui.row(10) {
                addView(ui.text(title, 15f, bold = true), LinearLayout.LayoutParams(0, WRAP, 1f))
                addView(chip(value, if (ok) ui.pal.ok else ui.pal.warn))
            })
            if (onClick != null && !ok) addView(ui.button(s(R.string.ui_set_up), Ui.Kind.TEXT) { onClick() })
        }
}

/** "What can JARVIS do?": every area of ability with its real status on this phone right now (from the same router the assistant itself uses). */
class CapabilitiesScreen : SettingsPage() {
    override val id = "capabilities"
    override fun title() = s(R.string.ui_cap_title)
    override fun onActivityResumed() { host.rebuild() }

    private fun nameOf(c: Capability): String = s(when (c) {
        Capability.CONVERSATION -> R.string.ui_capn_conversation
        Capability.INFORMATION -> R.string.ui_capn_information
        Capability.CONTENT_GENERATION -> R.string.ui_capn_content
        Capability.IMAGE_GENERATION -> R.string.ui_capn_image
        Capability.REMINDERS -> R.string.ui_capn_reminders
        Capability.NOTES -> R.string.ui_capn_notes
        Capability.TIMERS_ALARMS -> R.string.ui_capn_timers
        Capability.APP_LAUNCH -> R.string.ui_capn_apps
        Capability.DEVICE_CONTROL -> R.string.ui_capn_device
        Capability.MEDIA -> R.string.ui_capn_media
        Capability.COMMUNICATION -> R.string.ui_capn_comm
        Capability.NOTIFICATIONS -> R.string.ui_capn_notifications
        Capability.SYSTEM_NAVIGATION -> R.string.ui_capn_navigation
        Capability.FILES -> R.string.ui_capn_files
        Capability.ASSISTANT_SETTINGS -> R.string.ui_capn_settings
    })

    override fun fill(c: LinearLayout) {
        c.paragraph(s(R.string.ui_cap_intro))
        val list: List<CapabilityStatus> = try { app.capabilityRouter.status(app.lockState.read()) } catch (e: Exception) { emptyList() }
        if (list.isEmpty()) { c.addView(ui.errorState(s(R.string.ui_cap_error)) { host.rebuild() }); return }
        val pal = ui.pal
        list.forEach { st ->
            val state = when { !st.available -> s(R.string.ui_cap_blocked) to pal.err; st.missing.isNotEmpty() -> s(R.string.ui_cap_limited) to pal.warn; else -> s(R.string.ui_cap_works) to pal.ok }
            c.addView(ui.card(16, 14, 8) {
                addView(ui.row(10) {
                    addView(ui.text(nameOf(st.capability), 16f, bold = true), LinearLayout.LayoutParams(0, WRAP, 1f))
                    addView(ui.text(state.first, 12f, state.second, bold = true).apply { setPadding(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(4)); background = ui.rounded(Themes.withAlpha(state.second, 36), 12) })
                })
                if (st.note.isNotBlank()) addView(ui.muted(st.note, 13.5f))
                addView(ui.muted(if (st.offlineCapable) s(R.string.ui_cap_offline_yes) else s(R.string.ui_cap_offline_no), 12f))
                if (!st.allowedWhenLocked) addView(ui.muted(s(R.string.ui_cap_locked_no), 12f))
                st.missing.forEach { req ->
                    if (req == Requirement.API_KEY) addView(ui.button(s(R.string.ui_set_ai), Ui.Kind.GHOST) { com.jarvis.assistant.ui.Screens.create("settings:ai")?.let { host.push(it) } })
                    else if (app.permissions.settingsIntent(req) != null || app.permissions.runtimePermissions(req).isNotEmpty())
                        addView(ui.button(s(R.string.ui_cap_allow, app.permissions.title(req)), Ui.Kind.GHOST) { activity.grant(req) { host.rebuild() } })
                }
            })
        }
    }
}
