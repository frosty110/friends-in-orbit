package app.orbit.ui.screens.lists

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.orbit.ui.components.OrbitButton
import app.orbit.ui.components.OrbitButtonVariant
import app.orbit.ui.components.OrbitSwitch
import app.orbit.ui.components.PhIcon
import app.orbit.ui.theme.OrbitTheme
import java.time.LocalTime

/**
 * Active hours editor (LIST-05).
 *
 * Wraps Material3 [TimePicker] in a hand-rolled dialog (Material3 1.3.x has no
 * `TimePickerDialog` composable) and renders an
 * [ActiveHoursRangeBar] that draws TWO segments when `end < start` (overnight
 * list). Replaces an earlier buggy negative-width single-segment bar.
 *
 * Pure formatter helpers ([activeHoursReadout], [spansMidnight]) are tested by
 * `ActiveHoursFormatterTest`. Token-clean — no inline color hex literals, no
 * RoundedCornerShape, no fontSize literals.
 *
 * Consumed by the `ListConfigScreen` rewrite.
 */
@Composable
fun ActiveHoursEditor(
    start: LocalTime?,
    end: LocalTime?,
    onAlwaysActiveToggled: (Boolean) -> Unit,
    onTimesChanged: (LocalTime?, LocalTime?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val alwaysActive = start == null || end == null
    var editing by remember { mutableStateOf<TimeEditTarget?>(null) }

    Column(modifier = modifier.fillMaxWidth()) {
        AlwaysActiveToggleRow(
            alwaysActive = alwaysActive,
            onChange = onAlwaysActiveToggled,
        )
        if (start != null && end != null) {
            HairlineDivider()
            Column(Modifier.padding(horizontal = 16.dp, vertical = 18.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TimeChip(
                        label = formatHour12(start),
                        leadingIcon = "clock",
                        onClick = { editing = TimeEditTarget.Start },
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "to",
                        style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
                    )
                    TimeChip(
                        label = formatHour12(end),
                        leadingIcon = "clock",
                        onClick = { editing = TimeEditTarget.End },
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(12.dp))
                ActiveHoursRangeBar(start = start, end = end)
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    listOf("12a", "6a", "12p", "6p", "12a").forEach { tick ->
                        Text(
                            text = tick,
                            style = OrbitTheme.type.micro.copy(color = OrbitTheme.colors.fgSubtle),
                        )
                    }
                }
                if (spansMidnight(start, end)) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Overnight list — active across midnight.",
                        style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
                    )
                }
            }
        }
    }

    when (editing) {
        TimeEditTarget.Start -> {
            TimePickerDialogOrbit(
                initial = start ?: LocalTime.of(9, 0),
                onConfirm = {
                    onTimesChanged(it, end ?: LocalTime.of(17, 0))
                    editing = null
                },
                onDismiss = { editing = null },
            )
        }
        TimeEditTarget.End -> {
            TimePickerDialogOrbit(
                initial = end ?: LocalTime.of(17, 0),
                onConfirm = {
                    onTimesChanged(start ?: LocalTime.of(9, 0), it)
                    editing = null
                },
                onDismiss = { editing = null },
            )
        }
        null -> Unit
    }
}

private enum class TimeEditTarget { Start, End }

/**
 * When `end < start`, draws TWO terracotta segments
 * (start→right edge AND left edge→end) separated by the inactive midnight gap.
 *
 * An earlier `ActiveRangeBar` computed
 * `trackWidth * ((end - start) / 24f)` which produces a NEGATIVE width for
 * overnight ranges. This composable replaces it.
 */
@Composable
fun ActiveHoursRangeBar(
    start: LocalTime,
    end: LocalTime,
    modifier: Modifier = Modifier,
    alwaysActive: Boolean = false,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(OrbitTheme.shapes.full)
            .background(OrbitTheme.colors.lineSoft),
    ) {
        val trackWidth = maxWidth
        val accent = OrbitTheme.colors.accent
        val shapeFull = OrbitTheme.shapes.full

        if (alwaysActive) {
            Box(
                modifier = Modifier
                    .size(width = trackWidth, height = 6.dp)
                    .clip(shapeFull)
                    .background(accent),
            )
            return@BoxWithConstraints
        }

        val startFraction = hourFraction(start)
        val endFraction = hourFraction(end)

        if (start == end) {
            // Zero-range — no fill, entire track stays lineSoft.
            return@BoxWithConstraints
        }

        if (spansMidnight(start, end)) {
            // Segment A: start → right edge (1.0)
            val segAOffset = trackWidth * startFraction
            val segAWidth = trackWidth * (1f - startFraction)
            Box(
                modifier = Modifier
                    .offset(x = segAOffset)
                    .size(width = segAWidth, height = 6.dp)
                    .clip(shapeFull)
                    .background(accent),
            )
            // Segment B: 0.0 (left edge) → end
            val segBWidth = trackWidth * endFraction
            Box(
                modifier = Modifier
                    .size(width = segBWidth, height = 6.dp)
                    .clip(shapeFull)
                    .background(accent),
            )
        } else {
            val leftOffset = trackWidth * startFraction
            val fillWidth = trackWidth * (endFraction - startFraction)
            Box(
                modifier = Modifier
                    .offset(x = leftOffset)
                    .size(width = fillWidth, height = 6.dp)
                    .clip(shapeFull)
                    .background(accent),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerDialogOrbit(
    initial: LocalTime,
    onConfirm: (LocalTime) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        is24Hour = false,
    )
    Dialog(onDismissRequest = onDismiss) {
        Card(shape = OrbitTheme.shapes.lg) {
            Column(Modifier.padding(24.dp)) {
                TimePicker(state = state)
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                ) {
                    OrbitButton(
                        text = "Cancel",
                        onClick = onDismiss,
                        variant = OrbitButtonVariant.Ghost,
                    )
                    OrbitButton(
                        text = "OK",
                        onClick = { onConfirm(LocalTime.of(state.hour, state.minute)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AlwaysActiveToggleRow(
    alwaysActive: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!alwaysActive) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "Always active",
                style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
            )
            Text(
                text = "Suggest at any time of day",
                style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        OrbitSwitch(checked = alwaysActive, onCheckedChange = onChange)
    }
}

@Composable
private fun TimeChip(
    label: String,
    leadingIcon: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .height(48.dp)
            .clip(OrbitTheme.shapes.md)
            .background(OrbitTheme.colors.bgSubtle)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
    ) {
        PhIcon(
            name = leadingIcon,
            size = 18.dp,
            tint = OrbitTheme.colors.fgMuted,
        )
        Text(
            text = label,
            style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
        )
    }
}

@Composable
private fun HairlineDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(OrbitTheme.colors.lineSoft),
    )
}

// region Pure formatter helpers — tested by ActiveHoursFormatterTest

internal fun spansMidnight(start: LocalTime, end: LocalTime): Boolean = end < start

internal fun activeHoursReadout(start: LocalTime?, end: LocalTime?): String {
    if (start == null || end == null) return "Always"
    val s = formatHour12(start)
    val e = formatHour12(end)
    return if (spansMidnight(start, end)) "$s – $e (overnight)" else "$s – $e"
}

internal fun formatHour12(t: LocalTime): String {
    val h12 = when {
        t.hour == 0 -> 12
        t.hour > 12 -> t.hour - 12
        else -> t.hour
    }
    val ampm = if (t.hour >= 12) "pm" else "am"
    return if (t.minute == 0) "$h12$ampm" else String.format("%d:%02d%s", h12, t.minute, ampm)
}

private fun hourFraction(t: LocalTime): Float =
    (t.hour + t.minute / 60f) / 24f

// endregion

// region Previews

@Preview(name = "ActiveHoursEditor — light, normal range", showBackground = true)
@Composable
private fun ActiveHoursEditorLightNormalPreview() {
    OrbitTheme(darkTheme = false) {
        Box(modifier = Modifier.background(OrbitTheme.colors.surface).padding(8.dp)) {
            ActiveHoursEditor(
                start = LocalTime.of(9, 0),
                end = LocalTime.of(17, 0),
                onAlwaysActiveToggled = {},
                onTimesChanged = { _, _ -> },
            )
        }
    }
}

@Preview(name = "ActiveHoursEditor — dark, overnight", showBackground = true)
@Composable
private fun ActiveHoursEditorDarkOvernightPreview() {
    OrbitTheme(darkTheme = true) {
        Box(modifier = Modifier.background(OrbitTheme.colors.surface).padding(8.dp)) {
            ActiveHoursEditor(
                start = LocalTime.of(21, 0),
                end = LocalTime.of(2, 0),
                onAlwaysActiveToggled = {},
                onTimesChanged = { _, _ -> },
            )
        }
    }
}

@Preview(name = "ActiveHoursEditor — light, always active", showBackground = true)
@Composable
private fun ActiveHoursEditorAlwaysActivePreview() {
    OrbitTheme(darkTheme = false) {
        Box(modifier = Modifier.background(OrbitTheme.colors.surface).padding(8.dp)) {
            ActiveHoursEditor(
                start = null,
                end = null,
                onAlwaysActiveToggled = {},
                onTimesChanged = { _, _ -> },
            )
        }
    }
}

// endregion

// Suppress unused-symbol false positive — Spacer width helper for tooling parity.
@Suppress("unused")
private val unusedWidth = Modifier.width(0.dp)
