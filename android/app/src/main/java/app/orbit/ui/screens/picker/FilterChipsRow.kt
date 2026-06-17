package app.orbit.ui.screens.picker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.orbit.ui.components.PhIcon
import app.orbit.ui.theme.OrbitTheme

/**
 * Dynamic picker filter chips.
 *
 * Two stacked regions (UI bug fix — "filters need some work"):
 *   1. **Applied filters** (FlowRow, wraps, always fully visible): every active
 *      chip — including an active "In: {list}" — rendered with a trailing ✕ so
 *      the user can always *see and clear* what's filtering the list without
 *      hunting through a scroll. Hidden entirely when nothing is active.
 *   2. **Available filters** (LazyRow, horizontally scrollable): the unselected
 *      chips. Each carries a live count; a chip whose count is 0 under the
 *      current search/ignored context is **disabled and sorted to the end** so
 *      the actionable filters stay up front. The "In list…" dropdown chip
 *      trails the row (only when no list filter is already applied).
 *
 * "Recently added" is no longer a chip — it became a sort option (see
 * [app.orbit.ui.screens.picker.PickerSort.ByRecentlySaved]); the old chip
 * surfaced nothing for most users because `firstSeenByAppAt` clusters at the
 * first-sync moment. "Called recently" likewise moved to the sort control as
 * [app.orbit.ui.screens.picker.PickerSort.ByRecency] ("Recently called") —
 * surfacing recent callers by ordering reads better than hiding everyone else.
 *
 * Token discipline:
 *   - `selectedContainerColor = OrbitTheme.colors.accentTint` — cluster-tier
 *     terracotta tint (per the per-screen accent budget).
 *   - `Modifier.defaultMinSize(minHeight = spacing.tapMin)` per chip — 48dp floor.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilterChipsRow(
    activeFilters: Set<PickerFilter>,
    onToggle: (PickerFilter) -> Unit,
    countFor: (PickerFilter) -> Int,
    availableLists: List<PickerListSummary>,
    onSelectInList: (listId: Long, listName: String) -> Unit,
    onClearInList: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Recently-added is now a sort option, not a chip (see KDoc).
    val allChips: List<Pair<PickerFilter, String>> = listOf(
        // Android favorites (ContactsContract STARRED), seeded
        // into the picker so hand-curated closest people are one tap away.
        PickerFilter.Starred to "Starred",
        PickerFilter.CommonlyCalled to "Commonly called",
        PickerFilter.RarelyCalled to "Rarely called",
        PickerFilter.NeverCalled to "Never called",
        PickerFilter.LongGap to "Long gap",
        PickerFilter.Unsorted to "Unsorted",
    )

    val activeInList = activeFilters.firstNotNullOfOrNull { it as? PickerFilter.InList }
    val activeListName = activeInList?.let { active ->
        availableLists.firstOrNull { it.id == active.listId }?.name
    }

    val selectedChips = allChips.filter { it.first in activeFilters }
    // Enabled (count > 0) first; zero-count chips pushed to the end. `sortedBy`
    // is stable, so the within-group order from `allChips` is preserved.
    val availableChips = allChips
        .filterNot { it.first in activeFilters }
        .sortedBy { countFor(it.first) == 0 }

    val hasApplied = selectedChips.isNotEmpty() || activeInList != null

    Column(modifier = modifier) {
        // ── Applied filters — always visible so the active set is never hidden.
        if (hasApplied) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x2),
                verticalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x1),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = OrbitTheme.spacing.x4),
            ) {
                selectedChips.forEach { (filter, label) ->
                    AppliedChip(
                        text = "$label · ${countFor(filter)}",
                        onClear = { onToggle(filter) },
                    )
                }
                if (activeInList != null) {
                    AppliedChip(
                        text = "In: ${activeListName ?: "list"}",
                        onClear = onClearInList,
                    )
                }
            }
        }

        // ── Available filters — scrollable; zero-count chips disabled at the end.
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x2),
            contentPadding = PaddingValues(horizontal = OrbitTheme.spacing.x4),
            modifier = Modifier.padding(top = if (hasApplied) OrbitTheme.spacing.x2 else 0.dp),
        ) {
            items(availableChips, key = { it.first.toString() }) { (filter, label) ->
                val count = countFor(filter)
                FilterChip(
                    selected = false,
                    enabled = count > 0,
                    onClick = { onToggle(filter) },
                    label = { Text(label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = OrbitTheme.colors.accentTint,
                        selectedLabelColor = OrbitTheme.colors.fg,
                    ),
                    modifier = Modifier.defaultMinSize(minHeight = OrbitTheme.spacing.tapMin),
                )
            }

            // "In list…" anchored DropdownMenu — only when no list filter is
            // already applied (the active one renders in the applied row above).
            if (activeInList == null) {
                item(key = "in-list") {
                    InListChip(
                        availableLists = availableLists,
                        onSelectInList = onSelectInList,
                    )
                }
            }
        }
    }
}

/**
 * An active filter, shown in the applied-filters row. Selected-tint container
 * with a trailing ✕ — tapping anywhere clears the filter.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppliedChip(
    text: String,
    onClear: () -> Unit,
) {
    FilterChip(
        selected = true,
        onClick = onClear,
        label = { Text(text) },
        trailingIcon = { PhIcon(name = "x", size = 12.dp, tint = OrbitTheme.colors.fg) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = OrbitTheme.colors.accentTint,
            selectedLabelColor = OrbitTheme.colors.fg,
        ),
        modifier = Modifier.defaultMinSize(minHeight = OrbitTheme.spacing.tapMin),
    )
}

/**
 * "In list…" dropdown chip — anchors a [DropdownMenu] of non-archived lists.
 * DropdownMenu is used over ModalBottomSheet because Orbit list count is
 * typically < 20.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InListChip(
    availableLists: List<PickerListSummary>,
    onSelectInList: (listId: Long, listName: String) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = false,
            onClick = { menuExpanded = true },
            label = { Text("In list…") },
            trailingIcon = { PhIcon(name = "caret-down", size = 12.dp) },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = OrbitTheme.colors.accentTint,
                selectedLabelColor = OrbitTheme.colors.fg,
            ),
            modifier = Modifier.defaultMinSize(minHeight = OrbitTheme.spacing.tapMin),
        )
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            if (availableLists.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("No lists yet") },
                    enabled = false,
                    onClick = { /* disabled — never invoked */ },
                )
            } else {
                availableLists.forEach { list ->
                    DropdownMenuItem(
                        text = { Text(list.name) },
                        onClick = {
                            onSelectInList(list.id, list.name)
                            menuExpanded = false
                        },
                    )
                }
            }
        }
    }
}

private val previewLists: List<PickerListSummary> = listOf(
    PickerListSummary(id = 1L, name = "Inner orbit"),
    PickerListSummary(id = 2L, name = "Late night"),
    PickerListSummary(id = 3L, name = "People who ground me"),
)

@Preview(name = "FilterChipsRow — light, In: chip inactive", showBackground = true)
@Composable
private fun FilterChipsRowPreviewLight() {
    OrbitTheme(darkTheme = false) {
        FilterChipsRow(
            activeFilters = setOf(PickerFilter.CommonlyCalled),
            onToggle = {},
            countFor = { 14 },
            availableLists = previewLists,
            onSelectInList = { _, _ -> },
            onClearInList = {},
        )
    }
}

@Preview(name = "FilterChipsRow — dark, In: chip active", showBackground = true)
@Composable
private fun FilterChipsRowPreviewDark() {
    OrbitTheme(darkTheme = true) {
        FilterChipsRow(
            activeFilters = setOf(PickerFilter.RarelyCalled, PickerFilter.InList(listId = 1L)),
            onToggle = {},
            countFor = { 7 },
            availableLists = previewLists,
            onSelectInList = { _, _ -> },
            onClearInList = {},
        )
    }
}
