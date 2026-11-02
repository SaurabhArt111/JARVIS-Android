package com.jarvis.assistant.ui.settings

import android.widget.LinearLayout
import android.widget.Toast
import com.jarvis.assistant.R
import com.jarvis.assistant.platform.IslandSwitch
import com.jarvis.assistant.platform.IslandState
import com.jarvis.assistant.platform.ListeningState
import com.jarvis.assistant.ui.kit.Dialogs
import com.jarvis.assistant.ui.kit.Ui
import com.jarvis.core.model.Requirement
import com.jarvis.core.ports.PermState
import com.jarvis.assistant.voice.ModelProgress
import com.jarvis.core.settings.Keys
import com.jarvis.core.wake.WakeModels
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Wake word and background listening: explains the real costs and limits before anything is switched on. */
class WakePage : SettingsPage() {
    override val id = "settings:wake"
    override fun title() = s(R.string.ui_set_wake)
    override fun onActivityResumed() { host.rebuild() }

    override fun fill(c: LinearLayout) {
        c.addView(ui.banner(s(R.string.ui_wake_honest), ui.pal.accent, "info"))
        val running = ListeningState.running
        c.group(s(R.string.ui_wake_background), s(R.string.ui_wake_background_note)) {
            flag(s(R.string.ui_wake_listen), if (running) ListeningState.detail.ifBlank { s(R.string.ui_wake_running) } else s(R.string.ui_wake_stopped), Keys.BACKGROUND_LISTENING, false,
                allow = { on -> setListening(on) })
            flag(s(R.string.ui_wake_boot), s(R.string.ui_wake_boot_sub), Keys.LISTEN_ON_BOOT_PROMPT, true)
            val battery = app.permissions.state(Requirement.BATTERY_OPTIMIZATION)
            link(s(R.string.ui_wake_battery), s(R.string.ui_wake_battery_sub), "battery", if (battery == PermState.GRANTED) s(R.string.ui_wake_battery_ok) else s(R.string.ui_wake_battery_no)) { activity.grant(Requirement.BATTERY_OPTIMIZATION) { host.rebuild() } }
        }
        c.group(s(R.string.ui_wake_models), s(R.string.ui_wake_models_sub)) {
            val installed = app.wakeModels.verified()
            link(s(R.string.ui_wake_models), null, "download", if (installed) s(R.string.ui_wake_models_installed) else s(R.string.ui_wake_models_missing)) { showModels(installed) }
        }
        c.group(s(R.string.ui_wake_detect), s(R.string.ui_wake_detect_note)) {
            choice(s(R.string.ui_wake_engine), Keys.WAKE_ENGINE, listOf("auto" to s(R.string.ui_ai_auto), "openwakeword" to s(R.string.ui_wake_engine_oww), "speech" to s(R.string.ui_wake_engine_speech), "off" to s(R.string.ui_off)), "auto", s(R.string.ui_wake_engine_sub))
            choice(s(R.string.ui_wake_sens), Keys.WAKE_SENSITIVITY, listOf("low" to s(R.string.ui_wake_sens_low), "medium" to s(R.string.ui_wake_sens_medium), "high" to s(R.string.ui_wake_sens_high)), "medium", s(R.string.ui_wake_sens_sub))
            textRow(s(R.string.ui_wake_words), Keys.WAKE_WORDS, "jarvis", s(R.string.ui_wake_words_sub), { v -> if (v.split(',').any { it.trim().length in 1..1 }) s(R.string.ui_wake_words_bad) else null })
        }
    }

    private fun showModels(installed: Boolean) {
        val mb = "%.1f".format(WakeModels.ALL.sumOf { it.approxBytes } / 1_000_000.0)
        var job: Job? = null
        val dialog = Dialogs.show(activity, ui) { c, dismiss ->
            c.addView(ui.title(s(R.string.ui_wake_models), 19f))
            c.addView(ui.text(s(R.string.ui_wake_models_body, mb), 14.5f))
            c.addView(ui.text(s(R.string.ui_wake_models_licence), 12.5f, ui.pal.muted))
            val status = ui.text(if (installed) s(R.string.ui_wake_models_installed) else s(R.string.ui_wake_models_missing), 13.5f, ui.pal.muted)
            c.addView(status)
            if (installed) {
                c.addView(ui.button(s(R.string.ui_wake_models_remove), Ui.Kind.DANGER) {
                    dismiss()
                    Dialogs.confirm(activity, ui, s(R.string.ui_wake_models), s(R.string.ui_wake_models_remove_q), s(R.string.ui_wake_models_remove), danger = true) {
                        app.wakeModels.deleteAll(); host.rebuild()
                    }
                })
                c.addView(ui.button(s(R.string.ui_wake_models_close), Ui.Kind.GHOST) { dismiss() })
            } else {
                lateinit var go: android.view.View
                go = ui.button(s(R.string.ui_wake_models_download), Ui.Kind.PRIMARY) {
                    go.isEnabled = false; go.alpha = 0.5f
                    job = viewScope.launch {
                        app.wakeDownloader.download().collect { p ->
                            when (p) {
                                is ModelProgress.Downloading -> status.text = s(R.string.ui_wake_models_progress, p.fileName, p.index, p.count, if (p.total > 0) (p.bytes * 100 / p.total).toInt().coerceIn(0, 100) else 0)
                                ModelProgress.Verifying -> status.text = s(R.string.ui_wake_models_verifying)
                                ModelProgress.Done -> { status.text = s(R.string.ui_wake_models_done); host.rebuild() }
                                is ModelProgress.Failed -> { status.text = p.message; go.isEnabled = true; go.alpha = 1f }
                            }
                        }
                    }
                }
                c.addView(go)
                c.addView(ui.button(s(R.string.ui_wake_models_cancel), Ui.Kind.GHOST) { dismiss() })
            }
        }
        dialog.setOnDismissListener { job?.cancel() }
    }

    /** Turning it on needs the microphone and must start from this (visible) screen; the result is read back from the service, not assumed. */
    private fun setListening(on: Boolean): Boolean {
        if (!on) { val r = app.services.control.setBackgroundListening(false); if (!r.ok) Dialogs.message(activity, ui, s(R.string.ui_set_wake), r.message); host.rebuild(); return true }
        if (app.permissions.state(Requirement.MICROPHONE) != PermState.GRANTED) {
            activity.grant(Requirement.MICROPHONE) { st -> if (st == PermState.GRANTED) { startNow() } else host.rebuild() }
            return false
        }
        startNow()
        return false
    }

    private fun startNow() {
        val r = app.services.control.setBackgroundListening(true)
        if (!r.ok) Dialogs.message(activity, ui, s(R.string.ui_set_wake), r.message)
        host.rebuild()
    }
}

/** Dynamic Island: turning it on goes through the "Display over other apps" screen; the switch shows the real state of the service. */
class IslandPage : SettingsPage() {
    override val id = "settings:island"
    override fun title() = s(R.string.ui_set_island)
    override fun onActivityResumed() { host.rebuild() }

    private fun range(min: Int, max: Int): (String) -> String? = { v -> val n = v.toIntOrNull(); if (n == null || n < min || n > max) s(R.string.ui_range, min, max) else null }

    override fun fill(c: LinearLayout) {
        val overlay = app.permissions.state(Requirement.OVERLAY)
        c.addView(ui.banner(s(R.string.ui_island_honest), ui.pal.accent, "info"))
        c.group(s(R.string.ui_island_show)) {
            flag(s(R.string.ui_island_enable), if (IslandState.running) s(R.string.ui_island_running) else if (overlay == PermState.GRANTED) s(R.string.ui_island_stopped) else s(R.string.ui_island_needs_overlay), Keys.ISLAND_ENABLED, false,
                allow = { on -> toggle(on) })
            link(s(R.string.ui_island_overlay), s(R.string.ui_island_overlay_sub), "layers", app.permissions.statusText(overlay)) { activity.grant(Requirement.OVERLAY) { sync(); host.rebuild() } }
            link(s(R.string.ui_island_preview), s(R.string.ui_island_preview_sub), "play") { if (!IslandState.running || !app.islandSwitch.preview()) Toast.makeText(activity, s(R.string.ui_island_not_running), Toast.LENGTH_SHORT).show() }
        }
        c.group(s(R.string.ui_island_place), s(R.string.ui_island_place_note)) {
            choice(s(R.string.ui_island_position), Keys.ISLAND_POSITION, listOf("auto" to s(R.string.ui_island_pos_auto), "left" to s(R.string.ui_island_pos_left), "center" to s(R.string.ui_island_pos_center), "right" to s(R.string.ui_island_pos_right)), "auto", s(R.string.ui_island_position_sub)) { sync() }
            textRow(s(R.string.ui_island_offx), Keys.ISLAND_OFFSET_X, "0", s(R.string.ui_island_off_sub), range(-600, 600)) { sync() }
            textRow(s(R.string.ui_island_offy), Keys.ISLAND_OFFSET_Y, "0", s(R.string.ui_island_off_sub), range(-100, 400)) { sync() }
            textRow(s(R.string.ui_island_size), Keys.ISLAND_SIZE, "100", s(R.string.ui_island_size_sub), range(60, 160)) { sync() }
            textRow(s(R.string.ui_island_collapse), Keys.ISLAND_COLLAPSE_SECONDS, "4", s(R.string.ui_island_collapse_sub), range(2, 20))
            link(s(R.string.ui_island_reset), null, "refresh-cw") { settings.set(Keys.ISLAND_OFFSET_X, null); settings.set(Keys.ISLAND_OFFSET_Y, null); settings.set(Keys.ISLAND_SIZE, null); sync(); host.rebuild() }
        }
        c.group(s(R.string.ui_island_behaviour)) {
            choice(
                s(R.string.ui_island_visibility),
                Keys.ISLAND_ALWAYS_VISIBLE,
                listOf(
                    "Off" to s(R.string.ui_island_vis_hide),
                    "On" to s(R.string.ui_island_vis_always)
                ),
                "Off",
                s(R.string.ui_island_visibility_sub)
            ) { sync(); host.rebuild() }
            flag(s(R.string.ui_island_speak), s(R.string.ui_island_speak_sub), Keys.ISLAND_SPEAK, false)
        }
    }

    private fun sync() { if (settings.flag(Keys.ISLAND_ENABLED, false)) app.islandSwitch.sync() }

    private fun toggle(on: Boolean): Boolean {
        if (!on) { settings.setFlag(Keys.ISLAND_ENABLED, false); app.islandSwitch.sync(); host.rebuild(); return false }
        if (app.permissions.state(Requirement.OVERLAY) != PermState.GRANTED) {
            activity.grant(Requirement.OVERLAY) { st ->
                if (st == PermState.GRANTED) { settings.setFlag(Keys.ISLAND_ENABLED, true); report(app.islandSwitch.sync()) }
                host.rebuild()
            }
            return false
        }
        settings.setFlag(Keys.ISLAND_ENABLED, true)
        report(app.islandSwitch.sync())
        host.rebuild()
        return false
    }

    private fun report(r: IslandSwitch.Result) {
        if (r == IslandSwitch.Result.FAILED) Dialogs.message(activity, ui, s(R.string.ui_set_island), s(R.string.ui_island_failed))
    }
}

/** Lock screen: what JARVIS may do while the phone is locked. It is a policy; JARVIS never unlocks, bypasses or weakens the lock. */
class LockPage : SettingsPage() {
    override val id = "settings:lock"
    override fun title() = s(R.string.ui_set_lock)
    override fun onActivityResumed() { host.rebuild() }

    override fun fill(c: LinearLayout) {
        c.addView(ui.banner(s(R.string.ui_lock_honest), ui.pal.accent, "shield"))
        c.group(s(R.string.ui_lock_mode)) {
            flag(s(R.string.ui_lock_enable), s(R.string.ui_lock_enable_sub), Keys.LOCK_MODE_ENABLED, false)
            choice(s(R.string.ui_lock_privacy), Keys.LOCK_PRIVACY, listOf("strict" to s(R.string.ui_lock_strict), "balanced" to s(R.string.ui_lock_balanced), "open" to s(R.string.ui_lock_open)), "strict", s(R.string.ui_lock_privacy_sub)) { host.rebuild() }
        }
        c.paragraph(when (settings.get(Keys.LOCK_PRIVACY, "strict").lowercase()) {
            "open" -> s(R.string.ui_lock_open_desc)
            "balanced" -> s(R.string.ui_lock_balanced_desc)
            else -> s(R.string.ui_lock_strict_desc)
        })
        val modeOn = settings.flag(Keys.LOCK_MODE_ENABLED, false)
        c.group(s(R.string.ui_lock_allow), s(R.string.ui_lock_allow_note)) {
            flag(s(R.string.ui_lock_dictation), s(R.string.ui_lock_dictation_sub), Keys.LOCK_ALLOW_DICTATION, true, modeOn)
            flag(s(R.string.ui_lock_notifs), s(R.string.ui_lock_notifs_sub), Keys.LOCK_READ_NOTIFICATIONS, false, modeOn)
            flag(s(R.string.ui_lock_calls), s(R.string.ui_lock_calls_sub), Keys.LOCK_ALLOW_CALLS, false, modeOn)
            flag(s(R.string.ui_lock_contacts), s(R.string.ui_lock_contacts_sub), Keys.LOCK_ALLOW_CONTACT_LOOKUP, false, modeOn)
        }
        c.group(s(R.string.ui_lock_alerts)) {
            val st = app.permissions.state(Requirement.FULL_SCREEN_INTENT)
            link(s(R.string.ui_lock_fsi), s(R.string.ui_lock_fsi_sub), "bell", app.permissions.statusText(st)) { activity.grant(Requirement.FULL_SCREEN_INTENT) { host.rebuild() } }
        }
        c.addView(ui.sectionHeader(s(R.string.ui_lock_table)))
        c.addView(ui.card(16, 14, 10) {
            row(R.string.ui_lock_t_ok, R.string.ui_lock_t_ok_body, ui.pal.ok)
            row(R.string.ui_lock_t_confirm, R.string.ui_lock_t_confirm_body, ui.pal.warn)
            row(R.string.ui_lock_t_unlock, R.string.ui_lock_t_unlock_body, ui.pal.accent)
            row(R.string.ui_lock_t_never, R.string.ui_lock_t_never_body, ui.pal.err)
        })
        c.paragraph(s(R.string.ui_lock_limits))
    }

    private fun LinearLayout.row(title: Int, body: Int, color: Int) {
        addView(ui.column(gap = 2) {
            addView(ui.text(s(title), 14.5f, color, bold = true))
            addView(ui.muted(s(body), 13f))
        })
    }
}
