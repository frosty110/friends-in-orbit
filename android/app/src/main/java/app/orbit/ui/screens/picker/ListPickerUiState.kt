package app.orbit.ui.screens.picker

import androidx.compose.runtime.Immutable

/**
 * The reverse picker's state contract (BULK-06): the one `@Immutable` UiState
 * [ListPickerViewModel] publishes and [ListPickerScreen] renders (ARCH-02).
 * Moved out of the ViewModel on 2026-10-06 to sit beside the other screens'
 * contracts; until then it was the only screen state in the tree without the
 * annotation, so Compose could not skip the picker rows on an unchanged
 * reference (`List<ListRow>` and `Set<Long>` are inferred unstable).
 */
@Immutable
data class ListPickerUiState(
    val phase: Phase,
    val contactName: String,
    val lists: List<ListRow>,
    val selectedListIds: Set<Long>,
) {
    // Error (PICK-09): a data stream failed; the screen offers Retry.
    enum class Phase { Loading, Ready, Committing, NotFound, Error }

    @Immutable
    data class ListRow(
        val listId: Long,
        val name: String,
        val isMember: Boolean,
    )

    val canCommit: Boolean get() = selectedListIds.isNotEmpty() && phase != Phase.Committing
    val selectionCount: Int get() = selectedListIds.size
}
