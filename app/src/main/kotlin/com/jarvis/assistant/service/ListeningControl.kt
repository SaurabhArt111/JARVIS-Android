package com.jarvis.assistant.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.jarvis.assistant.platform.Intents
import com.jarvis.assistant.platform.ListeningState
import com.jarvis.core.settings.Keys

/** Start / stop helpers for the listening foreground service, shared by the UI, the tile and the boot prompt. */
object ListeningControl {
    sealed class StartResult {
        object Started : StartResult()
        data class Refused(val reason: String) : StartResult()
    }

    fun isRunning(): Boolean = ListeningState.running

    private fun intent(ctx: Context, action: String) = Intent().setComponent(ComponentName(ctx.packageName, Intents.LISTENING_SERVICE)).setAction(action)

    /**
     * Asks Android to start the microphone service. Android 12+ refuses this unless the app is visible (or exempt), and Android 14+ also needs the
     * microphone permission at that moment, so the call is wrapped and the reason is returned instead of throwing. "Started" means the request was accepted:
     * whether the service is really running is [isRunning], set by the service itself.
     */
    fun start(ctx: Context): StartResult = try {
        ctx.startForegroundService(intent(ctx, Intents.ACTION_LISTEN_START))
        StartResult.Started
    } catch (e: SecurityException) {
        StartResult.Refused("Android didn't allow the microphone service (${e.javaClass.simpleName}). Check the microphone permission.")
    } catch (e: IllegalStateException) { // includes ForegroundServiceStartNotAllowedException (API 31+)
        StartResult.Refused("Android only lets an app start the microphone while it is on screen. Open JARVIS and try again.")
    }

    /** Stops the service and records that the user does not want it. */
    fun stop(ctx: Context, settings: com.jarvis.core.settings.Settings) {
        settings.setFlag(Keys.BACKGROUND_LISTENING, false)
        try { ctx.stopService(intent(ctx, Intents.ACTION_LISTEN_STOP)) } catch (_: Exception) { }
    }
}
