package app.orbit.ui.screens.lists

import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure-Kotlin tests for [spansMidnight], which decides whether the active
 * hours bar draws one segment or two and whether the editor shows the
 * "Overnight list" note.
 *
 * Until 2026-10-06 this file also pinned an `activeHoursReadout` formatter
 * and its three strings ("9am – 5pm", "(overnight)", "Always"); no composable
 * ever called it, so the strings were dead and carried en dashes. Both went
 * with it; the editor itself says "9am to 5pm". The `ActiveHoursRangeBar`
 * two-segment renderer is checked visually in the screenshot gallery
 * (`ActiveHoursEditorDarkOvernightPreview`, `ListConfigActiveHoursSetPreview`).
 */
class ActiveHoursFormatterTest {

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
}
