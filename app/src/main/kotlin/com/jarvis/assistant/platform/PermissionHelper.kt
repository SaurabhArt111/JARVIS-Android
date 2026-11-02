package com.jarvis.assistant.platform

import android.Manifest
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import com.jarvis.assistant.R
import com.jarvis.core.model.Requirement
import com.jarvis.core.ports.PermState
import com.jarvis.core.ports.PermissionChecker
import com.jarvis.core.settings.Keys
import com.jarvis.core.settings.Settings

/**
 * One place that knows how each [Requirement] is checked and granted on Android.
 *
 *  - Runtime permissions (microphone, contacts, phone, notifications) -> [runtimePermissions]; the Activity asks with requestPermissions.
 *  - Special access (overlay, notification listener, accessibility, exact alarms, write settings, DND, full-screen intents, battery, assistant role)
 *    can only be granted by the user on a system screen -> [settingsIntent] / [open].
 *
 * [state] always re-reads the real system state, so it is safe to call from UI or tools at any time. Requirements that do not exist on this Android
 * version (for example POST_NOTIFICATIONS before 13) report GRANTED; hardware that is absent reports UNAVAILABLE.
 */
class PermissionHelper(context: Context, private val settings: Settings) : PermissionChecker {
    private val ctx = context.applicationContext

    companion object {
        /** Class names are strings so this file does not depend on the classes the surfaces layer defines. */
        const val NOTIFICATION_LISTENER_CLASS = "com.jarvis.assistant.access.JarvisNotificationListener"
        const val ACCESSIBILITY_CLASS = "com.jarvis.assistant.access.JarvisAccessibilityService"
        const val ANSWER_CALLS = Manifest.permission.ANSWER_PHONE_CALLS
    }

    private val listenerComponent get() = ComponentName(ctx.packageName, NOTIFICATION_LISTENER_CLASS)
    private val accessibilityComponent get() = ComponentName(ctx.packageName, ACCESSIBILITY_CLASS)

    private fun runtime(p: String) =
        if (ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) PermState.GRANTED else PermState.DENIED

    private fun hasFeature(f: String) = ctx.packageManager.hasSystemFeature(f)
    private fun flag(b: Boolean) = if (b) PermState.GRANTED else PermState.DENIED

    override fun state(requirement: Requirement): PermState = try {
        read(requirement)
    } catch (e: Exception) {
        PermState.UNAVAILABLE   // a vendor ROM that throws from a settings query: say "unknown/unavailable", never "granted"
    }

    private fun read(r: Requirement): PermState = when (r) {
        Requirement.MICROPHONE -> if (!hasFeature(PackageManager.FEATURE_MICROPHONE)) PermState.UNAVAILABLE else runtime(Manifest.permission.RECORD_AUDIO)
        Requirement.NOTIFICATIONS ->
            if (Build.VERSION.SDK_INT >= 33) runtime(Manifest.permission.POST_NOTIFICATIONS)
            else flag(ctx.getSystemService(NotificationManager::class.java).areNotificationsEnabled())
        Requirement.CONTACTS -> runtime(Manifest.permission.READ_CONTACTS)
        Requirement.CALL_PHONE -> if (!hasFeature(PackageManager.FEATURE_TELEPHONY)) PermState.UNAVAILABLE else runtime(Manifest.permission.CALL_PHONE)
        Requirement.OVERLAY -> flag(AndroidSettings.canDrawOverlays(ctx))
        Requirement.NOTIFICATION_LISTENER -> flag(ctx.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(listenerComponent))
        Requirement.ACCESSIBILITY -> flag(isAccessibilityEnabled())
        Requirement.EXACT_ALARM ->
            if (Build.VERSION.SDK_INT >= 31) flag(ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) else PermState.GRANTED
        Requirement.WRITE_SETTINGS -> flag(AndroidSettings.System.canWrite(ctx))
        Requirement.DND_ACCESS -> flag(ctx.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted)
        Requirement.FULL_SCREEN_INTENT ->
            if (Build.VERSION.SDK_INT >= 34) flag(ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()) else PermState.GRANTED
        Requirement.BATTERY_OPTIMIZATION -> flag(ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName))
        // Unknown (no role manager, setting unreadable) counts as "not set": the settings screen where the user picks the assistant still exists and must stay reachable.
        Requirement.ASSISTANT_ROLE -> flag(com.jarvis.assistant.assist.AssistantRoleHelper.isDefaultAssistant(ctx) == true)
        Requirement.UNLOCK -> flag(!ctx.getSystemService(KeyguardManager::class.java).isDeviceLocked)
        Requirement.NETWORK -> flag(isOnline())
        Requirement.API_KEY -> flag(hasAnyProvider())
        // depends on a specific package; the app launcher answers this per app. Not something the user can "grant".
        Requirement.APP_INSTALLED -> PermState.UNAVAILABLE
    }

    /** True when the default network claims internet access. (VALIDATED is deliberately not required: captive portals and slow validation shouldn't block a try.) */
    fun isOnline(): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** At least one chat provider is usable: a cloud key, or a configured Ollama address (local server on the user's network). */
    fun hasAnyProvider(): Boolean =
        settings.hasSecret(Keys.GROQ_API_KEY) || settings.hasSecret(Keys.COHERE_API_KEY) || settings.hasSecret(Keys.OPENAI_API_KEY) ||
            settings.get(Keys.OLLAMA_URL).isNotBlank()

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = AndroidSettings.Secure.getString(ctx.contentResolver, AndroidSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it.trim()) == accessibilityComponent }
    }

    // ------------------------------------------------------------------------------------------------ texts
    fun title(req: Requirement): String = ctx.getString(when (req) {
        Requirement.MICROPHONE -> R.string.perm_microphone_title
        Requirement.NOTIFICATIONS -> R.string.perm_notifications_title
        Requirement.CONTACTS -> R.string.perm_contacts_title
        Requirement.CALL_PHONE -> R.string.perm_call_phone_title
        Requirement.OVERLAY -> R.string.perm_overlay_title
        Requirement.NOTIFICATION_LISTENER -> R.string.perm_notification_listener_title
        Requirement.ACCESSIBILITY -> R.string.perm_accessibility_title
        Requirement.EXACT_ALARM -> R.string.perm_exact_alarm_title
        Requirement.WRITE_SETTINGS -> R.string.perm_write_settings_title
        Requirement.DND_ACCESS -> R.string.perm_dnd_access_title
        Requirement.FULL_SCREEN_INTENT -> R.string.perm_full_screen_intent_title
        Requirement.BATTERY_OPTIMIZATION -> R.string.perm_battery_optimization_title
        Requirement.ASSISTANT_ROLE -> R.string.perm_assistant_role_title
        Requirement.UNLOCK -> R.string.perm_unlock_title
        Requirement.NETWORK -> R.string.perm_network_title
        Requirement.API_KEY -> R.string.perm_api_key_title
        Requirement.APP_INSTALLED -> R.string.perm_app_installed_title
    })

    fun rationale(req: Requirement): String = ctx.getString(when (req) {
        Requirement.MICROPHONE -> R.string.perm_microphone_why
        Requirement.NOTIFICATIONS -> R.string.perm_notifications_why
        Requirement.CONTACTS -> R.string.perm_contacts_why
        Requirement.CALL_PHONE -> R.string.perm_call_phone_why
        Requirement.OVERLAY -> R.string.perm_overlay_why
        Requirement.NOTIFICATION_LISTENER -> R.string.perm_notification_listener_why
        Requirement.ACCESSIBILITY -> R.string.perm_accessibility_why
        Requirement.EXACT_ALARM -> R.string.perm_exact_alarm_why
        Requirement.WRITE_SETTINGS -> R.string.perm_write_settings_why
        Requirement.DND_ACCESS -> R.string.perm_dnd_access_why
        Requirement.FULL_SCREEN_INTENT -> R.string.perm_full_screen_intent_why
        Requirement.BATTERY_OPTIMIZATION -> R.string.perm_battery_optimization_why
        Requirement.ASSISTANT_ROLE -> R.string.perm_assistant_role_why
        Requirement.UNLOCK -> R.string.perm_unlock_why
        Requirement.NETWORK -> R.string.perm_network_why
        Requirement.API_KEY -> R.string.perm_api_key_why
        Requirement.APP_INSTALLED -> R.string.perm_app_installed_why
    })

    fun statusText(s: PermState): String = ctx.getString(when (s) {
        PermState.GRANTED -> R.string.perm_status_granted
        PermState.DENIED -> R.string.perm_status_denied
        PermState.UNAVAILABLE -> R.string.perm_status_unavailable
    })

    // ------------------------------------------------------------------------------------------------ granting
    /** Runtime permissions to pass to requestPermissions for [req]; empty when it is special access (use [settingsIntent]). */
    fun runtimePermissions(req: Requirement): Array<String> = when (req) {
        Requirement.MICROPHONE -> arrayOf(Manifest.permission.RECORD_AUDIO)
        Requirement.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()
        Requirement.CONTACTS -> arrayOf(Manifest.permission.READ_CONTACTS)
        // ANSWER_PHONE_CALLS rides along: it is what lets "answer"/"hang up" work; calling itself only needs CALL_PHONE
        Requirement.CALL_PHONE -> arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.ANSWER_PHONE_CALLS)
        else -> emptyArray()
    }

    private fun appUri() = Uri.fromParts("package", ctx.packageName, null)

    /** The app's own page in system settings: the fallback when Android no longer shows a permission dialog (denied twice) or a vendor screen is missing. */
    fun appDetailsIntent(): Intent = Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, appUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * The system screen where the user can grant [req]. For runtime permissions this is the app's details page (use [runtimePermissions] first). Null when there
     * is nothing for the user to grant (UNLOCK, NETWORK, API_KEY, APP_INSTALLED are not permissions).
     */
    fun settingsIntent(req: Requirement): Intent? {
        val i: Intent = when (req) {
            Requirement.MICROPHONE, Requirement.CONTACTS, Requirement.CALL_PHONE -> return appDetailsIntent()
            Requirement.NOTIFICATIONS -> Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, ctx.packageName)
            Requirement.OVERLAY -> Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, appUri())
            Requirement.NOTIFICATION_LISTENER -> Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            Requirement.ACCESSIBILITY -> Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)
            Requirement.EXACT_ALARM ->
                if (Build.VERSION.SDK_INT >= 31) Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, appUri()) else return null
            Requirement.WRITE_SETTINGS -> Intent(AndroidSettings.ACTION_MANAGE_WRITE_SETTINGS, appUri())
            Requirement.DND_ACCESS -> Intent(AndroidSettings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            Requirement.FULL_SCREEN_INTENT ->
                if (Build.VERSION.SDK_INT >= 34) Intent(AndroidSettings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, appUri()) else return null
            // The list screen needs no special permission; the direct "allow this app" dialog needs REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, which Google Play restricts.
            Requirement.BATTERY_OPTIMIZATION -> Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            Requirement.ASSISTANT_ROLE -> Intent(AndroidSettings.ACTION_VOICE_INPUT_SETTINGS)   // Settings > Apps > Default apps > Digital assistant app
            Requirement.UNLOCK, Requirement.NETWORK, Requirement.API_KEY, Requirement.APP_INSTALLED -> return null
        }
        return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Opens the screen for [req]; if this phone has no such screen, opens the app's details page instead. Returns false if nothing could be opened. */
    fun open(from: Context, req: Requirement): Boolean {
        val primary = settingsIntent(req) ?: return false
        for (i in listOf(primary, appDetailsIntent())) {
            try { from.startActivity(i); return true } catch (_: Exception) { /* ActivityNotFound / Security: try the fallback */ }
        }
        return false
    }

    /** What the Permissions screen lists. UNLOCK / NETWORK / API_KEY / APP_INSTALLED are conditions, not permissions, and are shown elsewhere. */
    fun allRequirements(): List<Requirement> = listOf(
        Requirement.MICROPHONE, Requirement.NOTIFICATIONS, Requirement.CONTACTS, Requirement.CALL_PHONE, Requirement.OVERLAY,
        Requirement.NOTIFICATION_LISTENER, Requirement.ACCESSIBILITY, Requirement.EXACT_ALARM, Requirement.FULL_SCREEN_INTENT,
        Requirement.WRITE_SETTINGS, Requirement.DND_ACCESS, Requirement.BATTERY_OPTIMIZATION, Requirement.ASSISTANT_ROLE,
    )
}
