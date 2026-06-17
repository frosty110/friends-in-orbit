package app.orbit.ui.screens.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import app.orbit.ui.components.OrbitButtonVariant
import app.orbit.ui.components.OrbitIconButton
import app.orbit.ui.theme.OrbitTheme

/**
 * Replaces [app.orbit.ui.components.OrbitAppBar] when Browse is in multi-select
 * mode. Same 56dp height as [OrbitAppBar] (`AppBar.kt:31`) so the
 * `AnimatedContent` cross-fade swap doesn't reflow the layout (it replaces the
 * app bar in place, not floating).
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
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .background(OrbitTheme.colors.bg)
                .padding(start = 8.dp, end = 8.dp),
        ) {
            OrbitIconButton(
                icon = "x",
                onClick = onExit,
                contentDescription = "Exit selection",
            )
            Text(
                text = "$count selected",
                style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
                modifier = Modifier.padding(start = 4.dp),
            )
            Spacer(modifier = Modifier.weight(1f))
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OrbitButton(
                    text = "Move to…",
                    onClick = onMove,
                    variant = OrbitButtonVariant.Ghost,
                    height = 40.dp,
                )
                OrbitButton(
                    text = "Copy to…",
                    onClick = onCopy,
                    variant = OrbitButtonVariant.Ghost,
                    height = 40.dp,
                )
                OrbitButton(
                    text = "Remove",
                    onClick = onRemove,
                    variant = OrbitButtonVariant.Ghost,
                    height = 40.dp,
                )
                OrbitIconButton(
                    icon = "dots-three-vertical",
                    onClick = onOverflow,
                    contentDescription = "More batch actions",
                )
            }
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

@Preview(name = "MultiSelectActionBar — light, 7 selected", showBackground = true)
@Composable
private fun MultiSelectActionBarLightPreview() {
    OrbitTheme(darkTheme = false) {
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

@Preview(name = "MultiSelectActionBar — dark, 1 selected", showBackground = true)
@Composable
private fun MultiSelectActionBarDarkPreview() {
    OrbitTheme(darkTheme = true) {
        Box(modifier = Modifier.background(OrbitTheme.colors.bg)) {
            MultiSelectActionBar(
                count = 1,
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
