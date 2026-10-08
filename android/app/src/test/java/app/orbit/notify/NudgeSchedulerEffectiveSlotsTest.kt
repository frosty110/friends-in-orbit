package app.orbit.notify

import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Pure assertions for [NudgeScheduler.effectiveSchedule]: the D-09 slot at the
 * window start is added only when every chosen time falls outside the active-hours
 * window, and never adds days or revives an emptied schedule.
 *
 * Plain JVM: the function is on the scheduler's companion since 2026-10-08
 * (List settings' summary reads it too, LIST-25), so no scheduler, Context or
 * WorkManager is built. Until then it was an instance member and this test
 * ran under Robolectric to satisfy the constructor.
 *
 * Test analogues: mirrors [NudgeScheduleNextSlotTest]'s fixed-clock style,
 * but adds the scheduling-layer concern: the effective schedule merges the implicit
 * `activeHoursStart` slot before [NudgeSchedule.nextSlot] is computed.
 */
class NudgeSchedulerEffectiveSlotsTest {

    private val weekdays = setOf(
        DayOfWeek.MONDAY,
        DayOfWeek.TUESDAY,
        DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY,
        DayOfWeek.FRIDAY
    )
    private val nineToFive = LocalTime.of(9, 0) to LocalTime.of(17, 0)

    private fun effective(explicit: NudgeSchedule, window: Pair<LocalTime, LocalTime>?) =
        NudgeScheduler.effectiveSchedule(explicit, window?.first, window?.second)

    // ─── No window: the fire-time gate never applies ─────────────────────────

    @Test
    fun effectiveSchedule_withNoWindow_returnsExplicitUnchanged() {
        val explicit = NudgeSchedule.DEFAULT // all 7 days, 10:00
        assertEquals(explicit, effective(explicit, window = null))
    }

    @Test
    fun effectiveSchedule_withOnlyOneWindowEnd_returnsExplicitUnchanged() {
        // The worker gates only when both ends are set, so half a window gates nothing.
        val explicit = NudgeSchedule.DEFAULT
        assertEquals(explicit, NudgeScheduler.effectiveSchedule(explicit, LocalTime.of(21, 0), null))
    }

    // ─── The user's choices survive a window ─────────────────────────────────

    @Test
    fun effectiveSchedule_keepsTheUsersDays() {
        // Regression: the window used to merge in all seven days, so "Weekdays at
        // 10am" nudged on weekends too.
        val explicit = NudgeSchedule(days = weekdays, times = listOf(LocalTime.of(10, 0)))
        assertEquals(weekdays, effective(explicit, nineToFive).days)
    }

    @Test
    fun effectiveSchedule_doesNotAddASlotWhenAChosenTimeIsInsideTheWindow() {
        // Regression: 10:00 inside 9-5 already posts; the old injection added 9:00
        // as a second daily nudge.
        val explicit = NudgeSchedule(days = weekdays, times = listOf(LocalTime.of(10, 0)))
        assertEquals(explicit, effective(explicit, nineToFive))
    }

    @Test
    fun effectiveSchedule_withNoDays_staysOff() {
        // Regression: "No days selected - nudges off" kept nudging once a window was set.
        val explicit = NudgeSchedule(days = emptySet(), times = listOf(LocalTime.of(10, 0)))
        val eff = effective(explicit, LocalTime.of(21, 0) to LocalTime.of(23, 0))
        assertEquals(explicit, eff)
        assertNull(eff.nextSlot(ZonedDateTime.of(2026, 10, 5, 8, 0, 0, 0, ZoneId.of("UTC"))))
    }

    @Test
    fun effectiveSchedule_withNoTimes_staysOff() {
        val explicit = NudgeSchedule(days = weekdays, times = emptyList())
        assertEquals(explicit, effective(explicit, nineToFive))
    }

    // ─── D-09: every chosen time is outside the window ───────────────────────

    @Test
    fun effectiveSchedule_addsTheWindowStartWhenEveryTimeIsOutside() {
        // 10:00 can never post inside 21:00-23:00; without the added slot the
        // gate would suppress this list forever.
        val explicit = NudgeSchedule.DEFAULT
        val eff = effective(explicit, LocalTime.of(21, 0) to LocalTime.of(23, 0))
        assertEquals(listOf(LocalTime.of(10, 0), LocalTime.of(21, 0)), eff.times)
        assertEquals(explicit.days, eff.days)
    }

    @Test
    fun effectiveSchedule_addsTheWindowStartOnTheUsersDaysOnly() {
        val explicit = NudgeSchedule(days = weekdays, times = listOf(LocalTime.of(9, 0)))
        val eff = effective(explicit, LocalTime.of(18, 0) to LocalTime.of(22, 0))
        assertTrue(LocalTime.of(18, 0) in eff.times)
        assertEquals(weekdays, eff.days, "the added slot must not add weekend days")
    }

    @Test
    fun effectiveSchedule_respectsAWindowThatSpansMidnight() {
        // 23:30 is inside 22:00-02:00, so nothing is added.
        val inside = NudgeSchedule(days = weekdays, times = listOf(LocalTime.of(23, 30)))
        assertEquals(inside, effective(inside, LocalTime.of(22, 0) to LocalTime.of(2, 0)))
        // 12:00 is outside it, so the window start is added.
        val outside = NudgeSchedule(days = weekdays, times = listOf(LocalTime.of(12, 0)))
        val lateWindow = LocalTime.of(22, 0) to LocalTime.of(2, 0)
        assertTrue(LocalTime.of(22, 0) in effective(outside, lateWindow).times)
    }

    @Test
    fun effectiveSchedule_doesNotDuplicateTheWindowStart() {
        // A window whose start is also a chosen time is covered by the inside-window
        // rule; the added slot can never duplicate it.
        val explicit = NudgeSchedule(days = weekdays, times = listOf(LocalTime.of(21, 0)))
        val eff = effective(explicit, LocalTime.of(21, 0) to LocalTime.of(23, 0))
        assertEquals(eff.times.distinct(), eff.times)
    }

    // ─── One definition of "inside the window" ───────────────────────────────

    @Test
    fun isInActiveWindow_takesTheStartNotTheEndAndWrapsMidnight() {
        assertTrue(isInActiveWindow(LocalTime.of(9, 0), LocalTime.of(9, 0), LocalTime.of(17, 0)))
        assertTrue(isInActiveWindow(LocalTime.of(16, 59), LocalTime.of(9, 0), LocalTime.of(17, 0)))
        assertFalse(isInActiveWindow(LocalTime.of(17, 0), LocalTime.of(9, 0), LocalTime.of(17, 0)))
        assertFalse(isInActiveWindow(LocalTime.of(8, 59), LocalTime.of(9, 0), LocalTime.of(17, 0)))
        assertTrue(isInActiveWindow(LocalTime.of(1, 0), LocalTime.of(22, 0), LocalTime.of(2, 0)))
        assertFalse(isInActiveWindow(LocalTime.of(2, 0), LocalTime.of(22, 0), LocalTime.of(2, 0)))
        assertFalse(isInActiveWindow(LocalTime.of(12, 0), LocalTime.of(22, 0), LocalTime.of(2, 0)))
    }

    @Test
    fun aTimeOnTheWindowsEndGetsTheStartAdded() {
        // The worker asks the gate a moment after its slot, so a 9pm slot under
        // a 5pm to 9pm window would be held every day. With the end out, the
        // scheduler treats 9pm as outside and adds the start (2026-10-08).
        val explicit = NudgeSchedule(days = DayOfWeek.entries.toSet(), times = listOf(LocalTime.of(21, 0)))
        val eff = effective(explicit, LocalTime.of(17, 0) to LocalTime.of(21, 0))
        assertEquals(listOf(LocalTime.of(21, 0), LocalTime.of(17, 0)), eff.times)
        // And the gate, asked a little after the 9pm slot, holds it, while the
        // 5pm slot asked a little after 5pm posts.
        assertFalse(isInActiveWindow(LocalTime.of(21, 0, 0, 200_000_000), LocalTime.of(17, 0), LocalTime.of(21, 0)))
        assertTrue(isInActiveWindow(LocalTime.of(17, 0, 0, 200_000_000), LocalTime.of(17, 0), LocalTime.of(21, 0)))
    }
}
