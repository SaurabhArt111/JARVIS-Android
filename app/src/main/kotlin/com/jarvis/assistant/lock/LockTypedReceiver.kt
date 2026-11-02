package com.jarvis.assistant.lock

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.platform.NotificationHelper
import com.jarvis.core.model.AssistantReply
import com.jarvis.core.model.Origin
import kotlinx.coroutines.launch

/** Receives the text typed into the listening notification's "Type a request" box and runs it like any other request, with the lock state read at that moment. */
class LockTypedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != LockNotificationActions.ACTION_TYPED) return
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(LockNotificationActions.KEY_TEXT)?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        val c = JarvisApp.container
        if (!LockNotificationActions.enabled(c.settings)) return          // switched off since the button was posted
        val pending = goAsync()
        c.scope.launch {
            try {
                val reply = try { c.engine.submit(text, Origin.LOCK_SCREEN, c.lockState.read(), echo = true) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { AssistantReply.error("Something went wrong. Try again.") }
                // the reply is already redacted for a locked phone; the notification helper additionally hides the text on the lock screen unless the user chose OPEN
                c.notificationHelper.postResult(context.getString(com.jarvis.assistant.R.string.app_name), reply.text, NotificationHelper.ID_LOCK + 1)
            } finally { pending.finish() }
        }
    }
}
