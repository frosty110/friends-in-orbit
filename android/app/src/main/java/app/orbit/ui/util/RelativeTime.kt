package app.orbit.ui.util

import android.content.Context
import android.text.format.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Single-source-of-truth relative-time formatters. Used by:
 *   - `ContactDetailViewModel.toUiCallEntry` — populates
 *     `CallEntry.relativeWhen` + `lengthLabel` from `CallEventEntity`.
 *   - `ContactDetailScreen.CallHistoryRow` — renders the same labels at the
 *     row level.
 *
 * Keeping these in one file prevents drift (e.g., the VM saying "today" while
 * the UI says "0 days ago"). Voice rule: lowercase verb fragments, no
 * exclamation, no "yesterday!" or "ages ago" — quiet factual labels only.
 */

/**
 * The one way Orbit says how long something has been (voice.md glossary):
 * "1 day", "9 days", "2 weeks", "5 weeks", "3 months", "1 year". Every
 * "time since a call" in the app is built from this, so one screen can't say
 * "27 days ago" while the next says "3 weeks" (UX rubric D7).
 *
 * Buckets: days below 14, weeks below 60 days, months below a year, then
 * years. Singular forms are honest ("1 week", never "1 weeks").
 */
fun formatSpan(days: Long): String {
    val d = days.coerceAtLeast(0L)
    fun unit(n: Long, word: String) = if (n == 1L) "1 $word" else "$n ${word}s"
    return when {
        d < 14L -> unit(d, "day")
        d < 60L -> unit(d / 7L, "week")
        d < 365L -> unit(d / 30L, "month")
        else -> unit(d / 365L, "year")
    }
}

/**
 * "today" / "yesterday" / "{span} ago", where span is [formatSpan]:
 * "9 days ago", "3 weeks ago", "2 months ago".
 *
 * Day-relative labels compare LOCAL calendar days, not 24-hour windows: an
 * 11pm call read the next morning is "yesterday", never "today".
 * [zone] defaults to [ZoneId.systemDefault] (same convention as
 * [formatAbsolute]); existing call sites pass only `(occurredAt, now)` and
 * keep their contracts.
 */
fun formatRelative(
    occurredAt: Instant,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val days = ChronoUnit.DAYS.between(
        occurredAt.atZone(zone).toLocalDate(),
        now.atZone(zone).toLocalDate(),
    ).coerceAtLeast(0L)
    return when (days) {
        0L -> "today"
        1L -> "yesterday"
        else -> "${formatSpan(days)} ago"
    }
}

/**
 * Whether clock times show as "16:30" or "4:30pm". Follows the phone's
 * 12/24-hour setting (it used to be hardcoded: 12-hour on call rows and
 * active hours, 24-hour in nudge summaries). Refreshed by the app on start
 * and every time it returns to the foreground, so a change in Android
 * settings shows up without a restart. Plain field, not state: screens
 * recompose on resume anyway.
 */
object TimeStyle {
    @Volatile
    var is24Hour: Boolean = false

    fun refresh(context: Context) {
        is24Hour = DateFormat.is24HourFormat(context)
    }
}

/**
 * A clock time in the phone's style: "16:30" / "16:00", or "4:30pm" / "4pm"
 * (lowercase marker, no space, minutes dropped on the hour, the app's quiet
 * 12-hour convention).
 */
fun formatClockTime(time: LocalTime, use24Hour: Boolean = TimeStyle.is24Hour): String {
    if (use24Hour) return String.format(Locale.ROOT, "%02d:%02d", time.hour, time.minute)
    val h12 = when {
        time.hour == 0 -> 12
        time.hour > 12 -> time.hour - 12
        else -> time.hour
    }
    val marker = if (time.hour >= 12) "pm" else "am"
    return if (time.minute == 0) "$h12$marker" else String.format(Locale.ROOT, "%d:%02d%s", h12, time.minute, marker)
}

/** Wall-clock label for call-log rows, in the phone's 12/24-hour style ([formatClockTime]). */
fun formatWallClock(
    occurredAt: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
    use24Hour: Boolean = TimeStyle.is24Hour,
): String = formatClockTime(occurredAt.atZone(zone).toLocalTime(), use24Hour)

/**
 * Calendar-day section header for the call log:
 * "Today" / "Yesterday" / "Wednesday 3 June" (year appended only when the
 * day falls outside [today]'s year, e.g. "Wednesday 3 June 2025").
 *
 * Pure over [LocalDate] so callers own the Instant→LocalDate conversion and
 * grouping + labelling can never disagree about which day a call landed on.
 */
fun formatDayHeader(day: LocalDate, today: LocalDate): String = when (day) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> {
        val base = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault()).format(day)
        if (day.year == today.year) base else "$base ${day.year}"
    }
}

/** "—" (no call) / "{n}s" / "{n} min" / "{h}h {m}m". */
fun formatDuration(seconds: Int): String =
    when {
        seconds <= 0 -> "—"
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60} min"
        else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    }

/**
 * Pure absolute formatter for note timestamps.
 *
 * Format: "MMM d · {clock time}", e.g. "Mar 14 · 2:14pm", or "Mar 14 · 14:14"
 * when the phone uses 24-hour time ([formatClockTime]).
 *
 * Pure — no `now` parameter. Uses [ZoneId.systemDefault] so the rendered date
 * matches the device's local time zone. Tests can pin a specific zone via the
 * overload that takes an explicit [zone]; the default is sufficient because
 * notes always render against the user's wall clock.
 */
fun formatAbsolute(
    occurredAt: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
    use24Hour: Boolean = TimeStyle.is24Hour,
): String {
    val ldt = occurredAt.atZone(zone)
    val date = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()).format(ldt)
    return "$date · ${formatClockTime(ldt.toLocalTime(), use24Hour)}"
}
