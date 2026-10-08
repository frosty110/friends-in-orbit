package app.orbit.ui.screens.lists

import androidx.compose.runtime.Immutable
import app.orbit.data.entity.ListType
import app.orbit.ui.util.UiText

/**
 * Lists Manager state contract (LIST-02 / LIST-07). Sealed interface
 * with four `@Immutable` variants for Compose skipping.
 *
 *   - `Ready` is partitioned into `active` and `archived` slots so the screen
 *     can render the reorderable LazyColumn over `active` and an
 *     `Archived ({N})` collapsible section over `archived`.
 *   - `archivedExpanded` carries the user-toggle state into the projection so
 *     the screen reads a single immutable snapshot per emission.
 *   - `ListTileState` carries `type: ListType` and an optional `ruleSummary`
 *     ([UiText], resolved by the row): a smart list's rule ("Recently added ·
 *     30 days") or a regular list's rhythm as its interval ("Every 14 days",
 *     "Every 3 days", "Every day"; LIST-30), decoded once in the ViewModel rather than inside the
 *     composable. The README promises every row a rhythm summary; regular
 *     lists had none until 2026-10-06.
 *   - `notificationsEnabled` drives the row menu's "Pause nudges" / "Resume
 *     nudges" entry, the same entry Home's long-press menu offers (LIST-23:
 *     the two menus read the same).
 *
 * `memberCount` comes from `ListRepository.observeMemberCountsByListId()`
 * (one grouped count query); a list with no members is absent from that map
 * and reads 0.
 *
 * `ListTileState` is intentionally local to this package; the Home tile state
 * (`app.orbit.ui.screens.home.ListTileState`) is a different shape and lives
 * elsewhere; they should not converge until both feature surfaces stabilise.
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
    // Defaulted so the row previews, which build tiles by hand, stay short.
    val notificationsEnabled: Boolean = true,
)
