package com.jarvis.assistant.access

import android.app.Notification
import com.jarvis.core.ports.NotificationInfo

/** A recent notification and, when the app offered one, the action JARVIS may use to reply. Held in memory only. */
class StoredNotification(val info: NotificationInfo, val replyAction: Notification.Action?, val replyInput: android.app.RemoteInput?)

/**
 * The most recent notifications, newest last, bounded at [MAX]. In memory only: nothing is written to disk, and everything is dropped when notification access
 * is disconnected or the integration is switched off, so message text is not kept longer than Android itself keeps the notification.
 */
object NotificationStore {
    const val MAX = 60
    private val lock = Any()
    private val items = LinkedHashMap<String, StoredNotification>()

    /** True once the list was filled from the system's active notifications (so a late-enabled integration is not empty). */
    @Volatile var seeded: Boolean = false

    fun put(n: StoredNotification) = synchronized(lock) {
        items.remove(n.info.key)               // an update moves to the newest position
        items[n.info.key] = n
        while (items.size > MAX) items.remove(items.keys.first())
    }

    fun remove(key: String) = synchronized(lock) { items.remove(key) }
    fun entry(key: String): StoredNotification? = synchronized(lock) { items[key] }
    fun info(key: String): NotificationInfo? = entry(key)?.info
    /** Newest first. */
    fun snapshot(): List<NotificationInfo> = synchronized(lock) { items.values.map { it.info }.asReversed() }
    fun size(): Int = synchronized(lock) { items.size }
    fun clear() = synchronized(lock) { items.clear(); seeded = false }
}
