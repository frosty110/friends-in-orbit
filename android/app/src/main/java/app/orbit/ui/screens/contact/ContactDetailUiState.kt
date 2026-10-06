package app.orbit.ui.screens.contact

import androidx.compose.runtime.Immutable
import app.orbit.data.CallEntry
import app.orbit.data.Contact
import app.orbit.data.NoteRow
import app.orbit.domain.rule.RuleParams
import app.orbit.ui.util.UiText

/**
 * ContactDetail state contract (CONTACT-01/02/06).
 *
 * Extends the original read-only scaffold with `Orphaned`, `listsOn`,
 * `recentCalls`, and `longestGapLabel` so the screen can render the read-only
 * detail surface (photo + hero + chip row + 5-row stats panel + history).
 *
 * The Notes section (NOTE-01) is surfaced via `Ready.notes`, which carries
 * [NoteRow] entries with VM-pre-formatted `relativeTimestamp` +
 * `absoluteTimestamp` fields per the Clock-injection invariant, so the
 * composable never reads a JVM "now". `Ready.draft` carries the input
 * text the user has typed but not yet submitted.
 *
 * Labels a person reads (`longestGapLabel`, `pausedLabel`,
 * `currentTemplateName`) are [UiText]: the VM holds no Context and the copy
 * lives in strings_contact.xml / strings_time.xml (UX rubric 3.4). Null means
 * "nothing to say", and the screen words that itself.
 *
 * [Ready] also carries the per-contact rule override surface (CONTACT-03).
 * Five fields drive the [RuleOverrideSection]:
 *   - `customScheduleVisible`: derived `listsOn.size >= 2`; the section's
 *     own AnimatedVisibility wraps the body but the screen also conditions
 *     the LazyColumn item on this flag for cleaner recomposition.
 *   - `currentTemplateName`: the inherited rhythm's name as it sits
 *     mid-sentence ("keep in touch") in "Follows the {X} rhythm from {Y}."
 *     Null when a stored override failed to decode (`currentParams == null`):
 *     that state always shows the editor, never the sentence, so there is no
 *     rhythm to name. (It used to hold "Custom schedule (recovering)", which
 *     no branch of the section ever displayed.)
 *   - `primaryListName`: the first list the contact appears on — drives the
 *     "from {Y}" half of the inherits copy.
 *   - `hasOverride`: true when `Contact.ruleOverrideJson != null` OR the
 *     user has the override editor peeked open this session (opening the
 *     editor is read-only; nothing persists until an actual change fires
 *     `onSaveOverride`).
 *   - `currentParams`: decoded RuleParams when a persisted override exists;
 *     null when none is persisted (peek-open editor renders defaults) or
 *     decode failed (recovery — UI handles gracefully).
 *
 * `Orphaned` carries what stays readable once the phone contact is gone (the
 * stats, the calls with their ids, the notes) and the screen draws the orphan
 * banner above the body when this variant is emitted (ContactEntity.isOrphaned,
 * CONTACT-06). Edit affordances (Log a connection, Add to lists, the schedule)
 * wait for a re-link, so it carries none of their inputs.
 *
 * `callLogDenied` (both variants) is READ_CALL_LOG, read by the screen on every
 * resume (ARCH-04) and pushed into the VM: while true the stats must not claim
 * "Never called" or "Not enough calls yet", because Orbit cannot know.
 */
sealed interface ContactDetailUiState {

    @Immutable data object Loading : ContactDetailUiState

    @Immutable
    data class Ready(
        val contact: Contact,
        val notes: List<NoteRow>,
        val listsOn: List<String>,
        val recentCalls: List<CallEntry>,
        // "21 days"; null below two calls, or when they fell on one day.
        val longestGapLabel: UiText?,
        // NOTE-01 — Notes input draft state, VM-owned.
        val draft: String = "",
        // CONTACT-05 — true when pausedUntil <= clock.now()
        // AND the pause is NOT the indefinite sentinel (the user explicitly
        // chose "until I unpause" — that case never auto-expires).
        val unpausePromptVisible: Boolean = false,
        // Non-null while a pause is in force: "Paused until 12 Oct" or
        // "Paused until you unpause". Drives the status line under the number
        // and swaps the overflow's Pause for Unpause. Before this, an active
        // pause was invisible here and an indefinite one could never be undone.
        val pausedLabel: UiText? = null,
        // The same shape for Ignore (CONTACT-10): "Ignored" under the number,
        // Unignore in place of Ignore in the overflow, and no Pause (a paused
        // ignored person surfaces nowhere either way). Before this an ignored
        // person's page looked normal and offered Ignore again. Archive gets
        // the status line only: ArchiveContactUseCase defers an unarchive
        // surface to v1.1.
        val isIgnored: Boolean = false,
        val isArchived: Boolean = false,
        // ContactEntity.phoneContactId: null for a call-log-only person. The
        // overflow offers "Open in Contacts" only when it is set.
        val phoneContactId: Long? = null,
        // READ_CALL_LOG is denied: see the class KDoc.
        val callLogDenied: Boolean = false,
        // CONTACT-03 — RuleOverrideSection inputs.
        val customScheduleVisible: Boolean = false,
        val currentTemplateName: UiText? = null,
        val primaryListName: String = "",
        val hasOverride: Boolean = false,
        val currentParams: RuleParams? = null,
        // LOG-03 — CallLog deep-link surface. When the
        // user taps a row in CallLogScreen, the contact route is opened with
        // `scrollToCallEventId` set; the screen reads it via VM, scrolls the
        // body LazyColumn to the matching row in [recentCalls] (found by its
        // key, the call-event id), tints that row `bgSubtle`, and renders an
        // inline "Add note to this call" Secondary button below it when
        // [retroNoteAffordanceFor] equals the row's call-event id (Secondary
        // because Call is the screen's one accent, rules.md §Design 5).
        //
        // [recentCallEventIds] is the parallel-indexed Long-id list for
        // [recentCalls] — `recentCalls[i]` corresponds to
        // `recentCallEventIds[i]`. This is the cleanest place to thread the
        // primary-key info down without mutating the wide [CallEntry]
        // shape (Model.kt:39 — direction / relativeWhen / lengthLabel only,
        // consumers across the call-log / detail surfaces would all need touching).
        val scrollToCallEventId: Long? = null,
        val retroNoteAffordanceFor: Long? = null,
        val recentCallEventIds: List<Long> = emptyList(),
        // Manual-log surface — parallel-indexed with [recentCalls]:
        // `recentCallIsManual[i]` is true when `recentCalls[i]` came from a
        // user-logged connection (CallSource.MANUAL) rather than the carrier
        // call log. Same parallel-list rationale as [recentCallEventIds].
        val recentCallIsManual: List<Boolean> = emptyList(),
        // Attempt surface — parallel-indexed with [recentCalls]:
        // `recentCallIsAttempt[i]` is true when `recentCalls[i]` was a reach-out
        // that didn't connect (CallSource.ATTEMPT — voicemail / no answer); the
        // row renders "Attempted" + a phone-slash icon.
        val recentCallIsAttempt: List<Boolean> = emptyList()
    ) : ContactDetailUiState

    /**
     * CONTACT-06 — phone contact removed; surfaces with a re-link/archive
     * affordance. Notes are Orbit's own data and stay readable here (the
     * banner says "History stays here"); until 2026-10-06 they vanished with
     * the phone contact.
     */
    @Immutable
    data class Orphaned(
        val contact: Contact,
        val listsOn: List<String>,
        val recentCalls: List<CallEntry>,
        val longestGapLabel: UiText?,
        val notes: List<NoteRow> = emptyList(),
        // Parallel-indexed call-event ids, see [Ready.recentCallEventIds].
        // The rows are keyed by these (rules.md Code 2).
        val recentCallEventIds: List<Long> = emptyList(),
        // Parallel-indexed MANUAL flags — see [Ready.recentCallIsManual].
        val recentCallIsManual: List<Boolean> = emptyList(),
        // Parallel-indexed ATTEMPT flags — see [Ready.recentCallIsAttempt].
        val recentCallIsAttempt: List<Boolean> = emptyList(),
        // READ_CALL_LOG is denied: see the class KDoc.
        val callLogDenied: Boolean = false
    ) : ContactDetailUiState

    @Immutable data object NotFound : ContactDetailUiState

    /**
     * CONTACT-08: a data stream failed. The screen says so and offers Retry;
     * before 2026-10-05 the exception escaped viewModelScope and crashed the
     * app (rubric 3.5, gate G5).
     */
    @Immutable data object Error : ContactDetailUiState
}
