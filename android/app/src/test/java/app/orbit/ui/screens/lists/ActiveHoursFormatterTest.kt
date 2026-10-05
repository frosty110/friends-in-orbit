package app.orbit.ui.screens.lists

import app.orbit.R
import app.orbit.ui.util.UiText
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure-Kotlin tests for [activeHoursReadout] and [spansMidnight].
 *
 * The readout is [UiText]: the words ("Always", "(overnight)") live in
 * strings_lists.xml, so these tests pin which sentence is chosen and the
 * times passed into it, without resolving resources.
 *
 * Covers overnight active hours at the formatter layer. The
 * `ActiveHoursRangeBar` two-segment renderer is verified visually via @Preview
 * + `/review-change lists/config` once it's wired into the screen.
 */
class ActiveHoursFormatterTest {

    @Test
    fun always_when_both_null() {
        assertEquals(UiText.res(R.string.lists_hours_readout_always), activeHoursReadout(null, null))
    }

    @Test
    fun normal_range_renders_dash_pair() {
        assertEquals(
            UiText.res(R.string.lists_hours_readout_range, "9am", "5pm"),
            activeHoursReadout(LocalTime.of(9, 0), LocalTime.of(17, 0)),
        )
    }

    @Test
    fun overnight_span_appends_overnight_suffix() {
        val out = activeHoursReadout(LocalTime.of(21, 0), LocalTime.of(2, 0))
        assertTrue(out is UiText.Res, "Expected a string resource, got: $out")
        assertEquals(R.string.lists_hours_readout_overnight, out.id, "Expected the '(overnight)' sentence, got: $out")
        assertEquals("9pm", out.args[0], "Expected start at 9pm, got: $out")
        assertEquals("2am", out.args[1], "Expected end at 2am, got: $out")
    }

    @Test
    fun spansMidnight_true_when_end_before_start() {
        assertTrue(spansMidnight(LocalTime.of(21, 0), LocalTime.of(2, 0)))
    }

    @Test
    fun spansMidnight_false_when_end_after_start() {
        assertFalse(spansMidnight(LocalTime.of(9, 0), LocalTime.of(17, 0)))
    }

    @Test
    fun spansMidnight_false_when_end_equals_start() {
        assertFalse(spansMidnight(LocalTime.of(12, 0), LocalTime.of(12, 0)))
    }

    @Test
    fun renders_minutes_when_nonzero() {
        assertEquals(
            UiText.res(R.string.lists_hours_readout_range, "9:30am", "5:15pm"),
            activeHoursReadout(LocalTime.of(9, 30), LocalTime.of(17, 15)),
        )
    }
}
