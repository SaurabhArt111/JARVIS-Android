package com.jarvis.assistant.access

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.pm.PackageManager
import android.service.notification.StatusBarNotification
import com.jarvis.core.notify.NotificationRules
import com.jarvis.core.ports.NotificationInfo

/** Turns the platform's StatusBarNotification into what the rest of JARVIS knows: sender, text, kind, and whether Android gave a reply action. */
class NotificationMapper(context: Context) {
    private val pm: PackageManager = context.applicationContext.packageManager
    private val labels = HashMap<String, String>()

    private fun label(pkg: String): String = labels.getOrPut(pkg) {
        try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { NotificationRules.labelFromPackage(pkg) }
    }

    /** Null for notifications that carry nothing worth reading: group summaries and empty ones. */
    @Suppress("DEPRECATION")
    fun map(sbn: StatusBarNotification): StoredNotification? {
        val n = sbn.notification ?: return null
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return null
        val x = n.extras ?: return null
        var title = x.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        var text = (x.getCharSequence(Notification.EXTRA_TEXT) ?: x.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString().orEmpty()
        val messagingStyle = x.getString(Notification.EXTRA_TEMPLATE) == Notification.MessagingStyle::class.java.name
        var conversation: String? = null
        if (messagingStyle) {
            val raw = x.getParcelableArray(Notification.EXTRA_MESSAGES)
            val last = if (raw != null) Notification.MessagingStyle.Message.getMessagesFromBundleArray(raw).lastOrNull() else null
            if (last != null) {
                text = last.text?.toString().orEmpty().ifBlank { text }
                val who = last.senderPerson?.name?.toString().orEmpty()
                val convo = x.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString().orEmpty()
                if (convo.isNotBlank()) { conversation = convo; if (who.isNotBlank()) text = "$who: $text" }
                else if (who.isNotBlank()) title = who
            }
        }
        if (title.isBlank() && text.isBlank()) return null
        val (action, input) = replyActionOf(n)
        val kind = NotificationRules.categorize(n.category, sbn.packageName, messagingStyle)
        val info = NotificationInfo(
            key = sbn.key, packageName = sbn.packageName, appLabel = label(sbn.packageName), title = title, text = text,
            postedAtMs = sbn.postTime, kind = kind, canReply = action != null,
            ongoing = sbn.isOngoing || (n.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0, conversation = conversation,
        )
        return StoredNotification(info, action, input)
    }

    /** The action a person would tap to type a reply: Android's own "reply" semantic first, otherwise any action with a free-text input. */
    private fun replyActionOf(n: Notification): Pair<Notification.Action?, RemoteInput?> {
        val actions = n.actions ?: return null to null
        fun input(a: Notification.Action): RemoteInput? = a.remoteInputs?.firstOrNull { it.allowFreeFormInput }
        actions.firstOrNull { it.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY && input(it) != null && it.actionIntent != null }
            ?.let { return it to input(it) }
        actions.firstOrNull { input(it) != null && it.actionIntent != null }?.let { return it to input(it) }
        return null to null
    }
}
