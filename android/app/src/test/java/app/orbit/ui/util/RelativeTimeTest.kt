package app.orbit.ui.util

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.test.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * RelativeTime formatter contracts.
 *
 * Calendar-day bug: the old implementation compared 24-hour windows, so an
 * 11pm call read "today" the next morning. Day-relative labels now compare
 * LOCAL calendar days in an explicit zone. Pluralization bug: 30..59 days
 * rendered "1 months ago" — singular forms are asserted here.
 *
 * Locale is pinned to English for the pattern-formatted labels
 * ([formatWallClock], [formatDayHeader]) because both use
 * `Locale.getDefault()` by design (matching [formatAbsolute]).
 */
class RelativeTimeTest {

    private val zone: ZoneId = ZoneId.of("America/Los_Angeles")

    private fun at(date: String, time: String): Instant =
        LocalDateTime.parse("${date}T$time").atZone(zone).toInstant()

    private var savedLocale: Locale = Locale.getDefault()

    @Before
    fun pinLocale() {
        savedLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(savedLocale)
    }

    // ── formatRelative — calendar-day comparison ────────────────────────────

    @Test
    fun `call at 11pm read at 9am next morning is yesterday`() {
        // Only 10 hours elapsed — the old 24h-window logic said "today".
        val call = at("2026-06-08", "23:00")
        val now = at("2026-06-09", "09:00")
        assertEquals("yesterday", formatRelative(call, now, zone))
    }

    @Test
    fun `call earlier the same calendar day is today`() {
        val call = at("2026-06-09", "01:15")
        val now = at("2026-06-09", "23:45")
        assertEquals("today", formatRelative(call, now, zone))
    }

    @Test
    fun `two calendar days back is 2 days ago even when under 48 hours elapsed`() {
        val call = at("2026-06-07", "23:00")
        val now = at("2026-06-09", "09:00")
        assertEquals("2 days ago", formatRelative(call, now, zone))
    }

    @Test
    fun `future occurredAt clamps to today`() {
        val call = at("2026-06-10", "10:00")
        val now = at("2026-06-09", "09:00")
        assertEquals("today", formatRelative(call, now, zone))
    }

    // ── formatRelative — pluralization ──────────────────────────────────────

    @Test
    fun `35 days back is 1 month ago singular`() {
        val now = at("2026-06-09", "12:00")
        val call = at("2026-05-05", "12:00") // 35 calendar days
        assertEquals("1 month ago", formatRelative(call, now, zone))
    }

    @Test
    fun `60 days back is 2 months ago`() {
        val now = at("2026-06-09", "12:00")
        val call = at("2026-04-10", "12:00") // 60 calendar days
        assertEquals("2 months ago", formatRelative(call, now, zone))
    }

    @Test
    fun `29 days back stays in the days band`() {
        val now = at("2026-06-09", "12:00")
        val call = at("2026-05-11", "12:00") // 29 calendar days
        assertEquals("29 days ago", formatRelative(call, now, zone))
    }

    // ── formatWallClock ─────────────────────────────────────────────────────

    @Test
    fun `wall clock label is lowercase with no space`() {
        assertEquals("4:30pm", formatWallClock(at("2026-06-09", "16:30"), zone))
        assertEquals("9:05am", formatWallClock(at("2026-06-09", "09:05"), zone))
    }

    // ── formatDayHeader ─────────────────────────────────────────────────────

    @Test
    fun `day header labels today yesterday and named days`() {
        val today = LocalDate.of(2026, 6, 9)
        assertEquals("Today", formatDayHeader(today, today))
        assertEquals("Yesterday", formatDayHeader(today.minusDays(1), today))
        // 2026-06-03 is a Wednesday; same year → no year suffix.
        assertEquals("Wednesday 3 June", formatDayHeader(LocalDate.of(2026, 6, 3), today))
    }

    @Test
    fun `day header appends the year for other years`() {
        val today = LocalDate.of(2026, 6, 9)
        // 2025-12-29 is a Monday.
        assertEquals(
            "Monday 29 December 2025",
            formatDayHeader(LocalDate.of(2025, 12, 29), today),
        )
    }
}
