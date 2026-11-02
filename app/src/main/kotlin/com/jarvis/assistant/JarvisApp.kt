package com.jarvis.assistant

import android.app.Application
import com.jarvis.assistant.data.CrashHandler
import com.jarvis.assistant.di.AppContainer
import com.jarvis.assistant.platform.AppForeground

/**
 * Process entry point. Order: crash handler first (so even a failure while building the graph leaves a record), then foreground tracking, then the object graph.
 * Receivers and services reach the graph through [container]; it exists before any of them runs because Android creates the Application first.
 */
class JarvisApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val c = AppContainer(this)
        container = c
        CrashHandler.install(this, c.appLog)
        AppForeground.install(this)
        c.start()
    }

    companion object {
        lateinit var container: AppContainer
            private set
    }
}
