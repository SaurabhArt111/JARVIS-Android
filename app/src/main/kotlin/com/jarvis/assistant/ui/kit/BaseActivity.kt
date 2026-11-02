package com.jarvis.assistant.ui.kit

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.di.AppContainer
import com.jarvis.core.model.Requirement
import com.jarvis.core.ports.PermState

/**
 * Base for JARVIS activities (plain android.app.Activity: no AppCompat):
 *  - the palette / [ui] toolkit from Settings (rebuilt on theme or font changes),
 *  - edge-to-edge,
 *  - back handling that works with Android 13+ OnBackInvokedCallback (targetSdk 36) and the old onBackPressed,
 *  - one-at-a-time permission requests with the rationale shown first ([grant]), including special-access screens that return to the app.
 * Configuration changes are handled in place (no recreation), so scroll position, typed text and running work survive rotation and folding.
 */
abstract class BaseActivity : Activity() {
    val container: AppContainer get() = JarvisApp.container
    lateinit var ui: Ui
        private set

    private var backCallback: OnBackInvokedCallback? = null
    private val permCallbacks = HashMap<Int, (Boolean) -> Unit>()
    private var nextCode = 100
    private var pendingSpecial: Pair<Requirement, (PermState) -> Unit>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshUi()
    }

    /** Re-reads the palette and font size from Settings. Call after the user changes appearance, then rebuild the visible screen. */
    fun refreshUi() {
        val s = container.settings
        ui = Ui(this, Themes.palette(this, s), Themes.fontScale(s))
        Edge.enable(this, lightBars = !ui.pal.dark)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(ui.pal.bg))
    }

    // ------------------------------------------------------------------------------------------------ back
    /** Return true if the back press was used (a screen popped, a dialog closed...). */
    protected open fun onBackRequested(): Boolean = false

    /** Back was pressed and nothing inside the app used it. Default: close the activity. */
    protected open fun onBackAtRoot() { finish() }

    /** Registers (or removes) the Android 13+ back callback. Activities that handle back themselves (MainActivity) turn it on once and decide at the root in [onBackAtRoot]. */
    fun setBackEnabled(enabled: Boolean) {
        if (Build.VERSION.SDK_INT < 33) return
        if (enabled && backCallback == null) {
            val cb = OnBackInvokedCallback { if (!onBackRequested()) onBackAtRoot() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
            backCallback = cb
        } else if (!enabled && backCallback != null) {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backCallback!!)
            backCallback = null
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!onBackRequested()) onBackAtRoot()
    }

    // ------------------------------------------------------------------------------------------------ activity results
    private val resultCallbacks = HashMap<Int, (Int, Intent?) -> Unit>()

    /** startActivityForResult with a callback instead of a request-code switch (system file dialogs, role requests). */
    fun launchForResult(intent: Intent, onResult: (resultCode: Int, data: Intent?) -> Unit): Boolean {
        val code = nextCode++
        resultCallbacks[code] = onResult
        return try { @Suppress("DEPRECATION") startActivityForResult(intent, code); true } catch (e: Exception) { resultCallbacks.remove(code); false }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        resultCallbacks.remove(requestCode)?.invoke(resultCode, data)
    }

    // ------------------------------------------------------------------------------------------------ permissions
    private fun askRuntime(perms: Array<String>, cb: (Boolean) -> Unit) {
        val code = nextCode++
        permCallbacks[code] = cb
        requestPermissions(perms, code)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permCallbacks.remove(requestCode)?.invoke(grantResults.isNotEmpty() && grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED })
    }

    /**
     * Asks for [req] the way Android wants it: explains why first, then either the system permission dialog (runtime permissions) or the right system settings screen
     * (special access). [onDone] gets the real state afterwards. A permission the user has blocked for good is sent to the app's settings page instead of a dialog that would not appear.
     */
    fun grant(req: Requirement, onDone: (PermState) -> Unit = {}) {
        val p = container.permissions
        if (p.state(req) == PermState.GRANTED) { onDone(PermState.GRANTED); return }
        if (p.state(req) == PermState.UNAVAILABLE) {
            Dialogs.message(this, ui, p.title(req), "This isn't available on this phone."); onDone(PermState.UNAVAILABLE); return
        }
        Dialogs.show(this, ui) { c, dismiss ->
            c.addView(ui.title(p.title(req), 19f))
            c.addView(ui.text(p.rationale(req), 14.5f))
            c.addView(ui.row(12) {
                add(ui.button("Not now", Ui.Kind.GHOST) { dismiss(); onDone(p.state(req)) }, 0, WRAP, 1f)
                add(ui.button("Continue") { dismiss(); startGrant(req, onDone) }, 0, WRAP, 1f)
            })
        }
    }

    private fun startGrant(req: Requirement, onDone: (PermState) -> Unit) {
        val p = container.permissions
        val runtime = p.runtimePermissions(req)
        if (runtime.isNotEmpty()) {
            askRuntime(runtime) { granted ->
                if (!granted && runtime.none { shouldShowRequestPermissionRationale(it) }) {
                    // Android won't show the dialog again: send the user to the app's permission page
                    Dialogs.message(this, ui, p.title(req), "Android isn't showing the permission prompt any more. Open JARVIS's settings page, choose Permissions, and allow it there.", "Open settings") {
                        pendingSpecial = req to onDone; p.open(this, req)
                    }
                } else onDone(p.state(req))
            }
        } else {
            pendingSpecial = req to onDone
            if (!p.open(this, req)) { pendingSpecial = null; Dialogs.message(this, ui, p.title(req), "I couldn't open the settings screen for this on your phone. Open Settings → Apps → JARVIS and look for it there."); onDone(p.state(req)) }
        }
    }

    override fun onResume() {
        super.onResume()
        pendingSpecial?.let { (req, cb) -> pendingSpecial = null; cb(container.permissions.state(req)) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshUi()
        onThemeOrSizeChanged()
    }

    /** Called after a configuration change (rotation, fold, dark mode, font scale): rebuild what is on screen. */
    protected open fun onThemeOrSizeChanged() {}

    fun startActivityOrToast(i: Intent): Boolean = try { startActivity(i); true } catch (e: Exception) { false }
    val appContext: Context get() = applicationContext
}
