package com.jarvis.core.island

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.roundToInt

class IslandGeometryTest {
    private val d = 2.75f
    private fun dp(v: Float) = (v * d).roundToInt()

    /** A typical 1080x2400 phone, density 2.75, status bar 24dp-ish (66 px). */
    private fun phone(cutouts: List<RectPx> = emptyList(), config: IslandConfig = IslandConfig(), expanded: Boolean = false, insets: InsetsPx = InsetsPx(), w: Int = 1080, h: Int = 2400, rotation: Int = 0, bar: Int = 66) =
        IslandInputs(w, h, d, rotation, bar, insets, cutouts, config, expanded)

    private fun IslandPlacement.insideScreen(w: Int, h: Int) = x >= 0 && y >= 0 && x + width <= w && y + height <= h
    private fun IslandPlacement.overlaps(r: RectPx) = bounds.intersects(r)

    // a 60 px punch hole centred at x = 540, 40..100 px from the top
    private val centreHole = RectPx(510, 40, 570, 100)

    @Test
    fun centredPunchHoleIsWrappedAndKeptClear() {
        val p = IslandGeometry.place(phone(listOf(centreHole)))
        assertEquals(IslandAnchor.CENTER, p.anchor)
        assertEquals(IslandSource.CUTOUT, p.source)
        assertEquals(540f, p.x + p.width / 2f, 1f)
        assertEquals("the cutout is reported so the view draws nothing there", listOf(centreHole), p.keepOut)
        assertTrue(p.width >= centreHole.width + 2 * dp(32f))
        assertTrue(p.insideScreen(1080, 2400))
        assertEquals("vertically on the hole's row", centreHole.centerY, p.y + p.height / 2f, 3f)
    }

    @Test
    fun leftPunchHoleGetsAnIslandBesideIt() {
        val hole = RectPx(120, 40, 180, 100)
        val p = IslandGeometry.place(phone(listOf(hole)))
        assertEquals(IslandAnchor.LEFT, p.anchor)
        assertEquals(IslandSource.CUTOUT, p.source)
        assertTrue("starts right of the hole", p.x >= hole.right)
        assertFalse(p.overlaps(hole))
        assertTrue(p.keepOut.isEmpty())
        assertTrue(p.insideScreen(1080, 2400))
    }

    @Test
    fun rightPunchHoleGetsAnIslandBesideIt() {
        val hole = RectPx(900, 40, 960, 100)
        val p = IslandGeometry.place(phone(listOf(hole)))
        assertEquals(IslandAnchor.RIGHT, p.anchor)
        assertTrue("ends left of the hole", p.x + p.width <= hole.left)
        assertFalse(p.overlaps(hole))
        assertTrue(p.insideScreen(1080, 2400))
    }

    @Test
    fun wideNotchIsWrappedWithRoomOnBothSides() {
        val notch = RectPx(340, 0, 740, 90) // 400 px wide
        val p = IslandGeometry.place(phone(listOf(notch)))
        assertEquals(IslandAnchor.CENTER, p.anchor)
        assertTrue(p.width >= 400 + 2 * dp(32f))
        assertEquals(listOf(notch), p.keepOut)
        assertTrue(p.reason.contains("notch"))
        assertTrue(p.insideScreen(1080, 2400))
    }

    @Test
    fun noCutoutMeansTopCentreBelowTheStatusBar() {
        val p = IslandGeometry.place(phone())
        assertEquals(IslandSource.FALLBACK, p.source)
        assertEquals(IslandAnchor.CENTER, p.anchor)
        assertEquals((1080 - p.width) / 2, p.x)
        assertTrue("below the status bar", p.y >= 66)
        assertEquals(dp(168f), p.width)
        assertTrue(p.keepOut.isEmpty())
    }

    @Test
    fun landscapeWithACutoutOnTheLeftEdgeStaysTopCentreAndClearsIt() {
        val side = RectPx(0, 480, 90, 600)
        val p = IslandGeometry.place(phone(listOf(side), w = 2400, h = 1080, rotation = 1, insets = InsetsPx(left = 90), bar = 66))
        assertEquals(IslandSource.FALLBACK, p.source)
        assertTrue(p.reason.contains("side edge"))
        assertEquals((2400 - p.width) / 2, p.x)
        assertFalse(p.overlaps(side))
        assertTrue(p.x >= 90 + dp(8f))
    }

    @Test
    fun landscapeWithACutoutOnTheRightEdgeStaysTopCentreAndClearsIt() {
        val side = RectPx(2310, 480, 2400, 600)
        val p = IslandGeometry.place(phone(listOf(side), w = 2400, h = 1080, rotation = 3, insets = InsetsPx(right = 90), bar = 66))
        assertEquals(IslandSource.FALLBACK, p.source)
        assertFalse(p.overlaps(side))
        assertTrue(p.x + p.width <= 2400 - 90 - dp(8f))
    }

    @Test
    fun tabletWithoutCutoutIsTopCentre() {
        val p = IslandGeometry.place(phone(w = 2560, h = 1600, bar = 72))
        assertEquals(IslandSource.FALLBACK, p.source)
        assertEquals((2560 - p.width) / 2, p.x)
        assertTrue(p.insideScreen(2560, 1600))
    }

    @Test
    fun foldableInnerDisplayWithACameraNearTheTopRight() {
        // the hole is inside the status-bar band but not touching the top edge
        val hole = RectPx(1900, 36, 1960, 96)
        val p = IslandGeometry.place(phone(listOf(hole), w = 2208, h = 1840, bar = 80))
        assertEquals(IslandSource.CUTOUT, p.source)
        assertEquals(IslandAnchor.RIGHT, p.anchor)
        assertFalse(p.overlaps(hole))
        assertTrue(p.insideScreen(2208, 1840))
    }

    @Test
    fun badDataIsIgnoredNotTrusted() {
        val junk = listOf(RectPx(0, 0, 0, 0), RectPx(500, 50, 400, 20), RectPx(2000, 40, 2100, 100), RectPx(-300, -300, -10, -10), RectPx(0, 0, 5000, 5000))
        val p = IslandGeometry.place(phone(junk))
        assertEquals(IslandSource.FALLBACK, p.source)
        assertTrue(p.keepOut.isEmpty())
        assertTrue(p.insideScreen(1080, 2400))
    }

    @Test
    fun zeroSizedOrNonsenseDisplaysDoNotCrash() {
        val a = IslandGeometry.place(IslandInputs(0, 0, d))
        assertTrue(a.width >= 0 && a.height >= 0)
        assertEquals(IslandSource.FALLBACK, a.source)
        val b = IslandGeometry.place(IslandInputs(1080, 2400, Float.NaN, bottomless()))
        assertTrue(b.insideScreen(1080, 2400))
        val c = IslandGeometry.place(IslandInputs(100, 100, 3f)) // far smaller than the island
        assertTrue(c.width > 0 && c.x >= 0)
    }

    private fun bottomless() = 0

    @Test
    fun userChoosesLeftEvenWithACentredCutout() {
        val p = IslandGeometry.place(phone(listOf(centreHole), IslandConfig(IslandPosition.LEFT)))
        assertEquals(IslandSource.SETTING, p.source)
        assertEquals(IslandAnchor.LEFT, p.anchor)
        assertEquals(dp(8f), p.x)
        assertFalse(p.overlaps(centreHole))
    }

    @Test
    fun userChoosesRightNextToARightCutoutAndStillClearsIt() {
        val hole = RectPx(900, 40, 960, 100)
        val p = IslandGeometry.place(phone(listOf(hole), IslandConfig(IslandPosition.RIGHT)))
        assertEquals(IslandSource.SETTING, p.source)
        assertFalse("shifted clear of the hole", p.overlaps(hole))
        assertTrue(p.insideScreen(1080, 2400))
    }

    @Test
    fun userChoosesCentreWithALeftCutout() {
        val hole = RectPx(120, 40, 180, 100)
        val p = IslandGeometry.place(phone(listOf(hole), IslandConfig(IslandPosition.CENTER)))
        assertEquals(IslandAnchor.CENTER, p.anchor)
        assertEquals((1080 - p.width) / 2, p.x)
        assertFalse(p.overlaps(hole))
    }

    @Test
    fun offsetsMoveTheIslandAndAreClamped() {
        val base = IslandGeometry.place(phone())
        val moved = IslandGeometry.place(phone(config = IslandConfig(offsetXdp = 30f, offsetYdp = 20f)))
        assertEquals(base.x + dp(30f), moved.x)
        assertEquals(base.y + dp(20f), moved.y)
        val wild = IslandGeometry.place(phone(config = IslandConfig(offsetXdp = 99999f, offsetYdp = 99999f)))
        assertTrue(wild.insideScreen(1080, 2400))
        val wildNeg = IslandGeometry.place(phone(config = IslandConfig(offsetXdp = -99999f, offsetYdp = -99999f)))
        assertTrue(wildNeg.insideScreen(1080, 2400))
        assertTrue("never above the status bar when there is no cutout", wildNeg.y >= 66)
    }

    @Test
    fun expandedGrowsDownwardKeepingTheTopAndAnchor() {
        val hole = RectPx(120, 40, 180, 100)
        val narrowCard = SizeDp(240f, 160f) // 660 px: fits to the right of the compact island, so the left edge can stay put
        val compact = IslandGeometry.place(IslandInputs(1080, 2400, d, 0, 66, InsetsPx(), listOf(hole), expandedSize = narrowCard))
        val big = IslandGeometry.place(IslandInputs(1080, 2400, d, 0, 66, InsetsPx(), listOf(hole), expanded = true, expandedSize = narrowCard))
        assertEquals(compact.y, big.y)
        assertTrue(big.height > compact.height)
        assertTrue(big.width > compact.width)
        assertEquals("left anchor keeps its left edge when the card fits", compact.x, big.x)
        assertTrue(big.insideScreen(1080, 2400))
    }

    @Test
    fun expandedCardThatWouldRunOffTheScreenIsShiftedBackInside() {
        val big = IslandGeometry.place(phone(listOf(RectPx(120, 40, 180, 100)), expanded = true)) // default 340 dp card = 935 px
        assertEquals(IslandAnchor.LEFT, big.anchor)
        assertTrue(big.x + big.width <= 1080 - dp(8f))
        assertTrue(big.x >= dp(8f))
    }

    @Test
    fun expandedCentredIslandStaysCentredAndReportsTheCutoutToAvoid() {
        val big = IslandGeometry.place(phone(listOf(centreHole), expanded = true))
        assertEquals(540f, big.x + big.width / 2f, 2f)
        assertEquals(listOf(centreHole), big.keepOut)
        assertTrue(big.insideScreen(1080, 2400))
    }

    @Test
    fun expandedRightAnchorStaysInsideTheScreen() {
        val big = IslandGeometry.place(phone(listOf(RectPx(900, 40, 960, 100)), expanded = true))
        assertTrue(big.insideScreen(1080, 2400))
        assertEquals(IslandAnchor.RIGHT, big.anchor)
    }

    @Test
    fun severalCutoutsTheNearestToTheTopWinsThenTheWiderOne() {
        val low = RectPx(100, 60, 160, 120)
        val high = RectPx(900, 10, 960, 70)
        assertEquals(IslandAnchor.RIGHT, IslandGeometry.place(phone(listOf(low, high))).anchor)
        val a = RectPx(100, 20, 160, 80)
        val b = RectPx(800, 20, 1000, 80) // same top, wider
        assertEquals(IslandAnchor.RIGHT, IslandGeometry.place(phone(listOf(a, b))).anchor)
        assertEquals(IslandAnchor.RIGHT, IslandGeometry.place(phone(listOf(b, a))).anchor)
    }

    @Test
    fun cutoutAtTheBottomOrFarBelowTheStatusBarIsNotATopCutout() {
        val bottom = RectPx(500, 2300, 580, 2400)
        assertEquals(IslandSource.FALLBACK, IslandGeometry.place(phone(listOf(bottom))).source)
        val mid = RectPx(500, 900, 560, 960)
        assertEquals(IslandSource.FALLBACK, IslandGeometry.place(phone(listOf(mid))).source)
    }

    @Test
    fun sizeSettingScalesTheIsland() {
        val small = IslandGeometry.place(phone(config = IslandConfig(sizePercent = 60)))
        val normal = IslandGeometry.place(phone())
        val large = IslandGeometry.place(phone(config = IslandConfig(sizePercent = 160)))
        assertTrue(small.width < normal.width && normal.width < large.width)
        assertEquals(normal.width, IslandGeometry.place(phone(config = IslandConfig(sizePercent = 100))).width)
        assertEquals("out-of-range percentages are clamped", small.width, IslandGeometry.place(phone(config = IslandConfig(sizePercent = 5))).width)
    }

    @Test
    fun touchWindowIsAtLeast48dpEachWayAndOnScreen() {
        for (p in listOf(IslandGeometry.place(phone()), IslandGeometry.place(phone(listOf(centreHole))), IslandGeometry.place(phone(config = IslandConfig(sizePercent = 60))))) {
            assertTrue("hit width ${p.hit.width}", p.hit.width >= dp(48f))
            assertTrue("hit height ${p.hit.height}", p.hit.height >= dp(48f))
            assertTrue(p.hit.left >= 0 && p.hit.top >= 0 && p.hit.right <= 1080 && p.hit.bottom <= 2400)
            assertTrue("the touch window contains the island", p.hit.left <= p.x && p.hit.top <= p.y && p.hit.right >= p.x + p.width && p.hit.bottom >= p.y + p.height)
        }
    }

    @Test
    fun waterfallInsetsAreRespected() {
        val p = IslandGeometry.place(phone(insets = InsetsPx(left = 60, right = 60), config = IslandConfig(IslandPosition.LEFT)))
        assertTrue(p.x >= 60 + dp(8f))
        val r = IslandGeometry.place(phone(insets = InsetsPx(left = 60, right = 60), config = IslandConfig(IslandPosition.RIGHT)))
        assertTrue(r.x + r.width <= 1080 - 60 - dp(8f))
    }

    @Test
    fun noRoomBesideTheCutoutPlacesTheIslandBelowIt() {
        // 120 dp wide screen (360 px at density 3) with a hole near the left: under 96 dp remain beside it
        val hole = RectPx(30, 20, 70, 60)
        val p = IslandGeometry.place(IslandInputs(360, 1200, 3f, 0, 72, InsetsPx(), listOf(hole)))
        assertFalse(p.overlaps(hole))
        assertTrue(p.reason.contains("below"))
        assertTrue(p.insideScreen(360, 1200))
    }

    @Test
    fun aHoleNearTheMiddleOfATinyScreenIsStillWrapped() {
        val hole = RectPx(60, 10, 110, 60)
        val p = IslandGeometry.place(IslandInputs(200, 800, 1f, 0, 24, InsetsPx(), listOf(hole)))
        assertEquals(IslandAnchor.CENTER, p.anchor)
        assertEquals(listOf(hole), p.keepOut)
        assertTrue(p.insideScreen(200, 800))
    }

    @Test
    fun positionSettingParsesAndDefaultsToAuto() {
        assertEquals(IslandPosition.LEFT, IslandPosition.fromSetting("Left"))
        assertEquals(IslandPosition.CENTER, IslandPosition.fromSetting(" center "))
        assertEquals(IslandPosition.AUTO, IslandPosition.fromSetting("sideways"))
        assertEquals(IslandPosition.AUTO, IslandPosition.fromSetting(null))
        assertNotNull(IslandPosition.values())
    }

    /** Random configurations must always satisfy the invariants: on screen, inside the safe insets, and clear of every cutout unless the cutout is reported as keep-out. */
    @Test
    fun randomConfigurationsKeepTheInvariants() {
        val rnd = Random(20261031)
        repeat(3000) { n ->
            val landscape = rnd.nextBoolean()
            val w = if (landscape) 1600 + rnd.nextInt(1200) else 700 + rnd.nextInt(900)
            val h = if (landscape) 700 + rnd.nextInt(900) else 1400 + rnd.nextInt(1200)
            val density = 1f + rnd.nextFloat() * 2.5f
            val cutouts = List(rnd.nextInt(4)) {
                val l = rnd.nextInt(w); val t = if (rnd.nextInt(3) == 0) rnd.nextInt(h) else rnd.nextInt(120)
                RectPx(l, t, l + 20 + rnd.nextInt(300), t + 20 + rnd.nextInt(150))
            }
            val insets = InsetsPx(rnd.nextInt(80), rnd.nextInt(120), rnd.nextInt(80), rnd.nextInt(80))
            val cfg = IslandConfig(IslandPosition.values()[rnd.nextInt(4)], (rnd.nextInt(400) - 200).toFloat(), (rnd.nextInt(300) - 100).toFloat(), 50 + rnd.nextInt(130))
            val inputs = IslandInputs(w, h, density, rnd.nextInt(4), rnd.nextInt(120), insets, cutouts, cfg, rnd.nextBoolean())
            val p = IslandGeometry.place(inputs)
            val msg = "case $n: $inputs -> $p"
            assertTrue(msg, p.width > 0 && p.height > 0)
            assertTrue(msg, p.insideScreen(w, h))
            assertTrue(msg, p.hit.left >= 0 && p.hit.top >= 0 && p.hit.right <= w && p.hit.bottom <= h)
            for (c in cutouts) {
                val clipped = RectPx(maxOf(c.left, 0), maxOf(c.top, 0), minOf(c.right, w), minOf(c.bottom, h))
                if (clipped.isEmpty || clipped.width > w * 9 / 10 || clipped.height > h * 9 / 10) continue
                if (p.bounds.intersects(clipped)) assertTrue("$msg: cutout $clipped overlaps without keep-out", clipped in p.keepOut)
            }
        }
    }
}
