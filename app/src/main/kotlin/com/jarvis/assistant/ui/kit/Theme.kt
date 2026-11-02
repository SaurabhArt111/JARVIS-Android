package com.jarvis.assistant.ui.kit

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import com.jarvis.core.settings.Keys
import com.jarvis.core.settings.Settings

/** Colour tokens for one theme (light or dark) + accent. Values come from the desktop JARVIS palette (Frontend/GUI/theme.py). */
class Palette(
    val dark: Boolean,
    val bg: Int, val bg2: Int, val panel: Int, val panel2: Int, val border: Int, val hover: Int,
    val text: Int, val muted: Int, val ok: Int, val warn: Int, val err: Int,
    val accent: Int, val accent2: Int, val user: Int, val onAccent: Int,
)

object Themes {
    /** name -> (dark-mode accent, light-mode accent, secondary) */
    val ACCENTS: Map<String, Triple<String, String, String>> = linkedMapOf(
        "Cyan" to Triple("#22D3EE", "#0891B2", "#4C9BFF"),
        "Blue" to Triple("#4C9BFF", "#0A6CFF", "#8B7BFF"),
        "Indigo" to Triple("#8E8CFF", "#5856D6", "#FF6AD5"),
        "Teal" to Triple("#3CCFD0", "#0E9AA7", "#4C9BFF"),
        "Green" to Triple("#4ADE80", "#1F9D55", "#3CCFD0"),
        "Orange" to Triple("#FFA73C", "#E4780A", "#FF5C7A"),
        "Pink" to Triple("#FF6B9A", "#E0306A", "#B07CFF"),
        "Graphite" to Triple("#A8B0BF", "#4B5563", "#8B7BFF"),
    )
    val MODES = listOf("System", "Light", "Dark")
    val SIZES = linkedMapOf("Small" to 0.9f, "Medium" to 1.0f, "Large" to 1.15f)

    fun isDark(ctx: Context, settings: Settings): Boolean = when (settings.get(Keys.THEME_MODE, "System")) {
        "Dark" -> true
        "Light" -> false
        else -> (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    fun fontScale(settings: Settings): Float = SIZES[settings.get(Keys.FONT_SIZE, "Medium")] ?: 1f

    private fun c(hex: String) = Color.parseColor(hex)

    private fun mix(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(), (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
    )

    fun palette(ctx: Context, settings: Settings): Palette {
        val dark = isDark(ctx, settings)
        val (d, l, a2) = ACCENTS[settings.get(Keys.ACCENT, "Cyan")] ?: ACCENTS.getValue("Cyan")
        val accent = c(if (dark) d else l)
        return if (dark) {
            val panel = c("#1A1C22")
            Palette(true, c("#0F1014"), c("#15161B"), panel, c("#22242C"), c("#2B2E38"), c("#262932"), c("#F3F4F7"), c("#9CA3B0"),
                c("#34D17A"), c("#FFB340"), c("#FF6259"), accent, c(a2), mix(panel, accent, 0.22f), Color.WHITE)
        } else {
            val panel = c("#FFFFFF")
            Palette(false, c("#F3F4F8"), c("#ECEEF3"), panel, c("#F5F6FA"), c("#E2E5EC"), c("#EEF0F5"), c("#1A1C21"), c("#6A7280"),
                c("#1E9E57"), c("#D9780A"), c("#D93B30"), accent, c(a2), mix(panel, accent, 0.12f), Color.WHITE)
        }
    }

    fun withAlpha(color: Int, alpha: Int): Int = Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
}
