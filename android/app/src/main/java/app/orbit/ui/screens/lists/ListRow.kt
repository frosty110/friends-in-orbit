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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import app.orbit.data.ChipTone
import app.orbit.data.entity.ListType
import app.orbit.ui.components.CountBadge
import app.orbit.ui.components.OrbitChip
import app.orbit.ui.components.OrbitDropdownMenu
import app.orbit.ui.components.OrbitMenuAction
import app.orbit.ui.components.OrbitMenuTone
import app.orbit.ui.components.PhIcon
import app.orbit.ui.theme.OrbitTheme

/**
 * Reorderable list-row composable for Lists Manager.
 *
 * Layout:
 *   ⠿  name + ruleSummary?     [Smart list]   [N]   ...
 *   |                                                |
 *   |                                                +-- overflow ([listRowMenuActions])
 *   +-- drag handle (own touch region)
 *
 * The drag handle owns its own [Box] with
 * [dragHandleModifier] (caller passes `Modifier.draggableHandle()` from the
 * sh.calvin.reorderable scope). The handle's clickable surface does NOT
 * inherit the row-level `clickable` — its 48x48dp touch region consumes drag
 * gestures so a tap on the handle never opens the row.
 *
 * The caller wraps each ReorderableItem content with
 * `Modifier.animateItem()` (NOT the older deprecated placement-animation API).
 */
@Composable
fun ListRow(
    tile: ListTileState,
    isDragging: Boolean,
    modifier: Modifier = Modifier,
    dragHandleModifier: Modifier = Modifier,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onArchive: () -> Unit,
    onConfigure: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onAddContacts: (() -> Unit)? = null,
) {
    @Suppress("UNUSED_VARIABLE") val draggingHint = isDragging // reserved for elevation hook
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = OrbitTheme.spacing.x4, vertical = OrbitTheme.spacing.x3),
    ) {
        // Drag handle — own 48dp touch region.
        Box(
            contentAlignment = Alignment.Center,
            modifier = dragHandleModifier
                .defaultMinSize(minWidth = OrbitTheme.spacing.tapMin, minHeight = OrbitTheme.spacing.tapMin)
                .semantics { contentDescription = "Reorder list" },
        ) {
            PhIcon(
                name = "dots-six-vertical",
                size = OrbitTheme.spacing.x5 - OrbitTheme.spacing.x1, // 16dp visual; tap region stays 48dp
                tint = OrbitTheme.colors.fgSubtle,
            )
        }
        Spacer(Modifier.width(OrbitTheme.spacing.x2))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = tile.name,
                style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
            )
            if (tile.ruleSummary != null) {
                Text(
                    text = tile.ruleSummary,
                    style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
                    modifier = Modifier.padding(top = OrbitTheme.spacing.x1 / 2),
                )
            }
        }
        if (tile.type == ListType.SMART) {
            Spacer(Modifier.width(OrbitTheme.spacing.x2))
            OrbitChip(label = "Smart list", tone = ChipTone.Terracotta)
        }
        if (tile.memberCount > 0) {
            Spacer(Modifier.width(OrbitTheme.spacing.x2))
            CountBadge(count = tile.memberCount)
        }
        Spacer(Modifier.width(OrbitTheme.spacing.x2))
        // BULK-05 — "Add contacts" entry. Trailing "+" affordance on
        // each active list row; routes to Routes.pickContacts(listId, "add").
        // Optional (null on archived rows / smart lists where membership is
        // rule-derived, not user-curated).
        if (onAddContacts != null && tile.type != ListType.SMART) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .defaultMinSize(minWidth = OrbitTheme.spacing.tapMin, minHeight = OrbitTheme.spacing.tapMin)
                    .clickable(onClick = onAddContacts)
                    .semantics { contentDescription = "Add contacts to ${tile.name}" },
            ) {
                PhIcon(
                    name = "plus",
                    size = OrbitTheme.spacing.x5 - OrbitTheme.spacing.x1,
                    tint = OrbitTheme.colors.fgMuted,
                )
            }
        }
        // Overflow "..." menu — own touch region, parent row clickable does not consume.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .defaultMinSize(minWidth = OrbitTheme.spacing.tapMin, minHeight = OrbitTheme.spacing.tapMin)
                .clickable { menuExpanded = true }
                .semantics { contentDescription = "More actions for ${tile.name}" },
        ) {
            PhIcon(
                name = "dots-three-vertical",
                size = OrbitTheme.spacing.x5 - OrbitTheme.spacing.x1,
                tint = OrbitTheme.colors.fgSubtle,
            )
            OrbitDropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                actions = listRowMenuActions(
                    listName = tile.name,
                    onRename = onRename,
                    onConfigure = onConfigure,
                    onMoveUp = onMoveUp,
                    onMoveDown = onMoveDown,
                    onArchive = onArchive,
                ),
            )
        }
    }
}

/**
 * The active-row overflow menu, as data so the ordering contract is
 * unit-testable (see `ListRowMenuOrderTest`).
 *
 * 2026-08-15 UAT — "Archive" used to sit second from the top in plain fg,
 * one slip away from a tap meant for "List settings". Everyday actions now
 * lead (rename → settings → the a11y reorder fallbacks) and Archive sinks
 * below the divider in danger, per the [OrbitMenuTone] contract.
 */
internal fun listRowMenuActions(
    listName: String,
    onRename: () -> Unit,
    onConfigure: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onArchive: () -> Unit,
): List<OrbitMenuAction> = listOf(
    OrbitMenuAction(label = "Rename", onClick = onRename),
    OrbitMenuAction(label = "List settings", onClick = onConfigure),
    // Accessibility fallback for keyboard / TalkBack reorder (UI-SPEC).
    OrbitMenuAction(label = "Move up", onClick = onMoveUp),
    OrbitMenuAction(label = "Move down", onClick = onMoveDown),
    OrbitMenuAction(
        label = "Archive",
        onClick = onArchive,
        tone = OrbitMenuTone.Destructive,
        supporting = "Hides $listName from home. You can restore it.",
    ),
)

@Preview(name = "ListRow — light, static")
@Composable
private fun ListRowPreviewLightStatic() {
    app.orbit.ui.theme.OrbitTheme(darkTheme = false) {
        ListRow(
            tile = ListTileState(
                id = 1L,
                name = "Inner orbit",
                memberCount = 12,
                type = ListType.STATIC,
                ruleSummary = null,
            ),
            isDragging = false,
            onClick = {},
            onRename = {},
            onArchive = {},
            onConfigure = {},
            onMoveUp = {},
            onMoveDown = {},
        )
    }
}

@Preview(name = "ListRow — dark, smart")
@Composable
private fun ListRowPreviewDarkSmart() {
    app.orbit.ui.theme.OrbitTheme(darkTheme = true) {
        ListRow(
            tile = ListTileState(
                id = 2L,
                name = "Recently added, not called",
                memberCount = 0,
                type = ListType.SMART,
                ruleSummary = "Recently added · 30 days",
            ),
            isDragging = false,
            onClick = {},
            onRename = {},
            onArchive = {},
            onConfigure = {},
            onMoveUp = {},
            onMoveDown = {},
        )
    }
}
