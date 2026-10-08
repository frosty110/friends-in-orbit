package app.orbit.ui.screens.card

import androidx.compose.runtime.Immutable
import app.orbit.data.Contact
import app.orbit.data.NoteRow
import app.orbit.data.entity.ListType
import app.orbit.ui.util.UiText
import java.time.Instant

/**
 * Card View state contract.
 *
 * Card-view audit (2026-10-06):
 *  - Every state that has a list carries its name (`listContext` on Ready,
 *    `listName` on the rest; [listName] reads either), so the app bar is
 *    titled in every state and TalkBack announces the pane. Only Ready used
 *    to carry it, and an empty or failed deck showed a bar with no title.
 *  - [listType] on Ready and both empty states: a smart list's members are
 *    its rule's matches, so "Add people" is not offered there (the sync would
 *    have removed whoever was added, silently).
 *  - `Ready.lastCallAt`: the newest connected call for the surfaced person,
 *    the evidence CARD-03 waits for before saying "Called {name}".
 *  - `Error` carries no cause string: a database message can quote a query
 *    over PII and nothing on screen reads it (rules.md Code 4). It is
 *    reachable for a failed read and for a malformed list id (CARD-07). It
 *    offers Try again only when a retry can re-read something (`canRetry`):
 *    an id that never parsed has nothing to re-read, so that deck offers Go
 *    home alone.
 *
 * Card-loop revision (2026-06-09):
 *  - `Ready.queueSize` now carries the list's real due-now count (was the
 *    dead constant 1).
 *  - `Ready.whyNowLine`: VM-built "You spoke 3 weeks ago." framing line
 *    derived from the last connected call; null when there is no history.
 *  - `EmptyNothingEligible` is a data class carrying the optional
 *    soonest-upcoming-member hint so the empty state can say
 *    "{name} comes up {when}." instead of a false "paused or out of reach".
 *
 * Tide marker (2026-05-08) — the terminal `AllCaughtUp` variant is gone.
 * The surface no longer drops future-due candidates, so the queue is
 * continuous; the only legitimate empty cases are `EmptyNoMembers` and
 * `EmptyNothingEligible`. `Ready.isAheadOfToday` says whether the surfaced
 * person is past the waterline. It shifted an eyebrow over the name from "Up
 * now" to "Coming up" until 2026-10-08, when the owner removed the eyebrow;
 * see the field for why it stays.
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
        // Nothing draws it since the eyebrow went (2026-10-08, CARD-04), and it
        // stays on purpose: Contact's equality is its id, so after a Later on
        // someone up now on a one-member list this is the only field that
        // changes, and that change is the new emission CardSwipeFrame
        // re-centers the held card on (`emissionKey`). Without it the card
        // would wait off-screen for the frame's stuck-card guard.
        val isAheadOfToday: Boolean = false,
        // 2026-06-09: why-now framing line ("You spoke 3 weeks ago."), built
        // by the VM from the most recent connected call as UiText (resolved by
        // the screen). Null when no history; the screen hides the line then.
        val whyNowLine: UiText? = null,
        // The list's type, so the menu and the empty deck can withhold "Add
        // people" on a smart list.
        val listType: ListType = ListType.STATIC,
        // CARD-03: when the surfaced person was last spoken to (connections
        // only, never an ATTEMPT). Null when never.
        val lastCallAt: Instant? = null,
    ) : CardViewUiState

    /**
     * The list has zero non-archived non-ignored memberships: the user has
     * not put anyone in this list. UI copy: "No one is in this list yet."
     * with "Add people"; on a smart list, "No one matches this rule right
     * now." with "List settings", since its members come from its rule.
     */
    @Immutable
    data class EmptyNoMembers(
        val listName: String = "",
        val listType: ListType = ListType.STATIC,
    ) : CardViewUiState

    /**
     * The list has visible members but none surfaces right now: paused, no
     * rule template, etc. When the feed can see a member with a date to come
     * back on, [upNextName] + [upNextLabel] carry the "{name} comes up
     * {when}." hint; both null means the screen falls back to the neutral
     * "No one needs a call right now." line.
     */
    @Immutable
    data class EmptyNothingEligible(
        val upNextName: String? = null,
        val upNextLabel: UiText? = null,
        val listName: String = "",
        val listType: ListType = ListType.STATIC,
    ) : CardViewUiState

    /**
     * A read failed, or the list id could not be parsed (CARD-07). The
     * screen offers Try again and Go home. [listName] is blank when the list
     * itself could not be read.
     *
     * [canRetry] is false when there is nothing a retry could re-read: the
     * route's list id never parsed, so the VM has no feed to re-subscribe
     * and Try again changed nothing. The screen then offers Go home alone
     * (rules.md Code 3: a control that does nothing is a silent short-circuit).
     * Carried on the state rather than decided in the screen so the two
     * cannot disagree about which Error this is.
     */
    @Immutable
    data class Error(val listName: String = "", val canRetry: Boolean = true) : CardViewUiState
}

/** The list's name in any state that knows it; blank while loading or when the list could not be read. */
val CardViewUiState.listName: String
    get() = when (this) {
        CardViewUiState.Loading -> ""
        is CardViewUiState.Ready -> listContext
        is CardViewUiState.EmptyNoMembers -> listName
        is CardViewUiState.EmptyNothingEligible -> listName
        is CardViewUiState.Error -> listName
    }

/** The list's type once the list has been read; null while loading or on a failed read. */
val CardViewUiState.listType: ListType?
    get() = when (this) {
        CardViewUiState.Loading, is CardViewUiState.Error -> null
        is CardViewUiState.Ready -> listType
        is CardViewUiState.EmptyNoMembers -> listType
        is CardViewUiState.EmptyNothingEligible -> listType
    }

/**
 * One-off messages for the Card view (2026-10-05): its snackbars, and since
 * 2026-10-07 the one message that is not a snackbar, [OpenNote].
 *
 * Each Later or Sooner carries its own [Undoable.token], and only the newest
 * token can be undone: the screen replaces an older snackbar the moment a
 * newer one arrives. Before, snackbars queued while the undo slot held only
 * the latest action, so Undo on the first of three quick swipes reverted the
 * third person (UX rubric gate G1).
 *
 * Text is [UiText]: the screen resolves it when it shows the snackbar, so
 * the copy lives in strings_card.xml.
 */
sealed interface CardMessage {

    /**
     * A Later or Sooner the user can take back with "Undo": "Kai moved to
     * later." / "Kai moved sooner." (CARD-02). [curtainText] is the same
     * sentence with "They" for the name, shown instead of [text] while the
     * privacy curtain is down (PRIV-03), as [Logged]'s is.
     */
    data class Undoable(val text: UiText, val curtainText: UiText, val token: Long) : CardMessage

    /**
     * The call log confirmed a call placed from this card; offers "Add a
     * note" (CARD-03). Only for a call too short or unanswered to be worth a
     * note by itself; one that is opens the page instead ([OpenNote]).
     */
    data class Called(val text: UiText, val contactId: Long) : CardMessage

    /**
     * CARD-10: a connection or attempt logged from the card. [curtainText]
     * is the same sentence without the name, shown instead of [text] while
     * the privacy curtain is down (PRIV-03).
     */
    data class Logged(val text: UiText, val curtainText: UiText) : CardMessage

    /**
     * CARD-11: a call placed from this card connected and lasted a minute or
     * more (NOTE-05's rule), so the page for writing about it opens by
     * itself, once, in place of [Called]'s snackbar.
     */
    data class OpenNote(val contactId: Long, val callEventId: Long) : CardMessage

    /** A write failed; says so (rules.md Code 3, no silent fallbacks). */
    data class Failed(val text: UiText) : CardMessage
}
