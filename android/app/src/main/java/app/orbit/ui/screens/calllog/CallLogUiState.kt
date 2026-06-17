package app.orbit.ui.screens.calllog

import androidx.compose.runtime.Immutable

/**
 * CallLogScreen state contract: calendar-day sections, direction filter,
 * honest pagination remainder.
 *
 * Sealed interface with three variants:
 *   - [Loading] — initial emission before the combine resolves.
 *   - [Ready]   — at least one correlated call event with a resolvable
 *                 contact; UI renders day-sectioned rows with sticky
 *                 headers. [Ready.sections] may be empty when the active
 *                 direction filter matches nothing — the UI keeps the
 *                 filter row visible and renders a quiet one-liner.
 *   - [Empty]   — no correlated events at all; UI renders the phone-icon
 *                 empty state per UI-SPEC §Layout Patterns.
 *
 * `LOG-01` filters at the DAO layer (`CallEventDao.observeForLog`
 * returns events with `contactId IS NOT NULL`). The VM additionally
 * drops rows whose contact has been hard-deleted between event
 * insertion and the current snapshot — defensive against stale FK
 * cascades in the test fakes.
 */
sealed interface CallLogUiState {

    @Immutable
    data object Loading : CallLogUiState

    /**
     * @property sections       Day-grouped render-ready rows (LOCAL calendar
     *                          days, newest first). May be empty under a
     *                          narrowing direction filter.
     * @property filter         The active direction filter; drives the chip
     *                          row's selected state.
     * @property remainingCount How many filtered rows are NOT yet rendered.
     *                          0 hides the "Show n more" footer; otherwise the
     *                          footer label shows
     *                          `min(remainingCount, PAGE_SIZE)` — the honest
     *                          size of the next increment.
     */
    @Immutable
    data class Ready(
        val sections: List<CallLogDaySection>,
        val filter: CallLogDirectionFilter = CallLogDirectionFilter.ALL,
        val remainingCount: Int = 0,
    ) : CallLogUiState

    @Immutable
    data object Empty : CallLogUiState
}

/**
 * Direction filter for the chip row. MANUAL "Logged" events
 * (user-logged connections) count as reaching out, so they stay visible
 * under [ALL] and [OUTGOING] and are hidden only under [INCOMING].
 */
enum class CallLogDirectionFilter(val label: String) {
    ALL("All"),
    INCOMING("Incoming"),
    OUTGOING("Outgoing"),
}

/**
 * One LOCAL calendar day of call rows.
 *
 * @property epochDay [java.time.LocalDate.toEpochDay] of the section's day —
 *                    stable LazyColumn key for the sticky header.
 * @property label    "Today" / "Yesterday" / "Wednesday 3 June" via
 *                    [app.orbit.ui.util.formatDayHeader].
 */
@Immutable
data class CallLogDaySection(
    val epochDay: Long,
    val label: String,
    val rows: List<CallLogRow>,
)

/**
 * Render-ready row for [CallLogScreen]. All formatters (wall-clock time,
 * duration label, direction word/icon) are pre-computed on the VM so the
 * composable never touches `Instant` or the JVM clock — see the B3 invariant.
 *
 * `listContext` is the formatted "from {ListName}" subtitle fragment. The
 * list context is the contact's most-recent
 * [ListMembership] (max `addedAt`); when the contact has zero memberships
 * (orphan path) the fragment is the empty string and the row's subtitle
 * collapses to "{duration} · {direction}".
 *
 * `phone` feeds the long-press "Call again" quick action (`ACTION_DIAL` via
 * [app.orbit.ui.util.dialPhoneNumber]; never dialled by the app itself).
 *
 * `timeLabel` is the wall-clock time of the call ("4:30pm") — the day itself
 * is carried by the row's [CallLogDaySection] header, so rows no longer
 * repeat a relative date.
 *
 * `isIgnored` carries [ContactEntity.isIgnored] forward so the row can
 * grey itself per IGNORE-09: 50% avatar opacity, fgSubtle name, and the
 * trailing " (ignored)" suffix on the display name.
 */
@Immutable
data class CallLogRow(
    val callEventId: Long,
    val contactId: Long,
    val name: String,
    val phone: String,
    val photoUri: String?,
    val listContext: String,            // "" when no membership
    val durationLabel: String,          // "" for manual events (subtitle skips blanks)
    val directionWord: String,          // "Outgoing" / "Incoming" / "Logged" (manual)
    val directionIconName: String,      // "phone-outgoing" / "phone-incoming" / "check-circle" (manual)
    val timeLabel: String,              // "4:30pm"
    val isIgnored: Boolean,
)
