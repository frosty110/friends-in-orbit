package app.orbit.ui.screens.lists

import androidx.compose.runtime.Immutable
import app.orbit.R
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
 *     ([ListRowSummary], resolved by the row): a smart list's rule ("Recently
 *     added · 30 days") or a regular list's rhythm as its interval ("Every 14
 *     days", "Every 3 days", "Every day"; LIST-30), decoded once in the
 *     ViewModel rather than inside the composable, or the fact that the
 *     stored rhythm or rule could not be read. The README promises every row a rhythm
 *     summary; regular lists had none until 2026-10-06.
 *   - `notificationsEnabled` drives the row menu's "Pause nudges" / "Resume
 *     nudges" entry, the same entry Home's long-press menu offers (LIST-23:
 *     the two menus read the same).
 *
 * `memberCount` comes from `ListRepository.observeMemberCountsByListId()`
 * (one grouped count query); a list with no members is absent from that map
 * and reads 0. The row says it in words at the end of its second line
 * ("Every 14 days · 12 people", "No one yet" at zero; [listRowMeta]); it was
 * an accent count pill until 2026-10-08 (rules.md Design 9).
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
    val ruleSummary: ListRowSummary?,
    // Defaulted so the row previews, which build tiles by hand, stay short.
    val notificationsEnabled: Boolean = true,
)

/**
 * What a list's row says about its rhythm or rule, or that it could not say.
 * [Readable] and [Unreadable] are told apart by type rather than being one
 * [UiText] because the row joins the member count to a rhythm ("Every 14
 * days · 12 people") but never to the error: a count after "Couldn't read
 * this list's rhythm" would read as part of what failed. With the error as
 * plain text the row could only tell the two apart by comparing strings.
 */
sealed interface ListRowSummary {
    /** The words the row shows for this summary. */
    val text: UiText

    /** A smart list's rule or a regular list's interval. */
    @Immutable data class Readable(override val text: UiText) : ListRowSummary

    /**
     * What the list does could not be read, so the row says that, alone
     * (rules.md Code 3: say so instead of nothing). One variant per thing a
     * list can fail to say, each in its own words.
     */
    sealed interface Unreadable : ListRowSummary {
        /** A regular list's stored rhythm does not decode; the deck fails on the same data. */
        @Immutable data object Rhythm : Unreadable {
            override val text: UiText = UiText.res(R.string.lists_rhythm_unreadable)
        }

        /**
         * A smart list's rule is missing or does not decode, so it can never
         * fill itself. Until 2026-11-10 the row showed the count alone, like
         * a working list with nothing to say (LIST-07).
         */
        @Immutable data object Rule : Unreadable {
            override val text: UiText = UiText.res(R.string.lists_rule_unreadable)
        }
    }
}
