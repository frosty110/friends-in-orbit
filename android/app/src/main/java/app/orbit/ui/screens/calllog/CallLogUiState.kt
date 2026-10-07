package app.orbit.ui.screens.calllog

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import app.orbit.R
import app.orbit.ui.util.UiText

/**
 * CallLogScreen state contract: calendar-day sections, direction filter,
 * honest pagination remainder, and the person the log is narrowed to.
 *
 * Every variant carries [scope], so the app bar can say whose calls these are
 * in every state, including Loading and the error and permission states
 * (LOG-04). Variants:
 *   - [Loading]          before the data, and the call-log permission, are
 *                        known. Renders a quiet skeleton, never "No calls yet".
 *   - [Ready]            at least one correlated call event with a resolvable
 *                        contact; UI renders day-sectioned rows with sticky
 *                        headers. [Ready.sections] may be empty when the active
 *                        direction filter matches nothing; the UI keeps the
 *                        filter row visible and renders a quiet one-liner.
 *                        [Ready.callLogDenied] adds a notice above the rows:
 *                        what Orbit already recorded is still true, but new
 *                        calls will not appear until access is back.
 *   - [Empty]            no correlated events, and Orbit can read the call log,
 *                        so "No calls yet" is true.
 *   - [PermissionDenied] no events AND no call-log access (LOG-05). "No calls
 *                        yet" would be false here: the app simply cannot see
 *                        them, so the screen explains and offers the fix.
 *   - [Error]            a data stream failed (rubric 3.5). Says so, with
 *                        Retry, instead of crashing or showing a false empty.
 *
 * `LOG-01` filters at the DAO layer (`CallEventDao.observeForLog`
 * returns events with `contactId IS NOT NULL`). The VM additionally
 * drops rows whose contact has been hard-deleted between event
 * insertion and the current snapshot — defensive against stale FK
 * cascades in the test fakes.
 */
sealed interface CallLogUiState {

    /** Everyone's calls, or one person's (LOG-04). */
    val scope: CallLogScope

    @Immutable
    data class Loading(override val scope: CallLogScope = CallLogScope.Everyone) : CallLogUiState

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
     * @property callLogDenied  READ_CALL_LOG is not granted: the rows are what
     *                          Orbit recorded before, and new calls won't show.
     */
    @Immutable
    data class Ready(
        val sections: List<CallLogDaySection>,
        val filter: CallLogDirectionFilter = CallLogDirectionFilter.ALL,
        val remainingCount: Int = 0,
        val callLogDenied: Boolean = false,
        override val scope: CallLogScope = CallLogScope.Everyone,
    ) : CallLogUiState

    @Immutable
    data class Empty(override val scope: CallLogScope = CallLogScope.Everyone) : CallLogUiState

    @Immutable
    data class PermissionDenied(override val scope: CallLogScope = CallLogScope.Everyone) : CallLogUiState

    @Immutable
    data class Error(override val scope: CallLogScope = CallLogScope.Everyone) : CallLogUiState
}

/**
 * Whose calls the log shows (LOG-04). "View all calls" on Contact detail opens
 * the log narrowed to that person; Settings opens it for everyone.
 */
@Immutable
sealed interface CallLogScope {

    @Immutable
    data object Everyone : CallLogScope

    /**
     * @property name The person's display name; blank until their contact row
     *                has loaded (while Loading, the app bar stays blank for
     *                that moment rather than briefly claiming "Call history"
     *                for everyone). A settled state with no name, such as an
     *                Error from a read that failed before its first emission,
     *                is titled "Call history" by the screen so TalkBack still
     *                gets a pane title.
     */
    @Immutable
    data class Person(
        val contactId: Long,
        val name: String = "",
    ) : CallLogScope
}

/**
 * Direction filter for the chip row. MANUAL "Logged" events (connections
 * logged by hand) and ATTEMPT "Attempted" events (reach-outs that did not
 * connect) count as reaching out, so they stay visible under [ALL] and
 * [OUTGOING] and are hidden only under [INCOMING].
 * [label] is the chip's string resource (strings_calllog.xml).
 */
enum class CallLogDirectionFilter(@StringRes val label: Int) {
    ALL(R.string.calllog_filter_all),
    INCOMING(R.string.calllog_filter_incoming),
    OUTGOING(R.string.calllog_filter_outgoing),
}

/**
 * One LOCAL calendar day of call rows.
 *
 * @property epochDay [java.time.LocalDate.toEpochDay] of the section's day —
 *                    stable LazyColumn key for the sticky header.
 * @property label    "Today" / "Yesterday" / "Wednesday 3 June" via
 *                    [app.orbit.ui.util.formatDayHeader], as [UiText].
 */
@Immutable
data class CallLogDaySection(
    val epochDay: Long,
    val label: UiText,
    val rows: List<CallLogRow>,
)

/**
 * Render-ready row for [CallLogScreen]. All formatters (wall-clock time,
 * duration label, direction icon) are pre-computed on the VM so the
 * composable never touches `Instant` or the JVM clock — see the B3 invariant.
 * The words are resources: [durationLabel] is [UiText], and the screen words
 * the direction from [kind] and "from {list}" from [listName]
 * (strings_calllog.xml).
 *
 * `listName` is the contact's most-recent [ListMembership] (max `addedAt`),
 * shown as "from {listName}"; when the contact has zero memberships
 * (orphan path) it is the empty string and the row's subtitle collapses to
 * "{duration} · {direction}".
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
    val listName: String,               // "" when no membership
    val durationLabel: UiText?,         // null for manual and attempted events (subtitle skips it)
    val directionIconName: String,      // "phone-outgoing" / "phone-incoming" / "check-circle" (manual) / "phone-slash" (attempted)
    val timeLabel: String,              // "4:30pm"
    val isIgnored: Boolean,
    // What happened, for the one-person log (LOG-04), where every row is the
    // same person and the row leads with the event instead ("You called",
    // "Sam called"). The screen words it so the privacy curtain can mask the
    // name; in the everyone view it is the subtitle's direction word.
    val kind: CallLogKind,
)

/** The kind of event a [CallLogRow] records. */
enum class CallLogKind { Outgoing, Incoming, Logged, Attempted }
