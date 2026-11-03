package com.jarvis.assistant.island

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.jarvis.assistant.R
import com.jarvis.assistant.ui.kit.Icons
import com.jarvis.core.model.UiAction

/**
 * The small chat that opens when the island is pulled down: a dark rounded card with the last few messages, a text box, a microphone button and send.
 * Plain framework views (the app has no AndroidX). It lives in its own focusable overlay window, which [IslandController] adds below the island and removes
 * again on close, outside tap or Back, so nothing invisible is ever left covering the screen.
 *
 * The panel draws and reports; it never talks to the engine itself (see [Listener]).
 */
class IslandChatPanel(ctx: Context, private val accent: Int) : FrameLayout(ctx) {
    interface Listener {
        fun onSend(text: String)
        fun onMic()
        fun onClose()
        fun onOpenApp()
        fun onAction(action: UiAction)
    }

    var listener: Listener? = null

    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = (v * density).toInt()

    private val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; addView(list, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)) }
    private val input = EditText(ctx)
    private val micBtn: ImageView
    private val sendBtn: ImageView
    private val status = TextView(ctx)
    private var pending: TextView? = null
    private var listening = false

    init {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(0xF2000000.toInt()); cornerRadius = dp(26f).toFloat(); setStroke(dp(1f), 0x33FFFFFF) }
            setPadding(dp(14f), dp(10f), dp(10f), dp(10f))
            elevation = dp(8f).toFloat()
        }

        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.app_name); setTextColor(accent); textSize = 13f; typeface = android.graphics.Typeface.DEFAULT_BOLD; letterSpacing = 0.08f
        }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        header.addView(iconButton("arrow_up_right", R.string.island_chat_open_app, 36f) { listener?.onOpenApp() })
        header.addView(iconButton("x", R.string.island_chat_close, 36f) { listener?.onClose() })
        card.addView(header, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        card.addView(scroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(4f); bottomMargin = dp(6f) })

        status.apply { setTextColor(0xFF9CA3B0.toInt()); textSize = 12f; visibility = View.GONE; setPadding(dp(4f), 0, 0, dp(4f)) }
        card.addView(status, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        input.apply {
            hint = ctx.getString(R.string.island_chat_hint)
            setHintTextColor(0xFF7B8190.toInt()); setTextColor(Color.WHITE); textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 3
            imeOptions = EditorInfo.IME_ACTION_SEND
            background = GradientDrawable().apply { setColor(0x1FFFFFFF); cornerRadius = dp(22f).toFloat() }
            setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
            setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_SEND) { submit(); true } else false }
        }
        row.addView(input, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        micBtn = iconButton("mic", R.string.island_chat_mic, 44f) { listener?.onMic() }
        sendBtn = iconButton("send", R.string.island_chat_send, 44f) { submit() }
        row.addView(micBtn)
        row.addView(sendBtn)
        card.addView(row, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addUserlessGreeting(ctx.getString(R.string.island_chat_greeting))
    }

    private fun iconButton(icon: String, label: Int, sizeDp: Float, onClick: () -> Unit): ImageView = ImageView(context).apply {
        setImageResource(Icons.res(context, icon))
        setColorFilter(Color.WHITE)
        val pad = dp((sizeDp - 20f) / 2f)
        setPadding(pad, pad, pad, pad)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        background = RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), null, GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) })
        contentDescription = context.getString(label)
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun bubble(text: String, mine: Boolean): TextView = TextView(context).apply {
        this.text = text
        textSize = 14.5f
        setTextColor(if (mine) 0xFF06121A.toInt() else Color.WHITE)
        setTextIsSelectable(true)
        setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
        background = GradientDrawable().apply { setColor(if (mine) accent else 0x26FFFFFF); cornerRadius = dp(16f).toFloat() }
        maxWidth = dp(290f)
    }

    private fun add(v: View, mine: Boolean) {
        val lp = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = if (mine) Gravity.END else Gravity.START
            topMargin = dp(4f); bottomMargin = dp(2f)
        }
        list.addView(v, lp)
        // keep memory and layout small: a pull-down chat is for quick things, the app keeps the history
        while (list.childCount > MAX_ROWS) list.removeViewAt(0)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun addUserlessGreeting(text: String) = add(bubble(text, mine = false), mine = false)

    fun addUser(text: String) = add(bubble(text, mine = true), mine = true)

    /** The assistant's answer. Buttons that answer a question are shown under it; anything else opens the full app. */
    fun addAssistant(text: String, actions: List<UiAction>) {
        clearPending()
        add(bubble(text, mine = false), mine = false)
        if (actions.isNotEmpty()) {
            val flow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            actions.take(3).forEach { a ->
                flow.addView(TextView(context).apply {
                    this.text = a.label; textSize = 13f; setTextColor(accent); gravity = Gravity.CENTER
                    setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
                    background = GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(dp(1f), accent); cornerRadius = dp(18f).toFloat() }
                    isClickable = true; isFocusable = true
                    setOnClickListener { listener?.onAction(a) }
                }, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6f) })
            }
            add(flow, mine = false)
        }
    }

    /** "..." bubble while the assistant works. */
    fun showThinking() {
        clearPending()
        pending = bubble("…", mine = false).also { add(it, mine = false) }
    }

    private fun clearPending() { pending?.let { list.removeView(it) }; pending = null }

    /** Shows what the recogniser has heard so far (or clears the line with ""). */
    fun setStatus(text: String) { status.text = text; status.visibility = if (text.isBlank()) View.GONE else View.VISIBLE }

    fun setListening(on: Boolean) {
        listening = on
        micBtn.setColorFilter(if (on) accent else Color.WHITE)
        if (!on) setStatus("")
    }

    fun isListening() = listening

    private fun submit() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        input.setText("")
        listener?.onSend(t)
    }

    fun focusInput() {
        input.requestFocus()
        input.postDelayed({
            try { context.getSystemService(InputMethodManager::class.java)?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT) } catch (_: Exception) { }
        }, 150)
    }

    // tapping anywhere outside the card closes it; Back closes it too
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) { listener?.onClose(); return true }
        return super.onTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) listener?.onClose()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    companion object { private const val MAX_ROWS = 14 }
}
