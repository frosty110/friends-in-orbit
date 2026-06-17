package app.orbit.ui.screens.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * [thresholdsContradictionLine] boundary. The "commonly called"
 * top band and "rarely called" bottom band overlap exactly when their sum
 * exceeds 100; at 100 they tile the called set with nothing left over, which
 * is consistent (no contact lands in both).
 */
class PickerThresholdsValidationTest {

    @Test
    fun consistent_bands_return_null() {
        assertNull(thresholdsContradictionLine(commonlyTopPct = 20, rarelyBottomPct = 20))
        assertNull(thresholdsContradictionLine(commonlyTopPct = 50, rarelyBottomPct = 50))
        assertNull(thresholdsContradictionLine(commonlyTopPct = 5, rarelyBottomPct = 90))
    }

    @Test
    fun sum_of_exactly_100_is_consistent() {
        assertNull(thresholdsContradictionLine(commonlyTopPct = 40, rarelyBottomPct = 60))
    }

    @Test
    fun overlapping_bands_return_the_helper_line() {
        val line = thresholdsContradictionLine(commonlyTopPct = 50, rarelyBottomPct = 51)
        assertNotNull(line)
        assertEquals("These two bands overlap — together they can't be more than 100%.", line)
    }

    @Test
    fun max_stepper_values_overlap() {
        // Stepper maxima are 50 (top) and 90 (bottom) — reachable contradiction.
        assertNotNull(thresholdsContradictionLine(commonlyTopPct = 50, rarelyBottomPct = 90))
    }
}
