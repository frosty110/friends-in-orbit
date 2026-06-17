package app.orbit.ui.screens.picker

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.orbit.ui.components.OrbitButton
import app.orbit.ui.theme.OrbitTheme

/**
 * Batch-action bottom bar (PICK-06). Docked as a bottom bar rather than a
 * floating card so it no longer overlaps the last list rows (a UI bug where
 * it blocked the list behind it). Rendered as the final sibling in the picker
 * Column; the list area takes the remaining height, so nothing is hidden
 * underneath.
 *
 * Hidden entirely when [selectionCount] == 0. Otherwise a
 * full-width surface bar with a top hairline carries: "{N} selected" text, a
 * Ghost "Clear" affordance on the left, and a Primary mode-driven CTA on the
 * right.
 *
 * CTA copy — singular variants
 * use the literal "1" (e.g. "Add 1 to Inner orbit"):
 *   - [PickerMode.Add]  → "Add {N} to {targetListName}"
 *   - [PickerMode.Move] → "Move {N} to {targetListName}"
 *   - [PickerMode.Copy] → "Copy {N} to {targetListName}"
 *
 * Disabled (greyed) when [isCommitting] is true — prevents double-tap commit
 * during an in-flight write.
 */
@Composable
fun BatchCounter(
    selectionCount: Int,
    targetListName: String,
    mode: PickerMode,
    isCommitting: Boolean,
    onClear: () -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selectionCount == 0) return

    val ctaCopy: String = when (mode) {
        PickerMode.Add -> "Add $selectionCount to $targetListName"
        PickerMode.Move -> "Move $selectionCount to $targetListName"
        PickerMode.Copy -> "Copy $selectionCount to $targetListName"
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(OrbitTheme.colors.surface),
    ) {
        // Top hairline separates the bar from the list it docks beneath.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(OrbitTheme.colors.lineSoft),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x3),
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = OrbitTheme.spacing.x4,
                    vertical = OrbitTheme.spacing.x3,
                ),
        ) {
            Text(
                text = "$selectionCount selected",
                style = OrbitTheme.type.body,
                color = OrbitTheme.colors.fg,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "Clear",
                style = OrbitTheme.type.button,
                color = OrbitTheme.colors.fgMuted,
                modifier = Modifier
                    .clickable(enabled = !isCommitting, onClick = onClear)
                    .padding(horizontal = OrbitTheme.spacing.x2, vertical = OrbitTheme.spacing.x2),
            )
            OrbitButton(
                text = ctaCopy,
                onClick = onCommit,
                enabled = !isCommitting,
            )
        }
    }
}

@Preview(name = "BatchCounter — Add light", showBackground = true)
@Composable
private fun BatchCounterAddPreviewLight() {
    OrbitTheme(darkTheme = false) {
        BatchCounter(
            selectionCount = 12,
            targetListName = "Inner orbit",
            mode = PickerMode.Add,
            isCommitting = false,
            onClear = {},
            onCommit = {},
        )
    }
}

@Preview(name = "BatchCounter — Move dark", showBackground = true)
@Composable
private fun BatchCounterMovePreviewDark() {
    OrbitTheme(darkTheme = true) {
        BatchCounter(
            selectionCount = 7,
            targetListName = "Late night",
            mode = PickerMode.Move,
            isCommitting = false,
            onClear = {},
            onCommit = {},
        )
    }
}

@Preview(name = "BatchCounter — Copy single light", showBackground = true)
@Composable
private fun BatchCounterCopyPreviewLight() {
    OrbitTheme(darkTheme = false) {
        BatchCounter(
            selectionCount = 1,
            targetListName = "People who ground me",
            mode = PickerMode.Copy,
            isCommitting = false,
            onClear = {},
            onCommit = {},
        )
    }
}
