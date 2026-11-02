package com.jarvis.assistant.platform

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import com.jarvis.core.apps.AppMatcher
import com.jarvis.core.ports.ActionResult
import com.jarvis.core.ports.AppLauncher
import com.jarvis.core.ports.AppMatch
import com.jarvis.core.util.JarvisLog

/**
 * Apps, links, files and system settings pages.
 *
 * Package visibility: only apps that declare a launcher activity (the <queries> entry in the manifest) plus browsers are visible; QUERY_ALL_PACKAGES is not used.
 * The fuzzy "which app" decision is core.apps.AppMatcher; this class only supplies the installed list and starts things through [ActivityStarter], which
 * reports honestly when Android would block a background start.
 */
class AndroidAppLauncher(context: Context, private val starter: ActivityStarter, private val log: JarvisLog = JarvisLog.None) : AppLauncher {
    private val ctx = context.applicationContext
    private val pm: PackageManager = ctx.packageManager

    @Volatile private var cache: List<Pair<String, String>> = emptyList()
    @Volatile private var cachedAt = 0L

    /** (label, package) of every launchable app, cached for a minute (the list rarely changes and querying labels is slow). */
    private fun installed(): List<Pair<String, String>> {
        val now = System.currentTimeMillis()
        if (cache.isNotEmpty() && now - cachedAt < 60_000) return cache
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = if (Build.VERSION.SDK_INT >= 33) pm.queryIntentActivities(main, PackageManager.ResolveInfoFlags.of(0))
        else @Suppress("DEPRECATION") pm.queryIntentActivities(main, 0)
        cache = list.map { it.loadLabel(pm).toString() to it.activityInfo.packageName }.distinctBy { it.second }.also { cachedAt = now }
        return cache
    }

    fun invalidate() { cachedAt = 0L }

    override fun resolve(query: String): List<AppMatch> = AppMatcher.resolve(query, installed())

    override fun launch(app: AppMatch): ActionResult {
        val intent = pm.getLaunchIntentForPackage(app.packageName) ?: return ActionResult.fail("${app.label} has no screen I can open.")
        val r = starter.start(intent, app.label, "Asked Android to open ${app.label}.")
        return if (r.ok) r.copy(verified = true) else r      // verified = the app's launch activity exists (resolved above), NOT that the screen appeared
    }

    // ------------------------------------------------------------------------------------------------ browsers & links
    private fun viewIntent(url: String) = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)

    override fun browsers(): List<AppMatch> {
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("http://www.example.com/")).addCategory(Intent.CATEGORY_BROWSABLE)
        val list = try {
            if (Build.VERSION.SDK_INT >= 33) pm.queryIntentActivities(probe, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
            else @Suppress("DEPRECATION") pm.queryIntentActivities(probe, PackageManager.MATCH_ALL)
        } catch (e: Exception) { emptyList() }
        return list.map { AppMatch(it.loadLabel(pm).toString(), it.activityInfo.packageName) }.distinctBy { it.packageName }
    }

    override fun openUrl(url: String, browser: AppMatch?): ActionResult {
        val u = try { Uri.parse(url) } catch (e: Exception) { null }
        if (u == null || (u.scheme != "http" && u.scheme != "https") || u.host.isNullOrBlank()) return ActionResult.fail("I only open web links that start with http or https.")
        val intent = viewIntent(url)
        if (browser != null) {
            intent.setPackage(browser.packageName)
            if (!starter.resolves(intent)) return ActionResult.fail("${browser.label} can't open that link on this phone.")
        }
        val where = browser?.label ?: "your browser"
        val r = starter.start(intent, where, "Asked Android to open the link in $where.")
        return if (r.ok) r.copy(verified = true) else r
    }

    // ------------------------------------------------------------------------------------------------ settings pages
    private val PAGES: Map<String, String> = mapOf(
        "wifi" to AndroidSettings.ACTION_WIFI_SETTINGS,
        "bluetooth" to AndroidSettings.ACTION_BLUETOOTH_SETTINGS,
        "display" to AndroidSettings.ACTION_DISPLAY_SETTINGS,
        "sound" to AndroidSettings.ACTION_SOUND_SETTINGS,
        "battery" to AndroidSettings.ACTION_BATTERY_SAVER_SETTINGS,
        "apps" to AndroidSettings.ACTION_APPLICATION_SETTINGS,
        "location" to AndroidSettings.ACTION_LOCATION_SOURCE_SETTINGS,
        "accessibility" to AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS,
        "date" to AndroidSettings.ACTION_DATE_SETTINGS,
        "language" to AndroidSettings.ACTION_LOCALE_SETTINGS,
        "storage" to AndroidSettings.ACTION_INTERNAL_STORAGE_SETTINGS,
        "privacy" to AndroidSettings.ACTION_PRIVACY_SETTINGS,
        "network" to AndroidSettings.ACTION_WIRELESS_SETTINGS,
        "airplane" to AndroidSettings.ACTION_AIRPLANE_MODE_SETTINGS,
        "developer" to AndroidSettings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
        "security" to AndroidSettings.ACTION_SECURITY_SETTINGS,
        "nfc" to AndroidSettings.ACTION_NFC_SETTINGS,
        "default apps" to AndroidSettings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS,
        "main" to AndroidSettings.ACTION_SETTINGS,
    )

    override fun openSettingsPage(page: String): ActionResult {
        val key = page.lowercase().trim().replace('_', ' ')
        val action = PAGES[key]
        if (action == null) {
            // Android has no public link to the general "Notifications" page (only a per-app one), so open the main Settings and say so.
            val main = starter.start(Intent(AndroidSettings.ACTION_SETTINGS), "Settings", "Opened Settings.")
            return if (main.ok) ActionResult.fail("Android doesn't give apps a direct link to the “$key” page, so I opened the main Settings screen — you'll find it there.")
            else main
        }
        val r = starter.start(Intent(action), "the $key settings", "Asked Android to open the $key settings.")
        return if (r.ok) r.copy(verified = true) else r
    }

    // ------------------------------------------------------------------------------------------------ files
    /**
     * Opens a file JARVIS saved. [uri] is a content:// URI from MediaStore (Documents/JARVIS) or from JARVIS's own file provider; a plain private path is
     * converted to the provider's URI. Nothing else is accepted, so a model-supplied path can never make JARVIS open arbitrary files.
     */
    override fun openFile(uri: String, mime: String?): ActionResult {
        val u = JarvisFileProvider.safeUriFor(ctx, uri) ?: return ActionResult.fail("That file isn't one JARVIS saved, so I won't open it.")
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(u, mime ?: "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val r = starter.start(intent, "the file", "Asked Android to open the file.")
        return if (r.ok) r.copy(verified = true) else r
    }

    override fun isInstalled(packageName: String): Boolean = installed().any { it.second == packageName } || try {
        pm.getLaunchIntentForPackage(packageName) != null
    } catch (_: Exception) { false }
}
