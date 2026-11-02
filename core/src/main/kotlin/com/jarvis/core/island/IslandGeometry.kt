package com.jarvis.core.island

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A rectangle in pixels; right/bottom are exclusive edges (width = right - left). */
data class RectPx(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    fun intersects(o: RectPx): Boolean = left < o.right && o.left < right && top < o.bottom && o.top < bottom
    fun inflate(d: Int) = RectPx(left - d, top - d, right + d, bottom + d)
}

data class InsetsPx(val left: Int = 0, val top: Int = 0, val right: Int = 0, val bottom: Int = 0)

data class SizeDp(val width: Float, val height: Float)

enum class IslandPosition {
    AUTO, LEFT, CENTER, RIGHT;

    companion object {
        fun fromSetting(v: String?): IslandPosition = values().firstOrNull { it.name.equals(v?.trim(), ignoreCase = true) } ?: AUTO
    }
}

enum class IslandAnchor { LEFT, CENTER, RIGHT }

/** Where the placement came from: the display cutout, the user's explicit choice, or the built-in top-centre default. */
enum class IslandSource { CUTOUT, SETTING, FALLBACK }

/** The user's choices from Settings (position, offsets in dp, size in percent). */
data class IslandConfig(val position: IslandPosition = IslandPosition.AUTO, val offsetXdp: Float = 0f, val offsetYdp: Float = 0f, val sizePercent: Int = 100)

data class IslandInputs(
    /** Size of the window the island lives in (current window bounds, so folding and split-screen are handled). */
    val displayWidth: Int,
    val displayHeight: Int,
    val density: Float,
    /** Surface.ROTATION_*; only used in the explanation, the cutout rectangles are already in the rotated coordinate space. */
    val rotation: Int = 0,
    val statusBarHeight: Int = 0,
    /** DisplayCutout safe insets (and waterfall insets). */
    val safeInsets: InsetsPx = InsetsPx(),
    /** DisplayCutout.getBoundingRects(), in the same coordinates as the window. May be empty or contain nonsense; both are handled. */
    val cutouts: List<RectPx> = emptyList(),
    val config: IslandConfig = IslandConfig(),
    val expanded: Boolean = false,
    val compact: SizeDp = SizeDp(168f, 36f),
    val expandedSize: SizeDp = SizeDp(340f, 168f),
)

/**
 * Result of [IslandGeometry.place]. [x]/[y]/[width]/[height] describe the island itself; [hit] is the (larger) touch window, at least 48 dp each way;
 * [keepOut] are cutout rectangles (display coordinates) that lie inside the island's bounds: the view must draw nothing and offer nothing to touch there.
 */
data class IslandPlacement(
    val x: Int, val y: Int, val width: Int, val height: Int,
    val anchor: IslandAnchor,
    val source: IslandSource,
    val reason: String,
    val hit: RectPx,
    val keepOut: List<RectPx> = emptyList(),
) {
    val bounds: RectPx get() = RectPx(x, y, x + width, y + height)
}

/**
 * Pure placement maths for the overlay island. It only ever sees what Android reports about the display cutout (DisplayCutout bounding rectangles and
 * insets); it does NOT know where the physical camera is and never claims to. With no usable cutout information the island goes to a configurable top-centre.
 *
 * Rules (each covered by tests):
 *  - a cutout in the top/status-bar band decides the anchor: centred -> the island wraps around it (content is kept off the cutout via [IslandPlacement.keepOut]);
 *    left or right -> the island sits beside it, toward the middle of the screen;
 *  - several cutouts: the one nearest the top edge wins, the wider one on a tie; cutouts on the side edges (landscape) only push the island away from them;
 *  - the user's position/offset choices override the automatic ones, but never move the island off screen or into a cutout it could avoid;
 *  - without a usable cutout the island is top-centre below the status bar; it is always inside the safe insets and at least 48 dp touchable.
 */
object IslandGeometry {
    private const val CENTER_BAND = 0.12f      // cutout centre within 12 % of the middle of the screen counts as "centred"
    private const val MIN_TOUCH_DP = 48f

    fun place(i: IslandInputs): IslandPlacement {
        val d = if (i.density.isFinite() && i.density > 0f) i.density else 1f
        fun dp(v: Float) = (v * d).roundToInt()
        val w = i.displayWidth
        val h = i.displayHeight
        val scale = i.config.sizePercent.coerceIn(60, 160) / 100f
        val compactW = dp(i.compact.width * scale)
        val compactH = max(dp(i.compact.height * scale), dp(28f))

        if (w <= 0 || h <= 0) {
            val r = RectPx(0, 0, max(compactW, 0), max(compactH, 0))
            return IslandPlacement(0, 0, r.width, r.height, IslandAnchor.CENTER, IslandSource.FALLBACK, "display size unknown", r)
        }

        val margin = dp(8f)
        val gap = dp(8f)
        val ins = InsetsPx(i.safeInsets.left.coerceIn(0, w / 3), i.safeInsets.top.coerceIn(0, h / 3), i.safeInsets.right.coerceIn(0, w / 3), i.safeInsets.bottom.coerceIn(0, h / 3))
        val minX = ins.left + margin
        val maxRight = w - ins.right - margin
        val bar = (if (i.statusBarHeight > 0) i.statusBarHeight else dp(24f)).coerceIn(0, h / 4)
        val landscape = w > h

        // ---- usable cutouts: clipped to the window, non-empty, not absurdly large
        val valid = i.cutouts.mapNotNull { clip(it, w, h) }.filter { it.width <= w * 9 / 10 && it.height <= h * 9 / 10 }
        val band = max(bar, dp(32f))
        val topCandidates = valid.filter { it.top <= band && it.height <= h / 4 && it.width <= w * 6 / 10 }
            .sortedWith(compareBy<RectPx> { it.top }.thenByDescending { it.width })
        val primary = topCandidates.firstOrNull()

        val cfg = i.config
        val minY = if (primary != null) 0 else bar
        var anchor: IslandAnchor
        var source: IslandSource
        var reason: String
        var cw = compactW
        val ch = compactH
        var x: Int
        var y: Int

        fun rowY(p: RectPx) = max(dp(2f), (p.centerY - ch / 2f).roundToInt())

        if (cfg.position == IslandPosition.AUTO) {
            if (primary != null) {
                val cx = primary.centerX
                anchor = when {
                    abs(cx - w / 2f) <= w * CENTER_BAND -> IslandAnchor.CENTER
                    cx < w / 2f -> IslandAnchor.LEFT
                    else -> IslandAnchor.RIGHT
                }
                source = IslandSource.CUTOUT
                y = rowY(primary)
                when (anchor) {
                    IslandAnchor.CENTER -> {
                        cw = max(cw, primary.width + 2 * dp(32f))
                        x = (cx - cw / 2f).roundToInt()
                        reason = "wraps the ${if (primary.width > dp(120f)) "wide notch" else "cutout"} at the top centre"
                    }
                    IslandAnchor.LEFT -> {
                        x = primary.right + gap
                        reason = "beside the cutout on the left, toward the middle"
                    }
                    IslandAnchor.RIGHT -> {
                        cw = min(cw, max(primary.left - gap - minX, 0)).let { if (it < dp(96f)) cw else it }
                        x = primary.left - gap - cw
                        reason = "beside the cutout on the right, toward the middle"
                    }
                }
                // beside-the-cutout needs room; otherwise drop under it
                if (anchor == IslandAnchor.LEFT) {
                    val room = maxRight - x
                    if (room < dp(96f)) { anchor = IslandAnchor.CENTER; x = (w - cw) / 2; y = primary.bottom + dp(4f); reason = "no room beside the cutout; placed below it" }
                    else cw = min(cw, room)
                } else if (anchor == IslandAnchor.RIGHT && x < minX) {
                    anchor = IslandAnchor.CENTER; x = (w - cw) / 2; y = primary.bottom + dp(4f); reason = "no room beside the cutout; placed below it"
                }
            } else {
                anchor = IslandAnchor.CENTER
                source = IslandSource.FALLBACK
                x = (w - cw) / 2
                y = bar + dp(4f)
                reason = when {
                    valid.isEmpty() -> "no display cutout reported; top-centre default"
                    landscape -> "the cutout is on a side edge in landscape; island kept at the top centre"
                    else -> "no cutout in the top band; top-centre default"
                }
            }
        } else {
            source = IslandSource.SETTING
            anchor = when (cfg.position) { IslandPosition.LEFT -> IslandAnchor.LEFT; IslandPosition.RIGHT -> IslandAnchor.RIGHT; else -> IslandAnchor.CENTER }
            y = if (primary != null) rowY(primary) else bar + dp(4f)
            x = when (anchor) { IslandAnchor.LEFT -> minX; IslandAnchor.RIGHT -> maxRight - cw; IslandAnchor.CENTER -> (w - cw) / 2 }
            reason = "position chosen in settings (${anchor.name.lowercase()})"
        }

        // ---- the user's offsets, then clamp into the safe area
        val dx = dp(cfg.offsetXdp).coerceIn(-w / 2, w / 2)
        val dy = dp(cfg.offsetYdp).coerceIn(-h / 4, h / 2)
        if (dx != 0 || dy != 0) reason += "; moved by your offset"
        x += dx
        y += dy
        cw = cw.coerceIn(1, max(maxRight - minX, 1))
        x = clampRange(x, minX, maxRight - cw)
        y = clampRange(y, minY, h - ins.bottom - margin - ch)

        // ---- keep clear of cutouts that can be avoided (everything except the wrap-around case)
        var rect = RectPx(x, y, x + cw, y + ch)
        val wrap = anchor == IslandAnchor.CENTER && primary != null && cfg.position != IslandPosition.LEFT && cfg.position != IslandPosition.RIGHT
        if (!wrap) {
            for (pass in 0 until 3) {
                val hitC = valid.firstOrNull { it.inflate(gap / 2).intersects(rect) } ?: break
                val roomRight = maxRight - (hitC.right + gap)
                val roomLeft = (hitC.left - gap) - minX
                val toRight = cfg.position != IslandPosition.RIGHT && (roomRight >= cw || roomRight >= roomLeft)
                val nx = if (toRight) hitC.right + gap else hitC.left - gap - cw
                if (nx >= minX && nx + cw <= maxRight) { x = nx; reason += "; shifted clear of the cutout" }
                else { y = clampRange(hitC.bottom + dp(4f), minY, h - ins.bottom - margin - ch); reason += "; moved below the cutout" }
                rect = RectPx(x, y, x + cw, y + ch)
            }
        }

        val compactPlacement = IslandPlacement(x, y, cw, ch, anchor, source, reason, hitRect(rect, dp(MIN_TOUCH_DP), w, h), keepOutFor(rect, valid, gap))
        if (!i.expanded) return compactPlacement
        return expand(compactPlacement, i, ::dp, valid, w, h, ins, margin, gap, scale)
    }

    private fun expand(c: IslandPlacement, i: IslandInputs, dp: (Float) -> Int, valid: List<RectPx>, w: Int, h: Int, ins: InsetsPx, margin: Int, gap: Int, scale: Float): IslandPlacement {
        val minX = ins.left + margin
        val maxRight = w - ins.right - margin
        val maxW = max(maxRight - minX, 1)
        val ew = dp(i.expandedSize.width * scale).coerceIn(min(dp(200f), maxW), maxW)
        val availH = max(h - ins.bottom - margin - c.y, dp(80f))
        val eh = min(dp(i.expandedSize.height * scale), availH).coerceAtLeast(c.height)
        val ex = when (c.anchor) {
            IslandAnchor.CENTER -> (c.x + c.width / 2f - ew / 2f).roundToInt()
            IslandAnchor.LEFT -> c.x
            IslandAnchor.RIGHT -> c.x + c.width - ew
        }.let { clampRange(it, minX, maxRight - ew) }
        val rect = RectPx(ex, c.y, ex + ew, c.y + eh)
        return c.copy(x = ex, width = ew, height = eh, hit = hitRect(rect, dp(MIN_TOUCH_DP), w, h), keepOut = keepOutFor(rect, valid, gap), reason = c.reason + "; expanded downward")
    }

    private fun keepOutFor(rect: RectPx, cutouts: List<RectPx>, gap: Int): List<RectPx> = cutouts.filter { it.inflate(gap / 2).intersects(rect) }

    private fun hitRect(r: RectPx, minDim: Int, w: Int, h: Int): RectPx {
        val needW = max(minDim - r.width, 0)
        val needH = max(minDim - r.height, 0)
        val left = clampRange(r.left - needW / 2, 0, max(w - max(r.width, minDim), 0))
        val top = clampRange(r.top - needH / 2, 0, max(h - max(r.height, minDim), 0))
        return RectPx(left, top, min(left + max(r.width, minDim), w), min(top + max(r.height, minDim), h))
    }

    private fun clip(r: RectPx, w: Int, h: Int): RectPx? {
        val c = RectPx(max(r.left, 0), max(r.top, 0), min(r.right, w), min(r.bottom, h))
        return if (c.isEmpty) null else c
    }

    /** Like coerceIn but tolerates hi < lo (tiny screens): the low bound wins, so the result is never negative-sized nonsense. */
    private fun clampRange(v: Int, lo: Int, hi: Int): Int = if (hi < lo) lo else v.coerceIn(lo, hi)
}
