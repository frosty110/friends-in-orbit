package app.orbit.ui.screens.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.orbit.ui.components.PhIcon
import app.orbit.ui.theme.OrbitTheme

/**
 * Secondary batch actions for the [MultiSelectActionBar]
 * overflow. Material3 [DropdownMenu] anchored from the bar's `dots-three-vertical` icon.
 *
 * Copy:
 *  - Item 1: "Ignore all" + leading icon `eye-slash`
 *  - Item 2: "Pause all" + leading icon `pause-circle`
 *
 * Caller hoists the `expanded` state and supplies the dismiss / per-item
 * lambdas; the menu component is purely presentational.
 *
 * Voice gate: sentence case, no exclamation marks, choices are framed neutrally
 * (the destructive intent is conveyed by the icon family, not by the copy).
 */
@Composable
fun MultiSelectOverflowMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onIgnoreAll: () -> Unit,
    onPauseAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = modifier.background(OrbitTheme.colors.surface),
    ) {
        DropdownMenuItem(
            text = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PhIcon(name = "eye-slash", size = 18.dp, tint = OrbitTheme.colors.fg)
                    Text(
                        text = "Ignore all",
                        style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
                    )
                }
            },
            onClick = onIgnoreAll,
        )
        DropdownMenuItem(
            text = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PhIcon(name = "pause-circle", size = 18.dp, tint = OrbitTheme.colors.fg)
                    Text(
                        text = "Pause all",
                        style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
                    )
                }
            },
            onClick = onPauseAll,
        )
    }
}

// region Previews

@Preview(name = "MultiSelectOverflowMenu — light, expanded", showBackground = true)
@Composable
private fun MultiSelectOverflowMenuLightPreview() {
    OrbitTheme(darkTheme = false) {
        Box(modifier = Modifier
            .background(OrbitTheme.colors.bg)
            .padding(24.dp)) {
            MultiSelectOverflowMenu(
                expanded = true,
                onDismiss = {},
                onIgnoreAll = {},
                onPauseAll = {},
            )
        }
    }
}

@Preview(name = "MultiSelectOverflowMenu — dark, expanded", showBackground = true)
@Composable
private fun MultiSelectOverflowMenuDarkPreview() {
    OrbitTheme(darkTheme = true) {
        Box(modifier = Modifier
            .background(OrbitTheme.colors.bg)
            .padding(24.dp)) {
            MultiSelectOverflowMenu(
                expanded = true,
                onDismiss = {},
                onIgnoreAll = {},
                onPauseAll = {},
            )
        }
    }
}

// endregion
