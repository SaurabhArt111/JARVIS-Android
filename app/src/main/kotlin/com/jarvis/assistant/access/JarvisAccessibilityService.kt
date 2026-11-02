package com.jarvis.assistant.access

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.jarvis.assistant.JarvisApp

/**
 * The smallest accessibility service that lets JARVIS press Home, Back, Recents, open the notification shade or quick settings, lock the screen, take a
 * screenshot, or show the power menu (GLOBAL_ACTION_*). Android offers these only to enabled accessibility services.
 *
 * What it deliberately does NOT do: it receives no events (the config declares none), cannot retrieve window content (`canRetrieveWindowContent` is false),
 * cannot perform gestures, and reads nothing from other apps' screens. [onAccessibilityEvent] is empty on purpose. Even when the user enables the service in
 * system settings, JARVIS uses it only while its own switch ([com.jarvis.core.settings.Keys.ACCESSIBILITY_ACTIONS]) is on.
 */
class JarvisAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: JarvisAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val c = JarvisApp.container
        c.attachNavigator(AndroidSystemNavigator(c.settings, { instance }, c.appLog))
        c.appLog.i("Accessibility", "shortcut service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* no event types are requested; nothing to read */ }

    override fun onInterrupt() { /* no feedback to interrupt */ }

    override fun onUnbind(intent: Intent?): Boolean {
        detach()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        detach()
        super.onDestroy()
    }

    private fun detach() {
        if (instance === this) {
            instance = null
            JarvisApp.container.attachNavigator(null)
        }
    }
}
