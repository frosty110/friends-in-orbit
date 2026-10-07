package app.orbit.ui.screens.lists

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * F-4 regression — the prior implementation rendered 1d/2w/1m/2m labels in a
 * `Row(Arrangement.SpaceBetween)`, placing them at fractions 0.0/0.333/0.667/1.0.
 * The slider's underlying axis is 1..60 days with thumb fraction (day-1)/59,
 * so labels must follow the same math: 2w(14d)→0.22, 1m(30d)→0.49, 2m(60d)→1.0.
 * The pre-fix UI said 2w≈20d and 1m≈40d. These tests lock the post-fix math.
 */
class IntervalScaleLabelsTest {

    private val tolerance = 0.001f

    @Test
    fun `1d at min fraction is 0`() {
        assertEquals(0f, intervalLabelFraction(day = 1, minDay = 1, maxDay = 60), tolerance)
    }

    @Test
    fun `60d at max fraction is 1`() {
        assertEquals(1f, intervalLabelFraction(day = 60, minDay = 1, maxDay = 60), tolerance)
    }

    @Test
    fun `2w (14d) lands near 0_22 — pre-fix was 0_333`() {
        // 13/59 ≈ 0.2203
        assertEquals(13f / 59f, intervalLabelFraction(day = 14, minDay = 1, maxDay = 60), tolerance)
    }

    @Test
    fun `1m (30d) lands near 0_49 (centered) — pre-fix was 0_667`() {
        // 29/59 ≈ 0.4915 — visually centered, matches founder's expectation in F-4
        assertEquals(29f / 59f, intervalLabelFraction(day = 30, minDay = 1, maxDay = 60), tolerance)
    }

    @Test
    fun `day below min clamps to 0`() {
        assertEquals(0f, intervalLabelFraction(day = 0, minDay = 1, maxDay = 60), tolerance)
        assertEquals(0f, intervalLabelFraction(day = -10, minDay = 1, maxDay = 60), tolerance)
    }

    @Test
    fun `day above max clamps to 1`() {
        assertEquals(1f, intervalLabelFraction(day = 100, minDay = 1, maxDay = 60), tolerance)
    }

    @Test
    fun `degenerate range guards against divide-by-zero`() {
        // minDay == maxDay → span of 0 would explode; helper coerces span to at least 1.
        assertEquals(0f, intervalLabelFraction(day = 5, minDay = 5, maxDay = 5), tolerance)
    }

    // At 200% text the 1 day, 2 weeks and 1 month labels were printed over one
    // another (seen in the gallery's 200% render of How often, 2026-10-07).
    // Pixel numbers below are the shape of that render: an 800px track.

    @Test
    fun `at normal size every tick fits`() {
        assertEquals(
            setOf(0, 1, 2, 3),
            ticksThatFit(lefts = listOf(0, 129, 343, 685), widths = listOf(60, 95, 100, 115), gap = 16)
        )
    }

    @Test
    fun `at 200 percent a tick that would overlap is left out and the ends stay`() {
        // "2 weeks" (left 81) would start inside "1 day" (0 to 120): dropped.
        // "1 month" (293 to 493) clears "1 day" and the end at 570: kept.
        assertEquals(
            setOf(0, 2, 3),
            ticksThatFit(lefts = listOf(0, 81, 293, 570), widths = listOf(120, 190, 200, 230), gap = 16)
        )
    }

    @Test
    fun `a middle tick that would run into the last one is left out`() {
        // "1 month" at 420 to 570 would end inside the last label, at 560.
        assertEquals(
            setOf(0, 1, 3),
            ticksThatFit(lefts = listOf(0, 200, 420, 560), widths = listOf(100, 150, 150, 240), gap = 16)
        )
    }

    @Test
    fun `no ticks, nothing to draw`() {
        assertEquals(emptySet<Int>(), ticksThatFit(emptyList(), emptyList(), gap = 16))
    }

    @Test
    fun `monotonicity — larger day yields larger or equal fraction`() {
        var prev = -1f
        for (day in 1..60) {
            val f = intervalLabelFraction(day = day, minDay = 1, maxDay = 60)
            assert(f >= prev) { "non-monotonic at day=$day: $f < $prev" }
            prev = f
        }
    }
}
