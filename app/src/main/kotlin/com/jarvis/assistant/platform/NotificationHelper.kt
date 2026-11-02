package com.jarvis.assistant.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.jarvis.assistant.R
import com.jarvis.assistant.receivers.ReminderActionReceiver
import com.jarvis.core.lock.LockPrivacy
import com.jarvis.core.ports.Reminder
import com.jarvis.core.settings.Keys
import com.jarvis.core.settings.Settings
import com.jarvis.core.util.JarvisLog

/**
 * Notification channels and the notifications JARVIS posts itself. POST_NOTIFICATIONS aware: [post] returns false (and logs) when the user has not allowed
 * notifications, so callers can fall back to speech / in-app display instead of assuming the user saw something.
 *
 * Privacy: with lock-screen privacy STRICT or BALANCED the lock screen shows only "You have a reminder" (notification visibility PRIVATE + a generic public version).
 */
class NotificationHelper(context: Context, private val settings: Settings, private val log: JarvisLog = JarvisLog.None) {
    private val ctx = context.applicationContext
    private val nm: NotificationManager = ctx.getSystemService(NotificationManager::class.java)

    companion object {
        const val CH_REMINDERS = "jarvis_reminders"
        const val CH_LISTENING = "jarvis_listening"
        const val CH_RESULTS = "jarvis_results"
        const val CH_TIMERS = "jarvis_timers"
        const val CH_ISLAND = "jarvis_island"
        const val CH_LOCK = "jarvis_lock"
        const val ID_LISTENING = 4001
        const val ID_RESUME = 4002
        const val ID_OPEN_APP = 4003
        const val ID_ISLAND = 4004
        const val ID_ISLAND_PERMISSION = 4005
        const val ID_LOCK = 4006
        const val ID_TIMER_BASE = 5000
        private const val ID_REMINDER_BASE = 100_000
        fun reminderNotificationId(reminderId: String) = ID_REMINDER_BASE + (reminderId.hashCode() and 0x0FFFFF)
        fun timerNotificationId(timerId: String) = ID_TIMER_BASE + (timerId.hashCode() and 0x0FFF)
    }

    init { ensureChannels() }

    fun ensureChannels() {
        fun ch(id: String, name: Int, desc: Int, importance: Int, configure: NotificationChannel.() -> Unit = {}) =
            NotificationChannel(id, ctx.getString(name), importance).apply { description = ctx.getString(desc); configure() }
        nm.createNotificationChannels(listOf(
            ch(CH_REMINDERS, R.string.notif_channel_reminders, R.string.notif_channel_reminders_desc, NotificationManager.IMPORTANCE_HIGH) {
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                enableVibration(true)
            },
            ch(CH_LISTENING, R.string.notif_channel_listening, R.string.notif_channel_listening_desc, NotificationManager.IMPORTANCE_LOW) {
                setShowBadge(false); lockscreenVisibility = Notification.VISIBILITY_SECRET
            },
            ch(CH_RESULTS, R.string.notif_channel_results, R.string.notif_channel_results_desc, NotificationManager.IMPORTANCE_DEFAULT) {
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
            ch(CH_ISLAND, R.string.notif_channel_island, R.string.notif_channel_island_desc, NotificationManager.IMPORTANCE_MIN) {
                setShowBadge(false); lockscreenVisibility = Notification.VISIBILITY_SECRET
            },
            ch(CH_LOCK, R.string.notif_channel_lock, R.string.notif_channel_lock_desc, NotificationManager.IMPORTANCE_HIGH) {
                setShowBadge(false); lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
            ch(CH_TIMERS, R.string.notif_channel_timers, R.string.notif_channel_timers_desc, NotificationManager.IMPORTANCE_HIGH) {
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE; enableVibration(true)
            },
        ))
    }

    fun canPost(): Boolean = nm.areNotificationsEnabled()

    /** Posts [n]; false when notifications are blocked (Android drops it silently, so the caller must not assume it was seen). */
    fun post(id: Int, n: Notification): Boolean {
        if (!canPost()) { log.w("Notify", "notifications are turned off for JARVIS — not shown"); return false }
        return try { nm.notify(id, n); true } catch (e: Exception) { log.w("Notify", "could not post (${e.javaClass.simpleName})"); false }
    }

    fun cancel(id: Int) { try { nm.cancel(id) } catch (_: Exception) { } }

    // ------------------------------------------------------------------------------------------------ pending intents
    /** Explicit, immutable. The [requestCode]+[data] pair keeps different reminders' intents apart. */
    private fun activityIntent(requestCode: Int, extras: Intent.() -> Unit = {}): PendingIntent {
        val i = Intent().setComponent(ComponentName(ctx.packageName, Intents.MAIN_ACTIVITY)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).apply(extras)
        return PendingIntent.getActivity(ctx, requestCode, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun actionIntent(action: String, id: String, notificationId: Int): PendingIntent {
        val i = Intent(ctx, ReminderActionReceiver::class.java).setAction(action).setData(Uri.parse("jarvis://reminder/$id/$action"))
            .putExtra(Intents.EXTRA_REMINDER_ID, id).putExtra(Intents.EXTRA_NOTIFICATION_ID, notificationId)
        return PendingIntent.getBroadcast(ctx, notificationId, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun hideOnLockScreen(): Boolean = LockPrivacy.parse(settings.get(Keys.LOCK_PRIVACY)) != LockPrivacy.OPEN

    private fun builder(channel: String): Notification.Builder = Notification.Builder(ctx, channel).setSmallIcon(R.drawable.ic_notification).setAutoCancel(true)

    // ------------------------------------------------------------------------------------------------ reminders & timers
    fun postReminder(r: Reminder): Boolean {
        val nid = reminderNotificationId(r.id)
        val title = ctx.getString(if (r.isAlarm) R.string.notif_alarm_title else R.string.notif_reminder_title)
        val open = activityIntent(nid) { putExtra(Intents.EXTRA_REMINDER_ID, r.id) }
        val b = builder(CH_REMINDERS)
            .setContentTitle(title).setContentText(r.text)
            .setStyle(Notification.BigTextStyle().bigText(r.text))
            .setCategory(if (r.isAlarm) Notification.CATEGORY_ALARM else Notification.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setWhen(r.triggerAt().toEpochMilli()).setShowWhen(true)
            .addAction(Notification.Action.Builder(null, ctx.getString(R.string.notif_action_done), actionIntent(Intents.ACTION_REMINDER_DONE, r.id, nid)).build())
            .addAction(Notification.Action.Builder(null, ctx.getString(R.string.notif_action_snooze), actionIntent(Intents.ACTION_REMINDER_SNOOZE, r.id, nid)).build())
        if (hideOnLockScreen()) {
            b.setVisibility(Notification.VISIBILITY_PRIVATE)
            b.setPublicVersion(builder(CH_REMINDERS).setContentTitle(title).setContentText(ctx.getString(R.string.notif_reminder_title)).build())
        }
        // An alarm-style reminder may light up the screen, but only where the user allowed full-screen alerts.
        if (r.isAlarm && (Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent())) b.setFullScreenIntent(open, true)
        return post(nid, b.build())
    }

    fun postTimerDone(timerId: String, label: String): Boolean {
        val nid = timerNotificationId(timerId)
        val b = builder(CH_TIMERS).setContentTitle(ctx.getString(R.string.notif_timer_title)).setContentText(label.ifBlank { "Timer" })
            .setCategory(Notification.CATEGORY_ALARM).setContentIntent(activityIntent(nid))
        return post(nid, b.build())
    }

    /** "Missed while the phone was off" summary after boot (reminders whose time passed). Text is withheld from the lock screen like any reminder. */
    fun postOverdueSummary(texts: List<String>): Boolean {
        if (texts.isEmpty()) return false
        val body = texts.take(5).joinToString("\n") + if (texts.size > 5) "\n+${texts.size - 5} more" else ""
        val b = builder(CH_REMINDERS).setContentTitle(ctx.getString(R.string.notif_overdue_title)).setContentText(texts.first())
            .setStyle(Notification.BigTextStyle().bigText(body)).setContentIntent(activityIntent(ID_OPEN_APP) { putExtra(Intents.EXTRA_OPEN_PAGE, "reminders") })
        if (hideOnLockScreen()) b.setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(builder(CH_REMINDERS).setContentTitle(ctx.getString(R.string.notif_overdue_title)).build())
        return post(ID_OPEN_APP, b.build())
    }

    // ------------------------------------------------------------------------------------------------ listening
    /** Android 14+ will not let an app start a microphone foreground service from BOOT_COMPLETED, so after a restart the user taps this to resume. */
    fun postResumeListening(): Boolean {
        val b = builder(CH_RESULTS).setContentTitle(ctx.getString(R.string.notif_resume_listening_title))
            .setContentText(ctx.getString(R.string.notif_resume_listening_text))
            .setStyle(Notification.BigTextStyle().bigText(ctx.getString(R.string.notif_resume_listening_text)))
            .setContentIntent(activityIntent(ID_RESUME) { putExtra(Intents.EXTRA_RESUME_LISTENING, true) })
        return post(ID_RESUME, b.build())
    }

    fun cancelResumeListening() = cancel(ID_RESUME)

    /** The ongoing status notification of the listening foreground service, with its Talk / Pause / Stop buttons. */
    fun listeningNotification(text: String, actions: List<Notification.Action> = emptyList(), showOnLockScreen: Boolean = false): Notification {
        val b = builder(CH_LISTENING).setContentTitle(ctx.getString(R.string.app_name)).setContentText(text).setOngoing(true).setAutoCancel(false)
            .setCategory(Notification.CATEGORY_SERVICE).setVisibility(if (showOnLockScreen) Notification.VISIBILITY_PRIVATE else Notification.VISIBILITY_SECRET)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(activityIntent(ID_LISTENING))
        // With the lock-screen assistant on, the notification may appear on the lock screen. Its text is only the listening status (never personal), and what a locked
        // phone shows is still up to the system's own notification privacy setting: the public version carries no buttons.
        if (showOnLockScreen) b.setPublicVersion(builder(CH_LISTENING).setContentTitle(ctx.getString(R.string.app_name)).setContentText(ctx.getString(R.string.svc_public_status)).build())
        actions.forEach { b.addAction(it) }
        return b.build()
    }

    /**
     * Brings up the lock-screen assistant from the background. Android 10+ blocks a background app from starting an activity, so this is a notification: with a full-screen
     * intent (where the user allows it) it opens over the lock screen, otherwise it is a heads-up the user taps.
     */
    fun postLockAssistant(open: PendingIntent, fullScreen: Boolean): Boolean {
        val b = builder(CH_LOCK).setContentTitle(ctx.getString(R.string.lock_notif_title)).setContentText(ctx.getString(R.string.lock_notif_text))
            .setCategory(Notification.CATEGORY_STATUS).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(open).setTimeoutAfter(60_000)
            .setPublicVersion(builder(CH_LOCK).setContentTitle(ctx.getString(R.string.lock_notif_title)).build())
        if (fullScreen) b.setFullScreenIntent(open, true)
        return post(ID_LOCK, b.build())
    }

    /** The quiet ongoing notification of the island overlay service. */
    fun islandNotification(actions: List<Notification.Action>): Notification {
        val b = builder(CH_ISLAND).setContentTitle(ctx.getString(R.string.island_notif_title)).setContentText(ctx.getString(R.string.island_notif_text))
            .setStyle(Notification.BigTextStyle().bigText(ctx.getString(R.string.island_notif_text)))
            .setOngoing(true).setAutoCancel(false).setCategory(Notification.CATEGORY_SERVICE).setVisibility(Notification.VISIBILITY_SECRET)
            .setContentIntent(activityIntent(ID_ISLAND) { putExtra(Intents.EXTRA_OPEN_PAGE, "island") })
        actions.forEach { b.addAction(it) }
        return b.build()
    }

    /** The island can't be shown without "Display over other apps": say so, and let a tap open the permissions screen. */
    fun postIslandNeedsOverlay(): Boolean {
        val b = builder(CH_RESULTS).setContentTitle(ctx.getString(R.string.island_needs_overlay_title)).setContentText(ctx.getString(R.string.island_needs_overlay_text))
            .setStyle(Notification.BigTextStyle().bigText(ctx.getString(R.string.island_needs_overlay_text)))
            .setContentIntent(activityIntent(ID_ISLAND_PERMISSION) { putExtra(Intents.EXTRA_OPEN_PAGE, "permissions") })
        return post(ID_ISLAND_PERMISSION, b.build())
    }

    // ------------------------------------------------------------------------------------------------ results & fallbacks
    /** An answer for a request made while the screen was off / app in the background. Tapping opens the chat. [text] is hidden on the lock screen per privacy. */
    fun postResult(title: String, text: String, id: Int = 4100 + (System.currentTimeMillis() % 800).toInt()): Boolean {
        val b = builder(CH_RESULTS).setContentTitle(title).setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(activityIntent(id))
        if (hideOnLockScreen()) b.setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(builder(CH_RESULTS).setContentTitle(title).build())
        return post(id, b.build())
    }

    /**
     * Android 10+ will not let a background app start another app's activity. A notification the user TAPS is allowed to, so that is the honest fallback:
     * JARVIS says it could not open the app itself and offers this.
     */
    fun postTapToOpen(label: String, launch: Intent): Boolean {
        val id = 4200 + (label.hashCode() and 0x3F)
        val pi = PendingIntent.getActivity(ctx, id, launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return post(id, builder(CH_RESULTS).setContentTitle("Open $label").setContentText("Tap to open $label").setContentIntent(pi).build())
    }
}
