package com.jarvis.assistant.ui.settings

import android.content.Intent
import android.os.Build
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import com.jarvis.assistant.R
import com.jarvis.assistant.data.CrashHandler
import com.jarvis.assistant.platform.FileActions
import com.jarvis.assistant.ui.kit.Dialogs
import com.jarvis.assistant.ui.kit.MATCH
import com.jarvis.assistant.ui.kit.PageOpener
import com.jarvis.assistant.ui.kit.Ui
import com.jarvis.assistant.ui.kit.WRAP
import com.jarvis.core.model.Requirement
import com.jarvis.core.ports.PermState
import com.jarvis.core.settings.Keys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Notifications and integrations. Each switch is independent: granting one permission never turns another feature on, and each feature is off until you turn it on. */
class IntegrationsPage : SettingsPage() {
    override val id = "settings:integrations"
    override fun title() = s(R.string.ui_set_integrations)
    override fun onActivityResumed() { host.rebuild() }

    /** Turning a feature on first takes the user to the system screen it needs; the switch flips only once the permission is really there. */
    private fun needs(req: Requirement, key: String): (Boolean) -> Boolean = { on ->
        if (!on) true
        else if (app.permissions.state(req) == PermState.GRANTED) true
        else { activity.grant(req) { st -> if (st == PermState.GRANTED) settings.setFlag(key, true); host.rebuild() }; false }
    }

    override fun fill(c: LinearLayout) {
        val listener = app.permissions.state(Requirement.NOTIFICATION_LISTENER) == PermState.GRANTED
        val integrate = settings.flag(Keys.NOTIF_INTEGRATION, false)

        c.addView(ui.banner(s(R.string.ui_int_honest), ui.pal.accent, "info"))
        c.group(s(R.string.ui_int_notifs), s(R.string.ui_int_notifs_note)) {
            link(s(R.string.ui_int_access), s(R.string.ui_int_access_sub), "bell", app.permissions.statusText(app.permissions.state(Requirement.NOTIFICATION_LISTENER))) { activity.grant(Requirement.NOTIFICATION_LISTENER) { host.rebuild() } }
            flag(s(R.string.ui_int_use), s(R.string.ui_int_use_sub), Keys.NOTIF_INTEGRATION, false, allow = needs(Requirement.NOTIFICATION_LISTENER, Keys.NOTIF_INTEGRATION))
            flag(s(R.string.ui_int_aloud), s(R.string.ui_int_aloud_sub), Keys.READ_NOTIFICATIONS_ALOUD, false, enabled = integrate && listener)
            flag(s(R.string.ui_int_whatsapp), s(R.string.ui_int_whatsapp_sub), Keys.WHATSAPP_QUICK_REPLY, false, enabled = integrate && listener)
        }
        c.paragraph(s(R.string.ui_int_whatsapp_note))
        c.group(s(R.string.ui_int_access_group), s(R.string.ui_int_access_disclosure)) {
            link(s(R.string.ui_int_a11y), s(R.string.ui_int_a11y_sub), "command", app.permissions.statusText(app.permissions.state(Requirement.ACCESSIBILITY))) { activity.grant(Requirement.ACCESSIBILITY) { host.rebuild() } }
            flag(s(R.string.ui_int_a11y_use), s(R.string.ui_int_a11y_use_sub), Keys.ACCESSIBILITY_ACTIONS, false, allow = needs(Requirement.ACCESSIBILITY, Keys.ACCESSIBILITY_ACTIONS))
        }
    }
}

/** Where files are saved, how long chats are kept, and moving your (non-secret) settings between phones. */
class StoragePage : SettingsPage() {
    override val id = "settings:storage"
    override fun title() = s(R.string.ui_set_storage)

    override fun fill(c: LinearLayout) {
        c.group(s(R.string.ui_sto_files), s(R.string.ui_sto_files_note)) {
            choice(s(R.string.ui_sto_location), Keys.STORAGE_LOCATION, listOf("documents" to s(R.string.ui_sto_documents), "private" to s(R.string.ui_sto_private)), "documents", s(R.string.ui_sto_location_sub))
            link(s(R.string.ui_sto_library), s(R.string.ui_sto_library_sub), "folder") { (activity as? PageOpener)?.openPage("library") }
        }
        c.group(s(R.string.ui_sto_chats), s(R.string.ui_sto_chats_note)) {
            choice(s(R.string.ui_sto_keep), Keys.KEEP_CONVERSATION_DAYS, listOf("0" to s(R.string.ui_sto_forever), "30" to s(R.string.ui_sto_30), "90" to s(R.string.ui_sto_90), "365" to s(R.string.ui_sto_365)), "0", s(R.string.ui_sto_keep_sub)) { app.pruneConversations() }
            link(s(R.string.ui_sto_clear), s(R.string.ui_sto_clear_sub), "trash-2") { confirmClear() }
        }
        c.group(s(R.string.ui_sto_backup), s(R.string.ui_sto_backup_note)) {
            link(s(R.string.ui_sto_export), s(R.string.ui_sto_export_sub), "upload") { exportSettings() }
            link(s(R.string.ui_sto_import), s(R.string.ui_sto_import_sub), "download") { importSettings() }
        }
    }

    private fun confirmClear() {
        Dialogs.confirm(activity, ui, s(R.string.ui_sto_clear_title), s(R.string.ui_sto_clear_body), s(R.string.ui_delete_yes), danger = true) {
            viewScope.launch {
                try { app.conversations.clearAll(); Toast.makeText(activity, s(R.string.ui_sto_cleared), Toast.LENGTH_SHORT).show() }
                catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Toast.makeText(activity, s(R.string.ui_err_generic), Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun exportSettings() {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE, "jarvis-settings.json")
        val ok = activity.launchForResult(i) { code, data ->
            val dest = data?.data ?: return@launchForResult
            if (code != android.app.Activity.RESULT_OK) return@launchForResult
            val json = app.androidSettings.export()
            Thread {
                val done = try { activity.contentResolver.openOutputStream(dest, "w")?.use { it.write(json.toByteArray(Charsets.UTF_8)); true } ?: false } catch (e: Exception) { false }
                activity.runOnUiThread { Toast.makeText(activity, if (done) s(R.string.ui_sto_export_done) else s(R.string.ui_sto_export_failed), Toast.LENGTH_LONG).show() }
            }.start()
        }
        if (!ok) Toast.makeText(activity, s(R.string.ui_lib_no_file_dialog), Toast.LENGTH_SHORT).show()
    }

    private fun importSettings() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain"))
        val ok = activity.launchForResult(i) { code, data ->
            val src = data?.data ?: return@launchForResult
            if (code != android.app.Activity.RESULT_OK) return@launchForResult
            Thread {
                val text = try { activity.contentResolver.openInputStream(src)?.use { String(it.readNBytes(256 * 1024), Charsets.UTF_8) } } catch (e: Exception) { null }
                val n = if (text == null) -1 else app.androidSettings.import(text)
                activity.runOnUiThread {
                    when {
                        n < 0 -> Toast.makeText(activity, s(R.string.ui_sto_import_failed), Toast.LENGTH_LONG).show()
                        n == 0 -> Toast.makeText(activity, s(R.string.ui_sto_import_none), Toast.LENGTH_LONG).show()
                        else -> { Toast.makeText(activity, s(R.string.ui_sto_import_done, n), Toast.LENGTH_LONG).show(); activity.refreshUi(); host.rebuild() }
                    }
                }
            }.start()
        }
        if (!ok) Toast.makeText(activity, s(R.string.ui_lib_no_file_dialog), Toast.LENGTH_SHORT).show()
    }
}

/** Diagnostics: versions, a redacted log you can read, copy or share, a crash report from the last run if there was one, and an honest statement of what has and hasn't been verified. */
class DiagnosticsPage : SettingsPage() {
    override val id = "settings:diagnostics"
    override fun title() = s(R.string.ui_set_diagnostics)

    private fun versionName(): String = try { activity.packageManager.getPackageInfo(activity.packageName, 0).let { "${it.versionName} (${it.longVersionCode})" } } catch (e: Exception) { "?" }

    override fun fill(c: LinearLayout) {
        CrashHandler.pending(activity)?.let { crash ->
            c.addView(ui.card(16, 14, 8) {
                addView(ui.text(s(R.string.ui_diag_crash_title), 16f, ui.pal.err, bold = true))
                addView(ui.muted(s(R.string.ui_diag_crash_body), 13f))
                addView(ui.text(crash.take(1500), 11.5f, ui.pal.muted, mono = true, selectable = true))
                addView(ui.row(8) {
                    addView(ui.button(s(R.string.ui_share), Ui.Kind.GHOST) { FileActions.shareText(activity, crash) }, LinearLayout.LayoutParams(0, WRAP, 1f))
                    addView(ui.button(s(R.string.ui_dismiss), Ui.Kind.PRIMARY) { CrashHandler.dismiss(activity); host.rebuild() }, LinearLayout.LayoutParams(0, WRAP, 1f))
                })
            })
        }
        c.group(s(R.string.ui_diag_app)) {
            link(s(R.string.ui_diag_version), null, "info", versionName(), {})
            link(s(R.string.ui_diag_android), null, "cpu", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})") {}
            link(s(R.string.ui_diag_device), null, "monitor", "${Build.MANUFACTURER} ${Build.MODEL}") {}
            val secretsOk = app.secretStore.lostSecrets().isEmpty()
            link(s(R.string.ui_diag_secure), s(R.string.ui_diag_secure_sub), "lock", if (secretsOk) s(R.string.ui_ok) else s(R.string.ui_diag_secure_lost)) {}
        }
        c.group(s(R.string.ui_diag_verified), s(R.string.ui_diag_verified_note)) {
            link(s(R.string.ui_diag_tested), s(R.string.ui_diag_tested_body), "check-circle") {}
            link(s(R.string.ui_diag_untested), s(R.string.ui_diag_untested_body), "alert-triangle") {}
        }
        c.addView(ui.sectionHeader(s(R.string.ui_diag_log)))
        val logView = ui.text(s(R.string.ui_diag_loading), 11.5f, ui.pal.muted, mono = true, selectable = true)
        c.addView(ui.card(12, 12, 8) { addView(logView) })
        c.addView(ui.row(8) {
            addView(ui.button(s(R.string.ui_copy), Ui.Kind.GHOST) { copy(logView.text.toString()) }, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(ui.button(s(R.string.ui_share), Ui.Kind.GHOST) { FileActions.shareText(activity, logView.text.toString()) }, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(ui.button(s(R.string.ui_diag_clear), Ui.Kind.DANGER) { app.appLog.clear(); logView.text = s(R.string.ui_diag_empty) }, LinearLayout.LayoutParams(0, WRAP, 1f))
        })
        c.paragraph(s(R.string.ui_diag_log_note))
        viewScope.launch {
            val tail = withContext(Dispatchers.IO) { app.appLog.readTail(24 * 1024) }
            logView.text = tail.ifBlank { s(R.string.ui_diag_empty) }
        }
    }

    private fun copy(text: String) {
        val cm = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("JARVIS log", text))
        Toast.makeText(activity, s(R.string.ui_copied), Toast.LENGTH_SHORT).show()
    }
}

/** About: version, what leaves the phone, and the licenses of what JARVIS is built on. */
class AboutPage : SettingsPage() {
    override val id = "settings:about"
    override fun title() = s(R.string.ui_set_about)

    override fun fill(c: LinearLayout) {
        c.addView(ui.card(16, 16, 6) {
            addView(ui.text(s(R.string.app_name), 22f, bold = true))
            addView(ui.muted(try { activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "" } catch (e: Exception) { "" }, 13f))
            addView(ui.text(s(R.string.ui_about_what), 14.5f))
        })
        c.group(s(R.string.ui_about_privacy)) {
            link(s(R.string.ui_about_p_ai), s(R.string.ui_about_p_ai_body), "cloud") {}
            link(s(R.string.ui_about_p_speech), s(R.string.ui_about_p_speech_body), "mic") {}
            link(s(R.string.ui_about_p_web), s(R.string.ui_about_p_web_body), "globe") {}
            link(s(R.string.ui_about_p_local), s(R.string.ui_about_p_local_body), "lock") {}
            link(s(R.string.ui_about_p_none), s(R.string.ui_about_p_none_body), "shield") {}
        }
        c.group(s(R.string.ui_about_licenses)) {
            link("Feather icons", s(R.string.ui_about_l_feather), "star") {}
            link("Kotlin and kotlinx.coroutines", s(R.string.ui_about_l_kotlin), "code") {}
            link("ONNX Runtime", s(R.string.ui_about_l_ort), "cpu") {}
            link("openWakeWord", s(R.string.ui_about_l_oww), "mic") {}
            link("Open-Meteo", s(R.string.ui_about_l_meteo), "cloud") {}
        }
        c.paragraph(s(R.string.ui_about_marks))
    }
}
