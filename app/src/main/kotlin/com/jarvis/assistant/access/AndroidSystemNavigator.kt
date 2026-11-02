package com.jarvis.assistant.access

import android.accessibilityservice.AccessibilityService
import com.jarvis.assistant.ui.kit.MainThread
import com.jarvis.core.model.Requirement
import com.jarvis.core.ports.ActionResult
import com.jarvis.core.ports.GlobalAction
import com.jarvis.core.ports.SystemNavigator
import com.jarvis.core.settings.Keys
import com.jarvis.core.settings.Settings
import com.jarvis.core.util.JarvisLog
import kotlinx.coroutines.withContext

/**
 * [SystemNavigator] through the accessibility service's global actions. Android returns only "the request was accepted", never whether the screen then changed, so
 * a successful result says what was asked and is not marked verified.
 */
class AndroidSystemNavigator(
    private val settings: Settings,
    private val service: () -> AccessibilityService?,
    private val log: JarvisLog = JarvisLog.None,
) : SystemNavigator {

    private fun code(a: GlobalAction): Int = when (a) {
        GlobalAction.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
        GlobalAction.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
        GlobalAction.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
        GlobalAction.NOTIFICATIONS -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
        GlobalAction.QUICK_SETTINGS -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
        GlobalAction.LOCK_SCREEN -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
        GlobalAction.SCREENSHOT -> AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
        GlobalAction.POWER_DIALOG -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
    }

    private fun words(a: GlobalAction) = when (a) {
        GlobalAction.HOME -> "go to the home screen"
        GlobalAction.BACK -> "go back"
        GlobalAction.RECENTS -> "open recent apps"
        GlobalAction.NOTIFICATIONS -> "open the notification shade"
        GlobalAction.QUICK_SETTINGS -> "open quick settings"
        GlobalAction.LOCK_SCREEN -> "lock the screen"
        GlobalAction.SCREENSHOT -> "take a screenshot"
        GlobalAction.POWER_DIALOG -> "show the power menu"
    }

    override suspend fun perform(action: GlobalAction): ActionResult {
        if (!settings.flag(Keys.ACCESSIBILITY_ACTIONS, false))
            return ActionResult.fail("The accessibility shortcuts are switched off in JARVIS settings (Settings > Integrations).")
        val svc = service() ?: return ActionResult.fail(
            "The JARVIS accessibility shortcut service isn't running. Turn it on in Android's accessibility settings to let me ${words(action)}.", Requirement.ACCESSIBILITY)
        return try {
            val accepted = withContext(MainThread) { svc.performGlobalAction(code(action)) }
            if (accepted) ActionResult.ok("I asked Android to ${words(action)}. It doesn't confirm the result.", verified = false)
            else ActionResult.fail("Android refused to ${words(action)}. Some phones block this action, for example the screenshot while a secure app is showing.")
        } catch (e: Exception) {
            log.w("Accessibility", "global action failed (${e.javaClass.simpleName})")
            ActionResult.fail("I couldn't ${words(action)}.")
        }
    }
}
