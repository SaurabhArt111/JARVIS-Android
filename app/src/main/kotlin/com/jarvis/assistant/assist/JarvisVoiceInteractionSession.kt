package com.jarvis.assistant.assist

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.platform.Intents
import com.jarvis.assistant.ui.AssistantActivity

/**
 * One use of the assistant gesture. This session draws nothing itself: [onShow] opens JARVIS's own screen and hides the session's (empty) window.
 *  - Unlocked, or locked without a secure lock: the compact chat ([Intents.ASSISTANT_ACTIVITY]), listening at once.
 *  - Locked with a secure lock: the lock-screen assistant, which applies the lock-screen privacy rules and never shows chat history.
 *
 * The session also receives the "assist data" Android collects for assistants (a description of what is on screen, and optionally a screenshot) through
 * onHandleAssist and onHandleScreenshot. JARVIS does not override those, so that data is dropped without being read, stored or sent anywhere; the
 * user can additionally turn "Use text from screen" and "Use screenshot" off under Settings > Apps > Default apps > Digital assistant app.
 */
class JarvisVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    companion object {
        const val ARG_FROM_KEYGUARD = "com.jarvis.assistant.extra.FROM_KEYGUARD"
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val c = JarvisApp.container
        val fromKeyguard = args?.getBoolean(ARG_FROM_KEYGUARD, false) == true
        val km = context.getSystemService(KeyguardManager::class.java)
        val locked = km?.isKeyguardLocked == true
        val secure = km?.isDeviceSecure != false          // when unsure, treat as secure
        val target = if (locked && secure) Intents.LOCK_ACTIVITY else Intents.ASSISTANT_ACTIVITY
        val intent = Intent().setClassName(context.packageName, target)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(AssistantActivity.EXTRA_LISTEN, true)
            // The assistant gesture on a dark, locked phone lights the screen only when it came from the lock-screen shortcut, which is itself a deliberate press.
            .putExtra(Intents.EXTRA_TURN_SCREEN_ON, fromKeyguard)
        var started = false
        try { context.startActivity(intent); started = true } catch (e: Exception) {
            c.appLog.w("Assist", "startActivity from the session failed (${e.javaClass.simpleName}); trying startAssistantActivity")
        }
        if (!started) {
            try { startAssistantActivity(intent); started = true } catch (e: Exception) {
                c.appLog.w("Assist", "startAssistantActivity failed (${e.javaClass.simpleName})")
            }
        }
        c.appLog.i("Assist", "assistant gesture: opened ${if (locked && secure) "lock-screen assistant" else "chat"} (started=$started)")
        hide()
    }
}
