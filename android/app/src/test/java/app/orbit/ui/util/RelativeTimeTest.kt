package app.orbit.ui.util

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.test.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RelativeTime formatter contracts.
 *
 * Calendar-day bug: the old implementation compared 24-hour windows, so an
 * 11pm call read "today" the next morning. Day-relative labels now compare
 * LOCAL calendar days in an explicit zone. Pluralization bug: 30..59 days
 * rendered "1 months ago": singular forms are asserted here.
 *
 * Locale is pinned to English for the pattern-formatted labels
 * ([formatWallClock], [formatDayHeader]) because both use
 * `Locale.getDefault()` by design (matching [formatAbsolute]).
 *
 * The words live in strings_time.xml (UX rubric 3.4) and the formatters
 * return [UiText], so this runs under Robolectric and resolves them against
 * the real English resources: the assertions are still the exact text a
 * person reads.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class RelativeTimeTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun UiText.text(): String = asString(context)

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

    // ── formatRelative: calendar-day comparison ────────────────────────────

    @Test
    fun `call at 11pm read at 9am next morning is yesterday`() {
        // Only 10 hours elapsed: the old 24h-window logic said "today".
        val call = at("2026-06-08", "23:00")
        val now = at("2026-06-09", "09:00")
        assertEquals("yesterday", formatRelative(call, now, zone).text())
    }

    @Test
    fun `call earlier the same calendar day is today`() {
        val call = at("2026-06-09", "01:15")
        val now = at("2026-06-09", "23:45")
        assertEquals("today", formatRelative(call, now, zone).text())
    }

    @Test
    fun `two calendar days back is 2 days ago even when under 48 hours elapsed`() {
        val call = at("2026-06-07", "23:00")
        val now = at("2026-06-09", "09:00")
        assertEquals("2 days ago", formatRelative(call, now, zone).text())
    }

    @Test
    fun `future occurredAt clamps to today`() {
        val call = at("2026-06-10", "10:00")
        val now = at("2026-06-09", "09:00")
        assertEquals("today", formatRelative(call, now, zone).text())
    }

    // ── formatRelative: pluralization ──────────────────────────────────────

    // Buckets come from formatSpan (voice.md glossary): days below 14, weeks
    // below 60, months below a year. "27 days ago" beside "3 weeks" on
    // another screen was the inconsistency this replaced.
    @Test
    fun `35 days back is 5 weeks ago`() {
        val now = at("2026-06-09", "12:00")
        val call = at("2026-05-05", "12:00") // 35 calendar days
        assertEquals("5 weeks ago", formatRelative(call, now, zone).text())
    }

    @Test
    fun `60 days back is 2 months ago`() {
        val now = at("2026-06-09", "12:00")
        val call = at("2026-04-10", "12:00") // 60 calendar days
        assertEquals("2 months ago", formatRelative(call, now, zone).text())
    }

    @Test
    fun `29 days back is 4 weeks ago`() {
        val now = at("2026-06-09", "12:00")
        val call = at("2026-05-11", "12:00") // 29 calendar days
        assertEquals("4 weeks ago", formatRelative(call, now, zone).text())
    }

    @Test
    fun `formatSpan buckets and singular forms`() {
        assertEquals("1 day", formatSpan(1).text())
        assertEquals("13 days", formatSpan(13).text())
        assertEquals("2 weeks", formatSpan(14).text())
        assertEquals("8 weeks", formatSpan(59).text())
        assertEquals("2 months", formatSpan(60).text())
        assertEquals("12 months", formatSpan(364).text())
        assertEquals("1 year", formatSpan(365).text())
        assertEquals("2 years", formatSpan(800).text())
        assertEquals("0 days", formatSpan(-3).text())
    }

    // ── formatAgo: the same words for a day count the caller already took ──

    // Card view and Home count whole days between instants and then need the
    // one "ago" wording as the argument of "Spoke {ago}". One gap per
    // bucket, plus the two day words and the negative clamp.
    @Test
    fun `formatAgo says today, yesterday, then the ago plural of each bucket`() {
        assertEquals("today", formatAgo(0).text())
        assertEquals("yesterday", formatAgo(1).text())
        assertEquals("3 days ago", formatAgo(3).text())
        assertEquals("3 weeks ago", formatAgo(21).text())
        assertEquals("3 months ago", formatAgo(90).text())
        assertEquals("1 year ago", formatAgo(400).text())
        assertEquals("today", formatAgo(-2).text())
    }

    // ── formatRelativeFine: the first day at a finer grain ─────────────────

    // Settings' sync rows read moments after a sync, where "today" says too
    // little; from a calendar day on the fine formatter hands over to
    // formatRelative so the two can never disagree.
    @Test
    fun `fine relative says just now, then minutes, then hours`() {
        val now = at("2026-06-09", "12:00")
        assertEquals("just now", formatRelativeFine(now.minusSeconds(0), now, zone).text())
        assertEquals("just now", formatRelativeFine(now.minusSeconds(59), now, zone).text())
        assertEquals("1 minute ago", formatRelativeFine(now.minusSeconds(60), now, zone).text())
        assertEquals("5 minutes ago", formatRelativeFine(now.minusSeconds(5 * 60 + 30), now, zone).text())
        assertEquals("59 minutes ago", formatRelativeFine(now.minusSeconds(59 * 60 + 59), now, zone).text())
        assertEquals("1 hour ago", formatRelativeFine(now.minusSeconds(3600), now, zone).text())
        assertEquals("3 hours ago", formatRelativeFine(now.minusSeconds(3 * 3600 + 1200), now, zone).text())
        assertEquals("23 hours ago", formatRelativeFine(now.minusSeconds(23 * 3600 + 59 * 60), now, zone).text())
    }

    @Test
    fun `fine relative hands over to the day words after a full day`() {
        val now = at("2026-06-09", "09:00")
        // 25 hours: a full day has passed, and the calendar day before is "yesterday".
        assertEquals("yesterday", formatRelativeFine(at("2026-06-08", "08:00"), now, zone).text())
        assertEquals("3 days ago", formatRelativeFine(at("2026-06-06", "09:00"), now, zone).text())
        assertEquals("2 weeks ago", formatRelativeFine(at("2026-05-26", "09:00"), now, zone).text())
    }

    @Test
    fun `fine relative clamps a future time to just now`() {
        val now = at("2026-06-09", "09:00")
        assertEquals("just now", formatRelativeFine(at("2026-06-09", "10:00"), now, zone).text())
    }

    // ── formatWallClock ─────────────────────────────────────────────────────

    @Test
    fun `wall clock label is lowercase with no space`() {
        assertEquals("4:30pm", formatWallClock(at("2026-06-09", "16:30"), zone, use24Hour = false))
        assertEquals("9:05am", formatWallClock(at("2026-06-09", "09:05"), zone, use24Hour = false))
        assertEquals("4pm", formatWallClock(at("2026-06-09", "16:00"), zone, use24Hour = false))
    }

    @Test
    fun `clock times follow the phone's 24-hour setting`() {
        assertEquals("16:30", formatWallClock(at("2026-06-09", "16:30"), zone, use24Hour = true))
        assertEquals("09:05", formatWallClock(at("2026-06-09", "09:05"), zone, use24Hour = true))
        assertEquals("00:00", formatClockTime(java.time.LocalTime.MIDNIGHT, use24Hour = true))
        assertEquals("12am", formatClockTime(java.time.LocalTime.MIDNIGHT, use24Hour = false))
    }

    // The marker comes from the locale (java.time), not a hardcoded English
    // "pm": Korean's is the word for afternoon. The pinned locale is restored
    // by restoreLocale().
    @Test
    fun `the am pm marker is the locale's own`() {
        Locale.setDefault(Locale.KOREAN)
        assertEquals("4:30오후", formatClockTime(java.time.LocalTime.of(16, 30), use24Hour = false))
    }

    // ── formatDayHeader ─────────────────────────────────────────────────────

    @Test
    fun `day header labels today yesterday and named days`() {
        val today = LocalDate.of(2026, 6, 9)
        assertEquals("Today", formatDayHeader(today, today).text())
        assertEquals("Yesterday", formatDayHeader(today.minusDays(1), today).text())
        // 2026-06-03 is a Wednesday; same year → no year suffix.
        assertEquals("Wednesday 3 June", formatDayHeader(LocalDate.of(2026, 6, 3), today).text())
    }

    @Test
    fun `day header appends the year for other years`() {
        val today = LocalDate.of(2026, 6, 9)
        // 2025-12-29 is a Monday.
        assertEquals(
            "Monday 29 December 2025",
            formatDayHeader(LocalDate.of(2025, 12, 29), today).text(),
        )
    }

    // ── formatDuration ──────────────────────────────────────────────────────

    @Test
    fun `durations read in seconds, minutes, then hours and minutes`() {
        assertEquals("45s", formatDuration(45).text())
        assertEquals("1 min", formatDuration(60).text())
        assertEquals("14 min", formatDuration(14 * 60 + 20).text())
        assertEquals("1h 5m", formatDuration(3600 + 5 * 60).text())
    }

    // The zero case used to be an em dash, which leaked onto Card view's stats.
    @Test
    fun `a zero duration is a number, not a dash`() {
        assertEquals("0s", formatDuration(0).text())
        assertEquals("0s", formatDuration(-4).text())
    }

    // ── axisTickLabels ──────────────────────────────────────────────────────

    @Test
    fun `strip ticks follow the 12 or 24 hour setting`() {
        assertEquals(
            listOf("12a", "6a", "12p", "6p", "12a"),
            axisTickLabels(use24Hour = false).map { context.getString(it) },
        )
        assertEquals(
            listOf("00", "06", "12", "18", "00"),
            axisTickLabels(use24Hour = true).map { context.getString(it) },
        )
    }
}
