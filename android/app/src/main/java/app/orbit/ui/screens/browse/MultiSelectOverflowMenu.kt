package app.orbit.ui.screens.browse

import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.PreviewLightDark
import app.orbit.R
import app.orbit.ui.components.OrbitDropdownMenu
import app.orbit.ui.components.OrbitMenuAction
import app.orbit.ui.components.OrbitMenuTone
import app.orbit.ui.theme.OrbitTheme

/**
 * Secondary batch actions for the [MultiSelectActionBar]
 * overflow. Anchored from the bar's `dots-three-vertical` icon via the shared
 * [OrbitDropdownMenu].
 *
 * Order (shared [OrbitDropdownMenu] contract: everyday first, destructive last):
 *  - "Select all" + `check-circle`: every row the current search and filters
 *    match, on screen or not (MOVE-05). Until 2026-10-06 the ViewModel had the
 *    method and the README the requirement, but no control reached it
 *    (browse-3). It is the one item that stays enabled while the selection is
 *    empty, which is what an entry from the app bar's Select starts with.
 *  - "Pause all" + `pause-circle`
 *  - "Ignore all" + `eye-slash`, in danger
 *
 * [hasSelection] false disables Pause all and Ignore all: a bulk write on an
 * empty set would be a silent no-op (rules.md Code 3). [enabled] false (a bulk
 * write in flight) disables all three.
 *
 * Caller hoists the `expanded` state and supplies the dismiss / per-item
 * lambdas; the menu component is purely presentational. The action list is
 * [multiSelectOverflowActions], `internal` so the order, tones and enabled
 * states are unit-tested (MultiSelectOverflowMenuTest), the
 * `browseRowMenuActions` precedent.
 *
 * Voice gate: sentence case, no exclamation marks. The copy stays neutral; the
 * destructive weight is carried by the tone and position, not by louder words.
 */
@Composable
fun MultiSelectOverflowMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onSelectAll: () -> Unit,
    onPauseAll: () -> Unit,
    onIgnoreAll: () -> Unit,
    modifier: Modifier = Modifier,
    hasSelection: Boolean = true,
    enabled: Boolean = true,
) {
    OrbitDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = modifier,
        actions = multiSelectOverflowActions(
            resources = LocalContext.current.resources,
            hasSelection = hasSelection,
            enabled = enabled,
            onSelectAll = onSelectAll,
            onPauseAll = onPauseAll,
            onIgnoreAll = onIgnoreAll,
        ),
    )
}

/**
 * The overflow's actions in the caller's order; [OrbitDropdownMenu] sinks the
 * destructive one last. Takes [Resources] because [OrbitMenuAction] carries
 * resolved text.
 */
internal fun multiSelectOverflowActions(
    resources: Resources,
    hasSelection: Boolean,
    enabled: Boolean,
    onSelectAll: () -> Unit,
    onPauseAll: () -> Unit,
    onIgnoreAll: () -> Unit,
): List<OrbitMenuAction> = listOf(
    OrbitMenuAction(
        label = resources.getString(R.string.browse_select_all),
        onClick = onSelectAll,
        icon = "check-circle",
        enabled = enabled,
    ),
    OrbitMenuAction(
        label = resources.getString(R.string.browse_select_pause_all),
        onClick = onPauseAll,
        icon = "pause-circle",
        enabled = enabled && hasSelection,
    ),
    OrbitMenuAction(
        label = resources.getString(R.string.browse_select_ignore_all),
        onClick = onIgnoreAll,
        icon = "eye-slash",
        tone = OrbitMenuTone.Destructive,
        enabled = enabled && hasSelection,
    ),
)

// region Previews

@PreviewLightDark
@Composable
private fun MultiSelectOverflowMenuPreview() {
    OrbitTheme {
        Box(
            modifier = Modifier
                .background(OrbitTheme.colors.bg)
                .padding(OrbitTheme.spacing.x6),
        ) {
            MultiSelectOverflowMenu(
                expanded = true,
                onDismiss = {},
                onSelectAll = {},
                onPauseAll = {},
                onIgnoreAll = {},
            )
        }
    }
}

/** Nothing selected yet: only Select all is live. */
@PreviewLightDark
@Composable
private fun MultiSelectOverflowMenuEmptySelectionPreview() {
    OrbitTheme {
        Box(
            modifier = Modifier
                .background(OrbitTheme.colors.bg)
                .padding(OrbitTheme.spacing.x6),
        ) {
            MultiSelectOverflowMenu(
                expanded = true,
                onDismiss = {},
                onSelectAll = {},
                onPauseAll = {},
                onIgnoreAll = {},
                hasSelection = false,
            )
        }
    }
}

// endregion
