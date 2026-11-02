package com.jarvis.assistant.ui.kit

import android.content.Context
import com.jarvis.assistant.R

/**
 * Icon names -> the Feather vector drawables in res/drawable (ic_<name with _>). Replies carry icon names ("check", "bell", "message" ...): some are aliases for the
 * nearest Feather glyph, and unknown names fall back to "info" instead of showing nothing.
 */
object Icons {
    private val ALIAS = mapOf(
        "alert" to "alert_circle", "message" to "message_circle", "users" to "user", "contacts" to "user", "phone_call" to "phone",
        "brightness" to "sun", "flashlight" to "zap", "torch" to "zap", "calculator" to "hash", "web" to "globe", "weather" to "cloud",
        "timer" to "clock", "alarm" to "clock", "note" to "file_text", "notes" to "file_text", "reminder" to "bell",
        "share" to "share_2", "trash" to "trash_2", "delete" to "trash_2", "add" to "plus", "more" to "more_vertical", "back" to "arrow_left",
    )
    private val cache = HashMap<String, Int>()

    /** Drawable resource id for [name], never 0 (falls back to the info glyph). */
    fun res(ctx: Context, name: String): Int = synchronized(cache) {
        val key = name.lowercase().trim().replace('-', '_').ifEmpty { "info" }
        cache.getOrPut(key) {
            val id = ctx.resources.getIdentifier("ic_" + (ALIAS[key] ?: key), "drawable", ctx.packageName)
            if (id != 0) id else R.drawable.ic_info
        }
    }
}
