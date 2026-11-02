package com.jarvis.assistant.ui.kit

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlin.coroutines.CoroutineContext

/** Runs coroutines on the main thread with only core coroutines APIs (no dependency on kotlinx-coroutines-android). */
object MainThread : CoroutineDispatcher() {
    private val handler = Handler(Looper.getMainLooper())
    override fun isDispatchNeeded(context: CoroutineContext): Boolean = Looper.myLooper() != Looper.getMainLooper()
    override fun dispatch(context: CoroutineContext, block: Runnable) { handler.post(block) }
}

/**
 * ViewModel without AndroidX: a plain object that owns a coroutine scope and survives configuration changes because it lives in [ViewModels], keyed by screen id.
 * [onCleared] runs when its screen is closed for good (popped), not when the screen is merely rebuilt (rotation, theme change).
 */
abstract class ViewModel(log: ((String, Throwable) -> Unit)? = null) {
    private val handler = CoroutineExceptionHandler { _, e -> log?.invoke("ViewModel", e) }
    protected val scope: CoroutineScope = CoroutineScope(SupervisorJob() + MainThread + handler)
    open fun onCleared() {}
    internal fun clear() { onCleared(); scope.cancel() }
}

object ViewModels {
    private val store = HashMap<String, ViewModel>()

    @Suppress("UNCHECKED_CAST")
    @Synchronized fun <T : ViewModel> get(key: String, create: () -> T): T = store.getOrPut(key) { create() } as T

    @Synchronized fun clear(key: String) { store.remove(key)?.clear() }
    @Synchronized fun clearAll() { store.values.forEach { it.clear() }; store.clear() }
}

/** Collects [flow] on the main thread while this view is attached to a window; stops when it is detached (no leaks, no work for screens nobody sees). */
fun <T> android.view.View.collectWhileAttached(flow: Flow<T>, action: (T) -> Unit) {
    var job: Job? = null
    val scope = CoroutineScope(SupervisorJob() + MainThread)
    fun start() { if (job == null) job = scope.launch { flow.collect { action(it) } } }
    fun stop() { job?.cancel(); job = null }
    addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: android.view.View) = start()
        override fun onViewDetachedFromWindow(v: android.view.View) = stop()
    })
    if (isAttachedToWindow) start()
}
