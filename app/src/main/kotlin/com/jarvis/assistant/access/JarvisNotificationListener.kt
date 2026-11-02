package com.jarvis.assistant.access

import android.app.NotificationManager
import android.content.ComponentName
import android.media.AudioManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.di.AppContainer
import com.jarvis.core.notify.NotificationRules
import com.jarvis.core.ports.NotificationInfo
import com.jarvis.core.ports.SpeechChannel
import com.jarvis.core.settings.Keys
import kotlinx.coroutines.launch

/**
 * Notification access. The user grants it on a system screen (Settings > Notifications > Device & app notifications), and JARVIS additionally keeps its own
 * switch ([Keys.NOTIF_INTEGRATION]): while that is off this service reads nothing, keeps nothing, and the gateway reports "off".
 *
 * What it does with what it sees:
 *  - keeps the last [NotificationStore.MAX] notifications in memory (never on disk), so "read my notifications" and "reply to that" work;
 *  - optionally reads new messages and calls aloud ([Keys.READ_NOTIFICATIONS_ALOUD]) following [NotificationRules.announcement] and the lock-screen privacy level;
 *  - provides the component Android requires before an app may read other apps' media sessions (now playing, media keys).
 * It never reads notifications of its own app, group summaries, or ongoing ones aloud, and never sends anything anywhere.
 */
class JarvisNotificationListener : NotificationListenerService() {
    private lateinit var c: AppContainer
    private lateinit var mapper: NotificationMapper
    private var gateway: AndroidNotificationGateway? = null
    @Volatile private var lastAnnouncementMs = 0L

    companion object {
        @Volatile var instance: JarvisNotificationListener? = null
            private set

        /** Fills the store from the system's own list when it has not been filled yet (for example when the user switched the integration on after connecting). */
        fun ensureSeeded() { instance?.takeIf { !NotificationStore.seeded }?.seed() }

        fun cancel(key: String): Boolean = try { instance?.cancelNotification(key) != null } catch (_: Exception) { false }
    }

    override fun onCreate() {
        super.onCreate()
        c = JarvisApp.container
        mapper = NotificationMapper(this)
    }

    private fun enabled() = c.settings.flag(Keys.NOTIF_INTEGRATION, false)

    override fun onListenerConnected() {
        instance = this
        val g = AndroidNotificationGateway(applicationContext, c.settings, c.permissions, c.appLog)
        gateway = g
        c.attachNotificationGateway(g)
        if (enabled()) seed()
        c.appLog.i("Notifications", "listener connected (integration ${if (enabled()) "on" else "off"})")
    }

    override fun onListenerDisconnected() {
        if (instance === this) {
            instance = null
            c.attachNotificationGateway(null)
        }
        NotificationStore.clear()
        c.appLog.i("Notifications", "listener disconnected")
        // Android may disconnect a listener when it is low on memory; asking to be rebound is the documented way back, and it is harmless if access was revoked.
        try { requestRebind(ComponentName(this, JarvisNotificationListener::class.java)) } catch (_: Exception) { }
    }

    fun seed() {
        try {
            val active = activeNotifications ?: return
            NotificationStore.clear()
            for (sbn in active) mapper.map(sbn)?.let { if (sbn.packageName != packageName) NotificationStore.put(it) }
            NotificationStore.seeded = true
        } catch (e: SecurityException) {
            c.appLog.w("Notifications", "access was revoked while reading the list")
        } catch (e: Exception) {
            c.appLog.w("Notifications", "could not read the active list (${e.javaClass.simpleName})")
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || !enabled() || sbn.packageName == packageName) return
        try {
            val stored = mapper.map(sbn) ?: return
            val previous = NotificationStore.info(stored.info.key)
            NotificationStore.put(stored)
            // Apps re-post the same notification for progress or grouping; only genuinely new text is worth saying.
            val changed = previous == null || previous.text != stored.info.text || previous.title != stored.info.title
            if (changed) announce(stored.info)
        } catch (e: Exception) {
            c.appLog.w("Notifications", "ignored a notification (${e.javaClass.simpleName})")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn != null) NotificationStore.remove(sbn.key)
    }

    private fun announce(info: NotificationInfo) {
        val s = c.settings
        if (!s.flag(Keys.READ_NOTIFICATIONS_ALOUD, false)) return
        // Never talk over a silenced phone or a Do Not Disturb period: the user asked for quiet.
        val audio = getSystemService(AudioManager::class.java)
        if (audio != null && audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm != null && nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) return
        if (c.speaker.isSpeaking()) return
        val policy = c.lockPolicy
        val privacy = policy.privacy()
        val now = System.currentTimeMillis()
        val ctx = NotificationRules.AnnounceContext(
            aloud = true,
            lockProtected = policy.effectivelyLocked(c.lockState.read()),
            lockReadAllowed = NotificationRules.lockReadAllowed(s.flag(Keys.LOCK_MODE_ENABLED, false), privacy, s.flag(Keys.LOCK_READ_NOTIFICATIONS, false)),
            privacy = privacy, nowMs = now, lastAnnouncementMs = lastAnnouncementMs, ownPackage = packageName,
        )
        val say = NotificationRules.announcement(info, ctx) ?: return
        lastAnnouncementMs = now
        c.scope.launch { c.speaker.speak(say, SpeechChannel.SYSTEM) }
    }
}
