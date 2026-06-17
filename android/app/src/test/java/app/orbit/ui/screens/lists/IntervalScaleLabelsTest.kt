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
