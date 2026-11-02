package com.jarvis.assistant.ui

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Outline
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import com.jarvis.assistant.platform.Intents
import com.jarvis.assistant.ui.chat.ChatScreen
import com.jarvis.assistant.ui.chat.SheetHooks
import com.jarvis.assistant.ui.kit.BaseActivity
import com.jarvis.assistant.ui.kit.MATCH
import com.jarvis.assistant.ui.kit.PageOpener
import com.jarvis.assistant.ui.kit.ScreenHost
import com.jarvis.core.model.Requirement
import com.jarvis.core.ports.PermState

/**
 * The compact chat that opens when JARVIS is the default assistant and the user long-presses Home (or uses the assistant gesture), and from the Quick Settings tile.
 * It is the same chat as the app (same history, same assistant), shown as a sheet over whatever was on screen; tapping outside or pressing back closes it.
 *
 * Locked phone: if the lock screen is secure, this activity hands over to the lock-screen assistant instead (which applies the lock-screen privacy rules) and
 * never shows chat history over the keyguard.
 */
class AssistantActivity : BaseActivity(), SheetHooks, PageOpener {
    private lateinit var host: ScreenHost

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (handOverToLockScreen()) { finish(); return }

        val screenH = resources.displayMetrics.heightPixels
        val sheet = FrameLayout(this).apply {
            val radius = ui.dpf(24f)
            background = ui.rounded(ui.pal.bg, 0)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height + radius.toInt(), radius) }
            }
            isClickable = true   // taps inside the sheet must not reach the scrim
        }
        host = ScreenHost(this, emptyList()) { if (it == "chat") ChatScreen(this) else null }
        host.topInsetEnabled = false
        sheet.addView(host.root, FrameLayout.LayoutParams(MATCH, MATCH))

        val scrim = FrameLayout(this).apply {
            setBackgroundColor(0x66000000)
            setOnClickListener { finish() }
            addView(sheet, FrameLayout.LayoutParams(MATCH, (screenH * 0.78f).toInt(), Gravity.BOTTOM))
        }
        setContentView(scrim)
        setBackEnabled(true)
        host.openTab("chat")
        if (intent.getBooleanExtra(EXTRA_LISTEN, false) && container.permissions.state(Requirement.MICROPHONE) == PermState.GRANTED) {
            window.decorView.post { (host.current as? ChatScreen)?.startListeningNow() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_LISTEN, false) && container.permissions.state(Requirement.MICROPHONE) == PermState.GRANTED) (host.current as? ChatScreen)?.startListeningNow()
    }

    override fun onResume() { super.onResume(); if (::host.isInitialized) host.onActivityResumed() }
    override fun onThemeOrSizeChanged() { if (::host.isInitialized) host.rebuild() }
    override fun onBackRequested(): Boolean = ::host.isInitialized && host.back()
    override fun onStop() { super.onStop(); if (!isChangingConfigurations) finish() }   // a transient sheet: leaving it closes it

    // ---- SheetHooks
    override fun expand() { startActivity(Intent().setComponent(ComponentName(packageName, Intents.MAIN_ACTIVITY)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)); finish() }
    override fun close() { finish() }

    // ---- PageOpener: any "open the settings page" request from inside the sheet goes to the full app
    override fun openPage(page: String) {
        startActivity(Intent().setComponent(ComponentName(packageName, Intents.MAIN_ACTIVITY)).putExtra(Intents.EXTRA_OPEN_PAGE, page).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    /** Secure keyguard showing: use the lock-screen assistant (written separately, found by class name) so the lock-screen privacy rules apply. */
    private fun handOverToLockScreen(): Boolean {
        val km = getSystemService(KeyguardManager::class.java) ?: return false
        if (!(km.isKeyguardLocked && km.isDeviceSecure)) return false
        val i = Intent().setClassName(packageName, LOCK_ACTIVITY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtras(intent.extras ?: Bundle())
        if (packageManager.resolveActivity(i, 0) == null) return false
        return try { startActivity(i); true } catch (e: Exception) { false }
    }

    companion object {
        const val EXTRA_LISTEN = "com.jarvis.assistant.extra.LISTEN"
        const val LOCK_ACTIVITY = "com.jarvis.assistant.lock.LockScreenActivity"
    }
}
