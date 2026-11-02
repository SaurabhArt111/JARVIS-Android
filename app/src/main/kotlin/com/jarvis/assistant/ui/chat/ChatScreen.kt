package com.jarvis.assistant.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.jarvis.assistant.R
import com.jarvis.assistant.platform.FileActions
import com.jarvis.assistant.ui.kit.Dialogs
import com.jarvis.assistant.ui.kit.MATCH
import com.jarvis.assistant.ui.kit.OrbView
import com.jarvis.assistant.ui.kit.Screen
import com.jarvis.assistant.ui.kit.Themes
import com.jarvis.assistant.ui.kit.Ui
import com.jarvis.assistant.ui.kit.ViewModels
import com.jarvis.assistant.ui.kit.WRAP
import com.jarvis.assistant.ui.kit.add
import com.jarvis.assistant.ui.kit.collectWhileAttached
import com.jarvis.core.engine.EngineState
import com.jarvis.core.engine.NoticeLevel
import com.jarvis.core.format.TimelineItem
import com.jarvis.core.model.ActionType
import com.jarvis.core.model.AssistantReply
import com.jarvis.core.model.Origin
import com.jarvis.core.model.ReplyKind
import com.jarvis.core.model.Requirement
import com.jarvis.core.model.UiAction
import com.jarvis.core.ports.PermState
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** The conversation: orb header, message list (history + live), progress line, banners and the input row. */
/** What the long-press-Home sheet adds to the chat header: a way to open the full app and a way to close the sheet. */
interface SheetHooks { fun expand(); fun close() }

class ChatScreen(private val sheet: SheetHooks? = null) : Screen(), ChatCards.Host {
    override val id = "chat"
    override val topLevel = true
    override val ownHeader = true
    override fun title() = activity.getString(R.string.ui_tab_chat)

    private val vm: ChatViewModel get() = ViewModels.get("chat") { ChatViewModel(app) }

    private lateinit var list: ListView
    private lateinit var adapter: ChatAdapter
    private lateinit var cards: ChatCards
    private var input: EditText? = null
    private var aiBannerDismissed = false
    private var lastKeys: List<String> = emptyList()
    private var firstRender = true
    private val timeFmt: DateFormat by lazy { DateFormat.getTimeInstance(DateFormat.SHORT) }

    private fun s(id: Int, vararg a: Any) = activity.getString(id, *a)

    override fun onShown() { vm.refreshFlags(); vm.greetIfDue() }
    override fun onActivityResumed() { vm.refreshFlags() }

    override fun onBack(): Boolean {
        if (vm.state.value.searchOpen) { vm.closeSearch(); return true }
        return false
    }

    /** Text handed in from outside (a share sheet, a notification action): put it in the input box, do not send it. */
    fun prefill(text: String) { input?.setText(text); input?.setSelection(input?.text?.length ?: 0) }
    fun sendNow(text: String, origin: Origin = Origin.TEXT) = vm.send(text, origin)
    /** Starts the microphone as if the user had tapped it (used when the assistant gesture opens the sheet). */
    fun startListeningNow() { if (vm.state.value.mic == ChatViewModel.Mic.OFF) vm.toggleListening() }

    // ================================================================================================ build
    override fun build(): View {
        val ui = ui; val pal = ui.pal
        cards = ChatCards(ui, app, this)
        adapter = ChatAdapter()

        val orb = OrbView(activity).apply { setColors(pal.accent, pal.accent2, pal.err); layoutParams = LinearLayout.LayoutParams(ui.dp(52), ui.dp(52)) }
        val status = ui.muted("", 12.5f, 1)
        val muteBtn = ui.iconButton("volume-2", s(R.string.ui_mute)) { vm.toggleMute() }
        val header = ui.row(8) {
            setPadding(ui.dp(12), ui.dp(6), ui.dp(4), ui.dp(6))
            addView(orb)
            addView(ui.column(gap = 2) { addView(ui.text(app.settings.assistantName(), 20f, bold = true, maxLines = 1)); addView(status) }, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (sheet == null) addView(ui.iconButton("search", s(R.string.ui_search)) { vm.openSearch() })
            addView(muteBtn)
            if (sheet == null) addView(ui.iconButton("more-vertical", s(R.string.ui_more)) { showMenu() })
            else {
                addView(ui.iconButton("maximize-2", s(R.string.ui_open_app)) { sheet.expand() })
                addView(ui.iconButton("x", s(R.string.ui_close)) { sheet.close() })
            }
        }
        val searchBar = ui.row(4) {
            setPadding(ui.dp(4), ui.dp(6), ui.dp(12), ui.dp(6))
            addView(ui.iconButton("arrow-left", s(R.string.ui_close_search)) { vm.closeSearch() })
            val f = ui.field(s(R.string.ui_search_hint), vm.state.value.searchQuery) { vm.search(it) }
            addView(f, LinearLayout.LayoutParams(0, WRAP, 1f))
        }

        val bannerHolder = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(12), 0, ui.dp(12), ui.dp(6)) }

        list = ListView(activity).apply {
            divider = null; dividerHeight = 0; selector = android.graphics.drawable.ColorDrawable(0)
            isVerticalScrollBarEnabled = false; clipToPadding = false; setPadding(0, ui.dp(4), 0, ui.dp(8))
            transcriptMode = AbsListView.TRANSCRIPT_MODE_DISABLED
            this.adapter = this@ChatScreen.adapter
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(v: AbsListView, st: Int) {}
                override fun onScroll(v: AbsListView, first: Int, visible: Int, total: Int) { if (first == 0 && total > 0) vm.loadOlder() }
            })
        }
        val emptyHolder = FrameLayout(activity)
        val body = FrameLayout(activity).apply {
            addView(list, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(emptyHolder, FrameLayout.LayoutParams(MATCH, MATCH))
        }

        // ---- progress line
        val progressText = ui.muted("", 13f, 2)
        val spinner = ProgressBar(activity).apply { indeterminateTintList = android.content.res.ColorStateList.valueOf(pal.accent); layoutParams = LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)) }
        val stopBtn = ui.text(s(R.string.ui_stop), 13f, pal.accent, bold = true).apply {
            setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10)); minimumHeight = ui.dp(40); gravity = Gravity.CENTER
            background = ui.ripple(null, 20); isClickable = true; isFocusable = true; setOnClickListener { vm.stop() }
        }
        val progressRow = ui.row(10) {
            setPadding(ui.dp(18), ui.dp(4), ui.dp(10), ui.dp(4)); visibility = View.GONE
            addView(spinner); addView(progressText, LinearLayout.LayoutParams(0, WRAP, 1f)); addView(stopBtn)
        }

        // ---- input row
        val field = ui.field(s(R.string.ui_input_hint), multiline = true).apply { maxLines = 4 }
        input = field
        val micBtn = ui.iconButton("mic", s(R.string.ui_mic)) { vm.toggleListening() }
        val sendBtn = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)); background = ui.ripple(ui.rounded(pal.accent, 24), 24)
            isClickable = true; isFocusable = true; contentDescription = s(R.string.ui_send)
            addView(ui.icon("send", 20, pal.onAccent).apply { layoutParams = FrameLayout.LayoutParams(ui.dp(20), ui.dp(20), Gravity.CENTER) })
            visibility = View.GONE
        }
        fun doSend() { val t = field.text.toString(); if (t.isNotBlank()) { vm.send(t); field.setText("") } }
        sendBtn.setOnClickListener { doSend() }
        field.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) { sendBtn.visibility = if (e.isNullOrBlank()) View.GONE else View.VISIBLE }
        })
        val inputRow = ui.row(6) {
            setPadding(ui.dp(12), ui.dp(6), ui.dp(8), ui.dp(8))
            addView(field, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(micBtn); addView(sendBtn)
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(header); addView(searchBar); addView(bannerHolder)
            addView(body, LinearLayout.LayoutParams(MATCH, 0, 1f))
            addView(progressRow); addView(inputRow)
        }
        lastKeys = emptyList(); firstRender = true

        // ---- render
        root.collectWhileAttached(vm.state) { st ->
            val searching = st.searchOpen
            header.visibility = if (searching) View.GONE else View.VISIBLE
            searchBar.visibility = if (searching) View.VISIBLE else View.GONE
            inputRow.visibility = if (searching) View.GONE else View.VISIBLE
            orb.mode = if (st.mic != ChatViewModel.Mic.OFF) EngineState.LISTENING else if (st.busy && st.engine == EngineState.IDLE) EngineState.THINKING else st.engine
            status.text = when {
                st.mic == ChatViewModel.Mic.HEARING -> s(R.string.ui_status_hearing)
                st.mic != ChatViewModel.Mic.OFF -> s(R.string.ui_status_listening)
                st.engine == EngineState.THINKING || st.busy -> s(R.string.ui_status_thinking)
                st.engine == EngineState.SPEAKING -> s(R.string.ui_status_speaking)
                st.engine == EngineState.ERROR -> s(R.string.ui_status_error)
                !st.aiReady -> s(R.string.ui_status_offline)
                else -> s(R.string.ui_status_ready)
            }
            orb.contentDescription = status.text
            // mute button shows the current state
            (muteBtn.getChildAt(0) as android.widget.ImageView).setImageResource(com.jarvis.assistant.ui.kit.Icons.res(activity, if (st.muted) "volume-x" else "volume-2"))
            muteBtn.contentDescription = if (st.muted) s(R.string.ui_unmute) else s(R.string.ui_mute)
            (micBtn.getChildAt(0) as android.widget.ImageView).apply {
                setImageResource(com.jarvis.assistant.ui.kit.Icons.res(activity, if (st.mic != ChatViewModel.Mic.OFF) "square" else "mic"))
                imageTintList = android.content.res.ColorStateList.valueOf(if (st.mic != ChatViewModel.Mic.OFF) pal.err else pal.text)
            }
            micBtn.contentDescription = if (st.mic != ChatViewModel.Mic.OFF) s(R.string.ui_mic_stop) else s(R.string.ui_mic)

            // banners
            bannerHolder.removeAllViews()
            st.banner?.let { b ->
                val color = when (b.level) { NoticeLevel.ERROR -> pal.err; NoticeLevel.WARN -> pal.warn; NoticeLevel.SUCCESS -> pal.ok; NoticeLevel.INFO -> pal.accent }
                bannerHolder.addView(ui.banner(b.text, color, "alert-triangle", s(R.string.ui_dismiss) to { vm.dismissBanner() }))
            }
            if (st.banner == null && !st.aiReady && !aiBannerDismissed && !searching) {
                bannerHolder.addView(ui.banner(s(R.string.ui_ai_not_set_up), pal.accent, "info", s(R.string.ui_set_up) to { openSettingsPage("ai") }))
            }

            // progress line
            val live = when {
                st.mic != ChatViewModel.Mic.OFF -> st.partial.ifBlank { s(R.string.ui_status_listening) }
                st.busy || st.progress != null -> st.progress ?: s(R.string.ui_status_thinking)
                else -> null
            }
            progressRow.visibility = if (live != null && !searching) View.VISIBLE else View.GONE
            progressText.text = live.orEmpty()
            stopBtn.visibility = if (st.busy || st.mic != ChatViewModel.Mic.OFF) View.VISIBLE else View.GONE

            // list or search results or empty states
            emptyHolder.removeAllViews()
            if (searching) {
                emptyHolder.visibility = View.GONE
                showSearchResults(st)
            } else {
                when {
                    st.loading -> { emptyHolder.visibility = View.VISIBLE; emptyHolder.addView(ui.loadingState(), FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER)) }
                    st.loadFailed -> { emptyHolder.visibility = View.VISIBLE; emptyHolder.addView(ui.errorState(s(R.string.ui_err_history)) { vm.retryLoad() }, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER)) }
                    st.items.isEmpty() -> { emptyHolder.visibility = View.VISIBLE; emptyHolder.addView(emptyChat(), FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER)) }
                    else -> emptyHolder.visibility = View.GONE
                }
                applyItems(st.items)
            }
        }
        viewScope.launch { vm.micLevel.collect { orb.level = it } }
        viewScope.launch {
            vm.effects.collect { e ->
                when (e) {
                    is ChatViewModel.Effect.NeedPermission -> activity.grant(e.req) { st -> if (st == PermState.GRANTED) vm.toggleListening() }
                    is ChatViewModel.Effect.Saved -> Toast.makeText(activity, s(R.string.ui_saved_to, e.file.name), Toast.LENGTH_LONG).show()
                    is ChatViewModel.Effect.Toast -> Toast.makeText(activity, e.text, Toast.LENGTH_SHORT).show()
                }
            }
        }
        return root
    }

    private fun emptyChat(): View {
        val ui = ui
        return ui.column(28, 24, 12) {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(ui.text(s(R.string.ui_empty_title, app.settings.assistantName()), 20f, bold = true, center = true))
            addView(ui.muted(s(R.string.ui_empty_body), 14f).apply { gravity = Gravity.CENTER_HORIZONTAL })
            val examples = listOf(R.string.ui_ex_1, R.string.ui_ex_2, R.string.ui_ex_3, R.string.ui_ex_4, R.string.ui_ex_5)
            val flow = com.jarvis.assistant.ui.kit.FlowLayout(activity, ui.dp(8))
            examples.forEach { r -> val t = s(r); flow.addView(ui.chip(t, false) { vm.send(t) }) }
            addView(flow, LinearLayout.LayoutParams(MATCH, WRAP))
        }
    }

    // ================================================================================================ list handling
    private fun applyItems(items: List<TimelineItem>) {
        val keys = items.map { it.key }
        if (keys == lastKeys && adapter.items === items) return
        val oldFirstKey = lastKeys.firstOrNull()
        val wasAtBottom = list.lastVisiblePosition >= adapter.count - 2
        val firstPos = list.firstVisiblePosition
        val topOffset = list.getChildAt(0)?.top ?: 0
        val prepended = oldFirstKey != null && keys.isNotEmpty() && keys.first() != oldFirstKey && keys.indexOf(oldFirstKey) > 0
        val delta = if (prepended) keys.indexOf(oldFirstKey) else 0
        val appended = lastKeys.isNotEmpty() && keys.size > lastKeys.size && !prepended
        adapter.items = items
        lastKeys = keys
        adapter.notifyDataSetChanged()
        when {
            firstRender && items.isNotEmpty() -> { list.setSelection(items.lastIndex); firstRender = false }
            prepended -> list.setSelectionFromTop(firstPos + delta, topOffset)
            appended && (wasAtBottom || (items.lastOrNull() as? TimelineItem.Message)?.isUser == true) -> list.smoothScrollToPosition(items.lastIndex)
        }
    }

    private fun showSearchResults(st: ChatViewModel.State) {
        // results replace the list while the search bar is open
        val ui = ui
        val results = st.searchResults
        val rows = ArrayList<TimelineItem>()
        results?.forEach { rows += TimelineItem.Message(it.role, it.text, it.atMs, null, it.id) }
        adapter.items = rows; adapter.notifyDataSetChanged(); lastKeys = emptyList(); firstRender = true
        val holder = list.parent as FrameLayout
        // simple message under the list when there is nothing to show
        val note = when {
            st.searchQuery.length < 2 -> s(R.string.ui_search_prompt)
            results == null -> s(R.string.ui_searching)
            results.isEmpty() -> s(R.string.ui_search_none)
            else -> null
        }
        (holder.getChildAt(1) as FrameLayout).apply {
            removeAllViews()
            if (note != null) { visibility = View.VISIBLE; addView(ui.emptyState("search", note, ""), FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER)) }
        }
    }

    // ================================================================================================ rows
    private inner class ChatAdapter : BaseAdapter() {
        var items: List<TimelineItem> = emptyList()
        override fun getCount() = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = items[position].key.hashCode().toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val ui = ui
            return when (val it = items[position]) {
                is TimelineItem.Day -> dayRow(it)
                is TimelineItem.Message -> if (it.isUser) userRow(it, parent) else assistantRow(it, parent, position == items.lastIndex)
            }
        }
    }

    private fun dayRow(d: TimelineItem.Day): View {
        val ui = ui
        return LinearLayout(activity).apply {
            gravity = Gravity.CENTER; setPadding(0, ui.dp(10), 0, ui.dp(6))
            addView(ui.text(d.label, 12f, ui.pal.muted, bold = true).apply { setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(4)); background = ui.rounded(ui.pal.panel, 12) })
        }
    }

    private fun maxBubble(parent: ViewGroup): Int = (minOf(parent.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels, ui.dp(720)) * 0.84f).toInt()

    private fun userRow(m: TimelineItem.Message, parent: ViewGroup): View {
        val ui = ui; val pal = ui.pal
        val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        val bubble = ui.text(m.text, 15f, pal.text).apply {
            maxWidth = maxBubble(parent)
            setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
            background = ui.rounded(pal.user, 18)
            setOnLongClickListener { copy(m.text); true }
        }
        col.addView(bubble)
        col.addView(ui.muted(timeFmt.format(Date(m.atMs)), 11f).apply { setPadding(ui.dp(4), ui.dp(2), ui.dp(4), 0) })
        return LinearLayout(activity).apply { gravity = Gravity.END; setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(4)); addView(col) }
    }

    private fun assistantRow(m: TimelineItem.Message, parent: ViewGroup, isLast: Boolean): View {
        val ui = ui; val pal = ui.pal
        val reply = m.reply
        val failed = reply != null && (reply.kind == ReplyKind.ERROR || !reply.ok)
        val tint = when { reply?.kind == ReplyKind.BLOCKED -> pal.warn; failed -> pal.err; else -> pal.accent }
        val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val bubble = ui.column(14, 10, 8) {
            background = ui.rounded(pal.panel, 18, if (failed) tint else pal.border)
            addView(ui.text(m.text, 15f, pal.text).apply { setOnLongClickListener { copy(m.text); true } })
            reply?.card?.let { c -> cards.render(c)?.let { v -> addView(v, LinearLayout.LayoutParams(MATCH, WRAP)) } }
            if (reply != null && reply.actions.isNotEmpty()) addView(actionRow(reply, isLast))
        }
        col.addView(bubble, LinearLayout.LayoutParams(WRAP, WRAP))
        col.addView(ui.muted(timeFmt.format(Date(m.atMs)) + if (reply?.extras?.get("offline") == true) " · " + s(R.string.ui_offline_tag) else "", 11f).apply { setPadding(ui.dp(4), ui.dp(2), ui.dp(4), 0) })
        val icon = FrameLayout(activity).apply {
            background = ui.rounded(com.jarvis.assistant.ui.kit.Themes.withAlpha(tint, 38), 16)
            addView(ui.icon(reply?.icon ?: "message", 16, tint).apply { layoutParams = FrameLayout.LayoutParams(ui.dp(16), ui.dp(16), Gravity.CENTER) })
            layoutParams = LinearLayout.LayoutParams(ui.dp(32), ui.dp(32)).apply { topMargin = ui.dp(2); marginEnd = ui.dp(8) }
        }
        return LinearLayout(activity).apply {
            setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(4))
            addView(icon)
            addView(col, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = ui.dp(28) })
            // keep long answers readable on tablets
            col.post { }
        }
    }

    /** Buttons under a reply. Yes/No and numbered choices only work for the question that is still open, so they appear on the last message only. */
    private fun actionRow(reply: AssistantReply, isLast: Boolean): View {
        val ui = ui
        val flow = com.jarvis.assistant.ui.kit.FlowLayout(activity, ui.dp(8))
        reply.actions.forEach { a ->
            val answers = a.type == ActionType.CHOOSE || a.type == ActionType.CONFIRM || a.type == ActionType.CANCEL
            if (answers && !isLast) return@forEach
            val kind = if (a.type == ActionType.CANCEL) Ui.Kind.GHOST else if (a.type == ActionType.CHOOSE) Ui.Kind.GHOST else Ui.Kind.PRIMARY
            flow.addView(ui.button(a.label, kind) { onAction(a) })
        }
        return flow
    }

    // ================================================================================================ actions
    private fun onAction(a: UiAction) {
        when (a.type) {
            ActionType.GRANT -> {
                val req = a.payload["requirement"]?.let { r -> Requirement.values().firstOrNull { it.name == r } } ?: return
                activity.grant(req) { st -> if (st == PermState.GRANTED) Toast.makeText(activity, s(R.string.ui_thanks_try_again), Toast.LENGTH_SHORT).show(); vm.refreshFlags() }
            }
            ActionType.OPEN_URL -> a.payload["url"]?.let { if (FileActions.openLink(activity, it) != FileActions.Result.OK) toast(R.string.ui_no_app_for_link) }
            ActionType.OPEN_FILE -> openFile(a.payload["uri"].orEmpty(), a.payload["mime"].orEmpty(), a.payload["name"].orEmpty())
            ActionType.CHOOSE, ActionType.CONFIRM, ActionType.CANCEL -> a.payload["answer"]?.let { vm.send(it) }
            ActionType.OPEN_SETTINGS_PAGE, ActionType.OPEN_APP_SCREEN -> openSettingsPage(a.payload["page"].orEmpty())
            ActionType.UNLOCK -> toast(R.string.ui_unlock_hint)
        }
    }

    private fun openSettingsPage(page: String) {
        (activity as? com.jarvis.assistant.ui.kit.PageOpener)?.openPage(if (page.isBlank()) "settings" else page)
    }

    override fun openFile(uri: String, mime: String, name: String) {
        when (FileActions.open(activity, uri, mime)) {
            FileActions.Result.OK -> {}
            FileActions.Result.NO_FILE -> toast(R.string.ui_file_missing)
            FileActions.Result.NO_APP -> toast(R.string.ui_no_app_for_file)
        }
    }

    override fun shareFile(uri: String, mime: String, name: String) {
        if (FileActions.share(activity, uri, mime, name) != FileActions.Result.OK) toast(R.string.ui_file_missing)
    }

    private fun toast(res: Int) = Toast.makeText(activity, res, Toast.LENGTH_SHORT).show()

    private fun copy(text: String) {
        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("JARVIS", text))
        toast(R.string.ui_copied)
    }

    private fun showMenu() {
        val labels = listOf(s(R.string.ui_menu_new), s(R.string.ui_menu_export), s(R.string.ui_menu_delete))
        Dialogs.choose(activity, ui, s(R.string.ui_menu_title), labels) { i ->
            when (i) {
                0 -> vm.newConversation()
                1 -> vm.exportConversation()
                2 -> Dialogs.confirm(activity, ui, s(R.string.ui_delete_title), s(R.string.ui_delete_body), s(R.string.ui_delete_yes), danger = true) { vm.deleteConversation() }
            }
        }
    }
}
