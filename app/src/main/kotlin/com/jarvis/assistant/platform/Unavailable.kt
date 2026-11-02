package com.jarvis.assistant.platform

import com.jarvis.core.model.Requirement
import com.jarvis.core.ports.ActionResult
import com.jarvis.core.ports.GlobalAction
import com.jarvis.core.ports.NotificationGateway
import com.jarvis.core.ports.NotificationInfo
import com.jarvis.core.ports.PermissionChecker
import com.jarvis.core.ports.SystemNavigator

/** Used until the notification listener service is connected: every call says what the user must enable, and nothing is pretended. */
class UnavailableNotifications(private val permissions: PermissionChecker) : NotificationGateway {
    override fun isAccessGranted() = permissions.isGranted(Requirement.NOTIFICATION_LISTENER)
    override fun active(): List<NotificationInfo> = emptyList()
    override suspend fun quickReply(key: String, text: String) = ActionResult.fail("Notification access isn't connected.", Requirement.NOTIFICATION_LISTENER)
    override fun dismiss(key: String) = ActionResult.fail("Notification access isn't connected.", Requirement.NOTIFICATION_LISTENER)
}

class UnavailableNavigator : SystemNavigator {
    override suspend fun perform(action: GlobalAction) = ActionResult.fail("The accessibility shortcut service isn't running. Turn it on in settings to let JARVIS press Home, Back or Recents.", Requirement.ACCESSIBILITY)
}

/** Whether the listening foreground service is running; set by the service itself so nobody has to guess. */
object ListeningState {
    @Volatile var running: Boolean = false
    /** The service's own one-line status ("Listening for “hey jarvis”", "Paused during a phone call"...), empty when it isn't running. */
    @Volatile var detail: String = ""
}

/** Whether the overlay (Dynamic Island) service is running; set by the service itself. */
object IslandState {
    @Volatile var running: Boolean = false
}
