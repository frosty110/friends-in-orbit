package app.orbit.ui.screens.lists

import androidx.compose.runtime.Immutable
import app.orbit.data.entity.ListType
import app.orbit.ui.util.UiText

/**
 * Lists Manager state contract (LIST-02 / LIST-07). Sealed interface
 * with three `@Immutable` variants for Compose skipping.
 *
 *   - `Ready` is partitioned into `active` and `archived` slots so the screen
 *     can render the reorderable LazyColumn over `active` and an
 *     `Archived ({N})` collapsible section over `archived`.
 *   - `archivedExpanded` carries the user-toggle state into the projection so
 *     the screen reads a single immutable snapshot per emission.
 *   - `ListTileState` gains `type: ListType` and an optional `ruleSummary`
 *     ([UiText], resolved by the row) so smart-list rows can render a
 *     "Smart list" chip + per-rule subtitle without re-decoding the JSON
 *     inside the composable.
 *
 * `memberCount` starts at 0 — hydrating it requires a per-list
 * `SELECT COUNT(*) GROUP BY listId` DAO query owned by the membership layer.
 *
 * `ListTileState` is intentionally local to this package; the Home tile state
 * (`app.orbit.ui.screens.home.ListTileState`) is a different shape and lives
 * elsewhere — they should not converge until both feature surfaces stabilise.
 */
sealed interface ListsManagerUiState {
    @Immutable data object Loading : ListsManagerUiState

    /** LIST-22: the lists could not be read; shown with Try again (rubric D6). */
    @Immutable data object Error : ListsManagerUiState

    @Immutable
    data class Ready(
        val active: List<ListTileState>,
        val archived: List<ListTileState>,
        val archivedExpanded: Boolean,
    ) : ListsManagerUiState

    @Immutable data object Empty : ListsManagerUiState
}

@Immutable
data class ListTileState(
    val id: Long,
    val name: String,
    val memberCount: Int,
    val type: ListType,
    val ruleSummary: UiText?,
)
