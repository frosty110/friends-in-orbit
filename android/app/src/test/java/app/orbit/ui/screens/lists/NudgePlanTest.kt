package app.orbit.ui.screens.lists

import app.orbit.notify.NudgeSchedule
import app.orbit.notify.NudgeScheduler
import app.orbit.notify.isInActiveWindow
import java.time.DayOfWeek
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * LIST-25: what List settings' "When to nudge" line and Make your first
 * list's summary say is [nudgePlan], read through the scheduler's own
 * [NudgeScheduler.effectiveSchedule] and the gate's [isInActiveWindow]. Until
 * 2026-10-08 the line read the stored schedule alone, so a list on the
 * default 10am that was set to Evenings said "Every day at 10am" while its
 * nudge came at 5pm. These pin the plan for each part of the day and that it
 * is the nudge's own answer, not a second calculation of it.
 *
 * Plain JVM: the plan, the scheduler's function and the gate's are pure.
 */
class NudgePlanTest {

    private fun t(hour: Int, minute: Int = 0): LocalTime = LocalTime.of(hour, minute)

    private val everyDay = DayOfWeek.entries.toSet()
    private val weekdays = setOf(
        DayOfWeek.MONDAY,
        DayOfWeek.TUESDAY,
        DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY,
        DayOfWeek.FRIDAY,
    )

    private fun plan(schedule: NudgeSchedule, part: DayPart) = nudgePlan(schedule, part.start, part.end)

    @Test
    fun evenings_on_the_default_ten_am_nudges_at_five_pm() {
        val plan = plan(NudgeSchedule.DEFAULT, DayPart.Evenings)

        assertEquals(NudgeSchedule(days = everyDay, times = listOf(t(17))), plan.posts)
        assertEquals(listOf(t(10)), plan.outside, "10am is held back, and the note says so")
        assertEquals(t(17), plan.startInstead, "the start stands in for it")
    }

    @Test
    fun afternoons_and_nights_come_at_their_start_too() {
        assertEquals(listOf(t(12)), plan(NudgeSchedule.DEFAULT, DayPart.Afternoons).posts.times)
        // Across midnight: 10am is outside 9pm to 7am, so the nudge comes at 9pm.
        val nights = plan(NudgeSchedule.DEFAULT, DayPart.Nights)
        assertEquals(listOf(t(21)), nights.posts.times)
        assertEquals(t(21), nights.startInstead)
    }

    @Test
    fun mornings_and_any_time_leave_ten_am_as_it_is() {
        listOf(DayPart.Mornings, DayPart.AnyTime).forEach { part ->
            val plan = plan(NudgeSchedule.DEFAULT, part)
            assertEquals(NudgeSchedule.DEFAULT, plan.posts, "$part")
            assertTrue(plan.outside.isEmpty(), "$part")
            assertNull(plan.startInstead, "$part")
        }
    }

    @Test
    fun a_time_outside_is_left_out_while_one_inside_still_comes() {
        val schedule = NudgeSchedule(days = weekdays, times = listOf(t(18), t(10)))

        val plan = plan(schedule, DayPart.Evenings)

        assertEquals(NudgeSchedule(days = weekdays, times = listOf(t(18))), plan.posts)
        assertEquals(listOf(t(10)), plan.outside)
        assertNull(plan.startInstead, "6pm still comes, so nothing is added")
    }

    @Test
    fun a_custom_window_is_read_the_same_way() {
        val plan = nudgePlan(NudgeSchedule(days = weekdays, times = listOf(t(8))), t(9), t(17))

        assertEquals(listOf(t(9)), plan.posts.times)
        assertEquals(weekdays, plan.posts.days, "the start never adds days")
        assertEquals(listOf(t(8)), plan.outside)
    }

    @Test
    fun an_emptied_schedule_stays_off_whatever_the_time_of_day() {
        val noDays = NudgeSchedule(days = emptySet(), times = listOf(t(10)))
        assertTrue(plan(noDays, DayPart.Evenings).posts.days.isEmpty())
        assertNull(plan(noDays, DayPart.Evenings).startInstead)

        val noTimes = NudgeSchedule(days = everyDay, times = emptyList())
        assertTrue(plan(noTimes, DayPart.Evenings).posts.times.isEmpty())
    }

    @Test
    fun the_plan_is_the_schedulers_slots_that_the_gate_lets_through() {
        // The summary cannot drift from the nudge: every time it names is a slot
        // the chain wakes at and the gate lets post, and every such slot is named.
        val schedules = listOf(
            NudgeSchedule.DEFAULT,
            NudgeSchedule(days = weekdays, times = listOf(t(6, 30), t(12), t(19, 45), t(23))),
            NudgeSchedule(days = setOf(DayOfWeek.SUNDAY), times = listOf(t(7))),
        )
        val windows = DayPart.entries.map { it.start to it.end } + listOf(t(9) to t(17), t(22) to t(2))
        schedules.forEach { schedule ->
            windows.forEach { (start, end) ->
                val slots = NudgeScheduler.effectiveSchedule(schedule, start, end)
                val posting = slots.times
                    .filter { start == null || end == null || isInActiveWindow(it, start, end) }
                    .distinct()
                    .sorted()
                val plan = nudgePlan(schedule, start, end)
                assertEquals(posting, plan.posts.times, "$schedule in $start..$end")
                assertEquals(slots.days, plan.posts.days, "$schedule in $start..$end")
            }
        }
    }
}
