package com.jarvis.assistant.platform

/**
 * Action and extra names shared by receivers, services, notifications and the UI. Everything here is internal to the app: every PendingIntent built from
 * these is explicit (component set) and immutable, and no receiver that handles them is exported.
 */
object Intents {
    const val PKG = "com.jarvis.assistant"

    // alarms & notification buttons
    const val ACTION_REMINDER_FIRE = "$PKG.action.REMINDER_FIRE"
    const val ACTION_TIMER_FIRE = "$PKG.action.TIMER_FIRE"
    const val ACTION_REMINDER_DONE = "$PKG.action.REMINDER_DONE"
    const val ACTION_REMINDER_SNOOZE = "$PKG.action.REMINDER_SNOOZE"
    const val ACTION_STOP_TIMERS = "$PKG.action.STOP_TIMERS"
    /** Sent to the listening foreground service (voice layer). */
    const val ACTION_LISTEN_START = "$PKG.action.LISTEN_START"
    const val ACTION_LISTEN_STOP = "$PKG.action.LISTEN_STOP"

    const val EXTRA_REMINDER_ID = "reminder_id"
    const val EXTRA_TIMER_ID = "timer_id"
    const val EXTRA_TIMER_LABEL = "timer_label"
    const val EXTRA_NOTIFICATION_ID = "notification_id"

    // opening the app from a notification (handled by MainActivity)
    /** Boolean extra: the user tapped "tap to resume listening"; MainActivity starts the listening service from the foreground (the only place Android 14+ allows it). */
    const val EXTRA_RESUME_LISTENING = "resume_listening"
    /** String extra: a page name from AssistantControl.pages() (chat, library, reminders, ...). */
    const val EXTRA_OPEN_PAGE = "open_page"
    /** Lock-screen assistant only: light the screen when it opens (set by deliberate hardware gestures, never by a background event). */
    const val EXTRA_TURN_SCREEN_ON = "turn_screen_on"

    /** Component names as strings, so this layer does not depend on classes written later by the UI / surfaces layers. */
    const val MAIN_ACTIVITY = "$PKG.ui.MainActivity"
    const val ASSISTANT_ACTIVITY = "$PKG.ui.AssistantActivity"
    const val LOCK_ACTIVITY = "$PKG.lock.LockScreenActivity"
    const val LISTENING_SERVICE = "$PKG.service.AssistantForegroundService"
    const val ISLAND_SERVICE = "$PKG.island.IslandService"
    /** Sent to the island service: show a short demo so the user can judge position and size. */
    const val ACTION_ISLAND_PREVIEW = "$PKG.action.ISLAND_PREVIEW"
    const val ACTION_ISLAND_START = "$PKG.action.ISLAND_START"
    const val ACTION_ISLAND_STOP = "$PKG.action.ISLAND_STOP"

    /** Snooze length for the notification button. */
    const val SNOOZE_MINUTES = 10L
}
