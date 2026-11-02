package com.jarvis.assistant.assist

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Whether JARVIS is the phone's digital assistant, and where the user can make it so. Android does not let an app select itself: the user does it in
 * Settings > Apps > Default apps > Digital assistant app (the screen is [Settings.ACTION_VOICE_INPUT_SETTINGS]; some phones use
 * [Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS]). Where Android offers the assistant role, [roleRequestIntent] shows its one-tap dialog instead; it must be started with
 * startActivityForResult, and it can be refused by the phone, in which case the settings screens are the way.
 */
object AssistantRoleHelper {
    /** True/false when Android can tell; null when it cannot (no role manager and the setting is unreadable). */
    fun isDefaultAssistant(ctx: Context): Boolean? {
        val rm = ctx.getSystemService(RoleManager::class.java)
        if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) return rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)
        // Some phones do not expose the role; the selected assistant is then recorded as "package/class" in a secure setting that apps may read.
        val v = try { Settings.Secure.getString(ctx.contentResolver, "assistant") } catch (_: Exception) { null } ?: return null
        return v.substringBefore('/') == ctx.packageName
    }

    /** The role-request dialog, or null when the phone offers no such role or JARVIS already holds it. */
    fun roleRequestIntent(ctx: Context): Intent? {
        val rm = ctx.getSystemService(RoleManager::class.java) ?: return null
        if (!rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT) || rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)) return null
        return try { rm.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT) } catch (_: Exception) { null }
    }

    /** System screens to try, in order, for choosing the assistant by hand; the last is JARVIS's own page in app settings. */
    fun settingsIntents(ctx: Context): List<Intent> = listOf(
        Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
        Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)),
    ).map { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
}
