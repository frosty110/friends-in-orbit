package app.orbit.ui.screens.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.orbit.ui.components.OrbitDropdownMenu
import app.orbit.ui.components.OrbitMenuAction
import app.orbit.ui.components.OrbitMenuTone
import app.orbit.ui.theme.OrbitTheme

/**
 * Secondary batch actions for the [MultiSelectActionBar]
 * overflow. Anchored from the bar's `dots-three-vertical` icon via the shared
 * [OrbitDropdownMenu].
 *
 * Copy / order (shared [OrbitDropdownMenu] contract — destructive last):
 *  - Item 1: "Pause all" + leading icon `pause-circle`
 *  - Item 2: "Ignore all" + leading icon `eye-slash`, in danger
 *
 * Caller hoists the `expanded` state and supplies the dismiss / per-item
 * lambdas; the menu component is purely presentational.
 *
 * Voice gate: sentence case, no exclamation marks. The copy stays neutral; the
 * destructive weight is carried by the tone and position, not by louder words.
 */
@Composable
fun MultiSelectOverflowMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onIgnoreAll: () -> Unit,
    onPauseAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OrbitDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = modifier,
        actions = listOf(
            OrbitMenuAction(
                label = "Pause all",
                onClick = onPauseAll,
                icon = "pause-circle",
            ),
            OrbitMenuAction(
                label = "Ignore all",
                onClick = onIgnoreAll,
                icon = "eye-slash",
                tone = OrbitMenuTone.Destructive,
            ),
        ),
    )
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
