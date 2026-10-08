package app.orbit.ui.screens.lists

import app.orbit.domain.usecase.timeOfDayPenalty
import app.orbit.notify.NudgeSchedule
import app.orbit.notify.NudgeScheduler
import app.orbit.notify.isInActiveWindow
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/**
 * LIST-25: the parts of the day are names for windows on the existing
 * active-hours columns, so the mapping has to hold both ways (a part writes
 * its window, a stored window reads back as its part) and anything else must
 * read back as Custom, untouched. Nights crosses midnight, so this also pins
 * that the window logic the parts rely on already handles that: the nudge
 * gate and the scheduler (`isInActiveWindow`, `NudgeScheduler
 * .effectiveSchedule`) and the widget's time-of-day weight
 * (`timeOfDayPenalty`, which has its own copy of the check).
 *
 * Replaces ActiveHoursFormatterTest, which pinned [spansMidnight] for the
 * active-hours editor's two-segment bar; the editor is gone, the helper is
 * still the nudge schedule's, and its three cases are kept below.
 *
 * Plain JVM: `effectiveSchedule` is on the scheduler's companion since
 * 2026-10-08, so no scheduler (and no Context) is built for it.
 */
class TimeOfDayMappingTest {

    private fun t(hour: Int, minute: Int = 0): LocalTime = LocalTime.of(hour, minute)

    // ─── A part writes its window ────────────────────────────────────────────

    @Test
    fun each_part_is_the_window_the_decision_names() {
        assertEquals(null to null, DayPart.AnyTime.start to DayPart.AnyTime.end)
        assertEquals(t(7) to t(12), DayPart.Mornings.start to DayPart.Mornings.end)
        assertEquals(t(12) to t(17), DayPart.Afternoons.start to DayPart.Afternoons.end)
        assertEquals(t(17) to t(21), DayPart.Evenings.start to DayPart.Evenings.end)
        assertEquals(t(21) to t(7), DayPart.Nights.start to DayPart.Nights.end)
    }

    // ─── A stored window reads back as its part ──────────────────────────────

    @Test
    fun every_part_round_trips_through_its_window() {
        DayPart.entries.forEach { part ->
            assertEquals(TimeOfDay.Part(part), timeOfDayFor(part.start, part.end), "$part")
        }
    }

    @Test
    fun a_missing_end_reads_as_any_time_because_no_window_applies() {
        // The gate and the scheduler apply a window only when both ends are
        // set, so this is what the list does today.
        assertEquals(TimeOfDay.Part(DayPart.AnyTime), timeOfDayFor(t(9), null))
        assertEquals(TimeOfDay.Part(DayPart.AnyTime), timeOfDayFor(null, t(17)))
    }

    @Test
    fun a_window_that_is_no_part_reads_back_as_custom_exactly_as_stored() {
        assertEquals(TimeOfDay.Custom(t(9), t(17)), timeOfDayFor(t(9), t(17)))
        // The old editor's own example: a late night list active 9pm to 2am.
        assertEquals(TimeOfDay.Custom(t(21), t(2)), timeOfDayFor(t(21), t(2)))
        // One minute off a part is not that part.
        assertEquals(TimeOfDay.Custom(t(7, 1), t(12)), timeOfDayFor(t(7, 1), t(12)))
        // Reversed Mornings is not Mornings.
        assertEquals(TimeOfDay.Custom(t(12), t(7)), timeOfDayFor(t(12), t(7)))
    }

    // ─── Nights across midnight ──────────────────────────────────────────────

    @Test
    fun spansMidnight_true_when_end_before_start() {
        assertTrue(spansMidnight(t(21), t(2)))
        assertTrue(spansMidnight(DayPart.Nights.start!!, DayPart.Nights.end!!))
    }

    @Test
    fun spansMidnight_false_when_end_after_start() {
        assertFalse(spansMidnight(t(9), t(17)))
        listOf(DayPart.Mornings, DayPart.Afternoons, DayPart.Evenings).forEach { part ->
            assertFalse(spansMidnight(part.start!!, part.end!!), "$part")
        }
    }

    @Test
    fun spansMidnight_false_when_end_equals_start() {
        assertFalse(spansMidnight(t(12), t(12)))
    }

    @Test
    fun the_nudge_gate_holds_nights_on_both_sides_of_midnight() {
        val (start, end) = DayPart.Nights.start!! to DayPart.Nights.end!!
        listOf(t(21), t(23, 30), t(0), t(3), t(6, 59)).forEach {
            assertTrue(isInActiveWindow(it, start, end), "$it is night")
        }
        // 7am is Mornings' start, not Nights' end too (the end is out).
        listOf(t(7), t(7, 1), t(12), t(20, 59)).forEach {
            assertFalse(isInActiveWindow(it, start, end), "$it is not night")
        }
    }

    @Test
    fun the_nudge_gate_holds_a_daytime_part_to_its_hours() {
        val (start, end) = DayPart.Mornings.start!! to DayPart.Mornings.end!!
        assertTrue(isInActiveWindow(t(7), start, end))
        assertTrue(isInActiveWindow(t(11, 59), start, end))
        // Noon is Afternoons' start, not Mornings' end too (the end is out).
        assertFalse(isInActiveWindow(t(12), start, end))
        assertFalse(isInActiveWindow(t(6, 59), start, end))
    }

    @Test
    fun the_widget_weight_counts_nights_as_inside_after_midnight() {
        val (start, end) = DayPart.Nights.start to DayPart.Nights.end
        val at = { time: LocalTime -> LocalDate.of(2026, 10, 7).atTime(time).toInstant(ZoneOffset.UTC) }
        assertEquals(Duration.ZERO, timeOfDayPenalty(at(t(2)), ZoneOffset.UTC, start, end))
        assertEquals(Duration.ZERO, timeOfDayPenalty(at(t(22)), ZoneOffset.UTC, start, end))
        // At 7:30pm the window opens in 90 minutes.
        assertEquals(Duration.ofMinutes(90), timeOfDayPenalty(at(t(19, 30)), ZoneOffset.UTC, start, end))
    }

    // ─── Why the parts start where they do ───────────────────────────────────

    private val everyDayAtSix = NudgeSchedule(days = DayOfWeek.entries.toSet(), times = listOf(t(18)))

    @Test
    fun a_nudge_time_outside_the_part_adds_the_parts_start_and_nothing_earlier() {
        // Mornings start at 7 so that this slot is 7am, not 5am.
        assertEquals(
            listOf(t(18), t(7)),
            NudgeScheduler.effectiveSchedule(everyDayAtSix, DayPart.Mornings.start, DayPart.Mornings.end).times
        )
        // Across midnight too: a 6pm time is outside Nights, so 9pm is added.
        assertEquals(
            listOf(t(18), t(21)),
            NudgeScheduler.effectiveSchedule(everyDayAtSix, DayPart.Nights.start, DayPart.Nights.end).times
        )
    }

    @Test
    fun a_nudge_time_inside_the_part_is_left_alone() {
        assertEquals(
            everyDayAtSix,
            NudgeScheduler.effectiveSchedule(everyDayAtSix, DayPart.Evenings.start, DayPart.Evenings.end)
        )
        assertEquals(
            everyDayAtSix,
            NudgeScheduler.effectiveSchedule(everyDayAtSix, DayPart.AnyTime.start, DayPart.AnyTime.end)
        )
    }
}
