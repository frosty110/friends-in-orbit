package app.orbit.domain.usecase

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * ADR 0008 — soft time-of-day weighting ([timeOfDayPenalty]).
 *
 * Pure function over (now, zone, window). All fixtures use UTC so the local
 * clock equals the `Z` time in the [Instant], keeping the arithmetic legible.
 */
class TimeOfDayWeightTest {

    private val utc: ZoneId = ZoneId.of("UTC")
    private fun at(hhmm: String): Instant = Instant.parse("2026-01-01T${hhmm}:00Z")
    private val nineToFive = LocalTime.of(9, 0) to LocalTime.of(17, 0)

    @Test
    fun `no penalty when the list has no window`() {
        assertEquals(Duration.ZERO, timeOfDayPenalty(at("08:00"), utc, null, LocalTime.of(17, 0)))
        assertEquals(Duration.ZERO, timeOfDayPenalty(at("08:00"), utc, LocalTime.of(9, 0), null))
    }

    @Test
    fun `no penalty inside a same-day window, endpoints inclusive`() {
        val (start, end) = nineToFive
        assertEquals(Duration.ZERO, timeOfDayPenalty(at("12:00"), utc, start, end))
        assertEquals(Duration.ZERO, timeOfDayPenalty(at("09:00"), utc, start, end), "start is inclusive")
        assertEquals(Duration.ZERO, timeOfDayPenalty(at("17:00"), utc, start, end), "end is inclusive")
    }

    @Test
    fun `no penalty inside a window that wraps midnight`() {
        val start = LocalTime.of(21, 0)
        val end = LocalTime.of(2, 0)
        assertEquals(Duration.ZERO, timeOfDayPenalty(at("23:00"), utc, start, end), "after start")
        assertEquals(Duration.ZERO, timeOfDayPenalty(at("01:00"), utc, start, end), "before end (next day)")
    }

    @Test
    fun `penalty equals minutes until the window opens when within the cap`() {
        val (start, end) = nineToFive
        // 08:00 → 60 minutes until 09:00, under the 6h cap.
        assertEquals(Duration.ofMinutes(60), timeOfDayPenalty(at("08:00"), utc, start, end))
    }

    @Test
    fun `penalty is capped at the max when the window is far off`() {
        val (start, end) = nineToFive
        // 00:00 → 540 minutes until 09:00, clamped to the 6h default cap.
        assertEquals(DEFAULT_TIME_OF_DAY_PENALTY_CAP, timeOfDayPenalty(at("00:00"), utc, start, end))
        assertEquals(Duration.ofHours(6), timeOfDayPenalty(at("00:00"), utc, start, end))
    }

    @Test
    fun `after the window closes the penalty targets the next day's opening`() {
        val (start, end) = nineToFive
        // 18:00 → floorMod(540 - 1080, 1440) = 900 min until next 09:00 → capped to 6h.
        assertEquals(Duration.ofHours(6), timeOfDayPenalty(at("18:00"), utc, start, end))
    }

    @Test
    fun `penalty decays as the opening approaches`() {
        val (start, end) = nineToFive
        val early = timeOfDayPenalty(at("06:00"), utc, start, end) // 180 min
        val late = timeOfDayPenalty(at("08:00"), utc, start, end) //  60 min
        assertEquals(Duration.ofMinutes(180), early)
        assertEquals(Duration.ofMinutes(60), late)
        assertTrue(late < early, "closer to opening → smaller penalty")
    }

    @Test
    fun `a custom max penalty caps the result`() {
        val (start, end) = nineToFive
        val cap = Duration.ofMinutes(30)
        // 00:00 would be 540 min, but the custom cap clamps it to 30.
        assertEquals(cap, timeOfDayPenalty(at("00:00"), utc, start, end, maxPenalty = cap))
    }
}
