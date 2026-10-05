package app.orbit.ui.screens.lists

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.orbit.R
import app.orbit.notify.NudgeSchedule
import app.orbit.ui.components.PhIcon
import app.orbit.ui.theme.OrbitTheme
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * NOTIF-10 — Nudge schedule editor hosted inside ListConfigBody's
 * Nudges SettingGroup.
 *
 * Renders (top to bottom):
 *  - 1a: Row of seven day-of-week chips (S M T W T F S).
 *  - 1b: Column of removable time chips plus an "Add time" affordance.
 *  - 1c: Schedule summary line (D-06 format rules, [scheduleSummary]).
 *  - 1d: "Nudges paused" badge when notificationsEnabled = false (D-04).
 *
 * Copy lives in strings_lists.xml (`lists_nudge_*`); it used to be constants
 * on NotificationCopy, some with em dashes.
 *
 * Save-on-change: every chip tap calls [onScheduleChange] immediately — no Apply
 * button. Mirrors every other ListConfigBody control.
 *
 * Token-clean: no raw colour literals, no raw font-size literals.
 * Reuses [TimePickerDialogOrbit] and [formatHour12] from [ActiveHoursEditor].
 */
@Composable
internal fun NudgeScheduleSection(
    schedule: NudgeSchedule?,
    notificationsEnabled: Boolean,
    onScheduleChange: (NudgeSchedule) -> Unit,
) {
    val effective = schedule ?: NudgeSchedule.DEFAULT

    // Tracks which time chip is being edited (null = none; -1 = adding a new time)
    var editingTimeIndex by remember { mutableStateOf<Int?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = OrbitTheme.spacing.x4,
                vertical = OrbitTheme.spacing.x3,
            ),
        verticalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x2),
    ) {
        // ── 1a. Day-of-week chip row ──────────────────────────────────────────
        DayChipRow(
            selectedDays = effective.days,
            onToggle = { day ->
                val newDays = if (day in effective.days) {
                    effective.days - day
                } else {
                    effective.days + day
                }
                onScheduleChange(effective.copy(days = newDays))
            },
        )

        // ── 1b. Time chip list + Add time affordance ──────────────────────────
        val sortedTimes = effective.times.sorted()
        Column(verticalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x2)) {
            sortedTimes.forEachIndexed { index, time ->
                TimeChipRow(
                    formattedTime = formatHour12(time),
                    onTap = { editingTimeIndex = index },
                    onRemove = {
                        val newTimes = sortedTimes.toMutableList().also { it.removeAt(index) }
                        onScheduleChange(effective.copy(times = newTimes))
                    },
                )
            }

            // "Add time" affordance
            val addTimeDescription = stringResource(R.string.lists_nudge_add_time_a11y)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(OrbitTheme.spacing.tapMin)
                    .clip(OrbitTheme.shapes.md)
                    .background(OrbitTheme.colors.bgSubtle)
                    .clickable { editingTimeIndex = ADD_TIME_SENTINEL }
                    .padding(horizontal = OrbitTheme.spacing.x4)
                    .semantics { contentDescription = addTimeDescription },
                horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x2),
            ) {
                PhIcon(
                    name = "plus",
                    size = OrbitTheme.spacing.x5,
                    tint = OrbitTheme.colors.fg,
                )
                Text(
                    text = stringResource(R.string.lists_nudge_add_time),
                    style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
                )
            }
        }

        // ── 1c. Schedule summary ─────────────────────────────────────────────
        ScheduleSummaryLine(schedule = effective)

        // ── 1d. Muted badge ───────────────────────────────────────────────────
        if (!notificationsEnabled) {
            MutedBadge()
        }
    }

    // ── TimePicker dialogs ────────────────────────────────────────────────────
    val idx = editingTimeIndex
    if (idx != null) {
        val initial = if (idx == ADD_TIME_SENTINEL) {
            LocalTime.of(9, 0)
        } else {
            effective.times.sorted().getOrElse(idx) { LocalTime.of(9, 0) }
        }
        TimePickerDialogOrbit(
            initial = initial,
            onConfirm = { picked ->
                val newTimes = if (idx == ADD_TIME_SENTINEL) {
                    (effective.times + picked).sorted()
                } else {
                    val mutable = effective.times.sorted().toMutableList()
                    mutable[idx] = picked
                    mutable.sorted()
                }
                onScheduleChange(effective.copy(times = newTimes))
                editingTimeIndex = null
            },
            onDismiss = { editingTimeIndex = null },
        )
    }
}

/** Sentinel index used to signal "Add time" mode in the shared dialog slot. */
private const val ADD_TIME_SENTINEL = -1

// ── Sub-composables ────────────────────────────────────────────────────────────

@Composable
private fun DayChipRow(
    selectedDays: Set<DayOfWeek>,
    onToggle: (DayOfWeek) -> Unit,
) {
    // Display order: S M T W T F S (Sunday first, locale-agnostic per D-05 spec).
    // The letters are the locale's narrow day names, as on Home's rhythm strip.
    val ordered = SUNDAY_FIRST.map { day -> day to day.getDisplayName(TextStyle.NARROW, Locale.getDefault()) }

    // No spacing between cells: each cell is an equal seventh of the row, so
    // the touch target is the full cell (about 49dp on a phone) while the
    // visible pill is inset to keep the gaps. With 8dp gaps the cells were
    // 42dp wide, under the 48dp floor (rules.md §Design 3).
    Row(
        modifier = Modifier.fillMaxWidth(),
    ) {
        ordered.forEach { (day, label) ->
            val selected = day in selectedDays
            // Cluster tier (rules.md §Design 5): a selected day is the soft
            // tint with an ink ring, not seven accent fills on one screen.
            val bgColor = if (selected) OrbitTheme.colors.accentTint else OrbitTheme.colors.bgSubtle
            val labelColor = if (selected) OrbitTheme.colors.fg else OrbitTheme.colors.fgMuted
            val cd = stringResource(
                if (selected) R.string.lists_nudge_day_selected else R.string.lists_nudge_day_unselected,
                day.fullName(),
            )

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .height(OrbitTheme.spacing.tapMin)
                    .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle(day) })
                    .semantics { contentDescription = cd },
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = OrbitTheme.spacing.hair)
                        .clip(OrbitTheme.shapes.full)
                        .background(bgColor)
                        .then(
                            if (selected) Modifier.border(1.5.dp, OrbitTheme.colors.fg, OrbitTheme.shapes.full) else Modifier,
                        ),
                ) {
                    Text(
                        text = label,
                        style = OrbitTheme.type.body.copy(color = labelColor),
                    )
                }
            }
        }
    }
}

@Composable
private fun TimeChipRow(
    formattedTime: String,
    onTap: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(OrbitTheme.spacing.tapMin)
            .clip(OrbitTheme.shapes.md)
            .background(OrbitTheme.colors.bgSubtle),
    ) {
        // Leading clock icon + tappable time label. The whole row height is
        // the target (it was the text's ~20dp; caught by the gallery's
        // accessibility audit).
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x2),
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clickable(
                    onClickLabel = stringResource(R.string.lists_nudge_change_time),
                    role = Role.Button,
                    onClick = onTap,
                )
                .padding(horizontal = OrbitTheme.spacing.x4),
        ) {
            PhIcon(
                name = "clock",
                size = OrbitTheme.spacing.x5,
                tint = OrbitTheme.colors.fgMuted,
            )
            Text(
                text = formattedTime,
                style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
            )
        }

        // Remove (X) button
        val removeDescription = stringResource(R.string.lists_nudge_remove_time, formattedTime)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(OrbitTheme.spacing.tapMin)
                .clickable(onClick = onRemove)
                .semantics { contentDescription = removeDescription },
        ) {
            PhIcon(
                name = "x",
                size = OrbitTheme.spacing.x5,
                tint = OrbitTheme.colors.fgMuted,
            )
        }
    }
}

@Composable
private fun ScheduleSummaryLine(schedule: NudgeSchedule) {
    when {
        schedule.days.isEmpty() -> {
            Text(
                text = stringResource(R.string.lists_nudge_summary_no_days),
                style = OrbitTheme.type.micro.copy(color = OrbitTheme.colors.fgSubtle),
            )
        }
        schedule.times.isEmpty() -> {
            Text(
                text = stringResource(R.string.lists_nudge_summary_no_time),
                style = OrbitTheme.type.micro.copy(color = OrbitTheme.colors.fgSubtle),
            )
        }
        else -> {
            val dayLabel = dayGroupLabel(schedule.days)
            val timeStrings = schedule.times.sorted().map { formatHour12(it) }
            Text(
                text = scheduleSummary(dayLabel, timeStrings),
                style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fg),
            )
        }
    }
}

/**
 * Read-only nudge summary for the onboarding first-list step. Onboarding hides the
 * full day/time editor (see `ListConfigBody`'s `if (!isOnboarding)`) to stay lean,
 * but the nudge is on by default there — so this line makes it *legible*: the user
 * sees exactly what "Reminders: on" means and owns it at creation. Turning it off
 * uses the "Reminders" toggle above; retiming is deferred to list settings.
 *
 * ADR 0009 — notifications are user-owned reminders: default-on (mission principle
 * 1), but never a surprise. This closes the gap where the default schedule was
 * disclosed only on the permission screen, not at the moment the list is built.
 */
@Composable
internal fun OnboardingNudgeSummary(schedule: NudgeSchedule?) {
    // Mirror the scheduler's own fallback (NudgeScheduler.kt): a list with no
    // stored schedule nudges on NudgeSchedule.DEFAULT — every day at 10:00.
    val effective = (schedule ?: NudgeSchedule.DEFAULT)
        .takeIf { it.days.isNotEmpty() && it.times.isNotEmpty() }
        ?: NudgeSchedule.DEFAULT
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = OrbitTheme.spacing.x4, vertical = OrbitTheme.spacing.x2),
        verticalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x1),
    ) {
        Text(
            text = scheduleSummary(
                dayGroupLabel(effective.days),
                effective.times.sorted().map { formatHour12(it) },
            ),
            style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fg),
        )
        Text(
            text = stringResource(R.string.lists_nudge_onboarding_hint),
            style = OrbitTheme.type.micro.copy(color = OrbitTheme.colors.fgSubtle),
        )
    }
}

@Composable
private fun MutedBadge() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(OrbitTheme.shapes.full)
            .background(OrbitTheme.colors.accentTint)
            .padding(
                horizontal = OrbitTheme.spacing.x3,
                vertical = OrbitTheme.spacing.x1,
            ),
    ) {
        PhIcon(
            name = "speaker-slash",
            size = OrbitTheme.spacing.x4,
            tint = OrbitTheme.colors.fg,
        )
        Spacer(Modifier.width(OrbitTheme.spacing.x1))
        Text(
            text = stringResource(R.string.lists_nudge_paused_badge),
            style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fg),
        )
    }
}

// ── Pure helpers ───────────────────────────────────────────────────────────────

/** Sunday-first display order shared by the chip row and the summary line. */
private val SUNDAY_FIRST: List<DayOfWeek> = listOf(
    DayOfWeek.SUNDAY,
    DayOfWeek.MONDAY,
    DayOfWeek.TUESDAY,
    DayOfWeek.WEDNESDAY,
    DayOfWeek.THURSDAY,
    DayOfWeek.FRIDAY,
    DayOfWeek.SATURDAY,
)

/**
 * The schedule as a sentence (D-06), from pre-formatted parts:
 *
 * | Schedule state | Result |
 * |---|---|
 * | All 7 days, one time | "Every day at {t}" |
 * | Mon–Fri, one time | "Weekdays at {t}" |
 * | Sat–Sun, one time | "Weekends at {t}" |
 * | Other days | "{days} at {t}" ("Sun, Wed at 10am") |
 * | Two times | "... at {t1} and {t2}" |
 * | Three or more | "... at {t1}, {t2}, and {t3}" |
 *
 * [dayGroupLabel] comes from [dayGroupLabel], [timeStrings] (non-empty) from
 * [formatHour12]. Moved here from NotificationCopy, its only caller being
 * this file; the words are strings_lists.xml's.
 */
@Composable
private fun scheduleSummary(dayGroupLabel: String, timeStrings: List<String>): String {
    val timePart = when (timeStrings.size) {
        1 -> timeStrings[0]
        2 -> stringResource(R.string.lists_nudge_times_two, timeStrings[0], timeStrings[1])
        else -> stringResource(
            R.string.lists_nudge_times_many,
            timeStrings.dropLast(1).joinToString(", "),
            timeStrings.last(),
        )
    }
    return stringResource(R.string.lists_nudge_summary, dayGroupLabel, timePart)
}

/** The locale's full day name, for accessibility content descriptions. */
private fun DayOfWeek.fullName(): String = getDisplayName(TextStyle.FULL, Locale.getDefault())

/**
 * Returns the D-06 day-group label for the given set of days.
 *
 * Rules (from [scheduleSummary] / UI-SPEC section 1c):
 *  - All 7 days → "Every day"
 *  - Mon–Fri only → "Weekdays"
 *  - Sat–Sun only → "Weekends"
 *  - Otherwise → the locale's short day names joined by commas (e.g. "Mon, Wed, Fri")
 */
@Composable
private fun dayGroupLabel(days: Set<DayOfWeek>): String {
    val allSevenDays = DayOfWeek.values().toSet()
    val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
    val weekend = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
    return when (days) {
        allSevenDays -> stringResource(R.string.lists_nudge_every_day)
        weekdays -> stringResource(R.string.lists_nudge_weekdays)
        weekend -> stringResource(R.string.lists_nudge_weekends)
        // Short names in Sun→Sat display order
        else -> SUNDAY_FIRST.filter { it in days }
            .joinToString(", ") { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
    }
}

// ── Previews ───────────────────────────────────────────────────────────────────

@Preview(name = "NudgeScheduleSection — light, every day, notifications on", showBackground = true)
@Composable
private fun NudgeScheduleSectionLightPreview() {
    OrbitTheme(darkTheme = false) {
        NudgeScheduleSection(
            schedule = NudgeSchedule.DEFAULT,
            notificationsEnabled = true,
            onScheduleChange = {},
        )
    }
}

@Preview(name = "NudgeScheduleSection — dark, muted badge, two times", showBackground = true)
@Composable
private fun NudgeScheduleSectionDarkMutedPreview() {
    OrbitTheme(darkTheme = true) {
        NudgeScheduleSection(
            schedule = NudgeSchedule(
                days = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
                times = listOf(LocalTime.of(9, 0), LocalTime.of(18, 30)),
            ),
            notificationsEnabled = false,
            onScheduleChange = {},
        )
    }
}

@Preview(name = "NudgeScheduleSection — light, empty schedule", showBackground = true)
@Composable
private fun NudgeScheduleSectionEmptyPreview() {
    OrbitTheme(darkTheme = false) {
        NudgeScheduleSection(
            schedule = NudgeSchedule(days = emptySet(), times = emptyList()),
            notificationsEnabled = true,
            onScheduleChange = {},
        )
    }
}

@Preview(name = "OnboardingNudgeSummary — light, default (every day 10am)", showBackground = true)
@Composable
private fun OnboardingNudgeSummaryLightPreview() {
    OrbitTheme(darkTheme = false) {
        OnboardingNudgeSummary(schedule = null)
    }
}

@Preview(name = "OnboardingNudgeSummary — dark, custom schedule", showBackground = true)
@Composable
private fun OnboardingNudgeSummaryDarkPreview() {
    OrbitTheme(darkTheme = true) {
        OnboardingNudgeSummary(
            schedule = NudgeSchedule(
                days = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
                times = listOf(LocalTime.of(9, 0)),
            ),
        )
    }
}
