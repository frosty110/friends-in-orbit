package app.orbit.ui.screens.card

import androidx.compose.runtime.Immutable
import app.orbit.data.Contact
import app.orbit.data.NoteRow

/**
 * Card View state contract.
 *
 * Card-loop revision (2026-06-09):
 *  - `Ready.queueSize` now carries the list's real due-now count (was the
 *    dead constant 1).
 *  - `Ready.whyNowLine` — VM-formatted "It's been 3 weeks." framing line
 *    derived from the last connected call; empty when there is no history.
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
        // 2026-06-09 — why-now framing line ("It's been 3 weeks."), formatted
        // by the VM from the most recent call event. Empty when no history;
        // the screen hides the line entirely then.
        val whyNowLine: String = "",
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
        val upNextLabel: String? = null,
    ) : CardViewUiState

    @Immutable
    data class Error(val cause: String) : CardViewUiState
}
