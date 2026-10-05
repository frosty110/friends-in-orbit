package app.orbit.ui.screens.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import app.orbit.R
import app.orbit.ui.components.OrbitButton
import app.orbit.ui.components.OrbitButtonVariant
import app.orbit.ui.components.OrbitIconButton
import app.orbit.ui.theme.OrbitTheme

/**
 * Replaces [app.orbit.ui.components.OrbitAppBar] when Browse is in multi-select
 * mode (the `AnimatedContent` swap replaces the app bar in place, it does not
 * float).
 *
 * Two rows: the title row (exit, "N selected", more) and an action row (Move
 * to…, Copy to…, Remove) that wraps whole buttons when the text is large. Until
 * 2026-10-05 all six controls shared one fixed 56dp row with 40dp buttons: at
 * 411dp "Remove" and the overflow were pushed off the screen entirely, the
 * buttons missed the 48dp floor (rules.md §Design 3), and the fixed height
 * clipped the count at 200% font scale (rubric gate G3). Every control here
 * is at least 48dp and every row grows with its text.
 *
 * Locked copy (verified verbatim against the copywriting contract):
 *  - Close icon contentDescription: "Exit selection"
 *  - Count title: "$count selected"
 *  - Move text-button: "Move to…" (single Unicode horizontal-ellipsis char `…`)
 *  - Copy text-button: "Copy to…"
 *  - Remove text-button: "Remove"
 *  - Overflow icon contentDescription: "More batch actions"
 *
 * The overflow ([MultiSelectOverflowMenu]) anchors from the dots-three-vertical icon —
 * caller hoists `var expanded by remember { mutableStateOf(false) }` and renders
 * the menu inside a [Box] alongside this bar.
 *
 * Move/Copy/Remove use [OrbitButtonVariant.Ghost] — voice rule: batch-mode
 * actions are choices, not destructive primaries. Destructive confirmation
 * lives downstream (Snackbar undo for Remove; ListSelectorSheet for Move/Copy).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MultiSelectActionBar(
    count: Int,
    onExit: () -> Unit,
    onMove: () -> Unit,
    onCopy: () -> Unit,
    onRemove: () -> Unit,
    onOverflow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(OrbitTheme.colors.bg),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                // Min, not fixed: the count wraps at 200% font scale.
                .heightIn(min = OrbitTheme.spacing.x9)
                .padding(horizontal = OrbitTheme.spacing.x2),
        ) {
            OrbitIconButton(
                icon = "x",
                onClick = onExit,
                contentDescription = stringResource(R.string.browse_select_exit),
            )
            Text(
                text = pluralStringResource(R.plurals.browse_selected_count, count, count),
                style = OrbitTheme.type.h3.copy(color = OrbitTheme.colors.fg),
                modifier = Modifier
                    .weight(1f)
                    .padding(start = OrbitTheme.spacing.x1)
                    // It names the mode the screen is in, as the app bar title
                    // it replaces does.
                    .semantics { heading() },
            )
            OrbitIconButton(
                icon = "dots-three-vertical",
                onClick = onOverflow,
                contentDescription = stringResource(R.string.browse_select_more),
            )
        }
        // A flow row, not equal weights: at 200% font scale a third of the
        // width is narrower than "Remove", which then broke mid-word. Here a
        // button that doesn't fit moves to the next line whole.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x1),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = OrbitTheme.spacing.x2, vertical = OrbitTheme.spacing.x1),
        ) {
            OrbitButton(
                text = stringResource(R.string.browse_select_move),
                onClick = onMove,
                variant = OrbitButtonVariant.Ghost,
            )
            OrbitButton(
                text = stringResource(R.string.browse_select_copy),
                onClick = onCopy,
                variant = OrbitButtonVariant.Ghost,
            )
            OrbitButton(
                text = stringResource(R.string.browse_select_remove),
                onClick = onRemove,
                variant = OrbitButtonVariant.Ghost,
            )
        }
        // 1dp hairline at bottom — gives the bar a defined edge in light mode.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(OrbitTheme.colors.line),
        )
    }
}

// region Previews

@PreviewLightDark
@PreviewFontScale
@Composable
private fun MultiSelectActionBarPreview() {
    OrbitTheme {
        Box(modifier = Modifier.background(OrbitTheme.colors.bg)) {
            MultiSelectActionBar(
                count = 7,
                onExit = {},
                onMove = {},
                onCopy = {},
                onRemove = {},
                onOverflow = {},
            )
        }
    }
}

/** The narrowest phone the rubric tests (gate G3). */
@PreviewLightDark
@Composable
private fun MultiSelectActionBarNarrowPreview() {
    OrbitTheme {
        Box(modifier = Modifier.width(360.dp).background(OrbitTheme.colors.bg)) {
            MultiSelectActionBar(
                count = 12,
                onExit = {},
                onMove = {},
                onCopy = {},
                onRemove = {},
                onOverflow = {},
            )
        }
    }
}

// endregion
