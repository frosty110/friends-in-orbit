package app.orbit.notify

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Verifies NOTIF-12: pure-JVM nextSlot day/DST boundary assertions.
 * All assertions use java.time and require no Android context.
 */
class NudgeScheduleNextSlotTest {

    @Test
    fun nextSlot_sameDayWhenCurrentTimeIsBeforeSlot() {
        // All-days 10:00 schedule; query at 09:00 → same-day 10:00
        val zone = ZoneId.of("America/New_York")
        val now = ZonedDateTime.of(2026, 6, 9, 9, 0, 0, 0, zone) // Tuesday 09:00
        val schedule = NudgeSchedule.DEFAULT // all days, 10:00
        val next = schedule.nextSlot(now)
        assertNotNull(next)
        assertEquals(now.toLocalDate(), next!!.toLocalDate(), "same day")
        assertEquals(LocalTime.of(10, 0), next.toLocalTime(), "10:00 slot")
    }

    @Test
    fun nextSlot_nextDayWhenCurrentTimeIsAfterSlot() {
        // All-days 10:00 schedule; query at 11:00 → next-day 10:00
        val zone = ZoneId.of("America/New_York")
        val now = ZonedDateTime.of(2026, 6, 9, 11, 0, 0, 0, zone) // Tuesday 11:00
        val schedule = NudgeSchedule.DEFAULT
        val next = schedule.nextSlot(now)
        assertNotNull(next)
        assertEquals(now.toLocalDate().plusDays(1), next!!.toLocalDate(), "next day")
        assertEquals(LocalTime.of(10, 0), next.toLocalTime(), "10:00 slot")
    }

    @Test
    fun nextSlot_skipsToScheduledDayOfWeek() {
        // Mon/Wed/Fri schedule; query on Tuesday → next slot is Wednesday
        val zone = ZoneId.of("America/New_York")
        val tuesday = ZonedDateTime.of(2026, 6, 9, 11, 0, 0, 0, zone) // Tuesday
        val schedule = NudgeSchedule(
            days = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
            times = listOf(LocalTime.of(10, 0)),
        )
        val next = schedule.nextSlot(tuesday)
        assertNotNull(next)
        assertEquals(DayOfWeek.WEDNESDAY, next!!.dayOfWeek)
    }

    @Test
    fun nextSlot_returnsNullForEmptyDaysSet() {
        val zone = ZoneId.of("America/New_York")
        val now = ZonedDateTime.of(2026, 6, 9, 9, 0, 0, 0, zone)
        val emptySchedule = NudgeSchedule(days = emptySet(), times = listOf(LocalTime.of(10, 0)))
        assertNull(emptySchedule.nextSlot(now))
    }

    @Test
    fun nextSlot_dstSpringForward_advancesCorrectly() {
        // Pitfall-2 DST guard — spring forward 2026-03-08 (US/Eastern).
        // 10:00 never falls in the skipped 02:00–03:00 window, so it must advance
        // by exactly one day across the spring-forward Sunday.
        val zone = ZoneId.of("America/New_York")
        // Saturday 11:00 (2026-03-07 = Saturday before spring-forward)
        val saturdayAfterSlot = ZonedDateTime.of(2026, 3, 7, 11, 0, 0, 0, zone)
        val schedule = NudgeSchedule.DEFAULT // all days, 10:00
        val next = schedule.nextSlot(saturdayAfterSlot)
        assertNotNull(next)
        // Next slot should be Sunday 2026-03-08 at 10:00 (even though DST transitions that day)
        assertEquals(LocalDate.of(2026, 3, 8), next!!.toLocalDate())
        assertEquals(LocalTime.of(10, 0), next.toLocalTime())
    }
}
