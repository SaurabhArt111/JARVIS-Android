package com.jarvis.assistant.platform

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.jarvis.core.util.JarvisLog

/**
 * "Swiped out of recents" safety net. On stock Android a foreground service survives that swipe. Some phones (MIUI/HyperOS, ColorOS, Funtouch, One UI with
 * "sleeping apps") kill the whole process instead, which silently ended wake-word listening and the island.
 *
 * The service therefore asks the system alarm service - which lives outside our process and survives a kill - to start it again a moment later. If the service
 * is still alive the alarm is a harmless no-op ([ACTION_REVIVE] is ignored by a running service). If the process was killed, the restart is attempted; when
 * Android refuses a background start, the service's own refusal path posts the "tap to resume" notification, so the user is never left with a silent failure.
 * An OEM "force stop" (or the user's Stop button) cancels alarms too, and that is the right outcome: we cannot and should not override it.
 */
object KeepAlive {
    const val ACTION_REVIVE = "${Intents.PKG}.action.REVIVE"
    private const val DELAY_MS = 2_500L

    private fun pending(ctx: Context, service: Class<*>, requestCode: Int, action: String): PendingIntent {
        val i = Intent(ctx, service).setAction(action)
        return PendingIntent.getForegroundService(ctx, requestCode, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Arms a one-shot restart of [service] with [action] (inexact, no special permission). */
    fun arm(ctx: Context, service: Class<*>, requestCode: Int, action: String, log: JarvisLog = JarvisLog.None) {
        try {
            val am = ctx.getSystemService(AlarmManager::class.java) ?: return
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + DELAY_MS, pending(ctx, service, requestCode, action))
            log.i("KeepAlive", "restart armed for ${service.simpleName}")
        } catch (e: Exception) {
            log.w("KeepAlive", "could not arm a restart (${e.javaClass.simpleName})")
        }
    }

    /** Cancels a restart armed by [arm] (the user stopped the feature on purpose). */
    fun disarm(ctx: Context, service: Class<*>, requestCode: Int, action: String) {
        try { ctx.getSystemService(AlarmManager::class.java)?.cancel(pending(ctx, service, requestCode, action)) } catch (_: Exception) { }
    }
}
