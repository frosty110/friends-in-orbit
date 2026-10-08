package app.orbit.ui.util

import org.junit.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals

/**
 * [comesUp], the one definition of "when someone comes up" that Card view's
 * Later, Sooner and Log a connection snackbars, the card's idle hints and
 * Browse's rows all word (CARD-02, CARD-09, CARD-10, BROWSE-07).
 *
 * The buckets are local calendar dates in the zone, the way [formatRelative]
 * words a call's age. Until 2026-10-08 they were whole 24-hour spans from
 * the clock (`Duration.toDays`), which named a day early whenever the time
 * fell at an earlier clock time than the moment of looking: Monday 10:00 to
 * Tuesday 09:00 read "Later today". Every case below that crosses midnight
 * at an off-hour fails against that version.
 *
 * Plain JUnit and a fixed zone: the function is pure, so nothing here
 * depends on the machine's zone or clock.
 */
class ComesUpTest {

    private val london: ZoneId = ZoneId.of("Europe/London")

    /** A local time in [zone]; 2026-10-05 is a Monday. */
    private fun at(dateTime: String, zone: ZoneId = london): Instant =
        LocalDateTime.parse(dateTime).atZone(zone).toInstant()

    @Test
    fun `the same day, later on, is later today`() {
        assertEquals(ComesUp.LaterToday, comesUp(at("2026-10-05T23:59"), at("2026-10-05T10:00"), london))
    }

    @Test
    fun `a time at or before the moment it is measured from is later today`() {
        // Sooner can land on the move's own instant; Browse says "Up now"
        // before asking, the card only ever passes that instant or later.
        val from = at("2026-10-05T10:00")
        assertEquals(ComesUp.LaterToday, comesUp(from, from, london))
        assertEquals(ComesUp.LaterToday, comesUp(at("2026-10-04T09:00"), from, london))
    }

    @Test
    fun `Monday 10am to Tuesday 9am is tomorrow, though under a day away`() {
        // 23 hours: the 24-hour count said "Later today".
        assertEquals(ComesUp.Tomorrow, comesUp(at("2026-10-06T09:00"), at("2026-10-05T10:00"), london))
    }

    @Test
    fun `1am tomorrow seen at 10pm is tomorrow`() {
        // The case the old KDoc kept on purpose as "later today".
        assertEquals(ComesUp.Tomorrow, comesUp(at("2026-10-06T01:00"), at("2026-10-05T22:00"), london))
    }

    @Test
    fun `Monday 10am to Wednesday 9am names Wednesday`() {
        // 47 hours: the 24-hour count said "Tomorrow".
        assertEquals(
            ComesUp.OnDay(DayOfWeek.WEDNESDAY),
            comesUp(at("2026-10-07T09:00"), at("2026-10-05T10:00"), london),
        )
    }

    @Test
    fun `Saturday 8pm to Monday 6pm names Monday`() {
        // 46 hours, two calendar days out: the weekday bucket, never "Tomorrow".
        assertEquals(
            ComesUp.OnDay(DayOfWeek.MONDAY),
            comesUp(at("2026-10-12T18:00"), at("2026-10-10T20:00"), london),
        )
    }

    @Test
    fun `next Monday at an earlier hour is a span, never today's weekday`() {
        // 6 days 23 hours: the 24-hour count said "Monday" on a Monday.
        assertEquals(ComesUp.InDays(7), comesUp(at("2026-10-12T09:00"), at("2026-10-05T10:00"), london))
    }

    @Test
    fun `each boundary falls on a calendar date`() {
        val from = at("2026-10-05T10:00")
        // Six days out is the last weekday; seven is a span, so a weekday
        // never repeats today's.
        assertEquals(ComesUp.OnDay(DayOfWeek.SUNDAY), comesUp(at("2026-10-11T23:30"), from, london))
        assertEquals(ComesUp.InDays(7), comesUp(at("2026-10-12T00:10"), from, london))
        // Spans count calendar days too, so formatSpan's "2 weeks" starts at
        // the fourteenth date, whatever the hour.
        assertEquals(ComesUp.InDays(13), comesUp(at("2026-10-18T23:00"), from, london))
        assertEquals(ComesUp.InDays(14), comesUp(at("2026-10-19T08:00"), from, london))
    }

    @Test
    fun `a whole-day rhythm measured from its own instant names its day`() {
        // A 2-day list logged at 18:00 comes up at 18:00 two days later: the
        // day after tomorrow, measured from the write's own instant.
        val logged = at("2026-10-05T18:00")
        assertEquals(ComesUp.Tomorrow, comesUp(logged.plus(Duration.ofHours(24)), logged, london))
        assertEquals(ComesUp.OnDay(DayOfWeek.WEDNESDAY), comesUp(logged.plus(Duration.ofHours(48)), logged, london))
        assertEquals(ComesUp.InDays(14), comesUp(logged.plus(Duration.ofDays(14)), logged, london))
    }

    @Test
    fun `the zone decides the date`() {
        // The same two instants: Monday 22:30 to Tuesday 06:00 in London,
        // Monday 14:30 to Monday 22:00 in Los Angeles.
        val from = Instant.parse("2026-10-05T21:30:00Z")
        val due = Instant.parse("2026-10-06T05:00:00Z")
        assertEquals(ComesUp.Tomorrow, comesUp(due, from, london))
        assertEquals(ComesUp.LaterToday, comesUp(due, from, ZoneId.of("America/Los_Angeles")))
    }

    @Test
    fun `a 23-hour day does not shift the bucket`() {
        // Clocks go forward in London at 01:00 on Sunday 29 March 2026.
        // 00:30 Sunday to 00:45 Monday is 23h15m on the clock, and tomorrow.
        assertEquals(ComesUp.Tomorrow, comesUp(at("2026-03-30T00:45"), at("2026-03-29T00:30"), london))
        // Saturday noon to Monday 9am is 44 hours, and Monday. Counting from
        // midnight to midnight would be 47 hours here, and say tomorrow.
        assertEquals(
            ComesUp.OnDay(DayOfWeek.MONDAY),
            comesUp(at("2026-03-30T09:00"), at("2026-03-28T12:00"), london),
        )
        // A daily list logged on the Saturday comes up on the Sunday.
        val logged = at("2026-03-28T10:00")
        assertEquals(ComesUp.Tomorrow, comesUp(logged.plus(Duration.ofHours(24)), logged, london))
    }

    @Test
    fun `a 25-hour day does not shift the bucket`() {
        // Clocks go back in London at 02:00 on Sunday 25 October 2026.
        // 00:30 to 23:45 that Sunday is 24h15m on the clock, and still today.
        assertEquals(ComesUp.LaterToday, comesUp(at("2026-10-25T23:45"), at("2026-10-25T00:30"), london))
        // Saturday 23:30 to Monday 00:15 is 25h45m, and Monday.
        assertEquals(
            ComesUp.OnDay(DayOfWeek.MONDAY),
            comesUp(at("2026-10-26T00:15"), at("2026-10-24T23:30"), london),
        )
    }
}
