package com.jarvis.assistant.platform

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.atomic.AtomicInteger

/**
 * Is JARVIS something the user can see right now? Android 10+ silently blocks apps from starting activities from the background, so code that opens
 * apps must know whether that is going to work BEFORE claiming it did. Updated by [JarvisApp] (activities) and by the island service ([overlayShown]).
 */
object AppForeground {
    private val started = AtomicInteger(0)
    @Volatile var overlayShown: Boolean = false

    val activityVisible: Boolean get() = started.get() > 0

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { started.incrementAndGet() }
            override fun onActivityStopped(activity: Activity) { started.updateAndGet { if (it > 0) it - 1 else 0 } }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
