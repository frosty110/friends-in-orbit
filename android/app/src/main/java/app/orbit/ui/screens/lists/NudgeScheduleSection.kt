package app.orbit.ui.screens.lists

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.orbit.R
import app.orbit.notify.NudgeSchedule
import app.orbit.notify.NudgeScheduler
import app.orbit.notify.isInActiveWindow
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
 *  - 1c: Schedule summary line (D-06 format rules, [scheduleSummary]): the
 *    nudge the list will really get, read through [nudgePlan] with the list's
 *    time of day ([activeHoursStart]..[activeHoursEnd], LIST-25), and under it
 *    a note naming any chosen time the time of day holds back.
 *  - 1d: "Nudges paused" badge when notificationsEnabled = false (D-04).
 *
 * Copy lives in strings_lists.xml (`lists_nudge_*`); it used to be constants
 * on NotificationCopy, some with em dashes.
 *
 * Save-on-change: every chip tap calls [onScheduleChange] immediately — no Apply
 * button. Mirrors every other ListConfigBody control.
 *
 * Token-clean: no raw colour literals, no raw font-size literals.
 * Reuses [TimePickerDialogOrbit] and [formatHour12] from TimeOfDayPicker.kt.
 */
@Composable
internal fun NudgeScheduleSection(
    schedule: NudgeSchedule?,
    activeHoursStart: LocalTime?,
    activeHoursEnd: LocalTime?,
    notificationsEnabled: Boolean,
    onScheduleChange: (NudgeSchedule) -> Unit,
) {
    val effective = schedule ?: NudgeSchedule.DEFAULT
    val plan = remember(effective, activeHoursStart, activeHoursEnd) {
        nudgePlan(effective, activeHoursStart, activeHoursEnd)
    }

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
        // The chips above are the times the user chose; the line says when the
        // nudge really comes, which the time of day can change (LIST-25).
        ScheduleSummaryLine(schedule = effective, plan = plan)

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
    // 42dp wide, under the 48dp floor (rules.md §Design 3). Where a seventh is
    // still under 48dp (a 360dp phone, or a narrow window) the days wrap into
    // two rows, four then three, rather than shrink (rubric gate G3).
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val rows = if (maxWidth / ordered.size >= OrbitTheme.spacing.tapMin) {
            listOf(ordered)
        } else {
            listOf(ordered.take(4), ordered.drop(4))
        }
        val perRow = rows.first().size
        Column(verticalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x1)) {
            rows.forEach { rowDays ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    rowDays.forEach { (day, label) ->
                        DayCell(
                            day = day,
                            label = label,
                            selected = day in selectedDays,
                            onToggle = onToggle,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // A short last row keeps the first row's cell width.
                    repeat(perRow - rowDays.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    day: DayOfWeek,
    label: String,
    selected: Boolean,
    onToggle: (DayOfWeek) -> Unit,
    modifier: Modifier = Modifier,
) {
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
        modifier = modifier
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
private fun ScheduleSummaryLine(schedule: NudgeSchedule, plan: NudgePlan) {
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
            Text(
                text = planSummary(plan),
                style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fg),
            )
            if (plan.outside.isNotEmpty()) {
                Text(
                    text = outsideNote(plan),
                    style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
                )
            }
        }
    }
}

/**
 * The plan as one line: the days and the times a nudge can post at, which is
 * what [nudgePlan] says will happen, not the times as stored.
 */
@Composable
private fun planSummary(plan: NudgePlan): String =
    scheduleSummary(dayGroupLabel(plan.posts.days), plan.posts.times.map { formatHour12(it) })

/**
 * Why a chosen time is not in the plan: "10am is outside this list's time of
 * day, so the nudge comes at 5pm instead." when the time of day's start stands
 * in for every chosen time, or "... so no nudge comes then." when another
 * chosen time still posts. The times are joined as the plan's are.
 */
@Composable
private fun outsideNote(plan: NudgePlan): String {
    val times = joinTimes(plan.outside.map { formatHour12(it) })
    val count = plan.outside.size
    val instead = plan.startInstead
    return if (instead != null) {
        pluralStringResource(R.plurals.lists_nudge_outside_moved, count, times, formatHour12(instead))
    } else {
        pluralStringResource(R.plurals.lists_nudge_outside_skipped, count, times)
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
internal fun OnboardingNudgeSummary(
    schedule: NudgeSchedule?,
    activeHoursStart: LocalTime?,
    activeHoursEnd: LocalTime?,
) {
    // Mirror the scheduler's own fallback (NudgeScheduler.kt): a list with no
    // stored schedule nudges on NudgeSchedule.DEFAULT — every day at 10:00.
    val effective = (schedule ?: NudgeSchedule.DEFAULT)
        .takeIf { it.days.isNotEmpty() && it.times.isNotEmpty() }
        ?: NudgeSchedule.DEFAULT
    // LIST-25: Time of day sits above this on the same step, so the line says
    // when the nudge really comes ("Every day at 5pm" for Evenings), the
    // scheduler's answer. Onboarding shows no nudge times, so there is no
    // chosen time to explain and no note under it.
    val plan = remember(effective, activeHoursStart, activeHoursEnd) {
        nudgePlan(effective, activeHoursStart, activeHoursEnd)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = OrbitTheme.spacing.x4, vertical = OrbitTheme.spacing.x2),
        verticalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x1),
    ) {
        Text(
            text = planSummary(plan),
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
 * What a list's nudge will really do, for the summary line (LIST-25).
 *
 * [posts] is the schedule the nudge chain runs ([NudgeScheduler.effectiveSchedule])
 * less the times the fire-time gate will hold back: the days, and the times a
 * nudge can post at, in order. [outside] is the chosen times the list's time
 * of day holds back, in order; empty with no time of day. [startInstead] is
 * the time of day's start when the scheduler added it because none of the
 * chosen times fits (D-09), else null.
 */
@Immutable
internal data class NudgePlan(
    val posts: NudgeSchedule,
    val outside: List<LocalTime>,
    val startInstead: LocalTime?,
)

/**
 * The one reading of a list's schedule and time of day that the screens say
 * out loud, built from the two definitions the nudge itself goes through:
 * [NudgeScheduler.effectiveSchedule], which decides the slots the chain wakes
 * at, and [isInActiveWindow], the question the worker's active-hours gate asks
 * at each slot. Neither is copied here, so the summary cannot drift from the
 * nudge (until 2026-10-08 the line read the stored schedule alone, and an
 * Evenings list said "Every day at 10am" while its nudge came at 5pm).
 *
 * The scheduler keeps a held-back time as a slot (the worker wakes, the gate
 * holds it, the chain moves on), so the gate's question is what takes it out
 * of [NudgePlan.posts]. An emptied schedule (no days or no times) is not a
 * plan: the line says "nudges off" or "No time set" for it, from the stored
 * schedule.
 */
internal fun nudgePlan(
    schedule: NudgeSchedule,
    activeHoursStart: LocalTime?,
    activeHoursEnd: LocalTime?,
): NudgePlan {
    val effective = NudgeScheduler.effectiveSchedule(schedule, activeHoursStart, activeHoursEnd)
    if (activeHoursStart == null || activeHoursEnd == null) {
        return NudgePlan(
            posts = effective.copy(times = effective.times.distinct().sorted()),
            outside = emptyList(),
            startInstead = null,
        )
    }
    val canPost = { time: LocalTime -> isInActiveWindow(time, activeHoursStart, activeHoursEnd) }
    return NudgePlan(
        posts = effective.copy(times = effective.times.filter(canPost).distinct().sorted()),
        outside = schedule.times.filterNot(canPost).distinct().sorted(),
        // A time the scheduler added is one the user never chose: the start.
        startInstead = (effective.times - schedule.times.toSet()).firstOrNull(),
    )
}

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
private fun scheduleSummary(dayGroupLabel: String, timeStrings: List<String>): String =
    stringResource(R.string.lists_nudge_summary, dayGroupLabel, joinTimes(timeStrings))

/**
 * "10am", "10am and 6pm", "9am, 1pm, and 6pm": times joined as the plan line
 * joins them, for the line and for the note under it. [timeStrings] is non-empty.
 */
@Composable
private fun joinTimes(timeStrings: List<String>): String = when (timeStrings.size) {
    1 -> timeStrings[0]
    2 -> stringResource(R.string.lists_nudge_times_two, timeStrings[0], timeStrings[1])
    else -> stringResource(
        R.string.lists_nudge_times_many,
        timeStrings.dropLast(1).joinToString(", "),
        timeStrings.last(),
    )
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
            activeHoursStart = null,
            activeHoursEnd = null,
            notificationsEnabled = true,
            onScheduleChange = {},
        )
    }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES, name = "NudgeScheduleSection — dark, muted badge, two times", showBackground = true)
@Composable
private fun NudgeScheduleSectionDarkMutedPreview() {
    OrbitTheme(darkTheme = true) {
        NudgeScheduleSection(
            schedule = NudgeSchedule(
                days = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
                times = listOf(LocalTime.of(9, 0), LocalTime.of(18, 30)),
            ),
            activeHoursStart = null,
            activeHoursEnd = null,
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
            activeHoursStart = null,
            activeHoursEnd = null,
            notificationsEnabled = true,
            onScheduleChange = {},
        )
    }
}

// LIST-25: the default 10am under Evenings. The line says when the nudge
// really comes ("Every day at 5pm"), and the note why 10am is not in it.
@Preview(name = "NudgeScheduleSection, light, evenings, 10am outside", showBackground = true)
@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES, name = "NudgeScheduleSection, dark, evenings, 10am outside", showBackground = true)
@Composable
private fun NudgeScheduleSectionOutsideTimeOfDayPreview() {
    OrbitTheme {
        NudgeScheduleSection(
            schedule = NudgeSchedule.DEFAULT,
            activeHoursStart = DayPart.Evenings.start,
            activeHoursEnd = DayPart.Evenings.end,
            notificationsEnabled = true,
            onScheduleChange = {},
        )
    }
}

@Preview(name = "OnboardingNudgeSummary — light, default (every day 10am)", showBackground = true)
@Composable
private fun OnboardingNudgeSummaryLightPreview() {
    OrbitTheme(darkTheme = false) {
        OnboardingNudgeSummary(schedule = null, activeHoursStart = null, activeHoursEnd = null)
    }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES, name = "OnboardingNudgeSummary — dark, custom schedule", showBackground = true)
@Composable
private fun OnboardingNudgeSummaryDarkPreview() {
    OrbitTheme(darkTheme = true) {
        OnboardingNudgeSummary(
            schedule = NudgeSchedule(
                days = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
                times = listOf(LocalTime.of(9, 0)),
            ),
            activeHoursStart = null,
            activeHoursEnd = null,
        )
    }
}

// LIST-25: Evenings picked on the step, so the default nudge comes at 5pm.
@Preview(name = "OnboardingNudgeSummary, light, evenings", showBackground = true)
@Composable
private fun OnboardingNudgeSummaryEveningsPreview() {
    OrbitTheme(darkTheme = false) {
        OnboardingNudgeSummary(
            schedule = null,
            activeHoursStart = DayPart.Evenings.start,
            activeHoursEnd = DayPart.Evenings.end,
        )
    }
}
