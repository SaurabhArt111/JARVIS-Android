package com.jarvis.assistant.lock

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import com.jarvis.assistant.R
import com.jarvis.assistant.platform.Intents
import com.jarvis.core.settings.Keys
import com.jarvis.core.settings.Settings

/**
 * Extra buttons for the "listening" notification when the lock-screen assistant is on: a text box ("Type a request") answered without opening any screen. The
 * existing "Talk" button already works the same way (the service listens and answers by voice). The typed text goes through the same engine and lock policy as everything
 * else, and the answer is posted as a notification whose text is already redacted for a locked phone.
 *
 * Android decides whether these buttons are visible on a locked phone: that follows the user's system setting for sensitive notification content, so on many phones
 * they only show after unlocking. The Quick Settings tile and the assistant gesture are the dependable ways to reach the assistant from the lock screen.
 */
object LockNotificationActions {
    const val ACTION_TYPED = "${Intents.PKG}.action.LOCK_TYPED"
    const val KEY_TEXT = "lock_typed_text"

    fun enabled(settings: Settings) = settings.flag(Keys.LOCK_MODE_ENABLED, false)

    fun build(ctx: Context, settings: Settings): List<Notification.Action> {
        if (!enabled(settings)) return emptyList()
        val input = RemoteInput.Builder(KEY_TEXT).setLabel(ctx.getString(R.string.lock_type_label)).build()
        // RemoteInput needs a mutable PendingIntent; it is explicit (our own receiver), so nothing else can redirect it.
        val pi = PendingIntent.getBroadcast(
            ctx, 11, Intent(ctx, LockTypedReceiver::class.java).setAction(ACTION_TYPED), PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_edit), ctx.getString(R.string.lock_type_action), pi)
            .addRemoteInput(input).setAllowGeneratedReplies(false)
        // Typing a request does not need the PIN first when the user allowed dictation from the lock screen; otherwise Android asks for it as usual.
        if (Build.VERSION.SDK_INT >= 31 && settings.flag(Keys.LOCK_ALLOW_DICTATION, true)) b.setAuthenticationRequired(false)
        return listOf(b.build())
    }
}
