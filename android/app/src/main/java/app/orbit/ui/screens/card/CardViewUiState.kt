package app.orbit.ui.screens.card

import androidx.compose.runtime.Immutable
import app.orbit.data.Contact
import app.orbit.data.NoteRow
import app.orbit.ui.util.UiText

/**
 * Card View state contract.
 *
 * Card-loop revision (2026-06-09):
 *  - `Ready.queueSize` now carries the list's real due-now count (was the
 *    dead constant 1).
 *  - `Ready.whyNowLine`: VM-built "It's been 3 weeks." framing line
 *    derived from the last connected call; null when there is no history.
 *  - `EmptyNothingEligible` is a data class carrying the optional
 *    soonest-upcoming-member hint so the empty state can say
 *    "{name} comes up {when}." instead of a false "paused or out of reach".
 *
 * Tide marker (2026-05-08) — the terminal `AllCaughtUp` variant is gone.
 * The surface no longer drops future-due candidates, so the queue is
 * continuous; the only legitimate empty cases are `EmptyNoMembers` and
 * `EmptyNothingEligible`. `Ready.isAheadOfToday` shifts the eyebrow label
 * from `due today` to `ahead of today` past the waterline.
 *
 * Earlier history:
 *  - This sealed contract was introduced as ARCH-02. Each variant is
 *    `@Immutable` for Compose skipping.
 *  - `Ready` was widened with the raw `contactId: Long` so `onSwipeLeft`
 *    can call `SkipContactUseCase.invoke(contactId, listId)`.
 *  - NOTE-03 widened `Ready` with `recentNotes`.
 *  - `nowHour` was added for the HeatStrip "current hour" highlight.
 */
sealed interface CardViewUiState {
    @Immutable data object Loading : CardViewUiState

    @Immutable
    data class Ready(
        val contactId: Long,
        val contact: Contact,
        val listContext: String,
        // 2026-06-09 — real due-now count from SurfaceQueueUseCase via CardFeed.
        val queueSize: Int,
        // NOTE-03 — up to 2 recent notes from last 30 days.
        val recentNotes: List<NoteRow> = emptyList(),
        // VM-snapshotted local hour (0..23) for the HeatStrip
        // "current hour" highlight. Honours the clock-injection invariant.
        val nowHour: Int = 0,
        // Tide marker (2026-05-08) — true when the surfaced contact's
        // engine-computed nextDueAt is in the future at the moment of emission.
        val isAheadOfToday: Boolean = false,
        // 2026-06-09: why-now framing line ("It's been 3 weeks."), built by
        // the VM from the most recent call event as UiText (resolved by the
        // screen). Null when no history; the screen hides the line then.
        val whyNowLine: UiText? = null,
    ) : CardViewUiState

    /**
     * The list has zero non-archived non-ignored memberships — the user
     * has not put anyone in this list. UI copy: "Add people to this list."
     */
    @Immutable data object EmptyNoMembers : CardViewUiState

    /**
     * The list has visible members but none survives filtering right now
     * — paused, outside active hours, no rule template, etc. When the feed
     * can see a future-due member, [upNextName] + [upNextLabel] carry the
     * "{name} comes up {when}." hint; both null means the screen falls back
     * to the neutral "No one needs a call right now." line.
     */
    @Immutable
    data class EmptyNothingEligible(
        val upNextName: String? = null,
        val upNextLabel: UiText? = null,
    ) : CardViewUiState

    @Immutable
    data class Error(val cause: String) : CardViewUiState
}

/**
 * One-off messages for the Card view's snackbar (2026-10-05).
 *
 * Each Later or Sooner carries its own [Undoable.token], and only the newest
 * token can be undone: the screen replaces an older snackbar the moment a
 * newer one arrives. Before, snackbars queued while the undo slot held only
 * the latest action, so Undo on the first of three quick swipes reverted the
 * third person (UX rubric gate G1).
 *
 * [text] is [UiText]: the screen resolves it when it shows the snackbar, so
 * the copy lives in strings_card.xml.
 */
sealed interface CardMessage {
    val text: UiText

    /** A Later or Sooner the user can take back with "Undo". */
    data class Undoable(override val text: UiText, val token: Long) : CardMessage

    /** The call log confirmed a call placed from this card; offers "Add a note". */
    data class Called(override val text: UiText, val contactId: Long) : CardMessage

    /** A write failed; says so (rules.md Code 3, no silent fallbacks). */
    data class Failed(override val text: UiText) : CardMessage
}
