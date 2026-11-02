package com.jarvis.assistant.ui.kit

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Title bar + content + bottom navigation, and a back stack of [Screen]s, built in code. Handles insets (status bar, cutout, gesture bar, keyboard) and keeps the
 * content to a readable width (<= 720 dp) on tablets and unfolded foldables.
 */
class ScreenHost(val activity: BaseActivity, private val tabs: List<Tab>, private val factory: (String) -> Screen?) {
    data class Tab(val id: String, val label: String, val icon: String)

    private val stack = ArrayList<Screen>()
    val root = FrameLayout(activity)
    private val page = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val topBar = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val content = FrameLayout(activity)
    private val nav = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
    private var insets = InsetsPx(0, 0, 0, 0, 0)

    val ui: Ui get() = activity.ui
    /** False for a sheet that does not reach the top of the screen (the long-press-Home assistant): no status-bar padding at its top. */
    var topInsetEnabled = true
    val current: Screen? get() = stack.lastOrNull()
    val depth: Int get() = stack.size

    init {
        page.addView(topBar, LinearLayout.LayoutParams(MATCH, WRAP))
        page.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))
        page.addView(nav, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(page, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER_HORIZONTAL))
        Edge.listen(root) { insets = it; applyInsets() }
    }

    // ------------------------------------------------------------------------------------------------ navigation
    fun openTab(id: String) {
        val s = factory(id) ?: return
        if (stack.size == 1 && stack[0].id == id) return
        val old = ArrayList(stack); stack.clear()
        old.forEach { it.onHidden(); it.destroyView(); it.onClosed() }
        attach(s)
    }

    fun push(s: Screen) { current?.let { it.onHidden(); it.destroyView() }; attach(s) }

    private fun attach(s: Screen) {
        s.host = this
        stack.add(s)
        render()
        s.onShown()
    }

    /** Goes back one screen. False when already at the root (the activity decides what to do). */
    fun back(): Boolean {
        val top = current ?: return false
        if (top.onBack()) return true
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        top.onHidden(); top.destroyView(); top.onClosed()
        render()
        current?.onShown()
        return true
    }

    /** Rebuilds the visible screen (rotation, theme/font change). View models are kept. */
    fun rebuild() { render() }

    fun saveIds(): ArrayList<String> = ArrayList(stack.map { it.id })

    fun restore(ids: List<String>): Boolean {
        val screens = ids.mapNotNull { factory(it) }
        if (screens.isEmpty()) return false
        screens.forEach { s -> s.host = this; stack.add(s) }
        render(); current?.onShown()
        return true
    }

    fun onActivityResumed() { current?.onActivityResumed() }

    // ------------------------------------------------------------------------------------------------ drawing
    private fun render() {
        val s = current ?: return
        val ui = ui
        root.setBackgroundColor(ui.pal.bg)
        content.removeAllViews()
        content.addView(s.buildView(), FrameLayout.LayoutParams(MATCH, MATCH))
        buildTopBar(s)
        buildNav(s)
        applyInsets()
    }

    private fun buildTopBar(s: Screen) {
        topBar.removeAllViews()
        if (s.ownHeader) { topBar.visibility = View.GONE; return }
        topBar.visibility = View.VISIBLE
        val ui = ui
        if (!s.topLevel && stack.size > 1) topBar.addView(ui.iconButton("chevron-left", "Back") { activity.onBackPressed() })
        else topBar.addView(View(activity), LinearLayout.LayoutParams(ui.dp(12), 1))
        topBar.addView(ui.text(s.title(), 20f, bold = true, maxLines = 1).apply { (this as TextView).contentDescription = s.title() }, LinearLayout.LayoutParams(0, WRAP, 1f))
        s.actions().forEach { a -> topBar.addView(ui.iconButton(a.icon, a.description) { a.run() }) }
        topBar.minimumHeight = ui.dp(56)
    }

    private fun buildNav(s: Screen) {
        nav.removeAllViews()
        val ui = ui
        if (!s.topLevel) { nav.visibility = View.GONE; return }
        nav.visibility = View.VISIBLE
        nav.background = ui.rounded(ui.pal.panel, 0, ui.pal.border).also { }
        tabs.forEach { t ->
            val sel = stack.firstOrNull()?.id == t.id
            val tint = if (sel) ui.pal.accent else ui.pal.muted
            val item = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setPadding(0, ui.dp(8), 0, ui.dp(8)); minimumHeight = ui.dp(56)
                background = ui.ripple(null, 12); isClickable = true; isFocusable = true
                contentDescription = t.label + if (sel) ", selected" else ""
                addView(ui.icon(t.icon, 22, tint))
                addView(ui.text(t.label, 11f, tint, bold = sel).apply { setPadding(0, ui.dp(3), 0, 0) })
                setOnClickListener { openTab(t.id) }
            }
            nav.addView(item, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
    }

    private fun applyInsets() {
        val ui = ui
        val i = if (topInsetEnabled) insets else insets.copy(top = 0)
        val screenW = activity.resources.displayMetrics.widthPixels
        val maxW = ui.dp(720)
        val availW = screenW - i.left - i.right
        val w = if (availW > maxW) maxW else MATCH
        (page.layoutParams as FrameLayout.LayoutParams).apply { width = w; gravity = Gravity.CENTER_HORIZONTAL }
        root.setPadding(i.left, 0, i.right, 0)
        topBar.setPadding(ui.dp(4), i.top, ui.dp(4), 0)
        val imeUp = i.imeBottom > i.bottom
        nav.visibility = if (current?.topLevel == true && !imeUp) View.VISIBLE else View.GONE
        nav.setPadding(0, 0, 0, i.bottom)
        content.setPadding(0, if (current?.ownHeader == true) i.top else 0, 0, if (current?.topLevel == true && !imeUp) 0 else maxOf(i.bottom, i.imeBottom))
        page.requestLayout()
    }
}
