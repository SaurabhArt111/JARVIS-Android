package com.jarvis.assistant.lock

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.jarvis.assistant.platform.AppForeground
import com.jarvis.assistant.platform.IslandState
import com.jarvis.assistant.platform.Intents
import com.jarvis.assistant.platform.NotificationHelper
import com.jarvis.core.model.Requirement
import com.jarvis.core.ports.PermissionChecker
import com.jarvis.core.util.JarvisLog

/**
 * Opens the lock-screen assistant when something other than the user's tap asks for it (for example a confirmation question after "Hey JARVIS" on a locked phone).
 *
 * Android 10+ forbids starting an activity from the background, so there are three honest routes, tried in this order:
 *  1. JARVIS already has a window on screen (its own activity, or the island overlay with "Display over other apps" allowed): start the activity directly;
 *  2. a notification with a full-screen intent, which Android shows over the lock screen where the user has allowed full-screen alerts;
 *  3. a heads-up notification the user taps.
 * The result says which one was used, so callers never assume the screen came up.
 */
class LockScreenLauncher(
    context: Context,
    private val notifications: NotificationHelper,
    private val permissions: PermissionChecker,
    private val log: JarvisLog = JarvisLog.None,
) {
    enum class How { ACTIVITY, FULL_SCREEN, NOTIFICATION, SKIPPED }

    private val ctx = context.applicationContext

    private fun intent(listen: Boolean, turnScreenOn: Boolean) = Intent().setClassName(ctx.packageName, Intents.LOCK_ACTIVITY)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra(com.jarvis.assistant.ui.AssistantActivity.EXTRA_LISTEN, listen)
        .putExtra(Intents.EXTRA_TURN_SCREEN_ON, turnScreenOn)

    fun show(listen: Boolean = false, turnScreenOn: Boolean = false): How {
        val i = intent(listen, turnScreenOn)
        val haveWindow = AppForeground.activityVisible || (IslandState.running && permissions.isGranted(Requirement.OVERLAY))
        if (haveWindow) {
            try { ctx.startActivity(i); return How.ACTIVITY } catch (e: Exception) { log.w("Lock", "direct start refused (${e.javaClass.simpleName}); using a notification") }
        }
        val pi = PendingIntent.getActivity(ctx, NotificationHelper.ID_LOCK, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val fullScreen = Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent()
        return if (notifications.postLockAssistant(pi, fullScreen)) (if (fullScreen) How.FULL_SCREEN else How.NOTIFICATION) else How.SKIPPED
    }
}
