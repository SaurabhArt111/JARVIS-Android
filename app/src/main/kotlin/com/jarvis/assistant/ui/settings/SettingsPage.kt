package com.jarvis.assistant.ui.settings

import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.jarvis.assistant.ui.kit.Dialogs
import com.jarvis.assistant.ui.kit.MATCH
import com.jarvis.assistant.ui.kit.Screen
import com.jarvis.assistant.ui.kit.Themes
import com.jarvis.assistant.ui.kit.Ui
import com.jarvis.assistant.ui.kit.WRAP
import com.jarvis.assistant.ui.kit.add
import com.jarvis.core.format.Formats

/**
 * Base for the Settings pages: a scrolling column of grouped rows bound straight to [com.jarvis.core.settings.Settings]. Rows update themselves in place, so
 * toggling something does not jump the scroll position; the scroll position also survives rotation.
 */
abstract class SettingsPage : Screen() {
    private var scrollPos = 0
    protected val settings get() = app.settings
    protected fun s(id: Int, vararg a: Any): String = activity.getString(id, *a)

    protected abstract fun fill(c: LinearLayout)

    override fun build(): View {
        val c = ui.column(padH = 16, padV = 8, gap = 10)
        fill(c)
        c.addView(ui.spacer(32))
        val sv: ScrollView = ui.scroll(c)
        sv.viewTreeObserver.addOnScrollChangedListener { scrollPos = sv.scrollY }
        sv.post { sv.scrollTo(0, scrollPos) }
        return sv
    }

    // ------------------------------------------------------------------------------------------------ building blocks
    /** A titled card whose children are rows separated by hairlines. */
    protected fun LinearLayout.group(title: String? = null, note: String? = null, rows: LinearLayout.() -> Unit) {
        if (title != null) addView(ui.sectionHeader(title))
        val card = ui.card(0, 4, 0, 18) {}
        val inner = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        inner.rows()
        // hairlines between rows
        val kids = (0 until inner.childCount).map { inner.getChildAt(it) }
        inner.removeAllViews()
        kids.forEachIndexed { i, v -> if (i > 0) inner.addView(ui.divider().apply { (layoutParams as LinearLayout.LayoutParams).setMargins(ui.dp(16), 0, 0, 0) }); inner.addView(v) }
        card.addView(inner)
        addView(card)
        if (note != null) addView(ui.muted(note, 12.5f).apply { setPadding(ui.dp(6), ui.dp(2), ui.dp(6), 0) })
    }

    protected fun LinearLayout.paragraph(text: String, color: Int = ui.pal.muted) { addView(ui.text(text, 13.5f, color).apply { setPadding(ui.dp(6), ui.dp(4), ui.dp(6), ui.dp(4)) }) }

    protected fun LinearLayout.link(title: String, subtitle: String? = null, icon: String? = null, value: String? = null, onClick: () -> Unit) { addView(ui.settingRow(title, subtitle, value, icon, true, onClick)) }

    /** On/off switch stored as a flag. [onChange] runs after the value was saved (return false from [allow] to refuse the change, e.g. a missing permission). */
    protected fun LinearLayout.flag(title: String, subtitle: String? = null, key: String, default: Boolean = false, enabled: Boolean = true, allow: ((Boolean) -> Boolean)? = null, onChange: ((Boolean) -> Unit)? = null) {
        addView(ui.switchRow(title, subtitle, settings.flag(key, default), enabled) { v ->
            if (allow != null && !allow(v)) { host.rebuild(); return@switchRow }
            settings.setFlag(key, v); onChange?.invoke(v)
        })
    }

    /** A row showing the current choice; tapping opens a list. [options] = value to label. */
    protected fun LinearLayout.choice(title: String, key: String, options: List<Pair<String, String>>, default: String, subtitle: String? = null, onChange: ((String) -> Unit)? = null) {
        fun label(): String = options.firstOrNull { it.first.equals(settings.get(key, default), true) }?.second ?: settings.get(key, default)
        val row = ui.settingRow(title, subtitle, label(), null, true) {}
        val valueView = lastValueView(row)
        row.setOnClickListener {
            val cur = options.indexOfFirst { it.first.equals(settings.get(key, default), true) }
            Dialogs.choose(activity, ui, title, options.map { it.second }, cur) { i ->
                settings.set(key, options[i].first); valueView?.text = label(); onChange?.invoke(options[i].first)
            }
        }
        addView(row)
    }

    /** Free text or a number. [validate] returns an error message, or null when the value is fine. Blank resets to the default. */
    protected fun LinearLayout.textRow(title: String, key: String, hint: String, subtitle: String? = null, validate: ((String) -> String?)? = null, onChange: ((String) -> Unit)? = null) {
        fun shown(): String = settings.get(key).let { if (it.isBlank()) s(com.jarvis.assistant.R.string.ui_not_set) else Formats.oneLine(it, 28) }
        val row = ui.settingRow(title, subtitle, shown(), null, true) {}
        val valueView = lastValueView(row)
        row.setOnClickListener { askText(title, hint, settings.get(key), validate) { v -> settings.set(key, v.ifBlank { null }); valueView?.text = shown(); onChange?.invoke(v) } }
        addView(row)
    }

    private fun askText(title: String, hint: String, initial: String, validate: ((String) -> String?)?, onSave: (String) -> Unit) {
        Dialogs.show(activity, ui) { c, dismiss ->
            c.addView(ui.title(title, 19f))
            val f = ui.field(hint, initial)
            val err = ui.text("", 13f, ui.pal.err).apply { visibility = View.GONE }
            c.addView(f); c.addView(err)
            c.addView(ui.row(12) {
                add(ui.button(s(com.jarvis.assistant.R.string.ui_cancel), Ui.Kind.GHOST) { dismiss() }, 0, WRAP, 1f)
                add(ui.button(s(com.jarvis.assistant.R.string.ui_save)) {
                    val v = f.text.toString().trim()
                    val e = if (v.isEmpty()) null else validate?.invoke(v)
                    if (e != null) { err.text = e; err.visibility = View.VISIBLE } else { dismiss(); onSave(v) }
                }, 0, WRAP, 1f)
            })
        }
    }

    /** An API key: never shown in full, stored encrypted (Android Keystore), can be replaced or removed. */
    protected fun LinearLayout.secretRow(title: String, key: String, whereTo: String, onChange: (() -> Unit)? = null) {
        fun shown(): String = if (settings.hasSecret(key)) Formats.maskSecret(settings.get(key)).ifEmpty { "••••••••" } else s(com.jarvis.assistant.R.string.ui_not_set)
        val row = ui.settingRow(title, null, shown(), "key", true) {}
        val valueView = lastValueView(row)
        row.setOnClickListener {
            Dialogs.show(activity, ui) { c, dismiss ->
                c.addView(ui.title(title, 19f))
                c.addView(ui.muted(s(com.jarvis.assistant.R.string.ui_secret_help, whereTo), 13f))
                val f = ui.field(s(com.jarvis.assistant.R.string.ui_secret_hint), secret = true)
                c.addView(f)
                c.addView(ui.row(8) {
                    if (settings.hasSecret(key)) add(ui.button(s(com.jarvis.assistant.R.string.ui_remove), Ui.Kind.DANGER) { dismiss(); settings.set(key, null); valueView?.text = shown(); onChange?.invoke() }, 0, WRAP, 1f)
                    add(ui.button(s(com.jarvis.assistant.R.string.ui_cancel), Ui.Kind.GHOST) { dismiss() }, 0, WRAP, 1f)
                    add(ui.button(s(com.jarvis.assistant.R.string.ui_save)) { val v = f.text.toString(); if (v.isNotBlank()) { settings.set(key, v); valueView?.text = shown(); onChange?.invoke() }; dismiss() }, 0, WRAP, 1f)
                })
            }
        }
        addView(row)
    }

    /** The value TextView that [Ui.settingRow] placed in the row (the last TextView before the chevron), so it can be updated in place. */
    private fun lastValueView(row: LinearLayout): TextView? {
        for (i in row.childCount - 1 downTo 0) { val v = row.getChildAt(i); if (v is TextView) return v }
        return null
    }
}
