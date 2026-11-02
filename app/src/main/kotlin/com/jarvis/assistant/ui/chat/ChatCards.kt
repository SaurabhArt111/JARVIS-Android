package com.jarvis.assistant.ui.chat

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.jarvis.assistant.R
import com.jarvis.assistant.di.AppContainer
import com.jarvis.assistant.ui.kit.MATCH
import com.jarvis.assistant.ui.kit.Themes
import com.jarvis.assistant.ui.kit.Ui
import com.jarvis.assistant.ui.kit.WRAP
import com.jarvis.assistant.ui.kit.add
import com.jarvis.core.format.Formats
import com.jarvis.core.format.MonthGrid
import com.jarvis.core.model.Card
import java.time.LocalDate

/** Draws the rich card under a reply (weather, timer, calendar, images, ...). Every card type degrades to a plain title + lines, so an unknown type still shows something. */
class ChatCards(private val ui: Ui, private val app: AppContainer, private val host: Host) {
    interface Host {
        fun openFile(uri: String, mime: String, name: String)
        fun shareFile(uri: String, mime: String, name: String)
    }

    private val pal get() = ui.pal
    private fun s(id: Int, vararg a: Any) = if (a.isEmpty()) ui.ctx.getString(id) else ui.ctx.getString(id, *a)

    fun render(card: Card): View? = when (card.type) {
        "weather" -> weather(card)
        "timer" -> timer(card)
        "calendar" -> calendar(card)
        "music" -> music(card)
        "level" -> level(card)
        "system" -> system(card)
        "images" -> images(card)
        "generating" -> generating(card)
        "file" -> file(card)
        "items", "choice" -> items(card)
        "browse", "info" -> info(card)
        else -> if (card.title.isNotBlank() || card.items.isNotEmpty()) items(card) else null
    }

    private fun surface(build: LinearLayout.() -> Unit): LinearLayout =
        ui.card(14, 12, 8, 14, pal.panel2, build)

    private fun header(card: Card, fallbackIcon: String): LinearLayout = ui.row(10) {
        addView(ui.icon(card.icon.ifBlank { fallbackIcon }, 20, pal.accent))
        if (card.title.isNotBlank()) addView(ui.text(card.title, 15f, bold = true), LinearLayout.LayoutParams(0, WRAP, 1f))
    }

    private fun info(card: Card): View? {
        if (card.title.isBlank() && card.subtitle.isBlank()) return null
        return surface {
            addView(ui.row(12) {
                addView(ui.icon(card.icon.ifBlank { "info" }, 22, pal.accent))
                addView(ui.column(gap = 2) {
                    if (card.title.isNotBlank()) addView(ui.text(card.title, 18f, bold = true))
                    if (card.subtitle.isNotBlank()) addView(ui.muted(card.subtitle, 13.5f))
                }, LinearLayout.LayoutParams(0, WRAP, 1f))
            })
        }
    }

    private fun items(card: Card): View = surface {
        if (card.title.isNotBlank() || card.icon.isNotBlank()) addView(header(card, "list"))
        card.items.take(8).forEachIndexed { i, it ->
            if (i > 0 || card.title.isNotBlank()) addView(ui.divider())
            addView(ui.column(gap = 2) {
                addView(ui.text(it.text, 14.5f))
                if (it.sub.isNotBlank()) addView(ui.muted(it.sub, 12.5f))
            })
        }
        if (card.items.size > 8) addView(ui.muted(s(R.string.ui_card_more, card.items.size - 8), 12.5f))
    }

    // ------------------------------------------------------------------------------------------------ weather
    private fun weather(c: Card): View = surface {
        val f = c.fields
        addView(ui.row(14) {
            addView(ui.text((f["temp"] ?: "?") + "°", 40f, bold = true))
            addView(ui.column(gap = 2) {
                addView(ui.text(c.title, 16f, bold = true))
                addView(ui.muted(f["desc"] ?: c.subtitle, 13.5f))
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(ui.icon(c.icon.ifBlank { "cloud" }, 32, pal.accent))
        })
        addView(ui.row(8) {
            f["feels"]?.let { addView(chip(s(R.string.ui_weather_feels, it))) }
            f["humidity"]?.let { addView(chip(s(R.string.ui_weather_humidity, it))) }
            f["wind"]?.let { addView(chip(s(R.string.ui_weather_wind, it))) }
        })
    }

    private fun chip(text: String): TextView = ui.text(text, 12.5f, pal.muted).apply {
        setPadding(ui.dp(10), ui.dp(5), ui.dp(10), ui.dp(5)); background = ui.rounded(pal.panel, 12, pal.border)
    }

    // ------------------------------------------------------------------------------------------------ timer
    private fun timer(c: Card): View = surface {
        val end = c.fields["endsAtMs"]?.toLongOrNull() ?: 0L
        addView(ui.row(12) {
            addView(ui.icon("clock", 22, pal.accent))
            addView(ui.column(gap = 2) {
                addView(ui.text(c.fields["label"] ?: c.title, 15f, bold = true))
                if (c.subtitle.isNotBlank()) addView(ui.muted(c.subtitle, 12.5f))
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (end > 0) addView(CountdownText(ui, end, s(R.string.ui_timer_done)))
        })
    }

    // ------------------------------------------------------------------------------------------------ calendar
    private fun calendar(c: Card): View = surface {
        val date = MonthGrid.parse(c.fields["date"]) ?: LocalDate.now()
        val marks = MonthGrid.marksIn(date, c.fields["marks"])
        addView(ui.text(date.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault()) + " " + date.year, 15f, bold = true))
        val first = java.time.temporal.WeekFields.of(java.util.Locale.getDefault()).firstDayOfWeek
        addView(ui.row { MonthGrid.headers(first).forEach { addView(dayCell(it.getDisplayName(java.time.format.TextStyle.NARROW, java.util.Locale.getDefault()), false, false, true), LinearLayout.LayoutParams(0, WRAP, 1f)) } })
        MonthGrid.weeks(date, first).forEach { week ->
            addView(ui.row { week.forEach { d -> addView(dayCell(d?.toString() ?: "", d != null && d == date.dayOfMonth, d != null && d in marks, false), LinearLayout.LayoutParams(0, WRAP, 1f)) } })
        }
        c.items.take(6).forEach { addView(ui.divider()); addView(ui.column(gap = 2) { addView(ui.text(it.text, 14f)); if (it.sub.isNotBlank()) addView(ui.muted(it.sub, 12f)) }) }
    }

    private fun dayCell(text: String, today: Boolean, marked: Boolean, head: Boolean): View = FrameLayout(ui.ctx).apply {
        minimumHeight = ui.dp(32)
        if (today) background = ui.rounded(pal.accent, 16)
        addView(ui.text(text, 12.5f, if (today) pal.onAccent else if (head) pal.muted else pal.text, bold = today || marked), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        if (marked && !today) addView(View(ui.ctx).apply { background = ui.rounded(pal.accent, 3) }, FrameLayout.LayoutParams(ui.dp(5), ui.dp(5), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = ui.dp(2) })
    }

    // ------------------------------------------------------------------------------------------------ music
    private fun music(c: Card): View = surface {
        addView(ui.row(12) {
            addView(ui.icon("music", 22, pal.accent))
            addView(ui.column(gap = 2) {
                addView(ui.text(c.title.ifBlank { s(R.string.ui_music_unknown) }, 15f, bold = true, maxLines = 1))
                if (c.subtitle.isNotBlank()) addView(ui.muted(c.subtitle, 13f, 1))
                val app = c.fields["app"].orEmpty(); val st = c.fields["state"].orEmpty().lowercase().replace('_', ' ')
                if (app.isNotBlank() || st.isNotBlank()) addView(ui.muted(listOf(app, st).filter { it.isNotBlank() }.joinToString(" · "), 12f))
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
        })
        val pos = c.fields["position"]?.toLongOrNull() ?: -1L; val dur = c.fields["duration"]?.toLongOrNull() ?: 0L
        if (pos >= 0 && dur > 0) {
            addView(bar((pos * 100 / dur).toInt().coerceIn(0, 100)))
            addView(ui.row { addView(ui.muted(Formats.clock(pos), 11.5f), LinearLayout.LayoutParams(0, WRAP, 1f)); addView(ui.muted(Formats.clock(dur), 11.5f)) })
        }
    }

    private fun bar(percent: Int, color: Int = pal.accent): View = ProgressBar(ui.ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100; progress = percent
        progressTintList = ColorStateList.valueOf(color); progressBackgroundTintList = ColorStateList.valueOf(Themes.withAlpha(pal.muted, 80))
        layoutParams = LinearLayout.LayoutParams(MATCH, ui.dp(8))
        contentDescription = "$percent%"
    }

    // ------------------------------------------------------------------------------------------------ level (volume, brightness)
    private fun level(c: Card): View = surface {
        val v = c.fields["value"]?.toIntOrNull()?.coerceIn(0, 100) ?: 0
        val muted = c.fields["muted"] == "true"
        addView(ui.row(10) {
            addView(ui.icon(c.icon.ifBlank { "volume-2" }, 20, pal.accent))
            addView(ui.text(c.title, 15f, bold = true), LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(ui.text(if (muted) s(R.string.ui_level_muted) else "$v%", 15f, if (muted) pal.warn else pal.text, bold = true))
        })
        addView(bar(if (muted) 0 else v, if (muted) pal.warn else pal.accent))
    }

    // ------------------------------------------------------------------------------------------------ system
    private fun system(c: Card): View = surface {
        val pct = c.fields["percent"]?.toIntOrNull()
        if (pct != null) {
            val charging = c.fields["charging"] == "true"
            addView(ui.row(12) {
                addView(ui.icon(c.icon.ifBlank { "battery" }, 22, pal.accent))
                addView(ui.text("$pct%", 26f, bold = true))
                addView(ui.muted(if (charging) s(R.string.ui_battery_charging) else s(R.string.ui_battery_unplugged), 13f), LinearLayout.LayoutParams(0, WRAP, 1f))
            })
            addView(bar(pct, if (pct <= 15 && !charging) pal.err else if (pct <= 30 && !charging) pal.warn else pal.ok))
        } else {
            if (c.title.isNotBlank()) addView(header(c, "cpu"))
            c.items.take(10).forEach { addView(ui.row(8) { addView(ui.muted(it.text, 13f), LinearLayout.LayoutParams(0, WRAP, 1f)); addView(ui.text(it.sub, 13f)) }) }
        }
    }

    // ------------------------------------------------------------------------------------------------ images
    private fun images(c: Card): View = surface {
        if (c.title.isNotBlank()) addView(header(c, "image"))
        if (c.subtitle.isNotBlank()) addView(ui.muted(c.subtitle, 12.5f, 2))
        val shown = c.items.take(4)
        val size = ui.dp(if (shown.size == 1) 220 else 120)
        val grid = com.jarvis.assistant.ui.kit.FlowLayout(ui.ctx, ui.dp(8))
        shown.forEach { it ->
            val iv = ImageView(ui.ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = ui.rounded(pal.panel, 12, pal.border)
                clipToOutline = true; outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                contentDescription = it.text
                isClickable = true; isFocusable = true
                layoutParams = LinearLayout.LayoutParams(size, size)
                setOnClickListener { _ -> host.openFile(it.sub, "image/*", it.text) }
            }
            app.images.load(it.sub, 480, iv, placeholder = null)
            grid.addView(iv, android.view.ViewGroup.LayoutParams(size, size))
        }
        addView(grid)
    }

    private fun generating(c: Card): View = surface {
        addView(ui.row(12) {
            addView(ProgressBar(ui.ctx).apply { indeterminateTintList = ColorStateList.valueOf(pal.accent); layoutParams = LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)) })
            addView(ui.column(gap = 2) {
                addView(ui.text(c.title.ifBlank { s(R.string.ui_creating_image) }, 15f, bold = true))
                if (c.subtitle.isNotBlank()) addView(ui.muted(c.subtitle, 12.5f, 2))
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
        })
    }

    // ------------------------------------------------------------------------------------------------ file
    private fun file(c: Card): View = surface {
        val uri = c.fields["uri"].orEmpty(); val mime = c.fields["mime"].orEmpty()
        addView(ui.row(12) {
            addView(ui.icon(c.icon.ifBlank { "file-text" }, 22, pal.accent))
            addView(ui.column(gap = 2) {
                addView(ui.text(c.title.ifBlank { s(R.string.ui_file_saved) }, 14f, pal.muted))
                addView(ui.text(c.subtitle, 15f, bold = true))
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
        })
        if (uri.isNotBlank()) addView(ui.row(8) {
            add(ui.button(s(R.string.ui_open), Ui.Kind.GHOST) { host.openFile(uri, mime, c.subtitle) }, 0, WRAP, 1f)
            add(ui.button(s(R.string.ui_share), Ui.Kind.GHOST) { host.shareFile(uri, mime, c.subtitle) }, 0, WRAP, 1f)
        })
    }
}

/** "4:12" counting down to [endsAtMs], ticking once a second while it is on screen only. Shows [doneText] at zero. */
class CountdownText(private val ui: Ui, private val endsAtMs: Long, private val doneText: String) : TextView(ui.ctx) {
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            val left = endsAtMs - System.currentTimeMillis()
            if (left <= 0) { text = doneText; setTextColor(ui.pal.ok); return }
            text = com.jarvis.core.format.Formats.countdown(endsAtMs, System.currentTimeMillis())
            handler.postDelayed(this, 1000 - (System.currentTimeMillis() % 1000))
        }
    }
    init {
        setTextColor(ui.pal.accent); textSize = 22f * ui.fontScale; typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
        includeFontPadding = false
        text = com.jarvis.core.format.Formats.countdown(endsAtMs, System.currentTimeMillis())
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); handler.removeCallbacks(tick); tick.run() }
    override fun onDetachedFromWindow() { handler.removeCallbacks(tick); super.onDetachedFromWindow() }
}
