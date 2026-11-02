package com.jarvis.assistant

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry

/** The app's context on the device/emulator under test. (Instrumented tests need a device; they are NOT run in the offline build environment.) */
object TestSupport {
    val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    fun uniqueDb(): String = "test_" + java.util.UUID.randomUUID().toString().take(8) + ".db"
}
