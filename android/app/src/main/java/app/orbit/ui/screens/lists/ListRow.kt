package app.orbit.ui.screens.lists

import android.content.res.Configuration
import android.content.res.Resources
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import app.orbit.R
import app.orbit.data.ChipTone
import app.orbit.data.entity.ListType
import app.orbit.ui.components.CountBadge
import app.orbit.ui.components.LocalPrivacyCurtain
import app.orbit.ui.components.OrbitChip
import app.orbit.ui.components.OrbitDropdownMenu
import app.orbit.ui.components.OrbitMenuAction
import app.orbit.ui.components.OrbitMenuTone
import app.orbit.ui.components.PhIcon
import app.orbit.ui.theme.OrbitTheme
import app.orbit.ui.util.UiText
import app.orbit.ui.util.asString

/**
 * Reorderable list-row composable for Lists Manager.
 *
 * Layout:
 *   ⠿  name + ruleSummary?     [Smart list]   [N]   ...
 *   |                                                |
 *   |                                                +-- overflow ([listRowMenuActions])
 *   +-- drag handle (own touch region)
 *
 * The second line is the list's rhythm as its interval ("Every 14 days",
 * "Every 3 days" for a Late night list, "Every day"; LIST-24) or, for a smart
 * list, its rule; the ViewModel decides which. [onConfigure] is the menu's
 * "List settings" and opens List settings, while [onClick] on the row opens
 * the list's deck (LIST-23): two destinations, so the screen wires them to
 * two callbacks.
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
    onToggleNudges: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onAddContacts: (() -> Unit)? = null,
) {
    @Suppress("UNUSED_VARIABLE") val draggingHint = isDragging // reserved for elevation hook
    var menuExpanded by remember { mutableStateOf(false) }
    // PRIV-03: the list's name, text and TalkBack labels alike, reads "List"
    // under the privacy curtain (it showed through on Lists until 2026-10-05).
    val shownName = if (LocalPrivacyCurtain.current) stringResource(R.string.components_curtain_list) else tile.name
    // Resolved here: the semantics blocks below are not composable.
    val reorderDescription = stringResource(R.string.lists_row_reorder)
    val addContactsDescription = stringResource(R.string.lists_row_add_people, shownName)
    val moreActionsDescription = stringResource(R.string.lists_row_more_actions, shownName)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(R.string.lists_row_open), onClick = onClick)
            .padding(horizontal = OrbitTheme.spacing.x4, vertical = OrbitTheme.spacing.x3),
    ) {
        // Drag handle — own 48dp touch region.
        Box(
            contentAlignment = Alignment.Center,
            modifier = dragHandleModifier
                .defaultMinSize(minWidth = OrbitTheme.spacing.tapMin, minHeight = OrbitTheme.spacing.tapMin)
                .semantics { contentDescription = reorderDescription },
        ) {
            PhIcon(
                name = "dots-six-vertical",
                size = OrbitTheme.spacing.x5 - OrbitTheme.spacing.x1, // 16dp visual; tap region stays 48dp
                tint = OrbitTheme.colors.fgSubtle,
            )
        }
        Spacer(Modifier.width(OrbitTheme.spacing.x2))
        // At large font scales the "Smart list" chip stacks under the name
        // instead of sitting beside it: side by side, the chip and the
        // trailing controls left the name a column so narrow it broke
        // mid-word at 200% (rubric gate G3; Home and Card view stack at the
        // same threshold).
        val largeText = LocalDensity.current.fontScale > 1.3f
        val stackChip = largeText && tile.type == ListType.SMART
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = shownName,
                style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
            )
            if (tile.ruleSummary != null) {
                Text(
                    text = tile.ruleSummary.asString(),
                    style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
                    modifier = Modifier.padding(top = OrbitTheme.spacing.x1 / 2),
                )
            }
            if (stackChip) {
                OrbitChip(
                    label = stringResource(R.string.lists_row_smart_chip),
                    tone = ChipTone.Terracotta,
                    modifier = Modifier.padding(top = OrbitTheme.spacing.x2),
                )
            }
        }
        if (tile.type == ListType.SMART && !stackChip) {
            Spacer(Modifier.width(OrbitTheme.spacing.x2))
            OrbitChip(label = stringResource(R.string.lists_row_smart_chip), tone = ChipTone.Terracotta)
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
                    .semantics { contentDescription = addContactsDescription },
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
                .semantics { contentDescription = moreActionsDescription },
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
                    resources = LocalContext.current.resources,
                    listName = shownName,
                    notificationsEnabled = tile.notificationsEnabled,
                    onRename = onRename,
                    onConfigure = onConfigure,
                    onToggleNudges = onToggleNudges,
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
 * unit-testable (see `ListRowMenuOrderTest`). Labels come from [resources]
 * because [OrbitMenuAction] carries resolved text.
 *
 * 2026-08-15 UAT — "Archive" used to sit second from the top in plain fg,
 * one slip away from a tap meant for "List settings". Everyday actions now
 * lead (rename → settings → nudges → the a11y reorder fallbacks) and Archive
 * sinks below the divider in danger, per the [OrbitMenuTone] contract.
 *
 * "Pause nudges" / "Resume nudges" and the Archive line use Home's strings on
 * purpose: Lists is the full manager and Home the convenience surface for the
 * same list, and the spec holds the two menus to the same labels (LIST-23).
 * Pausing a list's nudges used to be possible from Home but not from here.
 */
internal fun listRowMenuActions(
    resources: Resources,
    listName: String,
    notificationsEnabled: Boolean,
    onRename: () -> Unit,
    onConfigure: () -> Unit,
    onToggleNudges: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onArchive: () -> Unit,
): List<OrbitMenuAction> = listOf(
    OrbitMenuAction(label = resources.getString(R.string.lists_menu_rename), onClick = onRename),
    OrbitMenuAction(label = resources.getString(R.string.lists_menu_list_settings), onClick = onConfigure),
    OrbitMenuAction(
        label = resources.getString(
            if (notificationsEnabled) R.string.home_menu_pause_nudges else R.string.home_menu_resume_nudges,
        ),
        onClick = onToggleNudges,
    ),
    // Accessibility fallback for keyboard / TalkBack reorder (UI-SPEC).
    OrbitMenuAction(label = resources.getString(R.string.lists_menu_move_up), onClick = onMoveUp),
    OrbitMenuAction(label = resources.getString(R.string.lists_menu_move_down), onClick = onMoveDown),
    OrbitMenuAction(
        label = resources.getString(R.string.components_action_archive),
        onClick = onArchive,
        tone = OrbitMenuTone.Destructive,
        supporting = resources.getString(R.string.components_menu_archive_supporting, listName),
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
                ruleSummary = UiText.plural(R.plurals.lists_interval_every_days, 7, 7),
            ),
            isDragging = false,
            onClick = {},
            onRename = {},
            onArchive = {},
            onConfigure = {},
            onToggleNudges = {},
            onMoveUp = {},
            onMoveDown = {},
        )
    }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES, name = "ListRow — dark, smart")
@Composable
private fun ListRowPreviewDarkSmart() {
    app.orbit.ui.theme.OrbitTheme(darkTheme = true) {
        ListRow(
            tile = ListTileState(
                id = 2L,
                name = "Recently added, not called",
                memberCount = 0,
                type = ListType.SMART,
                ruleSummary = UiText.plural(R.plurals.lists_rule_summary_recently_added, 30, 30),
                notificationsEnabled = false,
            ),
            isDragging = false,
            onClick = {},
            onRename = {},
            onArchive = {},
            onConfigure = {},
            onToggleNudges = {},
            onMoveUp = {},
            onMoveDown = {},
        )
    }
}
