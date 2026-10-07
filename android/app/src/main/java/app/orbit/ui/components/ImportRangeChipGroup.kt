package app.orbit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import app.orbit.ui.theme.OrbitTheme
import app.orbit.ui.util.IMPORT_DAY_OPTIONS
import app.orbit.ui.util.asString
import app.orbit.ui.util.importRangeLabel

/**
 * How far back Orbit reads the call log, as one radio group: "1 month",
 * "3 months", "6 months", "1 year". The one control for this setting, on
 * both screens that offer it: Settings' import range row and onboarding's
 * sync step.
 *
 * [OrbitFilterChip]s with RadioButton semantics, since exactly one window is
 * chosen, inside one `selectableGroup`, so TalkBack counts them ("3 months,
 * radio button, 2 of 4") instead of announcing four lone buttons (the
 * CallLogScreen direction row and the rhythm rows do the same). A FlowRow,
 * not a Row: at 200% text a fixed row crushed the last chip to a sliver
 * (gate G3); wrapping keeps every label whole.
 *
 * Until 2026-10-07 each screen drew its own: onboarding this group, Settings
 * a Material FilterChip with checkbox semantics, an accent-tint fill and its
 * own private copy of the options and labels, so the same setting announced
 * as four checkboxes in one place and a radio group in the other.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ImportRangeChipGroup(
    selectedDays: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x2),
    ) {
        IMPORT_DAY_OPTIONS.forEach { days ->
            OrbitFilterChip(
                label = importRangeLabel(days).asString(),
                selected = selectedDays == days,
                onClick = { onSelect(days) },
                role = Role.RadioButton,
            )
        }
    }
}

@PreviewLightDark
@PreviewFontScale
@Composable
private fun ImportRangeChipGroupPreview() {
    OrbitTheme {
        ImportRangeChipGroup(
            selectedDays = 90,
            onSelect = {},
            modifier = Modifier
                .background(OrbitTheme.colors.bg)
                .padding(OrbitTheme.spacing.x4),
        )
    }
}
