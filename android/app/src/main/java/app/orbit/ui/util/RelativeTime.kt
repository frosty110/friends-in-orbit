package app.orbit.ui.util

import java.time.Instant
import java.time.LocalDate
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
 * "today" / "yesterday" / "{n} days ago" / "1 month ago" / "{n} months ago".
 *
 * Day-relative labels compare LOCAL calendar days, not 24-hour windows: an
 * 11pm call read the next morning is "yesterday", never "today".
 * [zone] defaults to [ZoneId.systemDefault] (same convention as
 * [formatAbsolute]); existing call sites pass only `(occurredAt, now)` and
 * keep their contracts. Singular forms are honest ("1 month ago", never
 * "1 months ago"); the 2..29 day band is always plural by construction.
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
    return when {
        days == 0L -> "today"
        days == 1L -> "yesterday"
        days < 30L -> "$days days ago"
        else -> {
            val months = days / 30L
            if (months == 1L) "1 month ago" else "$months months ago"
        }
    }
}

/**
 * Wall-clock label for call-log rows, e.g. "4:30pm". Lowercase am/pm marker,
 * no space — matches the quiet-voice convention set by [formatAbsolute].
 * [zone] defaults to [ZoneId.systemDefault].
 */
fun formatWallClock(occurredAt: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofPattern("h:mma", Locale.getDefault())
        .format(occurredAt.atZone(zone))
        .lowercase(Locale.getDefault())

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
 * Format: "MMM d · h:mm a" lowercase am/pm, e.g., "mar 14 · 2:14 pm". Sentence
 * case (no leading caps); matches the rest of the app's quiet-voice convention.
 *
 * Pure — no `now` parameter. Uses [ZoneId.systemDefault] so the rendered date
 * matches the device's local time zone. Tests can pin a specific zone via the
 * overload that takes an explicit [zone]; the default is sufficient because
 * notes always render against the user's wall clock.
 */
fun formatAbsolute(occurredAt: Instant, zone: ZoneId = ZoneId.systemDefault()): String {
    val ldt = occurredAt.atZone(zone)
    val date = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()).format(ldt)
    val time = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
        .format(ldt)
        .lowercase(Locale.getDefault())
    return "$date · $time"
}
