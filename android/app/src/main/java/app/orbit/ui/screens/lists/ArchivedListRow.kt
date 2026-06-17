package app.orbit.ui.screens.lists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import app.orbit.data.entity.ListType
import app.orbit.ui.components.OrbitButton
import app.orbit.ui.components.OrbitButtonVariant
import app.orbit.ui.components.PhIcon
import app.orbit.ui.theme.OrbitTheme

/**
 * Archived row composable for the collapsible "Archived (N)" section in Lists
 * Manager. NOT wrapped in `ReorderableItem` — archived
 * order is fixed (sortOrder DESC = most-recently-archived first).
 *
 * Layout: name (fg) + ruleSummary? (fgMuted) on the left, "Restore" ghost
 * button + "List settings" icon button on the right. 48dp tap targets enforced
 * via OrbitTheme.spacing.tapMin.
 */
@Composable
fun ArchivedListRow(
    tile: ListTileState,
    modifier: Modifier = Modifier,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    onConfigure: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = OrbitTheme.spacing.x4, vertical = OrbitTheme.spacing.x3),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = tile.name,
                style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fgMuted),
            )
            if (tile.ruleSummary != null) {
                Text(
                    text = tile.ruleSummary,
                    style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgSubtle),
                    modifier = Modifier.padding(top = OrbitTheme.spacing.x1 / 2),
                )
            }
        }
        Spacer(Modifier.width(OrbitTheme.spacing.x2))
        OrbitButton(
            text = "Restore",
            onClick = onRestore,
            variant = OrbitButtonVariant.Ghost,
        )
        Spacer(Modifier.width(OrbitTheme.spacing.x1))
        // D-25 — destructive trash affordance. Tap opens DeleteListDialog
        // (rendered by ListsManagerScreen). Danger tint signals destructive
        // intent; the dialog provides the actual gate.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .defaultMinSize(minWidth = OrbitTheme.spacing.tapMin, minHeight = OrbitTheme.spacing.tapMin)
                .clickable(onClick = onDelete)
                .semantics { contentDescription = "Delete ${tile.name}" },
        ) {
            PhIcon(
                name = "trash",
                size = OrbitTheme.spacing.x5 - OrbitTheme.spacing.x1,
                tint = OrbitTheme.colors.danger,
            )
        }
        Spacer(Modifier.width(OrbitTheme.spacing.x1))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .defaultMinSize(minWidth = OrbitTheme.spacing.tapMin, minHeight = OrbitTheme.spacing.tapMin)
                .clickable(onClick = onConfigure)
                .semantics { contentDescription = "List settings for ${tile.name}" },
        ) {
            PhIcon(
                name = "sliders-horizontal",
                size = OrbitTheme.spacing.x5 - OrbitTheme.spacing.x1,
                tint = OrbitTheme.colors.fgSubtle,
            )
        }
    }
}

@Preview(name = "ArchivedListRow — light")
@Composable
private fun ArchivedListRowPreviewLight() {
    app.orbit.ui.theme.OrbitTheme(darkTheme = false) {
        ArchivedListRow(
            tile = ListTileState(
                id = 9L,
                name = "Drifted",
                memberCount = 0,
                type = ListType.STATIC,
                ruleSummary = null,
            ),
            onRestore = {},
            onDelete = {},
            onConfigure = {},
        )
    }
}

@Preview(name = "ArchivedListRow — dark")
@Composable
private fun ArchivedListRowPreviewDark() {
    app.orbit.ui.theme.OrbitTheme(darkTheme = true) {
        ArchivedListRow(
            tile = ListTileState(
                id = 12L,
                name = "Recently added, not called",
                memberCount = 0,
                type = ListType.SMART,
                ruleSummary = "Recently added · 30 days",
            ),
            onRestore = {},
            onDelete = {},
            onConfigure = {},
        )
    }
}
