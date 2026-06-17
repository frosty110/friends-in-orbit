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
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.orbit.data.entity.ListEntity
import app.orbit.ui.theme.OrbitTheme

/**
 * Inline target-list picker for the multi-select Move/Copy
 * actions on Browse. Material3 [ModalBottomSheet] (`skipPartiallyExpanded = true`)
 * hosting a [LazyColumn] of non-archived [ListEntity] rows.
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
 * For [Mode.Move] the source list (`currentListId`) is filtered out — you cannot
 * move to source. For [Mode.Copy] all non-archived lists show.
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

    val title = when (mode) {
        Mode.Move -> "Move to which list?"
        Mode.Copy -> "Copy to which list?"
    }

    val visibleLists = lists.filter { entity ->
        !entity.isArchived && (mode == Mode.Copy || entity.id != currentListId)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = OrbitTheme.colors.surface,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = OrbitTheme.spacing.x4,
                    vertical = OrbitTheme.spacing.x3,
                ),
        ) {
            Text(
                text = title,
                style = OrbitTheme.type.h3.copy(color = OrbitTheme.colors.fg),
                modifier = Modifier.padding(bottom = OrbitTheme.spacing.x3),
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
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

@Composable
private fun ListSelectorRow(name: String, onPick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = OrbitTheme.spacing.tapMin)
            .clickable(onClick = onPick)
            .padding(
                horizontal = OrbitTheme.spacing.x2,
                vertical = OrbitTheme.spacing.x3,
            ),
    ) {
        Text(
            text = name,
            style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
        )
    }
}

enum class Mode { Move, Copy }

// region Previews

@Preview(name = "ListSelectorSheet — light, Move mode", showBackground = true)
@Composable
private fun ListSelectorSheetLightPreview() {
    OrbitTheme(darkTheme = false) {
        Box(modifier = Modifier.background(OrbitTheme.colors.bg)) {
            // Sheet renders empty in preview (ModalBottomSheet host requires real
            // window) — use a static placeholder column so the row layout shows.
            Column(modifier = Modifier
                .fillMaxWidth()
                .background(OrbitTheme.colors.surface)
                .padding(OrbitTheme.spacing.x4)) {
                Text(
                    text = "Move to which list?",
                    style = OrbitTheme.type.h3.copy(color = OrbitTheme.colors.fg),
                    modifier = Modifier.padding(bottom = OrbitTheme.spacing.x3),
                )
                listOf("Inner orbit", "Late night", "Family", "Mentors").forEach { name ->
                    ListSelectorRow(name = name, onPick = {})
                }
            }
        }
    }
}

// endregion
