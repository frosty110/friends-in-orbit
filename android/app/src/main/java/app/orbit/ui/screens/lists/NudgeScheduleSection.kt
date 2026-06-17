package app.orbit.ui.screens.lists

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import app.orbit.notify.NotificationCopy
import app.orbit.notify.NudgeSchedule
import app.orbit.ui.components.PhIcon
import app.orbit.ui.theme.OrbitTheme
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * NOTIF-10 — Nudge schedule editor hosted inside ListConfigBody's
 * Nudges SettingGroup.
 *
 * Renders (top to bottom):
 *  - 1a: Row of seven day-of-week chips (S M T W T F S).
 *  - 1b: Column of removable time chips plus an "Add time" affordance.
 *  - 1c: Schedule summary line (D-06 format rules via NotificationCopy.scheduleSummary).
 *  - 1d: Muted badge when notificationsEnabled = false (D-04).
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(OrbitTheme.spacing.tapMin)
                    .clip(OrbitTheme.shapes.md)
                    .background(OrbitTheme.colors.bgSubtle)
                    .clickable { editingTimeIndex = ADD_TIME_SENTINEL }
                    .padding(horizontal = OrbitTheme.spacing.x4)
                    .semantics { contentDescription = NotificationCopy.A11Y_ADD_TIME },
                horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x2),
            ) {
                PhIcon(
                    name = "plus",
                    size = OrbitTheme.spacing.x5,
                    tint = OrbitTheme.colors.accent,
                )
                Text(
                    text = NotificationCopy.LABEL_ADD_TIME,
                    style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.accent),
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
    // Display order: S M T W T F S (Sunday first, locale-agnostic per D-05 spec)
    val ordered = listOf(
        DayOfWeek.SUNDAY to "S",
        DayOfWeek.MONDAY to "M",
        DayOfWeek.TUESDAY to "T",
        DayOfWeek.WEDNESDAY to "W",
        DayOfWeek.THURSDAY to "T",
        DayOfWeek.FRIDAY to "F",
        DayOfWeek.SATURDAY to "S",
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x2),
    ) {
        ordered.forEach { (day, label) ->
            val selected = day in selectedDays
            val bgColor = if (selected) OrbitTheme.colors.accent else OrbitTheme.colors.bgSubtle
            val labelColor = if (selected) OrbitTheme.colors.accentFg else OrbitTheme.colors.fgMuted
            val cd = NotificationCopy.a11yDayChip(day.fullName(), selected)

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .height(OrbitTheme.spacing.tapMin)
                    .clip(OrbitTheme.shapes.full)
                    .background(bgColor)
                    .clickable { onToggle(day) }
                    .semantics { contentDescription = cd },
            ) {
                Text(
                    text = label,
                    style = OrbitTheme.type.body.copy(color = labelColor),
                )
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
        // Leading clock icon + tappable time label
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x2),
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onTap)
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
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(OrbitTheme.spacing.tapMin)
                .clickable(onClick = onRemove)
                .semantics { contentDescription = NotificationCopy.a11yRemoveTime(formattedTime) },
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
                text = NotificationCopy.SUMMARY_NO_DAYS,
                style = OrbitTheme.type.micro.copy(color = OrbitTheme.colors.fgSubtle),
            )
        }
        schedule.times.isEmpty() -> {
            Text(
                text = NotificationCopy.SUMMARY_NO_TIME,
                style = OrbitTheme.type.micro.copy(color = OrbitTheme.colors.fgSubtle),
            )
        }
        else -> {
            val dayLabel = dayGroupLabel(schedule.days)
            val timeStrings = schedule.times.sorted().map { formatHour12(it) }
            Text(
                text = NotificationCopy.scheduleSummary(dayLabel, timeStrings),
                style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fg),
            )
        }
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
            tint = OrbitTheme.colors.accent,
        )
        Spacer(Modifier.width(OrbitTheme.spacing.x1))
        Text(
            text = NotificationCopy.LABEL_MUTED_BADGE,
            style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.accent),
        )
    }
}

// ── Pure helpers ───────────────────────────────────────────────────────────────

/** Returns the full English day name for accessibility content descriptions. */
private fun DayOfWeek.fullName(): String = when (this) {
    DayOfWeek.SUNDAY -> "Sunday"
    DayOfWeek.MONDAY -> "Monday"
    DayOfWeek.TUESDAY -> "Tuesday"
    DayOfWeek.WEDNESDAY -> "Wednesday"
    DayOfWeek.THURSDAY -> "Thursday"
    DayOfWeek.FRIDAY -> "Friday"
    DayOfWeek.SATURDAY -> "Saturday"
}

/**
 * Returns the D-06 day-group label for the given set of days.
 *
 * Rules (from NotificationCopy.scheduleSummary KDoc / UI-SPEC section 1c):
 *  - All 7 days → "Every day"
 *  - Mon–Fri only → "Weekdays"
 *  - Sat–Sun only → "Weekends"
 *  - Otherwise → short names joined by commas (e.g. "Mon, Wed, Fri")
 */
private fun dayGroupLabel(days: Set<DayOfWeek>): String {
    val allSevenDays = DayOfWeek.values().toSet()
    val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
    val weekend = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
    return when (days) {
        allSevenDays -> "Every day"
        weekdays -> "Weekdays"
        weekend -> "Weekends"
        else -> {
            // Short names in Sun→Sat display order
            val ordered = listOf(
                DayOfWeek.SUNDAY to "Sun",
                DayOfWeek.MONDAY to "Mon",
                DayOfWeek.TUESDAY to "Tue",
                DayOfWeek.WEDNESDAY to "Wed",
                DayOfWeek.THURSDAY to "Thu",
                DayOfWeek.FRIDAY to "Fri",
                DayOfWeek.SATURDAY to "Sat",
            )
            ordered.filter { (day, _) -> day in days }.joinToString(", ") { (_, name) -> name }
        }
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
