package com.jarvis.assistant.platform

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.jarvis.core.model.Requirement
import com.jarvis.core.ports.PermState
import com.jarvis.core.settings.Keys
import com.jarvis.core.settings.Settings
import com.jarvis.core.util.JarvisLog

/**
 * Starts and stops the Dynamic-Island overlay service to match the setting. The overlay needs "Display over other apps"; without it nothing is started and the
 * caller is told, so the Settings switch never shows "on" for something that is not running. Called from the foreground UI (Android allows starting the service there).
 */
class IslandSwitch(context: Context, private val settings: Settings, private val permissions: PermissionHelper, private val log: JarvisLog = JarvisLog.None) {
    private val ctx = context.applicationContext

    enum class Result { STARTED, STOPPED, NEEDS_OVERLAY, FAILED }

    private fun intent(action: String) = Intent().setComponent(ComponentName(ctx.packageName, Intents.ISLAND_SERVICE)).setAction(action)

    /** Makes reality match the setting. */
    fun sync(): Result {
        if (!settings.flag(Keys.ISLAND_ENABLED, false)) {
            try { ctx.stopService(intent(Intents.ACTION_ISLAND_STOP)) } catch (e: Exception) { log.w("Island", "stopService failed (${e.javaClass.simpleName})") }
            return Result.STOPPED
        }
        if (permissions.state(Requirement.OVERLAY) != PermState.GRANTED) return Result.NEEDS_OVERLAY
        return try { ctx.startForegroundService(intent(Intents.ACTION_ISLAND_START)); Result.STARTED }
        catch (e: Exception) { log.w("Island", "could not start the island (${e.javaClass.simpleName})"); Result.FAILED }
    }

    /** Shows a short demo on the island (or does nothing if it is not running). */
    fun preview(): Boolean = try { ctx.startForegroundService(intent(Intents.ACTION_ISLAND_PREVIEW)); true } catch (e: Exception) { false }
}
