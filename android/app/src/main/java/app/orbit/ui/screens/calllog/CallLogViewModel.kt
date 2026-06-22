package app.orbit.ui.screens.calllog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.orbit.data.dao.ListMembershipDao
import app.orbit.data.entity.CallDirection
import app.orbit.data.entity.CallSource
import app.orbit.data.repository.CallEventRepository
import app.orbit.data.repository.ContactRepository
import app.orbit.data.repository.ListRepository
import app.orbit.domain.clock.Clock
import app.orbit.ui.util.formatDayHeader
import app.orbit.ui.util.formatDuration
import app.orbit.ui.util.formatWallClock
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * Chronological in-app call log VM, per the call-history spec:
 *
 *   - **Calendar-day sections.** Rows group by LOCAL calendar day
 *     ([ZoneId.systemDefault], same convention as `formatAbsolute`), not
 *     24-hour windows — an 11pm call read the next morning sits under
 *     "Yesterday". Headers via [formatDayHeader].
 *   - **Direction filter.** [CallLogDirectionFilter] over the full event
 *     set. MANUAL "Logged" events count as reaching out: visible under
 *     All and Outgoing, hidden under Incoming.
 *   - **Honest pagination.** The VM observes the full correlated set
 *     (`observeForLog(Int.MAX_VALUE)` — bounded in practice by the 90-day
 *     import window) and paginates in-memory in [PAGE_SIZE] increments.
 *     [CallLogUiState.Ready.remainingCount] is the real remainder of the
 *     *filtered* set, so the footer label never promises rows that don't
 *     exist and disappears exactly when everything is shown. (The render
 *     bound that the old SQL LIMIT provided is preserved by slicing;
 *     totals can't be known honestly without a second count query, which
 *     would touch the DAO — out of this wave's scope.)
 *
 * `observeForLog()` already filters `contactId IS NOT NULL` AND orders DESC.
 * The VM joins each event with its contact (for name and `isIgnored`), the
 * most-recent ListMembership (for list context), and the active list set (to
 * resolve the membership row's listId → name).
 *
 * **Combine shape:** the combine over (events, contacts, memberships, lists)
 * is **native typed 4-arity**. The view [Query] (filter + visible count, one
 * atomic StateFlow) is lifted in via the chained-typed-combine pattern — a
 * single `.combine(queryFlow)`, no vararg `combine(*flows)` + `Array<Any?>`
 * casts.
 *
 * **Clock injection:** `clock.now()` is read once per emission and
 * threaded into the day-header labelling; the composable layer never
 * reads the JVM time API.
 *
 * **Pitfall (orphaned event):** an event whose contactId no longer maps
 * to a contact (FK cascade window, fake repo race) is dropped via
 * `mapNotNull` — defensive against the DAO returning a stale row that the
 * contact observer hasn't propagated yet.
 */
@HiltViewModel
class CallLogViewModel @Inject constructor(
    private val callEventRepo: CallEventRepository,
    private val contactRepo: ContactRepository,
    private val listMembershipDao: ListMembershipDao,
    private val listRepo: ListRepository,
    private val clock: Clock,
) : ViewModel() {

    private val zone: ZoneId = ZoneId.systemDefault()

    /**
     * The user's view query. Filter and visible-count live in
     * ONE StateFlow so a filter change (which also resets pagination to a
     * single page) is one atomic emission — two separate flows would leak an
     * intermediate (new filter, old count) frame through the combine.
     */
    private data class Query(
        val filter: CallLogDirectionFilter = CallLogDirectionFilter.ALL,
        val visibleCount: Int = PAGE_SIZE,
    )

    private val queryFlow = MutableStateFlow(Query())

    fun onFilterChange(filter: CallLogDirectionFilter) {
        queryFlow.update { current ->
            if (current.filter == filter) current
            else Query(filter = filter, visibleCount = PAGE_SIZE)
        }
    }

    fun onShowMore() {
        queryFlow.update { it.copy(visibleCount = it.visibleCount + PAGE_SIZE) }
    }

    /** Pre-formatted row plus the raw fields filtering + grouping need. */
    private data class LogItem(
        val occurredAt: Instant,
        val direction: CallDirection,
        val isManual: Boolean,
        val row: CallLogRow,
    )

    // Native typed 4-arity combine for the data flows; filter and
    // visible-count lifted in via chained typed .combine. Chained
    // .flowOn(Dispatchers.Default) keeps the join + grouping off Main.
    val uiState: StateFlow<CallLogUiState> =
        combine(
            callEventRepo.observeForLog(Int.MAX_VALUE),
            contactRepo.observeAll(),
            listMembershipDao.observeAll(),
            listRepo.observeAll(),
        ) { events, contacts, memberships, lists ->
            val byContactId = contacts.associateBy { it.id }
            // "list context" = contact's most-recent ListMembership (max
            // addedAt). When two memberships share a
            // timestamp `maxByOrNull` returns one deterministically per
            // input order; UI doesn't pin a tiebreak because v1 doesn't
            // surface ties anywhere. Empty list → null → empty subtitle.
            val mostRecentListByContactId: Map<Long, Long> = memberships
                .groupBy { it.contactId }
                .mapValues { (_, ms) -> ms.maxByOrNull { it.addedAt }?.listId ?: 0L }
            val listById = lists.associateBy { it.id }

            events.mapNotNull { ev ->
                val contact = byContactId[ev.contactId] ?: return@mapNotNull null
                val listId = mostRecentListByContactId[ev.contactId]
                val listName = listId?.let { listById[it]?.name } ?: ""
                // Manual-log surface — user-logged connections (source =
                // MANUAL, durationSeconds = 0) render "Logged" + a check-circle.
                // Attempt surface — reach-outs that didn't connect (source =
                // ATTEMPT, durationSeconds = 0) render "Attempted" + phone-slash.
                // Both carry a blank durationLabel, skipped by the row's
                // subtitle builder.
                val isManual = ev.source == CallSource.MANUAL
                val isAttempt = ev.source == CallSource.ATTEMPT
                val (directionWord, directionIcon) = when {
                    isAttempt -> "Attempted" to "phone-slash"
                    isManual -> "Logged" to "check-circle"
                    else -> when (ev.direction) {
                        CallDirection.OUTGOING -> "Outgoing" to "phone-outgoing"
                        CallDirection.INCOMING -> "Incoming" to "phone-incoming"
                    }
                }
                LogItem(
                    occurredAt = ev.occurredAt,
                    direction = ev.direction,
                    isManual = isManual,
                    row = CallLogRow(
                        callEventId = ev.id,
                        contactId = contact.id,
                        name = contact.displayName,
                        phone = contact.phoneNumber,
                        photoUri = contact.photoUri,
                        listContext = if (listName.isBlank()) "" else "from $listName",
                        durationLabel = if (isManual || isAttempt) "" else formatDuration(ev.durationSeconds),
                        directionWord = directionWord,
                        directionIconName = directionIcon,
                        timeLabel = formatWallClock(ev.occurredAt, zone),
                        isIgnored = contact.isIgnored,
                    ),
                )
            }
        }
            .combine(queryFlow) { items, query ->
                buildState(items, query.filter, query.visibleCount)
            }
            .flowOn(Dispatchers.Default)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000L),
                initialValue = CallLogUiState.Loading,
            )

    private fun buildState(
        items: List<LogItem>,
        filter: CallLogDirectionFilter,
        visibleCount: Int,
    ): CallLogUiState {
        if (items.isEmpty()) return CallLogUiState.Empty

        val filtered = items.filter { item ->
            when (filter) {
                CallLogDirectionFilter.ALL -> true
                // MANUAL "Logged" events are user-initiated reach-outs —
                // they belong with Outgoing and never under Incoming.
                CallLogDirectionFilter.OUTGOING ->
                    item.isManual || item.direction == CallDirection.OUTGOING
                CallLogDirectionFilter.INCOMING ->
                    !item.isManual && item.direction == CallDirection.INCOMING
            }
        }
        val visible = filtered.take(visibleCount)
        val today = clock.now().atZone(zone).toLocalDate()

        // Items arrive sorted occurredAt DESC, so groupBy's LinkedHashMap
        // preserves newest-day-first section order.
        val sections = visible
            .groupBy { it.occurredAt.atZone(zone).toLocalDate() }
            .map { (day, dayItems) ->
                CallLogDaySection(
                    epochDay = day.toEpochDay(),
                    label = formatDayHeader(day, today),
                    rows = dayItems.map { it.row },
                )
            }
        return CallLogUiState.Ready(
            sections = sections,
            filter = filter,
            remainingCount = filtered.size - visible.size,
        )
    }

    companion object {
        /**
         * True pagination increment. Each "Show n more" tap
         * reveals at most this many additional rows; the footer label shows
         * the smaller of this and the real remainder.
         */
        const val PAGE_SIZE: Int = 200
    }
}
