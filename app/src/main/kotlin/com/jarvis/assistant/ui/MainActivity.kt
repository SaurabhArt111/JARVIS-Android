package com.jarvis.assistant.ui

import android.content.Intent
import android.os.Bundle
import com.jarvis.assistant.R
import com.jarvis.assistant.platform.Intents
import com.jarvis.assistant.ui.chat.ChatScreen
import com.jarvis.assistant.ui.kit.BaseActivity
import com.jarvis.assistant.ui.kit.Dialogs
import com.jarvis.assistant.ui.kit.PageOpener
import com.jarvis.assistant.ui.kit.ScreenHost
import com.jarvis.core.model.Origin

/**
 * The launcher activity: a bottom bar with Chat / Library / Reminders / Settings, and a back stack for the pages opened from them (permissions, capabilities,
 * settings sections). Everything works without the background service or the overlay. Handles notification taps and "open the X screen" requests
 * (EXTRA_OPEN_PAGE), the "tap to resume listening" notification (EXTRA_RESUME_LISTENING) and text shared from other apps (put in the input box, never sent).
 */
class MainActivity : BaseActivity(), PageOpener {
    private lateinit var host: ScreenHost
    private var resumeListening = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tabs = listOf(
            ScreenHost.Tab("chat", getString(R.string.ui_tab_chat), "message-circle"),
            ScreenHost.Tab("library", getString(R.string.ui_tab_library), "folder"),
            ScreenHost.Tab("reminders", getString(R.string.ui_tab_reminders), "bell"),
            ScreenHost.Tab("settings", getString(R.string.ui_tab_settings), "settings"),
        )
        host = ScreenHost(this, tabs) { Screens.create(it) }
        setContentView(host.root)
        setBackEnabled(true)
        val saved = savedInstanceState?.getStringArrayList(STACK)
        if (saved == null || !host.restore(saved)) host.openTab("chat")
        if (savedInstanceState == null) {
            if (!container.settings.flag(com.jarvis.core.settings.Keys.ONBOARDED, false)) Screens.create("onboarding")?.let { host.push(it) }
            handle(intent)
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handle(intent) }

    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); outState.putStringArrayList(STACK, host.saveIds()) }

    override fun onResume() {
        super.onResume()
        host.onActivityResumed()
        if (resumeListening) { resumeListening = false; window.decorView.post { startListeningFromForeground() } }
    }

    override fun onThemeOrSizeChanged() { host.rebuild() }

    override fun onBackRequested(): Boolean {
        if (host.back()) return true
        // a tab other than Chat: go to Chat first, like most tabbed apps
        if (host.current?.id != "chat") { host.openTab("chat"); return true }
        return false
    }

    /** At the root the app goes to the background instead of closing, so a running request or listening session is not interrupted. */
    override fun onBackAtRoot() { moveTaskToBack(true) }

    // ------------------------------------------------------------------------------------------------ intents
    private fun handle(i: Intent?) {
        i ?: return
        when {
            i.getBooleanExtra(Intents.EXTRA_RESUME_LISTENING, false) -> { i.removeExtra(Intents.EXTRA_RESUME_LISTENING); resumeListening = true }
            i.hasExtra(Intents.EXTRA_OPEN_PAGE) -> { openPage(i.getStringExtra(Intents.EXTRA_OPEN_PAGE).orEmpty()); i.removeExtra(Intents.EXTRA_OPEN_PAGE) }
            i.hasExtra(Intents.EXTRA_REMINDER_ID) -> { openPage("reminders"); i.removeExtra(Intents.EXTRA_REMINDER_ID) }
            i.action == Intent.ACTION_SEND && i.type?.startsWith("text/") == true -> {
                val text = i.getStringExtra(Intent.EXTRA_TEXT)?.take(4000).orEmpty()
                openPage("chat")
                (host.current as? ChatScreen)?.prefill(text)
                i.action = null
            }
        }
    }

    override fun openPage(page: String) {
        val id = Screens.idForPage(page) ?: return
        if (id in Screens.TABS) {
            if (page.lowercase().trim() == "notes") com.jarvis.assistant.ui.reminders.RemindersState.requestNotesTab()
            host.openTab(id)
        } else {
            if (host.current?.id == id) return
            val s = Screens.create(id) ?: return
            host.push(s)
        }
    }

    private fun startListeningFromForeground() {
        val r = container.services.control.setBackgroundListening(true)
        if (!r.ok) Dialogs.message(this, ui, getString(R.string.ui_bg_listening_title), r.message)
    }

    companion object { private const val STACK = "stack" }
}
