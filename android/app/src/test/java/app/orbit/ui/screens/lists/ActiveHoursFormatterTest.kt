package app.orbit.ui.screens.lists

import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure-Kotlin tests for [activeHoursReadout] and [spansMidnight].
 *
 * Covers overnight active hours at the formatter layer. The
 * `ActiveHoursRangeBar` two-segment renderer is verified visually via @Preview
 * + `/review-change lists/config` once it's wired into the screen.
 */
class ActiveHoursFormatterTest {

    @Test
    fun always_when_both_null() {
        assertEquals("Always", activeHoursReadout(null, null))
    }

    @Test
    fun normal_range_renders_dash_pair() {
        assertEquals(
            "9am – 5pm",
            activeHoursReadout(LocalTime.of(9, 0), LocalTime.of(17, 0)),
        )
    }

    @Test
    fun overnight_span_appends_overnight_suffix() {
        val out = activeHoursReadout(LocalTime.of(21, 0), LocalTime.of(2, 0))
        assertTrue(out.endsWith("(overnight)"), "Expected '(overnight)' suffix, got: $out")
        assertTrue(out.startsWith("9pm"), "Expected start at 9pm, got: $out")
        assertTrue(out.contains("2am"), "Expected end at 2am, got: $out")
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
            "9:30am – 5:15pm",
            activeHoursReadout(LocalTime.of(9, 30), LocalTime.of(17, 15)),
        )
    }
}
