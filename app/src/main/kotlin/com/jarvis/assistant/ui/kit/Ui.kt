package com.jarvis.assistant.ui.kit

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import kotlin.math.roundToInt

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/**
 * A tiny view toolkit: builds the app's screens in code (no XML layouts, no Material/AndroidX). One [Ui] = one context + one palette + the user's font-size
 * choice. Text sizes are in sp (so Android's own font scale also applies) times the app's Small/Medium/Large setting. Every touch target is at least 48 dp and
 * every icon-only control has a content description.
 */
class Ui(val ctx: Context, val pal: Palette, val fontScale: Float = 1f) {
    val density: Float = ctx.resources.displayMetrics.density
    fun dp(v: Int): Int = (v * density).roundToInt()
    fun dpf(v: Float): Float = v * density

    // ------------------------------------------------------------------------------------------------ drawables
    fun rounded(color: Int, radiusDp: Int = 14, stroke: Int? = null, strokeDp: Int = 1): GradientDrawable = GradientDrawable().apply {
        setColor(color); cornerRadius = dpf(radiusDp.toFloat()); if (stroke != null) setStroke(dp(strokeDp), stroke)
    }

    /** [base] with an accent-tinted ripple on press. */
    fun ripple(base: Drawable?, radiusDp: Int = 14): Drawable {
        val mask = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dpf(radiusDp.toFloat()) }
        return RippleDrawable(ColorStateList.valueOf(Themes.withAlpha(pal.accent, 70)), base, mask)
    }

    // ------------------------------------------------------------------------------------------------ text
    fun text(
        s: CharSequence, size: Float = 15f, color: Int = pal.text, bold: Boolean = false, maxLines: Int = 0, center: Boolean = false, mono: Boolean = false, selectable: Boolean = false,
    ): TextView = TextView(ctx).apply {
        text = s; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, size * fontScale)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        if (mono) typeface = Typeface.MONOSPACE
        if (maxLines > 0) { this.maxLines = maxLines; ellipsize = android.text.TextUtils.TruncateAt.END }
        if (center) gravity = Gravity.CENTER_HORIZONTAL
        setTextIsSelectable(selectable)
        includeFontPadding = false
        setLineSpacing(0f, 1.12f)
    }

    fun muted(s: CharSequence, size: Float = 13f, maxLines: Int = 0) = text(s, size, pal.muted, maxLines = maxLines)
    fun title(s: CharSequence, size: Float = 22f) = text(s, size, pal.text, bold = true)

    // ------------------------------------------------------------------------------------------------ icons
    fun icon(name: String, sizeDp: Int = 22, tint: Int = pal.text, description: String? = null): ImageView = ImageView(ctx).apply {
        setImageResource(Icons.res(ctx, name))
        imageTintList = ColorStateList.valueOf(tint)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        scaleType = ImageView.ScaleType.FIT_CENTER
        if (description != null) { contentDescription = description } else importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Icon-only button (48 dp target). [description] is announced by TalkBack. */
    fun iconButton(name: String, description: String, tint: Int = pal.text, sizeDp: Int = 22, onClick: () -> Unit): FrameLayout = FrameLayout(ctx).apply {
        minimumWidth = dp(48); minimumHeight = dp(48)
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        background = ripple(null, 24)
        isClickable = true; isFocusable = true
        contentDescription = description
        addView(icon(name, sizeDp, tint).apply { layoutParams = FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp), Gravity.CENTER) })
        setOnClickListener { onClick() }
    }

    // ------------------------------------------------------------------------------------------------ buttons & chips
    enum class Kind { PRIMARY, GHOST, DANGER, TEXT }

    fun button(label: String, kind: Kind = Kind.PRIMARY, icon: String? = null, onClick: () -> Unit): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        minimumHeight = dp(48)
        setPadding(dp(18), dp(10), dp(18), dp(10))
        val (bg, fg) = when (kind) {
            Kind.PRIMARY -> rounded(pal.accent, 14) to pal.onAccent
            Kind.GHOST -> rounded(pal.panel2, 14, pal.border) to pal.text
            Kind.DANGER -> rounded(Themes.withAlpha(pal.err, 36), 14, pal.err) to pal.err
            Kind.TEXT -> null to pal.accent
        }
        background = ripple(bg, 14)
        isClickable = true; isFocusable = true
        if (icon != null) addView(icon(icon, 18, fg).apply { (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(8) })
        addView(text(label, 15f, fg, bold = true))
        contentDescription = label
        setOnClickListener { onClick() }
    }

    fun chip(label: String, selected: Boolean, onClick: () -> Unit): TextView = text(label, 13f, if (selected) pal.onAccent else pal.muted, bold = selected).apply {
        gravity = Gravity.CENTER
        minimumHeight = dp(40)
        setPadding(dp(16), dp(8), dp(16), dp(8))
        background = ripple(if (selected) rounded(pal.accent, 20) else rounded(pal.panel, 20, pal.border), 20)
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
    }

    // ------------------------------------------------------------------------------------------------ containers
    fun column(padH: Int = 0, padV: Int = 0, gap: Int = 0, build: LinearLayout.() -> Unit = {}): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(padH), dp(padV), dp(padH), dp(padV))
        if (gap > 0) { showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE; dividerDrawable = GradientDrawable().apply { setSize(1, dp(gap)); setColor(Color.TRANSPARENT) } }
        build()
    }

    fun row(gap: Int = 0, center: Boolean = true, build: LinearLayout.() -> Unit = {}): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        if (center) gravity = Gravity.CENTER_VERTICAL
        if (gap > 0) { showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE; dividerDrawable = GradientDrawable().apply { setSize(dp(gap), 1); setColor(Color.TRANSPARENT) } }
        build()
    }

    /** Rounded surface. */
    fun card(padH: Int = 16, padV: Int = 14, gap: Int = 8, radius: Int = 18, fill: Int = pal.panel, build: LinearLayout.() -> Unit = {}): LinearLayout =
        column(padH, padV, gap, build).apply { background = rounded(fill, radius, pal.border) }

    fun scroll(content: View, fillViewport: Boolean = false): ScrollView = ScrollView(ctx).apply {
        isFillViewport = fillViewport; clipToPadding = false; isVerticalScrollBarEnabled = false; addView(content, ViewGroup.LayoutParams(MATCH, WRAP))
    }

    fun hscroll(content: View): HorizontalScrollView = HorizontalScrollView(ctx).apply {
        isHorizontalScrollBarEnabled = false; addView(content, ViewGroup.LayoutParams(WRAP, WRAP))
    }

    fun divider(): View = View(ctx).apply { setBackgroundColor(pal.border); layoutParams = LinearLayout.LayoutParams(MATCH, 1.coerceAtLeast(dp(1) / 2)) }

    fun spacer(h: Int = 0, w: Int = 0): View = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(if (w > 0) dp(w) else 0, if (h > 0) dp(h) else 0) }

    fun flexSpace(): View = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(0, 0, 1f) }

    // ------------------------------------------------------------------------------------------------ list rows
    fun sectionHeader(s: String): TextView = text(s.uppercase(), 12f, pal.muted, bold = true).apply { letterSpacing = 0.08f; setPadding(dp(4), dp(16), 0, dp(4)) }

    /** A tappable settings row: title, optional second line, optional value on the right, chevron when it opens something. */
    fun settingRow(title: String, subtitle: String? = null, value: String? = null, icon: String? = null, chevron: Boolean = true, onClick: (() -> Unit)? = null): LinearLayout =
        row(12) {
            minimumHeight = dp(56)
            setPadding(dp(16), dp(10), dp(12), dp(10))
            if (icon != null) addView(icon(icon, 20, pal.accent))
            addView(column(gap = 2) {
                addView(text(title, 15f))
                if (!subtitle.isNullOrBlank()) addView(muted(subtitle, 12.5f))
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (!value.isNullOrBlank()) addView(muted(value, 13f, 1))
            if (onClick != null && chevron) addView(icon("chevron-right", 18, pal.muted))
            if (onClick != null) { background = ripple(null, 14); isClickable = true; isFocusable = true; setOnClickListener { onClick() } }
        }

    fun switchRow(title: String, subtitle: String? = null, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit): LinearLayout = row(12) {
        minimumHeight = dp(56)
        setPadding(dp(16), dp(10), dp(12), dp(10))
        val sw = Switch(ctx)
        fun tint(on: Boolean) {
            sw.thumbTintList = ColorStateList.valueOf(if (on) pal.accent else pal.muted)
            sw.trackTintList = ColorStateList.valueOf(Themes.withAlpha(if (on) pal.accent else pal.muted, 110))
        }
        sw.apply {
            isChecked = checked; isEnabled = enabled; contentDescription = title
            tint(checked)
            setOnCheckedChangeListener { _, v -> tint(v); onChange(v) }
        }
        addView(column(gap = 2) {
            addView(text(title, 15f, if (enabled) pal.text else pal.muted))
            if (!subtitle.isNullOrBlank()) addView(muted(subtitle, 12.5f))
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(sw)
        isClickable = enabled; isFocusable = enabled
        if (enabled) { background = ripple(null, 14); setOnClickListener { sw.toggle() } }
    }

    fun field(hint: String, value: String = "", secret: Boolean = false, multiline: Boolean = false, imeSend: Boolean = false, onText: ((String) -> Unit)? = null): EditText = EditText(ctx).apply {
        setText(value); this.hint = hint
        setTextColor(pal.text); setHintTextColor(pal.muted)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f * fontScale)
        background = rounded(pal.panel2, 12, pal.border)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        minimumHeight = dp(48)
        inputType = when {
            secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        if (multiline) { maxLines = 5; gravity = Gravity.TOP or Gravity.START }
        if (imeSend) imeOptions = EditorInfo.IME_ACTION_SEND
        contentDescription = hint
        if (onText != null) addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { onText(s?.toString().orEmpty()) }
        })
    }

    // ------------------------------------------------------------------------------------------------ states
    fun emptyState(icon: String, title: String, body: String, action: Pair<String, () -> Unit>? = null): LinearLayout = column(32, 48, 10) {
        gravity = Gravity.CENTER_HORIZONTAL
        addView(FrameLayout(ctx).apply {
            background = rounded(Themes.withAlpha(pal.accent, 30), 36)
            addView(icon(icon, 32, pal.accent).apply { layoutParams = FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER) })
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(72))
        })
        addView(text(title, 18f, bold = true, center = true))
        addView(muted(body, 14f).apply { gravity = Gravity.CENTER_HORIZONTAL })
        if (action != null) addView(button(action.first, Kind.PRIMARY) { action.second() })
    }

    fun loadingState(label: String = "Loading…"): LinearLayout = column(32, 48, 12) {
        gravity = Gravity.CENTER_HORIZONTAL
        addView(android.widget.ProgressBar(ctx).apply { indeterminateTintList = ColorStateList.valueOf(pal.accent) })
        addView(muted(label, 14f))
    }

    fun errorState(message: String, retry: (() -> Unit)? = null): LinearLayout =
        emptyState("alert-circle", "Something went wrong", message, retry?.let { "Try again" to it })

    /** A coloured one-line notice (permission missing, offline, ...). */
    fun banner(message: String, color: Int = pal.warn, icon: String = "alert-triangle", action: Pair<String, () -> Unit>? = null): LinearLayout = row(10) {
        setPadding(dp(14), dp(10), dp(10), dp(10))
        background = rounded(Themes.withAlpha(color, 34), 14, Themes.withAlpha(color, 120))
        addView(icon(icon, 18, color))
        addView(text(message, 13.5f), LinearLayout.LayoutParams(0, WRAP, 1f))
        if (action != null) addView(text(action.first, 13.5f, color, bold = true).apply {
            setPadding(dp(10), dp(10), dp(10), dp(10)); minimumHeight = dp(40); gravity = Gravity.CENTER
            isClickable = true; isFocusable = true; background = ripple(null, 12); setOnClickListener { action.second() }
        })
    }
}

fun LinearLayout.add(v: View, width: Int = MATCH, height: Int = WRAP, weight: Float = 0f, top: Int = 0, bottom: Int = 0, start: Int = 0, end: Int = 0, ui: Ui? = null): View {
    val d = ui?.density ?: v.resources.displayMetrics.density
    val lp = LinearLayout.LayoutParams(width, height, weight)
    lp.setMargins((start * d).roundToInt(), (top * d).roundToInt(), (end * d).roundToInt(), (bottom * d).roundToInt())
    addView(v, lp)
    return v
}
