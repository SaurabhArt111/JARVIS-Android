package com.jarvis.assistant.platform

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.jarvis.core.model.Requirement
import com.jarvis.core.ports.ActionResult
import com.jarvis.core.ports.AssistantControl
import com.jarvis.core.ports.PermState
import com.jarvis.core.ports.PermissionChecker
import com.jarvis.core.settings.Keys
import com.jarvis.core.settings.Settings
import com.jarvis.core.util.JarvisLog

/** What the "jarvis" tool can do to the app itself: open one of its screens, switch background listening on or off. Results say what really happened. */
class AppAssistantControl(
    context: Context,
    private val settings: Settings,
    private val permissions: PermissionChecker,
    private val starter: ActivityStarter,
    private val notifications: NotificationHelper,
    private val log: JarvisLog = JarvisLog.None,
) : AssistantControl {
    private val ctx = context.applicationContext

    private val pages = listOf("chat", "library", "reminders", "notes", "settings", "permissions", "capabilities", "island", "help")
    override fun pages(): List<String> = pages

    override fun openPage(page: String): ActionResult {
        val key = page.lowercase().trim().removeSuffix(" screen").removeSuffix(" page").let { if (it == "files") "library" else if (it == "home" || it == "main") "chat" else it }
        if (key !in pages) return ActionResult.fail("I don't have a “$page” screen. I can open: ${pages.joinToString(", ")}.")
        val i = Intent().setComponent(ComponentName(ctx.packageName, Intents.MAIN_ACTIVITY)).putExtra(Intents.EXTRA_OPEN_PAGE, key)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val r = starter.start(i, "JARVIS's $key screen", "Opened the $key screen.")
        return if (r.ok) r.copy(verified = false) else r
    }

    private fun serviceIntent(action: String) = Intent().setComponent(ComponentName(ctx.packageName, Intents.LISTENING_SERVICE)).setAction(action)

    override fun setBackgroundListening(on: Boolean): ActionResult {
        if (!on) {
            settings.setFlag(Keys.BACKGROUND_LISTENING, false)
            try { ctx.stopService(serviceIntent(Intents.ACTION_LISTEN_STOP)) } catch (e: Exception) { log.w("Listening", "stopService failed (${e.javaClass.simpleName})") }
            val stopped = waitFor { !ListeningState.running }
            return if (stopped) ActionResult.ok("Background listening is off.", verified = true)
            else ActionResult(false, "I turned the setting off, but the listening service still reports running.", verified = true)
        }
        if (permissions.state(Requirement.MICROPHONE) != PermState.GRANTED) return ActionResult.fail("I need microphone permission before I can listen for the wake word.", Requirement.MICROPHONE)
        settings.setFlag(Keys.BACKGROUND_LISTENING, true)
        if (ListeningState.running) return ActionResult.ok("Background listening is already on.", verified = true)
        // Since Android 12/14 a microphone foreground service may only be started while the app is on screen.
        if (!AppForeground.activityVisible) {
            val posted = notifications.postResumeListening()
            return ActionResult(false, "Android only lets an app start the microphone while it's on screen. I saved that you want background listening" +
                (if (posted) " and put a notification up — tap it to start." else " — open JARVIS and I'll start it."))
        }
        return try {
            ctx.startForegroundService(serviceIntent(Intents.ACTION_LISTEN_START))
            if (waitFor(2500) { ListeningState.running }) ActionResult.ok("Background listening is on.", verified = true)
            else ActionResult(false, "I asked to start background listening, but the service didn't report running. Check the Background listening guide in Settings.", verified = true)
        } catch (e: Exception) {
            log.w("Listening", "could not start the listening service (${e.javaClass.simpleName})")
            ActionResult.fail("Android refused to start background listening right now (${e.javaClass.simpleName}).")
        }
    }

    private fun waitFor(timeoutMs: Long = 1500, cond: () -> Boolean): Boolean {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) { if (cond()) return true; Thread.sleep(60) }
        return cond()
    }
}
