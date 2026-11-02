package com.jarvis.assistant.assist

import android.os.Bundle
import android.service.voice.VoiceInteractionService
import com.jarvis.assistant.JarvisApp

/**
 * Makes JARVIS selectable as the phone's "Digital assistant app" (shown to the user as "Jarvis Air"; Settings > Apps > Default apps > Digital assistant app).
 * Once selected, Android binds this service and, on the assistant gesture (long-press Home, the power-button gesture or the corner swipe, depending on the phone),
 * opens a [JarvisVoiceInteractionSession], which in turn opens the compact chat. This class holds no state and does no work itself.
 */
class JarvisVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        JarvisApp.container.appLog.i("Assist", "voice-interaction service is ready (JARVIS is the default assistant)")
    }

    /**
     * The "voice assist" shortcut on the lock screen (on phones that have one). Android calls this even when the screen is off or locked. The only thing done here is to ask
     * Android to start a session; the session then decides, from the keyguard state, between the compact chat and the lock-screen assistant.
     */
    override fun onLaunchVoiceAssistFromKeyguard() {
        try {
            showSession(Bundle().apply { putBoolean(JarvisVoiceInteractionSession.ARG_FROM_KEYGUARD, true) }, 0)
        } catch (e: Exception) {
            JarvisApp.container.appLog.w("Assist", "could not start a session from the lock screen (${e.javaClass.simpleName})")
        }
    }
}
