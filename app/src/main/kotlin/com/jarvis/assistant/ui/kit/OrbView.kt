package com.jarvis.assistant.ui.kit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.provider.Settings as AndroidSettings
import android.view.View
import com.jarvis.core.engine.EngineState
import kotlin.math.min
import kotlin.math.sin

/**
 * The JARVIS orb: a glowing core with rings. Idle breathes slowly; listening swells with the microphone level; thinking turns a bright arc; speaking pulses;
 * an error tints the rings. Animation only runs while the view is on screen, and not at all when the user has turned animations off in Android's settings.
 */
class OrbView(context: Context) : View(context) {
    var mode: EngineState = EngineState.IDLE
        set(v) { if (field != v) { field = v; invalidate() } }
    /** 0..1 microphone loudness (only used while listening). */
    var level: Float = 0f
        set(v) { smoothTarget = v.coerceIn(0f, 1f) }

    private var accent = Color.rgb(0x22, 0xD3, 0xEE)
    private var accent2 = Color.rgb(0x4C, 0x9B, 0xFF)
    private var errColor = Color.rgb(0xFF, 0x5C, 0x7A)
    private var smooth = 0f
    private var smoothTarget = 0f
    private val core = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oval = RectF()
    private var baseR = 0f
    private var animationsOn = true
    private var running = false

    fun setColors(accent: Int, accent2: Int, err: Int) {
        this.accent = accent; this.accent2 = accent2; this.errColor = err
        rebuildShaders(); invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); rebuildShaders() }

    private fun rebuildShaders() {
        baseR = min(width, height) / 2f * 0.5f
        if (baseR <= 0f) return
        core.shader = RadialGradient(0f, 0f, baseR, intArrayOf(withA(accent, 255), withA(accent2, 235), withA(accent2, 90)), floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
        glow.shader = RadialGradient(0f, 0f, baseR * 2f, intArrayOf(withA(accent, 90), withA(accent, 0)), floatArrayOf(0.35f, 1f), Shader.TileMode.CLAMP)
    }

    private fun withA(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animationsOn = try { AndroidSettings.Global.getFloat(context.contentResolver, AndroidSettings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f } catch (e: Exception) { true }
        running = true; invalidate()
    }
    override fun onDetachedFromWindow() { running = false; super.onDetachedFromWindow() }
    override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(isVisible); running = isVisible && isAttachedToWindow; if (running) invalidate() }

    override fun onDraw(canvas: Canvas) {
        if (baseR <= 0f) return
        val t = if (animationsOn) SystemClock.uptimeMillis() else 0L
        smooth += (smoothTarget - smooth) * 0.25f
        val breath = (sin(t / 1100.0) * 0.5 + 0.5).toFloat()
        val pulse = (sin(t / 170.0) * 0.5 + 0.5).toFloat()
        val swell = when (mode) {
            EngineState.IDLE -> 0.03f * breath
            EngineState.LISTENING -> 0.05f + 0.30f * smooth
            EngineState.THINKING -> 0.04f * breath
            EngineState.SPEAKING -> 0.05f + 0.10f * pulse
            EngineState.ERROR -> 0.02f
        }
        val cx = width / 2f; val cy = height / 2f
        canvas.save(); canvas.translate(cx, cy)
        canvas.drawCircle(0f, 0f, baseR * 2f, glow)
        canvas.save(); val s = 1f + swell; canvas.scale(s, s); canvas.drawCircle(0f, 0f, baseR, core); canvas.restore()

        val ringColor = if (mode == EngineState.ERROR) errColor else accent
        val w = baseR * 0.05f
        for (i in 0 until 3) {
            ring.strokeWidth = w
            ring.color = withA(ringColor, (150 - i * 45).coerceAtLeast(20))
            val wobble = if (mode == EngineState.LISTENING) smooth * baseR * 0.08f * (i + 1) else if (mode == EngineState.SPEAKING) pulse * baseR * 0.04f * (i + 1) else 0f
            canvas.drawCircle(0f, 0f, baseR * (1.2f + 0.28f * i) + wobble, ring)
        }
        if (mode == EngineState.THINKING) {
            arc.strokeWidth = w * 1.8f; arc.color = withA(accent2, 235)
            val r = baseR * 1.48f; oval.set(-r, -r, r, r)
            val start = (t / 5 % 360).toFloat()
            canvas.drawArc(oval, start, 95f, false, arc)
            canvas.drawArc(oval, start + 180f, 40f, false, arc)
        }
        canvas.restore()
        if (running && animationsOn) { if (mode == EngineState.IDLE) postInvalidateDelayed(66) else postInvalidateOnAnimation() }   // idle breathing needs ~15 fps, not 60
    }

    override fun onMeasure(w: Int, h: Int) {
        val d = (resources.displayMetrics.density * 56).toInt()
        setMeasuredDimension(resolveSize(d, w), resolveSize(d, h))
    }
}
