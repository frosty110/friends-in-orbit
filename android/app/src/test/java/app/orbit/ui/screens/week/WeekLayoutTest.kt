package app.orbit.ui.screens.week

import app.orbit.data.entity.CallDirection
import app.orbit.data.entity.CallEventEntity
import app.orbit.data.entity.ContactEntity
import app.orbit.data.feed.bucketRhythm
import app.orbit.domain.callEventFixture
import app.orbit.domain.contactFixture
import app.orbit.ui.screens.home.RhythmCall
import app.orbit.ui.util.formatDuration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * HOME-13: where the Week screen puts a call, on the JVM.
 *
 * The bucketing is the strip's own (`bucketRhythm` in HomeFeed.kt), so these
 * tests are also the strip's: which day a call falls on and where in the day
 * it starts, in a named zone so a daylight-saving day is the same day
 * wherever the suite runs. America/New_York changes its clocks on
 * 8 March 2026 (2am becomes 3am, a 23 hour day) and 1 November 2026
 * (2am becomes 1am, a 25 hour day).
 */
class WeekLayoutTest {

    private val zone: ZoneId = ZoneId.of("America/New_York")
    private val kai = contactFixture(id = 1L, displayName = "Kai Mensah")
    private val contacts: Map<Long, ContactEntity> = mapOf(kai.id to kai)

    private fun at(local: String): Instant = LocalDateTime.parse(local).atZone(zone).toInstant()

    private fun call(
        id: Long,
        local: String,
        seconds: Int = 14 * 60,
        direction: CallDirection = CallDirection.OUTGOING,
        contactId: Long = 1L,
    ): CallEventEntity = callEventFixture(id, contactId, at(local), direction, seconds)

    private fun bucket(calls: List<CallEventEntity>, firstDay: LocalDate, days: Int = 7) =
        bucketRhythm(calls, contacts, firstDay, days, zone)

    // ── bucketing: the day and the start ─────────────────────────────────

    @Test
    fun `a call at 6-40pm lands in its day's column at 6-40pm`() {
        // Wednesday 30 September is the third day of 28 Sep to 4 Oct.
        val days = bucket(listOf(call(1L, "2026-09-30T18:40")), LocalDate.of(2026, 9, 28))

        assertEquals(listOf(0, 0, 1, 0, 0, 0, 0), days.map { it.calls.size })
        val placed = days[2].calls.single()
        assertEquals(18 * 60 + 40, placed.minuteOfDay)
        assertEquals("Kai Mensah", placed.contactName)
    }

    @Test
    fun `a call that runs past midnight stays on the day it started`() {
        // 11:40pm for 45 minutes ends at 12:25am the next day.
        val days = bucket(listOf(call(1L, "2026-09-30T23:40", seconds = 45 * 60)), LocalDate.of(2026, 9, 28))

        assertEquals(1, days[2].calls.size, "the start day, as the strip shows it")
        assertTrue(days[3].calls.isEmpty())
        assertEquals(23 * 60 + 40, days[2].calls.single().minuteOfDay)
    }

    @Test
    fun `on the day the clocks go forward a call sits at its wall-clock time and in its own day`() {
        val days = bucket(
            listOf(
                // 6:40pm EDT. Counted as time since midnight (EST) it is
                // 17h40m, which would draw it an hour above its label.
                call(1L, "2026-03-08T18:40"),
                // 12:30am the next day. A 24 hour window from 8 March's
                // midnight runs to 1am on the 9th and would claim it.
                call(2L, "2026-03-09T00:30"),
            ),
            firstDay = LocalDate.of(2026, 3, 8),
            days = 2,
        )

        assertEquals(listOf(1L), days[0].calls.map { it.callEventId })
        assertEquals(18 * 60 + 40, days[0].calls.single().minuteOfDay)
        assertEquals(listOf(2L), days[1].calls.map { it.callEventId })
        assertEquals(30, days[1].calls.single().minuteOfDay)
    }

    @Test
    fun `on the day the clocks go back both 1-30am calls and a late call stay on that day`() {
        val firstOneThirty = LocalDateTime.parse("2026-11-01T01:30").atZone(zone)
        val secondOneThirty = firstOneThirty.withLaterOffsetAtOverlap()
        val days = bucketRhythm(
            listOf(
                callEventFixture(1L, 1L, firstOneThirty.toInstant(), CallDirection.OUTGOING, 10 * 60),
                callEventFixture(2L, 1L, secondOneThirty.toInstant(), CallDirection.INCOMING, 10 * 60),
                // 11:30pm EST. A 24 hour window from 1 November's midnight
                // (EDT) ends at 11pm EST and would push this into the 2nd.
                call(3L, "2026-11-01T23:30"),
            ),
            contacts,
            LocalDate.of(2026, 11, 1),
            2,
            zone,
        )

        assertEquals(listOf(1L, 2L, 3L), days[0].calls.map { it.callEventId }, "in the order they happened")
        assertEquals(listOf(90, 90, 23 * 60 + 30), days[0].calls.map { it.minuteOfDay })
        assertTrue(days[1].calls.isEmpty())
    }

    @Test
    fun `the three-minute floor is the strip's`() {
        val days = bucket(
            listOf(
                call(1L, "2026-09-30T10:00", seconds = 179),
                call(2L, "2026-09-30T11:00", seconds = 180),
                // A connection logged by hand is written with 0 seconds.
                call(3L, "2026-09-30T12:00", seconds = 0),
            ),
            LocalDate.of(2026, 9, 28),
        )

        assertEquals(listOf(2L), days[2].calls.map { it.callEventId })
    }

    // ── weeks: this week is the strip's, and how far back they go ────────

    private val today: LocalDate = LocalDate.of(2026, 10, 7)

    @Test
    fun `this week is the seven days ending today, the strip's own days`() {
        val calls = listOf(call(1L, "2026-10-01T09:00"), call(2L, "2026-10-07T20:15"))

        val pages = weekPages(calls, contacts, today, zone)

        assertEquals(1, pages.size, "nothing older, so no earlier week to step back to")
        assertEquals(LocalDate.of(2026, 10, 1), pages[0].firstDay)
        assertEquals(today, pages[0].lastDay)
        assertEquals(bucket(calls, today.minusDays(6)), pages[0].days)
    }

    @Test
    fun `the weeks go back to the one holding the earliest call, and no further`() {
        val calls = listOf(
            call(1L, "2026-10-06T09:00"),
            // 16 days before today: two weeks back.
            call(2L, "2026-09-21T09:00"),
        )

        val pages = weekPages(calls, contacts, today, zone)

        assertEquals(3, pages.size)
        assertEquals(LocalDate.of(2026, 9, 24), pages[1].firstDay)
        assertEquals(LocalDate.of(2026, 9, 30), pages[1].lastDay)
        assertEquals(LocalDate.of(2026, 9, 17), pages[2].firstDay)
        assertTrue(pages[1].isEmpty, "a quiet week in between is still a week")
        assertEquals(listOf(2L), pages[2].days.flatMap { day -> day.calls.map { it.callEventId } })
        assertEquals(9 * 60, pages[2].earliestMinute)
    }

    @Test
    fun `a call too short for the strip does not stretch the history`() {
        val calls = listOf(call(1L, "2026-10-06T09:00"), call(2L, "2026-08-01T09:00", seconds = 120))

        assertEquals(1, weekPages(calls, contacts, today, zone).size)
    }

    @Test
    fun `a list with no calls has this week alone, and it is empty`() {
        val pages = weekPages(emptyList(), contacts, today, zone)

        assertEquals(1, pages.size)
        assertTrue(pages[0].isEmpty)
        assertEquals(null, pages[0].earliestMinute)
    }

    // ── placement: height, the floor, touch targets, lanes ───────────────

    private fun rc(id: Long, minuteOfDay: Int, minutes: Int) = RhythmCall(
        callEventId = id,
        contactId = id,
        contactName = "Kai",
        photoUri = null,
        durationSeconds = minutes * 60,
        direction = CallDirection.OUTGOING,
        durationLabel = formatDuration(minutes * 60),
        timeLabel = "",
        minuteOfDay = minuteOfDay,
    )

    // The screen's numbers: 14dp and 48dp at 40dp an hour.
    private fun place(vararg calls: RhythmCall) = placeDay(calls.toList(), minBlockMinutes = 21, minTouchMinutes = 72)

    @Test
    fun `a call is drawn from its start for as long as it lasted, with a full touch target`() {
        val p = place(rc(1L, 18 * 60 + 40, 52)).single()

        assertEquals(1120, p.top)
        assertEquals(1120 + 52, p.bottom)
        assertEquals(72, p.touchBottom - p.touchTop)
        assertTrue(p.touchTop <= p.top && p.bottom <= p.touchBottom, "the block sits inside its target")
        assertEquals(0, p.lane)
        assertEquals(1, p.lanes)
    }

    @Test
    fun `a three-minute call is drawn at the floor and keeps a 48dp target`() {
        val p = place(rc(1L, 600, 3)).single()

        assertEquals(600, p.top)
        assertEquals(621, p.bottom)
        assertEquals(72, p.touchBottom - p.touchTop)
    }

    @Test
    fun `a call past midnight stops at the bottom edge`() {
        val p = place(rc(1L, 23 * 60 + 30, 45)).single()

        assertEquals(23 * 60 + 30, p.top)
        assertEquals(DAY_MINUTES, p.bottom)
        assertEquals(DAY_MINUTES, p.touchBottom)
        assertEquals(72, p.touchBottom - p.touchTop)
    }

    @Test
    fun `overlapping calls sit side by side and a later one has the column again`() {
        val placed = place(
            rc(1L, 9 * 60 + 5, 26),
            rc(2L, 9 * 60 + 20, 64),
            rc(3L, 15 * 60, 30),
        ).associateBy { it.call.callEventId }

        assertEquals(0, placed.getValue(1L).lane)
        assertEquals(1, placed.getValue(2L).lane)
        assertEquals(2, placed.getValue(1L).lanes)
        assertEquals(2, placed.getValue(2L).lanes)
        assertEquals(0, placed.getValue(3L).lane)
        assertEquals(1, placed.getValue(3L).lanes, "a quiet afternoon is not narrowed by a busy morning")
    }

    @Test
    fun `short calls whose targets would overlap share the column, so neither target is covered`() {
        // Twenty minutes apart: the blocks do not touch, the 48dp targets would.
        val placed = place(rc(1L, 12 * 60, 3), rc(2L, 12 * 60 + 20, 3))

        assertEquals(setOf(0, 1), placed.map { it.lane }.toSet())
        assertTrue(placed.all { it.lanes == 2 })
    }

    @Test
    fun `calls a few hours apart each have the whole column`() {
        val placed = place(rc(1L, 8 * 60, 10), rc(2L, 12 * 60, 10))

        assertTrue(placed.all { it.lane == 0 && it.lanes == 1 })
    }
}
