package com.jarvis.assistant.ui.kit

import android.view.View
import com.jarvis.assistant.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

data class TopAction(val icon: String, val description: String, val run: () -> Unit)

/**
 * One screen of the app. [build] creates the view tree (again after rotation or a theme change); long-lived state belongs in a [ViewModel] fetched with
 * `ViewModels.get("<screen id>")`, which survives rebuilds. [viewScope] lives as long as the current view tree.
 */
abstract class Screen {
    abstract val id: String
    open fun title(): String = ""
    /** Top-level screens (the bottom-bar tabs) show the bottom navigation; the others show a back arrow. */
    open val topLevel: Boolean get() = false
    /** The screen draws its own header (the Chat screen does), so the host draws no title bar. */
    open val ownHeader: Boolean get() = false

    lateinit var host: ScreenHost
        internal set
    val ui: Ui get() = host.activity.ui
    val app: AppContainer get() = host.activity.container
    val activity: BaseActivity get() = host.activity

    private var _viewScope: CoroutineScope? = null
    protected val viewScope: CoroutineScope get() = _viewScope ?: CoroutineScope(SupervisorJob() + MainThread).also { _viewScope = it }

    abstract fun build(): View
    internal fun buildView(): View { _viewScope?.cancel(); _viewScope = null; return build() }
    internal fun destroyView() { _viewScope?.cancel(); _viewScope = null }

    open fun actions(): List<TopAction> = emptyList()
    open fun onShown() {}
    open fun onHidden() {}
    /** The activity came back to the foreground (e.g. after a system settings screen): refresh anything that depends on permissions. */
    open fun onActivityResumed() {}
    /** Popped for good: release view models. */
    open fun onClosed() {}
    /** True if the screen consumed the back press (closing a search box, ...). */
    open fun onBack(): Boolean = false
}

/** Implemented by the activity that hosts the screens so a screen can ask for another page ("settings", "permissions", "reminders", ...) without knowing the activity class. */
interface PageOpener { fun openPage(page: String) }
