package com.jarvis.assistant.platform

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings as AndroidSettings
import com.jarvis.core.model.Requirement
import com.jarvis.core.ports.ActionResult
import com.jarvis.core.util.JarvisLog

/**
 * The single place that starts another app's screen. Why it exists: since Android 10 an app that is NOT in the foreground may not start activities (the call
 * silently does nothing, no exception). Claiming "Opening Spotify" in that case would be a lie, so before starting we check whether JARVIS is allowed to, and if it
 * is not we say so and post a notification the user can TAP (a tap is always allowed to open things).
 *
 * Allowed when: one of JARVIS's activities is visible; or (Android 14+) the island overlay is on screen AND "Display over other apps" is granted;
 * or (Android 10-13) "Display over other apps" is granted.
 * Not covered: sessions started through the system assistant gesture, where Android itself allows the start - those have an activity visible anyway.
 */
class ActivityStarter(context: Context, private val notifications: NotificationHelper, private val log: JarvisLog = JarvisLog.None) {
    private val ctx = context.applicationContext

    fun canStartFromHere(): Boolean {
        if (AppForeground.activityVisible) return true
        val overlay = AndroidSettings.canDrawOverlays(ctx)
        return overlay && (Build.VERSION.SDK_INT < 34 || AppForeground.overlayShown)
    }

    fun resolves(intent: Intent): Boolean = try { intent.resolveActivity(ctx.packageManager) != null } catch (_: Exception) { false }

    /**
     * Starts [intent]. [label] is what the user called it ("Spotify", "the dialer"); [okMessage] is returned on success (worded as "asked Android to...",
     * because Android does not confirm that the screen really appeared). verified = the intent resolved to an activity before starting.
     */
    fun start(intent: Intent, label: String, okMessage: String): ActionResult {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val found = resolves(intent)
        if (!found) return ActionResult.fail("No app on this phone can open $label.")
        if (!canStartFromHere()) {
            val posted = notifications.postTapToOpen(label, intent)
            log.i("Launch", "background start not allowed — tap-to-open notification ${if (posted) "posted" else "could not be posted"}")
            val why = "Android doesn't let JARVIS open $label while it's in the background."
            return if (posted) ActionResult(false, "$why I've put a notification on screen — tap it to open $label. (Allowing \"Display over other apps\" and keeping the island on lets me do it directly.)", listOf(Requirement.OVERLAY))
            else ActionResult(false, "$why Open JARVIS first, or allow \"Display over other apps\".", listOf(Requirement.OVERLAY))
        }
        return try {
            ctx.startActivity(intent)
            ActionResult(true, okMessage, verified = false)
        } catch (e: ActivityNotFoundException) {
            ActionResult.fail("No app on this phone can open $label.")
        } catch (e: SecurityException) {
            log.w("Launch", "start refused (SecurityException)")
            ActionResult.fail("Android refused to open $label.")
        } catch (e: Exception) {
            log.w("Launch", "start failed (${e.javaClass.simpleName})")
            ActionResult.fail("I couldn't open $label (${e.javaClass.simpleName}).")
        }
    }
}
