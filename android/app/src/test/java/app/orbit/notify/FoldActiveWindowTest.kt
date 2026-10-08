package app.orbit.notify

import java.time.DayOfWeek
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test

/**
 * LIST-25: [foldActiveWindow] is what [app.orbit.data.db.MIGRATION_13_14] and
 * the importer turn a list's old time of day into. The promise is that nobody's
 * nudge moves, so the fold must give exactly the times the scheduler
 * ([NudgeScheduler.effectiveSchedule]) woke at and the worker's gate
 * ([isInActiveWindow]) let post; the last test checks that for every part of
 * the day and some custom windows, the rest pin the cases by name.
 *
 * The windows are the retired parts of the day as they sit in people's
 * databases (Mornings 7am to noon, Afternoons noon to 5pm, Evenings 5pm to
 * 9pm, Nights 9pm to 7am), plus two custom ones from the older start and end
 * pickers. Plain JVM: everything here is pure.
 */
class FoldActiveWindowTest {

    private fun t(hour: Int, minute: Int = 0): LocalTime = LocalTime.of(hour, minute)

    private val mornings = t(7) to t(12)
    private val afternoons = t(12) to t(17)
    private val evenings = t(17) to t(21)
    private val nights = t(21) to t(7)

    private val everyDay = DayOfWeek.entries.toSet()
    private val weekdays = setOf(
        DayOfWeek.MONDAY,
        DayOfWeek.TUESDAY,
        DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY,
        DayOfWeek.FRIDAY,
    )

    private fun NudgeSchedule.fold(window: Pair<LocalTime, LocalTime>) = foldActiveWindow(window.first, window.second)

    @Test
    fun evenings_on_the_default_ten_am_becomes_five_pm() {
        // The case the owner's review turned on: 10am was held back every day
        // and the nudge came at 5pm. Now the list says 5pm, and nudges at 5pm.
        assertEquals(NudgeSchedule(days = everyDay, times = listOf(t(17))), NudgeSchedule.DEFAULT.fold(evenings))
    }

    @Test
    fun a_time_on_the_windows_end_becomes_the_start() {
        // 9pm under Evenings: the end is outside the window (the gate asks a
        // moment after the slot), so the start was the nudge.
        assertEquals(listOf(t(17)), NudgeSchedule(everyDay, listOf(t(21))).fold(evenings).times)
    }

    @Test
    fun afternoons_and_nights_become_their_start_too() {
        assertEquals(listOf(t(12)), NudgeSchedule.DEFAULT.fold(afternoons).times)
        // Across midnight: 10am is outside 9pm to 7am.
        assertEquals(listOf(t(21)), NudgeSchedule.DEFAULT.fold(nights).times)
    }

    @Test
    fun a_time_inside_is_kept_as_it_is() {
        assertEquals(NudgeSchedule.DEFAULT, NudgeSchedule.DEFAULT.fold(mornings))
        // Nights on both sides of midnight.
        assertEquals(listOf(t(1), t(23)), NudgeSchedule(everyDay, listOf(t(23), t(1))).fold(nights).times)
    }

    @Test
    fun a_time_outside_is_dropped_while_one_inside_stays() {
        val schedule = NudgeSchedule(days = weekdays, times = listOf(t(18), t(10)))
        // 10am never posted under Evenings; 6pm did, so nothing is added.
        assertEquals(NudgeSchedule(days = weekdays, times = listOf(t(18))), schedule.fold(evenings))
    }

    @Test
    fun a_custom_window_folds_the_same_way_and_never_changes_the_days() {
        val folded = NudgeSchedule(days = weekdays, times = listOf(t(8))).fold(t(9) to t(17))
        assertEquals(listOf(t(9)), folded.times)
        assertEquals(weekdays, folded.days)
    }

    @Test
    fun no_window_leaves_the_schedule_untouched() {
        val schedule = NudgeSchedule(days = weekdays, times = listOf(t(23), t(6, 30)))
        assertSame(schedule, schedule.foldActiveWindow(null, null))
        assertSame(schedule, schedule.foldActiveWindow(t(17), null), "half a window was never applied")
        assertSame(schedule, schedule.foldActiveWindow(null, t(21)))
    }

    @Test
    fun nudges_off_stay_off() {
        val noTimes = NudgeSchedule(days = everyDay, times = emptyList())
        assertSame(noTimes, noTimes.fold(evenings), "no time set stays no time set; the start is not added")

        // No days chosen: still off. The times are folded anyway, so choosing
        // days again nudges when the list used to, not at a time it never did.
        val noDays = NudgeSchedule(days = emptySet(), times = listOf(t(10)))
        assertEquals(NudgeSchedule(days = emptySet(), times = listOf(t(17))), noDays.fold(evenings))
    }

    @Test
    fun spans_midnight_only_when_the_end_is_before_the_start() {
        assertTrue(spansMidnight(nights.first, nights.second))
        assertTrue(spansMidnight(t(22), t(2)))
        listOf(mornings, afternoons, evenings).forEach { (start, end) -> assertFalse(spansMidnight(start, end)) }
        assertFalse(spansMidnight(t(9), t(9)))
    }

    @Test
    fun the_fold_is_the_schedulers_slots_that_the_gate_let_post() {
        // The promise behind the migration: for a list with chosen days, the
        // folded times are exactly the slots the chain woke at and the gate let
        // through, so after the fold the nudge comes when it did before.
        val schedules = listOf(
            NudgeSchedule.DEFAULT,
            NudgeSchedule(days = weekdays, times = listOf(t(6, 30), t(12), t(19, 45), t(23))),
            NudgeSchedule(days = setOf(DayOfWeek.SUNDAY), times = listOf(t(7))),
            NudgeSchedule(days = everyDay, times = listOf(t(17), t(21))),
        )
        val windows = listOf(mornings, afternoons, evenings, nights, t(9) to t(17), t(22) to t(2))
        schedules.forEach { schedule ->
            windows.forEach { (start, end) ->
                val posted = NudgeScheduler.effectiveSchedule(schedule, start, end).times
                    .filter { isInActiveWindow(it, start, end) }
                    .distinct()
                    .sorted()
                val folded = schedule.foldActiveWindow(start, end)
                assertEquals(posted, folded.times, "$schedule in $start..$end")
                assertEquals(schedule.days, folded.days, "$schedule in $start..$end")
                // And with the window gone, the scheduler runs the folded times as they are.
                assertEquals(folded, NudgeScheduler.effectiveSchedule(folded, null, null))
            }
        }
    }
}
