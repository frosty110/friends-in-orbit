package app.orbit.ui.screens.contact

import androidx.compose.runtime.Immutable
import app.orbit.data.CallEntry
import app.orbit.data.Contact
import app.orbit.data.NoteRow
import app.orbit.domain.rule.RuleParams

/**
 * ContactDetail state contract (CONTACT-01/02/06).
 *
 * Extends the original read-only scaffold with `Orphaned`, `listsOn`,
 * `recentCalls`, and `longestGapLabel` so the screen can render the read-only
 * detail surface (photo + hero + chip row + 5-row stats panel + history).
 *
 * The Notes section (NOTE-01) is surfaced via `Ready.notes`, which carries
 * [NoteRow] entries with VM-pre-formatted `relativeTimestamp` +
 * `absoluteTimestamp` String fields per the Clock-injection invariant —
 * the composable never reads a JVM "now". `Ready.draft` carries the input
 * text the user has typed but not yet submitted.
 *
 * [Ready] also carries the per-contact rule override surface (CONTACT-03).
 * Five fields drive the [RuleOverrideSection]:
 *   - `customScheduleVisible`: derived `listsOn.size >= 2`; the section's
 *     own AnimatedVisibility wraps the body but the screen also conditions
 *     the LazyColumn item on this flag for cleaner recomposition.
 *   - `currentTemplateName`: human-readable template name shown in the
 *     "Inherits {X} from {Y}" copy. When `currentParams == null` (corrupted
 *     JSON), this flips to "Custom schedule (recovering)".
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
 * `Orphaned` mirrors `Ready` payload-wise — the screen draws an extra warm
 * banner above the body when this variant is emitted (ContactEntity.isOrphaned).
 */
sealed interface ContactDetailUiState {

    @Immutable data object Loading : ContactDetailUiState

    @Immutable
    data class Ready(
        val contact: Contact,
        val notes: List<NoteRow>,
        val listsOn: List<String>,
        val recentCalls: List<CallEntry>,
        val longestGapLabel: String,
        // NOTE-01 — Notes input draft state, VM-owned.
        val draft: String = "",
        // CONTACT-05 — true when pausedUntil <= clock.now()
        // AND the pause is NOT the indefinite sentinel (the user explicitly
        // chose "until I unpause" — that case never auto-expires).
        val unpausePromptVisible: Boolean = false,
        // CONTACT-03 — RuleOverrideSection inputs.
        val customScheduleVisible: Boolean = false,
        val currentTemplateName: String = "",
        val primaryListName: String = "",
        val hasOverride: Boolean = false,
        val currentParams: RuleParams? = null,
        // LOG-03 — CallLog deep-link surface. When the
        // user taps a row in CallLogScreen, the contact route is opened with
        // `scrollToCallEventId` set; the screen reads it via VM, scrolls the
        // body LazyColumn to the matching row in [recentCalls], and renders
        // an inline "Add note to this call" Primary button below the
        // highlighted row when [retroNoteAffordanceFor] equals the row's
        // call-event id.
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
    ) : ContactDetailUiState

    /** CONTACT-06 — phone contact removed; surfaces with a re-link/archive affordance. */
    @Immutable
    data class Orphaned(
        val contact: Contact,
        val listsOn: List<String>,
        val recentCalls: List<CallEntry>,
        val longestGapLabel: String,
        // Parallel-indexed MANUAL flags — see [Ready.recentCallIsManual].
        val recentCallIsManual: List<Boolean> = emptyList(),
    ) : ContactDetailUiState

    @Immutable data object NotFound : ContactDetailUiState
}
