package com.jarvis.assistant.ui.library

import android.content.Intent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Toast
import com.jarvis.assistant.R
import com.jarvis.assistant.platform.FileActions
import com.jarvis.assistant.ui.kit.Dialogs
import com.jarvis.assistant.ui.kit.MATCH
import com.jarvis.assistant.ui.kit.Screen
import com.jarvis.assistant.ui.kit.SquareFrame
import com.jarvis.assistant.ui.kit.TopAction
import com.jarvis.assistant.ui.kit.Ui
import com.jarvis.assistant.ui.kit.ViewModels
import com.jarvis.assistant.ui.kit.WRAP
import com.jarvis.assistant.ui.kit.add
import com.jarvis.assistant.ui.kit.collectWhileAttached
import com.jarvis.core.format.Formats
import com.jarvis.core.ports.ContentCategory
import com.jarvis.core.ports.StoredFile
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Everything JARVIS saved for you: browse by folder, search, open, share, save a copy elsewhere, delete (always asks first), clean up old files (shows what will go first). */
class LibraryScreen : Screen() {
    override val id = "library"
    override val topLevel = true
    override fun title() = activity.getString(R.string.ui_tab_library)

    private val vm: LibraryViewModel get() = ViewModels.get("library") { LibraryViewModel(app) }
    private var searchShown = false
    private val dateFmt: DateFormat by lazy { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    private fun s(id: Int, vararg a: Any) = if (a.isEmpty()) activity.getString(id) else activity.getString(id, *a)

    override fun actions(): List<TopAction> = listOf(
        TopAction("search", s(R.string.ui_search)) { searchShown = !searchShown; host.rebuild() },
        TopAction("trash-2", s(R.string.ui_lib_cleanup)) { showCleanup() },
    )

    override fun onShown() { vm.refresh() }
    override fun onActivityResumed() { vm.refresh() }
    override fun onBack(): Boolean { if (searchShown) { searchShown = false; vm.setQuery(""); host.rebuild(); return true }; return false }

    private fun categoryLabel(c: ContentCategory?): String = s(when (c) {
        null -> R.string.ui_cat_all
        ContentCategory.CONVERSATIONS -> R.string.ui_cat_conversations
        ContentCategory.NOTES -> R.string.ui_cat_notes
        ContentCategory.GENERATED_TEXT -> R.string.ui_cat_generated
        ContentCategory.IMAGES -> R.string.ui_cat_images
        ContentCategory.AUDIO -> R.string.ui_cat_audio
        ContentCategory.EXPORTS -> R.string.ui_cat_exports
        ContentCategory.OTHER -> R.string.ui_cat_other
    })

    private fun iconFor(f: StoredFile): String = when {
        f.mime.startsWith("image/") -> "image"
        f.mime.startsWith("audio/") -> "music"
        f.category == ContentCategory.CONVERSATIONS -> "message-circle"
        f.category == ContentCategory.NOTES -> "file-text"
        else -> "file"
    }

    override fun build(): View {
        val ui = ui; val pal = ui.pal
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

        if (searchShown) {
            root.add(ui.field(s(R.string.ui_lib_search_hint), vm.state.value.query) { vm.setQuery(it) }, MATCH, WRAP, 0f, 4, 8, 16, 16, ui)
        }
        val chips = ui.row(8) { setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(8)) }
        root.addView(ui.hscroll(chips))
        val summary = ui.muted("", 12.5f)
        root.add(summary, MATCH, WRAP, 0f, 0, 6, 20, 20, ui)
        val body = FrameLayout(activity)
        root.addView(body, LinearLayout.LayoutParams(MATCH, 0, 1f))

        root.collectWhileAttached(vm.state) { st ->
            chips.removeAllViews()
            (listOf<ContentCategory?>(null) + ContentCategory.values().toList()).forEach { c ->
                chips.addView(ui.chip(categoryLabel(c), st.category == c) { vm.setCategory(c) })
            }
            summary.text = if (st.totalCount == 0) "" else s(R.string.ui_lib_summary, st.totalCount, Formats.bytes(st.totalBytes), if (st.privateStorage) s(R.string.ui_lib_where_private) else s(R.string.ui_lib_where_documents))
            body.removeAllViews()
            when {
                st.loading && st.files.isEmpty() -> body.addView(ui.loadingState(), FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER))
                st.failed -> body.addView(ui.errorState(s(R.string.ui_lib_error)) { vm.refresh() }, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER))
                st.files.isEmpty() -> body.addView(
                    if (st.query.isNotBlank()) ui.emptyState("search", s(R.string.ui_lib_no_match), s(R.string.ui_lib_no_match_body, st.query))
                    else ui.emptyState("folder", s(R.string.ui_lib_empty), s(R.string.ui_lib_empty_body)),
                    FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER))
                st.category == ContentCategory.IMAGES || st.files.all { it.mime.startsWith("image/") } -> body.addView(grid(st.files), FrameLayout.LayoutParams(MATCH, MATCH))
                else -> body.addView(listView(st.files), FrameLayout.LayoutParams(MATCH, MATCH))
            }
        }
        return root
    }

    // ================================================================================================ list / grid
    private fun listView(files: List<StoredFile>): View = ListView(activity).apply {
        divider = null; dividerHeight = 0; selector = android.graphics.drawable.ColorDrawable(0); isVerticalScrollBarEnabled = false
        clipToPadding = false; setPadding(0, 0, 0, ui.dp(16))
        adapter = object : BaseAdapter() {
            override fun getCount() = files.size
            override fun getItem(p: Int): Any = files[p]
            override fun getItemId(p: Int) = files[p].id.hashCode().toLong()
            override fun getView(p: Int, c: View?, parent: ViewGroup): View = fileRow(files[p])
        }
    }

    private fun fileRow(f: StoredFile): View {
        val ui = ui; val pal = ui.pal
        return ui.row(12) {
            setPadding(ui.dp(16), ui.dp(8), ui.dp(4), ui.dp(8)); minimumHeight = ui.dp(60)
            background = ui.ripple(null, 14); isClickable = true; isFocusable = true
            setOnClickListener { open(f) }
            setOnLongClickListener { actionsFor(f); true }
            addView(FrameLayout(activity).apply {
                background = ui.rounded(com.jarvis.assistant.ui.kit.Themes.withAlpha(pal.accent, 30), 12)
                addView(ui.icon(iconFor(f), 20, pal.accent).apply { layoutParams = FrameLayout.LayoutParams(ui.dp(20), ui.dp(20), Gravity.CENTER) })
                layoutParams = LinearLayout.LayoutParams(ui.dp(44), ui.dp(44))
            })
            addView(ui.column(gap = 2) {
                addView(ui.text(f.name, 15f, maxLines = 1))
                addView(ui.muted(listOf(categoryLabel(f.category), Formats.bytes(f.sizeBytes), dateFmt.format(Date(f.createdAt.toEpochMilli()))).joinToString(" · "), 12f, 1))
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(ui.iconButton("more-vertical", s(R.string.ui_more), pal.muted, 20) { actionsFor(f) })
        }
    }

    private fun grid(files: List<StoredFile>): View = GridView(activity).apply {
        numColumns = GridView.AUTO_FIT; columnWidth = ui.dp(110); stretchMode = GridView.STRETCH_COLUMN_WIDTH
        horizontalSpacing = ui.dp(6); verticalSpacing = ui.dp(6); setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(16)); clipToPadding = false
        isVerticalScrollBarEnabled = false; selector = android.graphics.drawable.ColorDrawable(0)
        adapter = object : BaseAdapter() {
            override fun getCount() = files.size
            override fun getItem(p: Int): Any = files[p]
            override fun getItemId(p: Int) = files[p].id.hashCode().toLong()
            override fun getView(p: Int, c: View?, parent: ViewGroup): View {
                val f = files[p]
                val cell = SquareFrame(activity).apply {
                    background = ui.rounded(ui.pal.panel, 12, ui.pal.border); clipToOutline = true; outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                    isClickable = true; isFocusable = true; contentDescription = f.name
                    setOnClickListener { open(f) }; setOnLongClickListener { actionsFor(f); true }
                }
                val iv = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                cell.addView(iv, FrameLayout.LayoutParams(MATCH, MATCH))
                if (f.mime.startsWith("image/")) app.images.load(f.uri, 320, iv, onFail = { iv.setImageResource(R.drawable.ic_image); iv.scaleType = ImageView.ScaleType.CENTER; iv.imageTintList = android.content.res.ColorStateList.valueOf(ui.pal.muted) })
                else { iv.setImageResource(com.jarvis.assistant.ui.kit.Icons.res(activity, iconFor(f))); iv.scaleType = ImageView.ScaleType.CENTER; iv.imageTintList = android.content.res.ColorStateList.valueOf(ui.pal.muted) }
                return cell
            }
        }
    }

    // ================================================================================================ actions
    private fun open(f: StoredFile) {
        if (f.mime.startsWith("text/") || f.mime == "application/json") { preview(f); return }
        when (FileActions.open(activity, f.uri, f.mime)) {
            FileActions.Result.OK -> {}
            FileActions.Result.NO_FILE -> toast(R.string.ui_file_missing)
            FileActions.Result.NO_APP -> toast(R.string.ui_no_app_for_file)
        }
    }

    /** Text files open in a small in-app viewer first (no app needed); "Open elsewhere" hands them to another app. */
    private fun preview(f: StoredFile) {
        viewScope.launch {
            val text = vm.readText(f)
            if (text == null) { toast(R.string.ui_file_missing); return@launch }
            Dialogs.show(activity, ui) { c, dismiss ->
                c.addView(ui.title(f.name, 17f))
                c.addView(ui.scroll(ui.text(text.ifBlank { s(R.string.ui_lib_empty_file) }, 13.5f, selectable = true)), LinearLayout.LayoutParams(MATCH, ui.dp(320)))
                c.addView(ui.row(8) {
                    add(ui.button(s(R.string.ui_share), Ui.Kind.GHOST) { share(f) }, 0, WRAP, 1f)
                    add(ui.button(s(R.string.ui_close), Ui.Kind.PRIMARY) { dismiss() }, 0, WRAP, 1f)
                })
            }
        }
    }

    private fun share(f: StoredFile) { if (FileActions.share(activity, f) != FileActions.Result.OK) toast(R.string.ui_file_missing) }

    private fun actionsFor(f: StoredFile) {
        val labels = listOf(s(R.string.ui_open), s(R.string.ui_share), s(R.string.ui_lib_save_copy), s(R.string.ui_lib_delete))
        Dialogs.choose(activity, ui, f.name, labels) { i ->
            when (i) {
                0 -> if (f.mime.startsWith("text/") || f.mime == "application/json") preview(f) else open(f)
                1 -> share(f)
                2 -> saveCopy(f)
                3 -> confirmDelete(f)
            }
        }
    }

    /** "Save a copy": the system file dialog lets the user pick any folder or cloud drive; JARVIS needs no storage permission for it. */
    private fun saveCopy(f: StoredFile) {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(f.mime.ifBlank { "*/*" }).putExtra(Intent.EXTRA_TITLE, f.name)
        val launched = activity.launchForResult(i) { code, data ->
            val dest = data?.data
            if (code == android.app.Activity.RESULT_OK && dest != null) {
                Thread {
                    val ok = FileActions.copyTo(activity, f.uri, dest)
                    activity.runOnUiThread { toast(if (ok) R.string.ui_lib_copy_saved else R.string.ui_lib_copy_failed) }
                }.start()
            }
        }
        if (!launched) toast(R.string.ui_lib_no_file_dialog)
    }

    private fun confirmDelete(f: StoredFile) {
        Dialogs.confirm(activity, ui, s(R.string.ui_lib_delete_title), s(R.string.ui_lib_delete_body, f.name), s(R.string.ui_delete_yes), danger = true) {
            vm.delete(f) { ok -> if (ok) toast(R.string.ui_lib_deleted) else Dialogs.message(activity, ui, s(R.string.ui_lib_delete_title), s(R.string.ui_lib_delete_failed)) }
        }
    }

    // ================================================================================================ cleanup
    private fun showCleanup() {
        val options = listOf(7, 30, 90, 180)
        Dialogs.choose(activity, ui, s(R.string.ui_lib_cleanup_title), options.map { s(R.string.ui_lib_older_than, it) }) { i ->
            val days = options[i]
            val cat = vm.state.value.category
            vm.previewCleanup(days, cat) { list ->
                if (list.isEmpty()) { Dialogs.message(activity, ui, s(R.string.ui_lib_cleanup_title), s(R.string.ui_lib_cleanup_none, days)); return@previewCleanup }
                val size = Formats.bytes(list.sumOf { it.sizeBytes })
                val names = list.take(5).joinToString("\n") { "• " + it.name } + if (list.size > 5) "\n" + s(R.string.ui_card_more, list.size - 5) else ""
                Dialogs.confirm(activity, ui, s(R.string.ui_lib_cleanup_title), s(R.string.ui_lib_cleanup_body, list.size, size, categoryLabel(cat), names), s(R.string.ui_delete_yes), danger = true) {
                    vm.cleanup(list) { deleted, attempted ->
                        val msg = if (deleted == attempted) s(R.string.ui_lib_cleanup_done, deleted) else s(R.string.ui_lib_cleanup_partial, deleted, attempted - deleted)
                        Dialogs.message(activity, ui, s(R.string.ui_lib_cleanup_title), msg)
                    }
                }
            }
        }
    }

    private fun toast(res: Int) = Toast.makeText(activity, res, Toast.LENGTH_SHORT).show()
}
