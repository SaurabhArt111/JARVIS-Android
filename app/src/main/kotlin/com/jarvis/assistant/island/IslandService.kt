package com.jarvis.assistant.island

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Display
import android.view.WindowManager
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.di.AppContainer
import com.jarvis.assistant.platform.AppForeground
import com.jarvis.assistant.platform.IslandState
import com.jarvis.assistant.platform.Intents
import com.jarvis.assistant.platform.KeepAlive
import com.jarvis.assistant.platform.NotificationHelper
import com.jarvis.core.settings.Keys

/**
 * Owns the island overlay. It is a foreground service (type "specialUse", declared with its explanation in the manifest) because Android treats an
 * app with a plain background service as idle after a short while, which would remove the island; the ongoing notification is minimal-importance and has
 * a "Turn off" button. Without "Display over other apps" it does not pretend: it posts a notification that opens the permissions screen and stops.
 *
 * Start it only from the visible app (Settings does, through IslandSwitch) or from the boot receiver: Android 12+ refuses to start foreground services from the background otherwise.
 */
class IslandService : Service() {
    private lateinit var c: AppContainer
    private var controller: IslandController? = null
    private var foregroundStarted = false

    private companion object { const val REVIVE_CODE = 4402 }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        c = JarvisApp.container
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A service started with startForegroundService() must call startForeground() promptly, even if it then stops itself.
        if (!enterForeground()) { stopSelf(); return START_NOT_STICKY }
        when (intent?.action) {
            Intents.ACTION_ISLAND_STOP -> {
                KeepAlive.disarm(this, IslandService::class.java, REVIVE_CODE, Intents.ACTION_ISLAND_START)
                c.settings.setFlag(Keys.ISLAND_ENABLED, false); shutDown(); return START_NOT_STICKY
            }
        }
        if (!Settings.canDrawOverlays(this)) {
            c.appLog.w("Island", "no overlay permission; not showing the island")
            c.notificationHelper.postIslandNeedsOverlay()
            shutDown()
            return START_NOT_STICKY
        }
        val ctl = controller ?: createController().also { controller = it; it.start() }
        IslandState.running = true
        when (intent?.action) {
            Intents.ACTION_ISLAND_PREVIEW -> ctl.preview()
            else -> ctl.relayout() // START: pick up changed settings
        }
        return START_STICKY
    }

    /** Swiped out of recents: stock Android keeps this service; OEM task killers do not. Ask the alarm service to bring the island back (see [KeepAlive]). */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (c.settings.flag(Keys.ISLAND_ENABLED, false)) KeepAlive.arm(this, IslandService::class.java, REVIVE_CODE, Intents.ACTION_ISLAND_START, c.appLog)
        super.onTaskRemoved(rootIntent)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        controller?.relayout() // rotation, fold, density, split-screen
    }

    override fun onDestroy() {
        controller?.stop()
        controller = null
        IslandState.running = false
        AppForeground.overlayShown = false
        super.onDestroy()
    }

    private fun createController(): IslandController {
        val window: Context = if (Build.VERSION.SDK_INT >= 30) {
            val dm = getSystemService(DisplayManager::class.java)
            val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
            createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } else this
        return IslandController(this, c, window)
    }

    private fun enterForeground(): Boolean {
        if (foregroundStarted) return true
        val stop = PendingIntent.getService(this, 11, Intent(this, IslandService::class.java).setAction(Intents.ACTION_ISLAND_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n: Notification = c.notificationHelper.islandNotification(listOf(Notification.Action.Builder(null, getString(R.string.island_stop), stop).build()))
        return try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NotificationHelper.ID_ISLAND, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NotificationHelper.ID_ISLAND, n)
            foregroundStarted = true
            true
        } catch (e: Exception) {
            c.appLog.w("Island", "startForeground refused (${e.javaClass.simpleName})")
            false
        }
    }

    private fun shutDown() {
        controller?.stop(); controller = null
        IslandState.running = false
        if (foregroundStarted) { stopForeground(STOP_FOREGROUND_REMOVE); foregroundStarted = false }
        stopSelf()
    }
}
