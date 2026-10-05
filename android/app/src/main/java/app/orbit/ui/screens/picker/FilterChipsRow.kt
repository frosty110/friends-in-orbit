package app.orbit.ui.screens.picker

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import app.orbit.R
import app.orbit.ui.components.LocalPrivacyCurtain
import app.orbit.ui.components.OrbitDropdownMenu
import app.orbit.ui.components.OrbitFilterChip
import app.orbit.ui.components.OrbitMenuAction
import app.orbit.ui.theme.OrbitTheme

/**
 * Dynamic picker filter chips.
 *
 * Two stacked regions (UI bug fix — "filters need some work"):
 *   1. **Applied filters** (FlowRow, wraps, always fully visible): every active
 *      chip: including an active "On {list}", rendered with a trailing ✕ so
 *      the user can always *see and clear* what's filtering the list without
 *      hunting through a scroll. Hidden entirely when nothing is active.
 *   2. **Available filters** (LazyRow, horizontally scrollable): the unselected
 *      chips. A chip whose count is 0 under the current search/ignored context
 *      is **disabled and sorted to the end** so the actionable filters stay up
 *      front. The "On a list" menu chip trails the row (only when no list
 *      filter is already applied).
 *
 * "Recently added" is no longer a chip — it became a sort option (see
 * [app.orbit.ui.screens.picker.PickerSort.ByRecentlySaved]); the old chip
 * surfaced nothing for most users because `firstSeenByAppAt` clusters at the
 * first-sync moment. "Called recently" likewise moved to the sort control as
 * [app.orbit.ui.screens.picker.PickerSort.ByRecency] ("Recently called") —
 * surfacing recent callers by ordering reads better than hiding everyone else.
 *
 * Every chip is the shared [OrbitFilterChip] (2026-10-05). These were Material
 * `FilterChip`s with a colour override, one of the four chip styles the UX
 * rubric counted (D4), and the "On a list" menu was a stock Material
 * `DropdownMenu`; it is the shared [OrbitDropdownMenu] now. Selected chips use
 * the cluster-tier `accentTint` (per the per-screen accent budget) and carry a
 * check or an ✕, so colour is never the only signal.
 *
 * Plain words (rubric D7): "Unsorted" is "Not on a list", and the list filter
 * reads "On a list" / "On {list}" rather than "In list…" / "In: {list}". List
 * names are masked to "a list" under the privacy curtain.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilterChipsRow(
    activeFilters: Set<PickerFilter>,
    onToggle: (PickerFilter) -> Unit,
    countFor: (PickerFilter) -> Int,
    availableLists: List<PickerListSummary>,
    onSelectInList: (listId: Long, listName: String) -> Unit,
    onClearInList: () -> Unit,
    modifier: Modifier = Modifier,
    // A quiet line explaining why some chips are greyed (e.g. "long gap" needs
    // call history). Null when nothing is disabled or no explanation applies.
    disabledHint: String? = null,
) {
    val curtain = LocalPrivacyCurtain.current
    // Recently-added is now a sort option, not a chip (see KDoc).
    val allChips: List<Pair<PickerFilter, String>> = listOf(
        // Android favorites (ContactsContract STARRED), seeded
        // into the picker so hand-curated closest people are one tap away.
        PickerFilter.Starred to stringResource(R.string.picker_filter_starred),
        PickerFilter.CommonlyCalled to stringResource(R.string.picker_filter_commonly_called),
        PickerFilter.RarelyCalled to stringResource(R.string.picker_filter_rarely_called),
        PickerFilter.NeverCalled to stringResource(R.string.picker_filter_never_called),
        PickerFilter.LongGap to stringResource(R.string.picker_filter_long_gap),
        PickerFilter.Unsorted to stringResource(R.string.picker_filter_not_on_a_list),
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
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = OrbitTheme.spacing.x4),
            ) {
                selectedChips.forEach { (filter, label) ->
                    AppliedChip(
                        text = stringResource(R.string.picker_filter_applied, label, countFor(filter)),
                        onClear = { onToggle(filter) },
                    )
                }
                if (activeInList != null) {
                    AppliedChip(
                        text = if (curtain || activeListName == null) {
                            stringResource(R.string.picker_filter_on_a_list)
                        } else {
                            stringResource(R.string.picker_filter_on_list, activeListName)
                        },
                        onClear = onClearInList,
                    )
                }
            }
        }

        // ── Available filters — scrollable; zero-count chips disabled at the end.
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x2),
            contentPadding = PaddingValues(horizontal = OrbitTheme.spacing.x4),
        ) {
            items(availableChips, key = { it.first.toString() }) { (filter, label) ->
                OrbitFilterChip(
                    label = label,
                    selected = false,
                    enabled = countFor(filter) > 0,
                    onClick = { onToggle(filter) },
                )
            }

            // "On a list" menu: only when no list filter is already applied
            // (the active one renders in the applied row above).
            if (activeInList == null) {
                item(key = "in-list") {
                    InListChip(
                        availableLists = availableLists,
                        onSelectInList = onSelectInList,
                    )
                }
            }
        }

        // Why a chip is greyed: a disabled chip can't say it itself, so
        // the screen hands us the reason (usually: no call history yet).
        disabledHint?.let { hint ->
            Text(
                text = hint,
                style = OrbitTheme.type.meta,
                color = OrbitTheme.colors.fgSubtle,
                modifier = Modifier.padding(
                    start = OrbitTheme.spacing.x4,
                    end = OrbitTheme.spacing.x4,
                    top = OrbitTheme.spacing.x1,
                ),
            )
        }
    }
}

/**
 * An active filter, shown in the applied-filters row. Selected, with a
 * trailing ✕; tapping anywhere on it clears the filter.
 */
@Composable
private fun AppliedChip(
    text: String,
    onClear: () -> Unit,
) {
    OrbitFilterChip(
        label = text,
        selected = true,
        onClick = onClear,
        trailingIcon = "x",
    )
}

/**
 * "On a list" menu chip: anchors an [OrbitDropdownMenu] of non-archived
 * lists. A menu rather than a sheet because Orbit list count is typically
 * under 20.
 */
@Composable
private fun InListChip(
    availableLists: List<PickerListSummary>,
    onSelectInList: (listId: Long, listName: String) -> Unit,
) {
    val curtain = LocalPrivacyCurtain.current
    var menuExpanded by remember { mutableStateOf(false) }
    Box {
        OrbitFilterChip(
            label = stringResource(R.string.picker_filter_on_a_list),
            selected = false,
            onClick = { menuExpanded = true },
            role = Role.DropdownList,
            trailingIcon = "caret-down",
        )
        OrbitDropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            actions = if (availableLists.isEmpty()) {
                listOf(
                    OrbitMenuAction(
                        label = stringResource(R.string.picker_filter_no_lists),
                        onClick = {},
                        enabled = false,
                    ),
                )
            } else {
                availableLists.map { list ->
                    OrbitMenuAction(
                        label = if (curtain) stringResource(R.string.components_curtain_list) else list.name,
                        onClick = { onSelectInList(list.id, list.name) },
                    )
                }
            },
        )
    }
}

private val previewLists: List<PickerListSummary> = listOf(
    PickerListSummary(id = 1L, name = "Inner orbit"),
    PickerListSummary(id = 2L, name = "Late night"),
    PickerListSummary(id = 3L, name = "People who ground me"),
)

@PreviewLightDark
@PreviewFontScale
@Composable
private fun FilterChipsRowPreview() {
    OrbitTheme {
        Box(modifier = Modifier.background(OrbitTheme.colors.bg)) {
            FilterChipsRow(
                activeFilters = setOf(PickerFilter.RarelyCalled, PickerFilter.InList(listId = 1L)),
                onToggle = {},
                countFor = { if (it == PickerFilter.LongGap) 0 else 7 },
                availableLists = previewLists,
                onSelectInList = { _, _ -> },
                onClearInList = {},
                disabledHint = stringResource(R.string.picker_filters_greyed),
            )
        }
    }
}
