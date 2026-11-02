package com.jarvis.assistant.access

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.jarvis.core.model.Requirement
import com.jarvis.core.notify.NotificationRules
import com.jarvis.core.ports.ActionResult
import com.jarvis.core.ports.NotificationGateway
import com.jarvis.core.ports.NotificationInfo
import com.jarvis.core.ports.PermissionChecker
import com.jarvis.core.settings.Keys
import com.jarvis.core.settings.Settings
import com.jarvis.core.util.JarvisLog

/**
 * [NotificationGateway] on top of [NotificationStore]. Registered by [JarvisNotificationListener] while it is connected.
 *
 * Replying never drives another app's screen: the reply goes through the notification's own RemoteInput action, the same one the notification shade uses.
 * Android reports no delivery receipt for that, so a successful call says "handed to <app>" and is never [ActionResult.verified].
 * WhatsApp is held to its own switch ([Keys.WHATSAPP_QUICK_REPLY]) on top of the general one.
 */
class AndroidNotificationGateway(
    context: Context,
    private val settings: Settings,
    private val permissions: PermissionChecker,
    private val log: JarvisLog = JarvisLog.None,
) : NotificationGateway {
    private val ctx = context.applicationContext

    private fun integrationOn() = settings.flag(Keys.NOTIF_INTEGRATION, false)

    override fun isAccessGranted() = permissions.isGranted(Requirement.NOTIFICATION_LISTENER) && integrationOn()

    override fun active(): List<NotificationInfo> {
        if (!isAccessGranted()) return emptyList()
        JarvisNotificationListener.ensureSeeded()
        return NotificationStore.snapshot()
    }

    private fun gate(): ActionResult? = when {
        !permissions.isGranted(Requirement.NOTIFICATION_LISTENER) -> ActionResult.fail("Notification access isn't granted.", Requirement.NOTIFICATION_LISTENER)
        !integrationOn() -> ActionResult.fail("Notification integration is switched off in JARVIS settings (Settings > Integrations).")
        else -> null
    }

    override suspend fun quickReply(key: String, text: String): ActionResult {
        gate()?.let { return it }
        val message = text.trim()
        if (message.isEmpty()) return ActionResult.fail("There is nothing to send: the reply is empty.")
        val entry = NotificationStore.entry(key) ?: return ActionResult.fail("That notification is gone, so there is nothing to reply to.")
        val app = entry.info.appLabel
        if (NotificationRules.isWhatsApp(entry.info.packageName) && !settings.flag(Keys.WHATSAPP_QUICK_REPLY, false))
            return ActionResult.fail("WhatsApp quick reply is switched off. You can turn it on in Settings > Integrations.")
        val action = entry.replyAction
        val input: RemoteInput = entry.replyInput ?: return ActionResult.fail("$app offers no reply button on this notification, so I can't answer it from here.")
        val pending = action?.actionIntent ?: return ActionResult.fail("$app offers no reply button on this notification, so I can't answer it from here.")
        return try {
            val fill = Intent()
            val results = Bundle().apply { putCharSequence(input.resultKey, message) }
            RemoteInput.addResultsToIntent(arrayOf(input), fill, results)
            pending.send(ctx, 0, fill)
            log.i("Notifications", "reply handed to ${entry.info.packageName}")
            ActionResult.ok("I handed your reply to $app. Android doesn't tell me whether it was delivered.", verified = false)
        } catch (e: PendingIntent.CanceledException) {
            ActionResult.fail("The reply button on that notification has expired, so $app didn't get your message.")
        } catch (e: Exception) {
            log.w("Notifications", "reply failed (${e.javaClass.simpleName})")
            ActionResult.fail("$app didn't accept the reply.")
        }
    }

    override fun dismiss(key: String): ActionResult {
        gate()?.let { return it }
        if (NotificationStore.entry(key) == null) return ActionResult.fail("That notification is already gone.")
        val asked = JarvisNotificationListener.cancel(key)
        return if (asked) { NotificationStore.remove(key); ActionResult.ok("I asked Android to dismiss it.") }
        else ActionResult.fail("Notification access isn't connected right now.", Requirement.NOTIFICATION_LISTENER)
    }
}
