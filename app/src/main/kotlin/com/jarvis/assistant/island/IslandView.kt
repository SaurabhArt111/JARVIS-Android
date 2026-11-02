package com.jarvis.assistant.island

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.jarvis.assistant.R
import com.jarvis.assistant.ui.kit.Icons
import com.jarvis.core.island.IslandMode
import com.jarvis.core.island.IslandPlacement
import com.jarvis.core.island.IslandUiState
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The island, drawn with Canvas: a black pill (like the desktop island of the original app) with, per mode, an icon plus a listening waveform, thinking
 * dots, speaking ripples, an image-progress ring or one line of text. Tapping opens a larger card (real child views, so the four buttons are ordinary,
 * labelled 48 dp touch targets). The view is exactly as large as the placement's touch window, so it never blocks anything outside the pill.
 *
 * Around a display cutout the pill is drawn "split": nothing is drawn or touchable inside the cutout rectangles ([IslandPlacement.keepOut]).
 * Accessibility: the content description follows the state (polite live region) and the pill offers Talk / Type / Close / Move as accessibility actions.
 */
@SuppressLint("ViewConstructor")
class IslandView(ctx: Context, private val accent: Int) : FrameLayout(ctx) {
    interface Listener {
        fun onTap()
        fun onTalk()
        fun onType()
        fun onClose()
        /** The user asked to move the island (long press or the Move button); the following drag is reported through [onDrag]. */
        fun onMoveStart()
        fun onDrag(dx: Int, dy: Int)
        fun onDragEnd()
    }

    var listener: Listener? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private var state = IslandUiState()
    private var placement: IslandPlacement? = null
    private var keepOut: List<RectF> = emptyList()
    private val current = RectF()
    private val target = RectF()
    private var animator: ValueAnimator? = null
    private var cardAlpha = 0f
    private var lastWin: com.jarvis.core.island.RectPx? = null
    private var expandedNow = false
    private var armedMove = false
    private var moving = false
    private var downX = 0f
    private var downY = 0f
    private var originX = 0f
    private var originY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private val startNs = System.nanoTime()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1f) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = dp(13f); typeface = Typeface.DEFAULT_BOLD }
    private val shape = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val iconCache = HashMap<String, android.graphics.drawable.Drawable>()

    // ---- expanded card (ordinary views)
    private val cardTitleIcon = ImageView(ctx)
    private val cardTitle = TextView(ctx).apply { setTextColor(accent); textSize = 13f; typeface = Typeface.DEFAULT_BOLD; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val cardBody = TextView(ctx).apply { setTextColor(Color.WHITE); textSize = 15f; maxLines = 6; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(0f, 1.1f) }
    private val card = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = INVISIBLE; alpha = 0f }
    private val talkBtn: ImageView
    private val typeBtn: ImageView
    private val moveBtn: ImageView
    private val closeBtn: ImageView

    private val detector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean { if (!expandedNow) listener?.onTap(); return true }
        override fun onLongPress(e: MotionEvent) { performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); beginMove(lastRawX, lastRawY) }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (!expandedNow && vy < -dp(600f) && abs(vy) > abs(vx)) { listener?.onClose(); return true }
            return false
        }
        override fun onDown(e: MotionEvent) = true
    })

    init {
        setWillNotDraw(false)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(cardTitleIcon, LinearLayout.LayoutParams(dp(18f).toInt(), dp(18f).toInt()).apply { rightMargin = dp(8f).toInt() })
        head.addView(cardTitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(head)
        card.addView(cardBody, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(6f).toInt() })
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        talkBtn = button("mic", R.string.island_btn_talk) { listener?.onTalk() }
        typeBtn = button("edit-3", R.string.island_btn_type) { listener?.onType() }
        moveBtn = button("move", R.string.island_btn_move) { listener?.onMoveStart(); armedMove = true; invalidate() }
        closeBtn = button("x", R.string.island_btn_close) { listener?.onClose() }
        listOf(talkBtn, typeBtn, moveBtn, closeBtn).forEach { row.addView(it, LinearLayout.LayoutParams(dp(48f).toInt(), dp(48f).toInt())) }
        card.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        card.setPadding(dp(16f).toInt(), dp(10f).toInt(), dp(12f).toInt(), dp(6f).toInt())
        card.setOnClickListener { listener?.onTap() } // tapping the text collapses the card
        addView(card, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    private fun button(icon: String, label: Int, onClick: () -> Unit): ImageView = ImageView(context).apply {
        setImageResource(Icons.res(context, icon))
        setColorFilter(Color.WHITE)
        val pad = dp(14f).toInt()
        setPadding(pad, pad, pad, pad)
        val bg = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x26FFFFFF) }
        background = RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), InsetDrawable(bg, dp(6f).toInt()), null)
        contentDescription = context.getString(label)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    // ------------------------------------------------------------------------------------------------ binding
    /** Shows [s] at [p] (the placement already reflects whether the card is open) inside the window currently at [win]. [animate] false jumps without the grow/shrink animation. */
    fun bind(s: IslandUiState, p: IslandPlacement, win: com.jarvis.core.island.RectPx, animate: Boolean = true) {
        val wasExpanded = expandedNow
        // the window may have moved or been resized since the last bind: keep the drawn rectangle where it is on screen
        lastWin?.let { old -> if (!current.isEmpty) current.offset((old.left - win.left).toFloat(), (old.top - win.top).toFloat()) }
        lastWin = win
        state = s
        placement = p
        expandedNow = s.expanded && p.height > dp(60f)
        val hit = win
        target.set((p.x - hit.left).toFloat(), (p.y - hit.top).toFloat(), (p.x - hit.left + p.width).toFloat(), (p.y - hit.top + p.height).toFloat())
        keepOut = p.keepOut.map { RectF((it.left - hit.left).toFloat(), (it.top - hit.top).toFloat(), (it.right - hit.left).toFloat(), (it.bottom - hit.top).toFloat()) }

        cardTitle.text = s.title.ifBlank { "JARVIS" }
        cardBody.text = when {
            s.text.isNotBlank() -> s.text
            s.mode == IslandMode.LISTENING -> "Listening…"
            s.mode == IslandMode.THINKING -> "Thinking…"
            else -> "How can I help you, sir?"
        }
        cardTitleIcon.setImageResource(Icons.res(context, s.icon.ifBlank { "info" }))
        cardTitleIcon.setColorFilter(accent)
        contentDescription = context.getString(R.string.island_cd_state, s.title, s.text).trim().trimEnd('.')

        val from = RectF(if (current.isEmpty) shrunk(target) else current)
        animator?.cancel()
        if (!animate || from == target) {
            current.set(target)
            cardAlpha = if (expandedNow) 1f else 0f
            applyCard()
        } else {
            val growing = expandedNow && !wasExpanded
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = if (growing) 300 else 220
                interpolator = if (growing) OvershootInterpolator(0.9f) else DecelerateInterpolator()
                val fromAlpha = cardAlpha
                val toAlpha = if (expandedNow) 1f else 0f
                addUpdateListener {
                    val t = it.animatedValue as Float
                    current.set(from.left + (target.left - from.left) * t, from.top + (target.top - from.top) * t, from.right + (target.right - from.right) * t, from.bottom + (target.bottom - from.bottom) * t)
                    cardAlpha = (fromAlpha + (toAlpha - fromAlpha) * it.animatedFraction).coerceIn(0f, 1f)
                    applyCard()
                    invalidate()
                }
                start()
            }
        }
        requestLayout()
        invalidate()
    }

    private fun shrunk(r: RectF) = RectF(r.centerX() - r.width() * 0.5f, r.top, r.centerX() + r.width() * 0.5f, r.top + min(r.height(), dp(36f)) * 0.8f)

    private fun applyCard() {
        card.alpha = cardAlpha
        card.visibility = if (cardAlpha > 0.02f || expandedNow) VISIBLE else INVISIBLE
    }

    /** Updates what is drawn without re-placing the window (used for the 10-per-second microphone level). */
    fun updateState(s: IslandUiState) { state = s; invalidate() }

    /** The user finished (or abandoned) moving. */
    fun endMoveMode() { armedMove = false; moving = false; invalidate() }

    // ------------------------------------------------------------------------------------------------ layout
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val w = max(target.width().toInt(), 1)
        val topPad = keepOutTopPad()
        val h = max(target.height().toInt() - topPad, 1)
        card.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val topPad = keepOutTopPad()
        card.layout(target.left.toInt(), target.top.toInt() + topPad, target.right.toInt(), target.bottom.toInt())
    }

    /** Space to leave empty at the top of the card so text never sits under a cutout. */
    private fun keepOutTopPad(): Int {
        val k = keepOut.firstOrNull { RectF.intersects(it, target) } ?: return 0
        return max(0f, k.bottom - target.top + dp(4f)).toInt()
    }

    // ------------------------------------------------------------------------------------------------ drawing
    override fun onDraw(canvas: Canvas) {
        if (current.isEmpty) return
        val radius = min(current.height() / 2f, dp(28f))
        canvas.drawRoundRect(current, radius, radius, fill)
        stroke.color = when {
            moving || armedMove -> accent
            state.mode == IslandMode.ERROR -> 0x99FF6259.toInt()
            else -> 0x26FFFFFF
        }
        stroke.pathEffect = if (armedMove || moving) DashPathEffect(floatArrayOf(dp(6f), dp(4f)), 0f) else null
        canvas.drawRoundRect(current, radius, radius, stroke)

        if (!expandedNow && cardAlpha < 0.5f) drawCompact(canvas)
        if (needsFrames()) postInvalidateOnAnimation()
    }

    private fun needsFrames(): Boolean = animator?.isRunning == true || armedMove || moving ||
        state.mode == IslandMode.LISTENING || state.mode == IslandMode.THINKING || state.mode == IslandMode.SPEAKING || state.mode == IslandMode.IMAGE

    private fun phase(): Float = ((System.nanoTime() - startNs) / 1_000_000_000.0).toFloat()

    private fun modeColor(): Int = if (state.mode == IslandMode.ERROR) 0xFFFF6259.toInt() else accent

    private fun drawCompact(canvas: Canvas) {
        val r = current
        val pad = dp(12f)
        val icon = dp(18f)
        val cy = r.centerY()
        val ko = keepOut.firstOrNull { RectF.intersects(it, r) }
        val iconLeft: Float
        val zoneL: Float
        val zoneR: Float
        if (ko != null) { // split around the cutout: icon on its left, content on its right
            iconLeft = max(r.left + pad, ko.left - dp(6f) - icon)
            zoneL = ko.right + dp(8f); zoneR = r.right - pad
        } else {
            iconLeft = r.left + pad
            zoneL = iconLeft + icon + dp(10f); zoneR = r.right - pad
        }
        drawIcon(canvas, state.icon.ifBlank { "info" }, iconLeft, cy - icon / 2f, icon, modeColor())
        if (zoneR - zoneL < dp(20f)) return
        val color = modeColor()
        val t = phase()
        when {
            armedMove || moving -> drawTextLine(canvas, context.getString(R.string.island_move_hint), zoneL, zoneR, cy, Color.WHITE)
            state.mode == IslandMode.LISTENING -> {
                shape.color = color; shape.strokeWidth = dp(3f); shape.style = Paint.Style.STROKE
                val n = max(3, min(9, ((zoneR - zoneL) / dp(8f)).toInt()))
                val total = n * dp(3f) + (n - 1) * dp(4f)
                var x = zoneL + (zoneR - zoneL - total) / 2f + dp(1.5f)
                val maxH = min(r.height() - dp(12f), dp(26f))
                for (i in 0 until n) {
                    val wob = 0.5f + 0.5f * sin(t * 7f + i * 1.3f)
                    val hgt = dp(4f) + (maxH - dp(4f)) * min(1f, 0.12f + state.level * 0.9f * wob + 0.10f * wob)
                    canvas.drawLine(x, cy - hgt / 2f, x, cy + hgt / 2f, shape)
                    x += dp(7f)
                }
            }
            state.mode == IslandMode.THINKING -> {
                shape.style = Paint.Style.FILL
                val cx = (zoneL + zoneR) / 2f
                for (i in -1..1) {
                    val k = 0.5f + 0.5f * sin(t * 6f - i * 1.1f)
                    shape.color = color; shape.alpha = (90 + 165 * k).toInt()
                    canvas.drawCircle(cx + i * dp(12f), cy, dp(3f) + dp(1.5f) * k, shape)
                }
                shape.alpha = 255
            }
            state.mode == IslandMode.SPEAKING -> {
                val cx = (zoneL + zoneR) / 2f
                val maxR = min(r.height() / 2f - dp(3f), dp(14f))
                shape.style = Paint.Style.FILL; shape.color = color; shape.alpha = 255
                canvas.drawCircle(cx, cy, dp(3.5f), shape)
                shape.style = Paint.Style.STROKE; shape.strokeWidth = dp(1.6f)
                for (k in 0..1) {
                    val f = (t * 0.9f + k * 0.5f) % 1f
                    shape.alpha = (255 * (1f - f)).toInt()
                    canvas.drawCircle(cx, cy, dp(3.5f) + (maxR - dp(3.5f)) * f, shape)
                }
                shape.alpha = 255
            }
            state.mode == IslandMode.IMAGE -> {
                val cx = zoneR - dp(12f)
                val rr = dp(9f)
                shape.style = Paint.Style.STROKE; shape.strokeWidth = dp(2.5f); shape.color = Color.WHITE; shape.alpha = 50
                canvas.drawCircle(cx, cy, rr, shape)
                shape.color = color; shape.alpha = 255
                val oval = RectF(cx - rr, cy - rr, cx + rr, cy + rr)
                val p = state.progress
                if (p == null) canvas.drawArc(oval, (t * 280f) % 360f, 100f, false, shape) else canvas.drawArc(oval, -90f, 360f * p.coerceIn(0f, 1f), false, shape)
                drawTextLine(canvas, state.text.ifBlank { state.title }, zoneL, cx - rr - dp(8f), cy, Color.WHITE)
            }
            else -> drawTextLine(canvas, state.text.ifBlank { state.title }, zoneL, zoneR, cy, Color.WHITE)
        }
    }

    private fun drawTextLine(canvas: Canvas, s: String, l: Float, r: Float, cy: Float, color: Int) {
        if (r - l < dp(12f)) return
        text.color = color
        val clipped = TextUtils.ellipsize(s.replace('\n', ' '), android.text.TextPaint(text), r - l, TextUtils.TruncateAt.END).toString()
        val fm = text.fontMetrics
        canvas.drawText(clipped, l, cy - (fm.ascent + fm.descent) / 2f, text)
    }

    private fun drawIcon(canvas: Canvas, name: String, left: Float, top: Float, size: Float, color: Int) {
        val dr = iconCache.getOrPut(name) { context.getDrawable(Icons.res(context, name))!!.mutate() }
        dr.setTint(color)
        dr.setBounds(left.toInt(), top.toInt(), (left + size).toInt(), (top + size).toInt())
        dr.draw(canvas)
    }

    // ------------------------------------------------------------------------------------------------ touch
    private fun beginMove(rawX: Float, rawY: Float) {
        moving = true
        originX = rawX; originY = rawY
        listener?.onMoveStart()
        invalidate()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = !expandedNow // compact: the view itself handles every touch; expanded: the buttons do

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        lastRawX = ev.rawX; lastRawY = ev.rawY
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX; downY = ev.rawY
                if (armedMove) { moving = true; originX = ev.rawX; originY = ev.rawY; invalidate() }
            }
            MotionEvent.ACTION_MOVE -> if (moving) listener?.onDrag((ev.rawX - originX).toInt(), (ev.rawY - originY).toInt())
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (moving) {
                moving = false; armedMove = false
                listener?.onDragEnd()
                invalidate()
                return true
            }
        }
        return if (moving) true else detector.onTouchEvent(ev) || super.onTouchEvent(ev)
    }

    // ------------------------------------------------------------------------------------------------ accessibility
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(ACT_TALK, context.getString(R.string.island_btn_talk)))
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(ACT_TYPE, context.getString(R.string.island_btn_type)))
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(ACT_MOVE, context.getString(R.string.island_btn_move)))
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(ACT_CLOSE, context.getString(R.string.island_btn_close)))
        info.isClickable = true
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean = when (action) {
        ACT_TALK -> { listener?.onTalk(); true }
        ACT_TYPE -> { listener?.onType(); true }
        ACT_CLOSE -> { listener?.onClose(); true }
        ACT_MOVE -> { listener?.onMoveStart(); armedMove = true; invalidate(); true }
        AccessibilityNodeInfo.ACTION_CLICK -> { listener?.onTap(); true }
        else -> super.performAccessibilityAction(action, arguments)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val ACT_TALK = 0x7f0a0001
        const val ACT_TYPE = 0x7f0a0002
        const val ACT_MOVE = 0x7f0a0003
        const val ACT_CLOSE = 0x7f0a0004
    }
}
