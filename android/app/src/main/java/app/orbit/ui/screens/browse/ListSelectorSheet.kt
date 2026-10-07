package app.orbit.ui.screens.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import app.orbit.R
import app.orbit.data.entity.ListEntity
import app.orbit.data.entity.ListType
import app.orbit.ui.components.LocalPrivacyCurtain
import app.orbit.ui.components.OrbitButton
import app.orbit.ui.components.OrbitButtonVariant
import app.orbit.ui.theme.OrbitTheme

/**
 * Inline target-list picker for the multi-select Move/Copy
 * actions on Browse. Material3 [ModalBottomSheet] (`skipPartiallyExpanded = true`)
 * hosting a [LazyColumn] of the lists the selection can go to.
 *
 * Move/Copy v1 dispatches via this inline sheet, NOT
 * via nav-to-picker. The full-screen picker still ships for the BULK-05 "Add"
 * entry from Lists Manager + per-list Browse trailing "+" — that flow does NOT
 * carry pre-selected ids. Browse multi-select Move/Copy uses this sheet
 * because the BULK-05 picker would have to receive the selectedIds via nav
 * arg, and `LongArray` over a route URL is fragile; the use case takes
 * `(sourceListId, targetListId, contactIds)` directly.
 *
 * Title copy:
 *  - [Mode.Move]: "Move to which list?"
 *  - [Mode.Copy]: "Copy to which list?"
 *
 * Which lists show, in both modes (browse-1, browse-7): regular
 * ([ListType.STATIC]), non-archived lists other than `currentListId`. A smart
 * list is never a target: its rows are written by `SmartListMembershipSync`,
 * so the sync's next reconcile would silently remove whoever does not match
 * its rule, after the Undo window, and a Move had already taken them off the
 * source list (features/orbit-lists/README.md). The source list is left out of
 * Copy too: `CopyContactsUseCase` reports the full count even when every id is
 * already a member, so "Copy to…" the current list said "Copied 3 to Family"
 * with an Undo that did nothing. The use cases guard the same rule, so a list
 * that changes between the sheet and the write still fails loudly.
 *
 * When nothing qualifies (a new user with one list) the sheet says so instead
 * of showing a title over nothing: "No other lists yet" / "Make another list
 * first." with a Ghost Done (rubric gate G4, no dead ends).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListSelectorSheet(
    mode: Mode,
    lists: List<ListEntity>,
    currentListId: Long?,
    onPick: (targetListId: Long, targetListName: String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = OrbitTheme.colors.surface,
        // Token, not literal: the top-rounded sheet shape lives in Shape.kt
        // (the PauseDurationSheet and LogConnectionSheet precedent).
        shape = OrbitTheme.shapes.bottomSheet,
        modifier = modifier,
    ) {
        ListSelectorSheetContent(
            mode = mode,
            lists = lists,
            currentListId = currentListId,
            onPick = onPick,
            onDone = onDismiss,
        )
    }
}

/**
 * The sheet's content, also what the previews render: Material's
 * [ModalBottomSheet] is window-anchored and draws nothing inside `@Preview`,
 * and one content composable means the preview cannot drift from the live
 * sheet (the PauseDurationSheet pattern).
 */
@Composable
internal fun ListSelectorSheetContent(
    mode: Mode,
    lists: List<ListEntity>,
    currentListId: Long?,
    onPick: (targetListId: Long, targetListName: String) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(
        when (mode) {
            Mode.Move -> R.string.browse_move_to_which_list
            Mode.Copy -> R.string.browse_copy_to_which_list
        },
    )

    val visibleLists = lists.filter { entity ->
        !entity.isArchived && entity.type == ListType.STATIC && entity.id != currentListId
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = OrbitTheme.spacing.x4,
                vertical = OrbitTheme.spacing.x3,
            ),
    ) {
        Text(
            text = title,
            style = OrbitTheme.type.h3.copy(color = OrbitTheme.colors.fg),
            modifier = Modifier
                .padding(bottom = OrbitTheme.spacing.x3)
                // A heading, so TalkBack users land on the question (the
                // ListsManagerScreen sheet does the same).
                .semantics { heading() },
        )
        if (visibleLists.isEmpty()) {
            NoTargetsMessage(onDone = onDone)
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    // A cap so a long list of lists scrolls inside the sheet
                    // instead of pushing the title off the top; the value is
                    // about six rows, a layout bound rather than a spacing
                    // token, the way BrowseRow sizes its position column.
                    .heightIn(max = 320.dp),
            ) {
                items(
                    items = visibleLists,
                    key = { it.id },
                    contentType = { "listSelectorRow" },
                ) { entity ->
                    ListSelectorRow(
                        name = entity.name,
                        onPick = { onPick(entity.id, entity.name) },
                    )
                }
            }
        }
    }
}

/** The sheet with nothing to offer: say so, and give one way out. */
@Composable
private fun NoTargetsMessage(onDone: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = OrbitTheme.spacing.x3),
    ) {
        Text(
            text = stringResource(R.string.browse_move_no_targets_title),
            style = OrbitTheme.type.body,
            color = OrbitTheme.colors.fg,
        )
        Text(
            text = stringResource(R.string.browse_move_no_targets_body),
            style = OrbitTheme.type.body,
            color = OrbitTheme.colors.fgMuted,
            modifier = Modifier.padding(top = OrbitTheme.spacing.x1),
        )
        OrbitButton(
            text = stringResource(R.string.components_action_done),
            onClick = onDone,
            variant = OrbitButtonVariant.Ghost,
            modifier = Modifier.padding(top = OrbitTheme.spacing.x3),
        )
    }
}

@Composable
private fun ListSelectorRow(name: String, onPick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = OrbitTheme.spacing.tapMin)
            .clip(OrbitTheme.shapes.md)
            // A button for TalkBack, not plain clickable text (DESIGN.md, the
            // OrbitButton row).
            .clickable(role = Role.Button, onClick = onPick)
            .padding(
                horizontal = OrbitTheme.spacing.x2,
                vertical = OrbitTheme.spacing.x3,
            ),
    ) {
        Text(
            // PRIV-03: list names read "List" under the curtain.
            text = if (LocalPrivacyCurtain.current) stringResource(R.string.components_curtain_list) else name,
            style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
        )
    }
}

enum class Mode { Move, Copy }

// region Previews

private val previewLists = listOf(
    ListEntity(id = 1L, name = "Inner orbit", sortOrder = 0),
    ListEntity(id = 2L, name = "Late night", sortOrder = 1, type = ListType.SMART),
    ListEntity(id = 3L, name = "Family", sortOrder = 2),
    ListEntity(id = 4L, name = "Mentors", sortOrder = 3),
    ListEntity(id = 5L, name = "Old crowd", sortOrder = 4, isArchived = true),
)

/** Move from Inner orbit: Family and Mentors; the smart and archived lists are left out. */
@PreviewLightDark
@PreviewFontScale
@Composable
private fun ListSelectorSheetPreview() {
    OrbitTheme {
        Box(modifier = Modifier.background(OrbitTheme.colors.bg)) {
            ListSelectorSheetContent(
                mode = Mode.Move,
                lists = previewLists,
                currentListId = 1L,
                onPick = { _, _ -> },
                onDone = {},
                modifier = Modifier.background(OrbitTheme.colors.surface),
            )
        }
    }
}

/** One list only: nowhere to move to yet. */
@PreviewLightDark
@Composable
private fun ListSelectorSheetNoTargetsPreview() {
    OrbitTheme {
        Box(modifier = Modifier.background(OrbitTheme.colors.bg)) {
            ListSelectorSheetContent(
                mode = Mode.Copy,
                lists = previewLists.take(1),
                currentListId = 1L,
                onPick = { _, _ -> },
                onDone = {},
                modifier = Modifier.background(OrbitTheme.colors.surface),
            )
        }
    }
}

// endregion
