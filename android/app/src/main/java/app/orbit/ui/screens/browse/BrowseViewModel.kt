package app.orbit.ui.screens.browse

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.orbit.data.Contact
import app.orbit.data.entity.CallEventEntity
import app.orbit.data.entity.ContactEntity
import app.orbit.data.entity.ListEntity
import app.orbit.data.entity.ListMembershipEntity
import app.orbit.data.feed.BrowseFeed
import app.orbit.data.mappers.toUiContact
import app.orbit.data.repository.ContactRepository
import app.orbit.data.repository.ListRepository
import app.orbit.domain.clock.Clock
import app.orbit.domain.model.PauseDuration
import app.orbit.domain.search.ContactSearch
import app.orbit.domain.undo.UndoStack
import app.orbit.domain.usecase.BulkIgnoreUseCase
import app.orbit.domain.usecase.BulkPauseUseCase
import app.orbit.domain.usecase.BulkRemoveFromListUseCase
import app.orbit.domain.usecase.CopyContactsUseCase
import app.orbit.domain.usecase.IgnoreContactUseCase
import app.orbit.domain.usecase.MoveContactsUseCase
import app.orbit.domain.usecase.PauseContactUseCase
import app.orbit.ui.screens.picker.SnackbarEvent
import app.orbit.ui.util.formatRelative
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Browse ViewModel — wires per-list filtering + lastCallAt sort +
 * debounced search + 2 filter chips + curtain-aware UI.
 *
 * Multi-select widening — multi-select state + 11 dispatch methods + bulk
 * use-case injection (Move/Copy/BulkRemove/BulkIgnore/BulkPause) + UndoStack +
 * snackbar event flow.
 *
 * Sort: queue order (matching [SurfaceQueueUseCase]); non-queued members
 * (paused / out-of-active-hours / no-template / engine-null) trail, sorted by
 * displayName. Queue order arrives in [BrowseFeedSnapshot.queueOrder] — ADR 0006
 * puts that composition in the feed singleton, not the VM. The due-dot /
 * paused-ignored status / call-log-denied notice are independent of ordering and
 * unchanged.
 *
 * Filter logic (per the BROWSE-02 chip-composition rule):
 *   - Search × chip = AND (search narrows the chip-filtered set).
 *   - Chip × chip = UNION (OR). With both "Called recently" + "Not called yet"
 *     active, results show the union — their AND-intersection is always empty
 *     (a contact cannot have ≥1 call in 30 days AND zero CallEvents).
 *
 * Search debounce (250ms) lives in the screen — see [BrowseListScreen]'s
 * `snapshotFlow { queryText }.debounce(250)` chain. The VM only echoes the
 * `SavedStateHandle`-backed query for restoration on rotation.
 *
 * **Mapper note:** imports `toUiContact` from
 * `app.orbit.data.mappers.ContactMapper.kt` (singular file — NOT the plural
 * variant).
 */
@HiltViewModel
class BrowseViewModel @Inject constructor(
    private val contactRepo: ContactRepository,
    private val listRepo: ListRepository,
    private val browseFeed: BrowseFeed,
    private val clock: Clock,
    // Multi-select bulk dispatch.
    private val moveUseCase: MoveContactsUseCase,
    private val copyUseCase: CopyContactsUseCase,
    private val bulkRemoveFromListUseCase: BulkRemoveFromListUseCase,
    private val bulkIgnoreUseCase: BulkIgnoreUseCase,
    private val bulkPauseUseCase: BulkPauseUseCase,
    // Single-row quick actions from the Browse long-press menu.
    private val ignoreContactUseCase: IgnoreContactUseCase,
    private val pauseContactUseCase: PauseContactUseCase,
    private val undoStack: UndoStack,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    // listId arrives as String. null parse -> empty UI.
    private val listId: Long? = savedStateHandle.get<String>("listId")?.toLongOrNull()

    // Zone for the shared relative-time formatter (CallLogViewModel
    // convention: read once, not per row).
    private val zone: ZoneId = ZoneId.systemDefault()

    val searchQuery: StateFlow<String> =
        savedStateHandle.getStateFlow(SEARCH_QUERY_KEY, "")

    fun onSearchChanged(q: String) {
        savedStateHandle[SEARCH_QUERY_KEY] = q
    }

    private val _activeFilters = MutableStateFlow<Set<BrowseFilter>>(emptySet())
    val activeFilters: StateFlow<Set<BrowseFilter>> = _activeFilters.asStateFlow()

    fun onToggleFilter(filter: BrowseFilter) {
        _activeFilters.value = _activeFilters.value.toMutableSet().also { current ->
            if (filter in current) current.remove(filter) else current.add(filter)
        }
    }

    /** 2026-06-09 #19 — FilteredEmpty's "Clear filters" action. */
    fun onClearFilters() {
        _activeFilters.value = emptySet()
    }

    // ─── 2026-06-09 #19 — real READ_CALL_LOG state ──────────────────────────────
    //
    // Pushed from the screen on every ON_RESUME (CardViewScreen precedent —
    // returning from Settings clears the notice once the user grants the
    // permission). The VM stays Android-free so JVM unit tests can drive the
    // denied path directly.
    private val _callLogDenied = MutableStateFlow(false)

    fun onCallLogPermissionChanged(denied: Boolean) {
        _callLogDenied.value = denied
    }

    /**
     * 2026-06-09 #19 — the real list name for the app-bar title (the screen
     * previously hardcoded "Your people" even though the name was one map away).
     * Sourced from the process-scoped [BrowseFeed.lists]; empty while the list
     * row hasn't emitted (screen falls back to the generic title).
     */
    val listName: StateFlow<String> =
        browseFeed.lists
            .map { lists -> lists.firstOrNull { it.id == listId }?.name.orEmpty() }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000L),
                initialValue = "",
            )

    // ─── Multi-select state ─────────────────────────────────────────────────────
    //
    // M4 + H5: `selectedIdsFlow` and `isMultiSelectFlow` use
    // `SavedStateHandle.getStateFlow()` as the source-of-truth — the bundle IS
    // the writable backing store. Setters write `savedStateHandle[K] = value`
    // directly; the StateFlow re-emits to downstream consumers. This eliminates
    // the prior mirror-chain pattern that wrote a Bundle on Main per emission.
    // Bundle-compatible types: `LongArray` for the id set, `Boolean` for the
    // mode flag.
    private val isMultiSelectFlow: StateFlow<Boolean> =
        savedStateHandle.getStateFlow(KEY_IS_MULTI_SELECT, false)

    private val selectedIdsFlow: StateFlow<Set<Long>> =
        savedStateHandle.getStateFlow<LongArray?>(KEY_SELECTED_IDS, null)
            .map { it?.toSet().orEmpty() }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = savedStateHandle.get<LongArray>(KEY_SELECTED_IDS)
                    ?.toSet().orEmpty(),
            )

    private val _isCommitting = MutableStateFlow(false)

    private val _snackbarEvents = MutableSharedFlow<SnackbarEvent>(extraBufferCapacity = 1)
    val snackbarEvents: SharedFlow<SnackbarEvent> = _snackbarEvents.asSharedFlow()

    /**
     * Non-archived lists for the inline Move/Copy `ListSelectorSheet`. Filters
     * out the source list at sheet level (Move cannot target source); Copy
     * shows all.
     *
     * Sourced from process-scoped [BrowseFeed.lists]; the VM-owned
     * `stateIn(WhileSubscribed)` block is gone (ADR 0006 §Rule 1).
     */
    val lists: StateFlow<List<ListEntity>> = browseFeed.lists

    /**
     * Browse VM is a thin subscriber to [BrowseFeed].
     *
     * The combine chain over `observeMembersOfList × observeAll × observeRecent`
     * lives in the singleton; the VM combines that singleton snapshot with
     * screen-ephemeral state (search query, filter chips, multi-select flags).
     * Initial value flips from `BrowseUiState.Loading` to `BrowseUiState.Empty`
     * so the screen never renders the retired Loading shell.
     */
    val uiState: StateFlow<BrowseUiState> =
        if (listId == null) {
            flowOf<BrowseUiState>(BrowseUiState.Empty)
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000L),
                    initialValue = BrowseUiState.Empty,
                )
        } else {
            combine(
                browseFeed.forList(listId),
                searchQuery,
                _activeFilters,
                _callLogDenied,
            ) { snapshot, query, filters, callLogDenied ->
                buildState(
                    snapshot.memberships,
                    snapshot.allContacts,
                    snapshot.callEvents,
                    snapshot.queueOrder,
                    query,
                    filters,
                    callLogDenied,
                )
            }
                // combine arity caps at 5 — chain three more for multi-select state.
                .combine(isMultiSelectFlow) { state, isMs ->
                    if (state is BrowseUiState.Ready) state.copy(isMultiSelect = isMs) else state
                }
                .combine(selectedIdsFlow) { state, selected ->
                    if (state is BrowseUiState.Ready) state.copy(selectedIds = selected) else state
                }
                .combine(_isCommitting) { state, committing ->
                    if (state is BrowseUiState.Ready) state.copy(isCommitting = committing) else state
                }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000L),
                    initialValue = BrowseUiState.Empty,
                )
        }

    // ─── Multi-select transitions (MOVE-01, MOVE-06) ────────────────────────────

    fun onEnterMultiSelect(initialId: Long) {
        savedStateHandle[KEY_SELECTED_IDS] = longArrayOf(initialId)
        savedStateHandle[KEY_IS_MULTI_SELECT] = true
    }

    fun onToggleSelect(id: Long) {
        val current = selectedIdsFlow.value
        val next = if (id in current) current - id else current + id
        savedStateHandle[KEY_SELECTED_IDS] = next.toLongArray()
        if (next.isEmpty()) savedStateHandle[KEY_IS_MULTI_SELECT] = false  // exit on empty (MOVE-06)
    }

    fun onExitMultiSelect() {
        savedStateHandle[KEY_SELECTED_IDS] = LongArray(0)
        savedStateHandle[KEY_IS_MULTI_SELECT] = false
    }

    fun onSelectAllVisible(visibleIds: Set<Long>) {
        // MOVE-05: Select-all = visible filtered rows (UI-rendered).
        val next = selectedIdsFlow.value + visibleIds
        savedStateHandle[KEY_SELECTED_IDS] = next.toLongArray()
    }

    fun onSelectAllMatching(matchingIds: Set<Long>) {
        // MOVE-05: Select-all-matching = all filtered rows incl. unrendered.
        // Pitfall 8 — VM operates on the matching id set passed in, NEVER walks UI nodes.
        val next = selectedIdsFlow.value + matchingIds
        savedStateHandle[KEY_SELECTED_IDS] = next.toLongArray()
    }

    // ─── Bulk dispatch — Remove/Ignore/Pause use the bulk use cases ─────────────

    fun onBulkRemove() = viewModelScope.launch {
        val ids = selectedIdsFlow.value.toList()
        if (ids.isEmpty()) return@launch
        val srcListId = listId ?: return@launch
        _isCommitting.value = true
        try {
            val sourceListName = listRepo.getById(srcListId)?.name ?: ""
            val result = bulkRemoveFromListUseCase(srcListId, ids, sourceListName)
            undoStack.put(UndoStack.PendingUndo(result.inverse, result.label))
            _snackbarEvents.tryEmit(SnackbarEvent(result.label, "Undo"))
            onExitMultiSelect()
        } finally {
            _isCommitting.value = false
        }
    }

    fun onBulkIgnore() = viewModelScope.launch {
        val ids = selectedIdsFlow.value.toList()
        if (ids.isEmpty()) return@launch
        _isCommitting.value = true
        try {
            val result = bulkIgnoreUseCase(ids)
            undoStack.put(UndoStack.PendingUndo(result.inverse, result.label))
            _snackbarEvents.tryEmit(SnackbarEvent(result.label, "Undo"))
            onExitMultiSelect()
        } finally {
            _isCommitting.value = false
        }
    }

    fun onBulkPause(duration: PauseDuration) = viewModelScope.launch {
        val ids = selectedIdsFlow.value.toList()
        if (ids.isEmpty()) return@launch
        _isCommitting.value = true
        try {
            val result = bulkPauseUseCase(ids, duration)
            undoStack.put(UndoStack.PendingUndo(result.inverse, result.label))
            _snackbarEvents.tryEmit(SnackbarEvent(result.label, "Undo"))
            onExitMultiSelect()
        } finally {
            _isCommitting.value = false
        }
    }

    // ─── Single-row quick actions ───────────────────────────────────────────────
    //
    // BROWSE-04 + IGNORE-02 + IGNORE-03: from Browse single-row long-press
    // DropdownMenu. Backed by IgnoreContactUseCase + PauseContactUseCase
    // (NOT the bulk variants — single-row dispatch keeps the snackbar copy
    // singular: "Ignored {Name}" / "Paused {Name} for 1 week"). The
    // multi-select bulk handlers above remain untouched — Select menu item
    // continues to invoke `onEnterMultiSelect`.
    //
    // Pause has no `inverse` from the use case (it returns Unit), so we
    // capture the prior `pausedUntil` Instant before dispatch and build the
    // inverse closure here against `contactRepo.setPausedUntil(id, prior)`.

    fun onSingleRowIgnore(contactId: Long, contactName: String) = viewModelScope.launch {
        val result = ignoreContactUseCase(contactId, contactName)
        undoStack.put(UndoStack.PendingUndo(result.inverse, result.label))
        _snackbarEvents.tryEmit(SnackbarEvent(result.label, "Undo"))
    }

    fun onSingleRowPause(
        contactId: Long,
        contactName: String,
        duration: PauseDuration,
    ) = viewModelScope.launch {
        val prior = contactRepo.getById(contactId)?.pausedUntil
        pauseContactUseCase(contactId, duration)
        val labelSuffix = when (duration) {
            PauseDuration.OneWeek -> "for 1 week"
            PauseDuration.OneMonth -> "for 1 month"
            PauseDuration.Indefinite -> "indefinitely"
        }
        val label = "Paused $contactName $labelSuffix"
        undoStack.put(
            UndoStack.PendingUndo(
                inverse = { contactRepo.setPausedUntil(contactId, prior) },
                label = label,
            ),
        )
        _snackbarEvents.tryEmit(SnackbarEvent(label, "Undo"))
    }

    // ─── Move/Copy via inline ListSelectorSheet ─────────────────────────────────
    //
    // Move/Copy dispatch through the use cases after the inline sheet
    // returns a target list. This sidesteps the LongArray-over-route-URL nav
    // complexity (BULK-05 picker still ships as full-screen for the no-selection
    // "Add" entry, which doesn't need to carry pre-selected ids).

    fun onBulkMove(targetListId: Long, targetListName: String) = viewModelScope.launch {
        val ids = selectedIdsFlow.value.toList()
        val srcListId = listId ?: return@launch
        if (ids.isEmpty()) return@launch
        _isCommitting.value = true
        try {
            val result = moveUseCase(srcListId, targetListId, ids, targetListName)
            undoStack.put(UndoStack.PendingUndo(result.inverse, result.label))
            _snackbarEvents.tryEmit(SnackbarEvent(result.label, "Undo"))
            onExitMultiSelect()
        } finally {
            _isCommitting.value = false
        }
    }

    fun onBulkCopy(targetListId: Long, targetListName: String) = viewModelScope.launch {
        val ids = selectedIdsFlow.value.toList()
        if (ids.isEmpty()) return@launch
        _isCommitting.value = true
        try {
            val result = copyUseCase(targetListId, ids, targetListName)
            undoStack.put(UndoStack.PendingUndo(result.inverse, result.label))
            _snackbarEvents.tryEmit(SnackbarEvent(result.label, "Undo"))
            onExitMultiSelect()
        } finally {
            _isCommitting.value = false
        }
    }

    fun onUndo() = viewModelScope.launch {
        undoStack.take()?.inverse?.invoke()
    }

    /**
     * LOW polish — surface a snackbar when a Browse row's UI id ("c-<long>") fails
     * to parse to a Long. Without this, the row long-press / tap falls into a
     * silent no-op that's invisible to the user. Mirrors the existing snackbar
     * surface used by bulk-action and single-row mutations.
     */
    fun onContactIdParseFail() {
        _snackbarEvents.tryEmit(SnackbarEvent("Couldn't open contact"))
    }

    private fun buildState(
        memberships: List<ListMembershipEntity>,
        allContacts: List<ContactEntity>,
        callEvents: List<CallEventEntity>,
        queueOrder: List<Long>,
        query: String,
        filters: Set<BrowseFilter>,
        callLogDenied: Boolean,
    ): BrowseUiState {
        if (memberships.isEmpty()) return BrowseUiState.Empty

        // 2026-06-09 #19 — both filter chips answer "when was this person last
        // called?", which is unanswerable without READ_CALL_LOG. Surfacing a
        // result set would be a lie (every contact reads never-called), so the
        // hard CallLogDenied gate fires instead of a false filter result.
        if (callLogDenied && filters.isNotEmpty()) return BrowseUiState.CallLogDenied

        // Build (contact, lastCallAt?) pairs scoped to this list's members.
        val memberIds = memberships.map { it.contactId }.toSet()
        val contactsHere = allContacts.filter { it.id in memberIds }

        val lastCallByContact: Map<Long, Instant?> =
            callEvents.groupBy { it.contactId }
                .mapValues { (_, events) -> events.maxByOrNull { it.occurredAt }?.occurredAt }

        // Apply filter chips per W-02 chip-composition rule (UI-SPEC §BROWSE-02).
        val now = clock.now()
        val recentlyCalledThreshold = now.minus(Duration.ofDays(30))
        val pairs: List<Pair<ContactEntity, Instant?>> =
            contactsHere.map { it to lastCallByContact[it.id] }

        val afterFilter: List<Pair<ContactEntity, Instant?>> =
            if (filters.isEmpty()) {
                pairs
            } else {
                pairs.filter { (_, lastCallAt) ->
                    val matches = mutableSetOf<BrowseFilter>()
                    if (lastCallAt != null && lastCallAt.isAfter(recentlyCalledThreshold)) {
                        matches += BrowseFilter.CalledRecently
                    }
                    if (lastCallAt == null) {
                        matches += BrowseFilter.NotCalledYet
                    }
                    // UNION (OR) semantics across chips — W-02 user decision.
                    // Reference: UI-SPEC §BROWSE-02 chip-composition rule.
                    matches.any { it in filters }
                }
            }

        // Queue-order sort replaces lastCallAt DESC. `queueOrder` is the
        // canonical rendering order for queued members (SurfaceQueueUseCase). Non-queued
        // members (paused / out-of-active-hours / no-template / engine-null) follow,
        // sorted by displayName for stable rendering. Search still runs AFTER this sort;
        // ContactSearch.filterRanked is a stable rank-only sort, so queue order survives
        // within each rank band (the position number stays meaningful while filtering).
        val queuePositionByEntityId: Map<Long, Int> =
            queueOrder.withIndex().associate { (idx, contactId) -> contactId to (idx + 1) }
        val (queuedPairs, nonQueuedPairs) = afterFilter.partition { (entity, _) ->
            entity.id in queuePositionByEntityId
        }
        val sorted =
            queuedPairs.sortedBy { (entity, _) -> queuePositionByEntityId.getValue(entity.id) } +
                nonQueuedPairs.sortedBy { (entity, _) -> entity.displayName.lowercase() }

        // #16 — diacritic-folded, rank-ordered, phone-aware matching via the
        // shared domain matcher (replaces the naive displayName.contains).
        // Search × chip composition stays AND (UI-SPEC §BROWSE-02).
        val q = query.trim()
        val afterSearch =
            if (q.isEmpty()) {
                sorted
            } else {
                ContactSearch.filterRanked(
                    items = sorted,
                    query = q,
                    name = { (entity, _) -> entity.displayName },
                    phone = { (entity, _) -> entity.normalizedPhone },
                )
            }

        return when {
            afterSearch.isNotEmpty() -> {
                // 2026-06-09 #19 — per-row orientation. Due rides the persisted
                // membership nextDueAt (null or past = due, matching the
                // dueCount SQL in ListRepository.recomputeDueCountForList);
                // paused/ignored suppress the dot — a paused person is not
                // surfaceable, so claiming "due" would conflict with the
                // status word.
                val membershipByContact = memberships.associateBy { it.contactId }
                val dueIds = mutableSetOf<String>()
                val rowStatus = mutableMapOf<String, BrowseRowStatus>()
                afterSearch.forEach { (entity, _) ->
                    val uiId = "c-${entity.id}"
                    val status = when {
                        entity.isIgnored -> BrowseRowStatus.Ignored
                        entity.pausedUntil?.isAfter(now) == true -> BrowseRowStatus.Paused
                        else -> null
                    }
                    if (status != null) {
                        rowStatus[uiId] = status
                    } else {
                        val nextDueAt = membershipByContact[entity.id]?.nextDueAt
                        if (nextDueAt == null || !nextDueAt.isAfter(now)) dueIds += uiId
                    }
                }
                BrowseUiState.Ready(
                    contacts = afterSearch.map { (entity, lastCallAt) ->
                        entity.toUiContact().withLastCallLabel(lastCallAt, now)
                    },
                    searchQuery = q,
                    activeFilters = filters,
                    callLogPermissionDenied = callLogDenied,
                    dueIds = dueIds,
                    rowStatus = rowStatus,
                    queuePositions = queuePositionByEntityId.mapKeys { (entityId, _) -> "c-$entityId" },
                )
            }
            q.isNotEmpty() -> BrowseUiState.NoMatches(q)
            // 2026-06-09 #19 — members exist but the chips excluded everyone;
            // "No one here yet." would be false. Distinct state with a
            // clear-filters action.
            filters.isNotEmpty() -> BrowseUiState.FilteredEmpty
            else -> BrowseUiState.Empty
        }
    }

    /**
     * Helper — overlay a relative-time `lastCalledLabel` on the minimal-safe
     * Contact projection. Empty label triggers "Never called" in BrowseRow.
     *
     * Delegates to the shared [formatRelative]
     * (`ui/util/RelativeTime.kt`): local calendar-day comparison + honest
     * singulars ("1 month ago", never "1 months ago"). Zone follows the
     * CallLogViewModel convention ([ZoneId.systemDefault] held in a field).
     */
    private fun Contact.withLastCallLabel(lastCallAt: Instant?, now: Instant): Contact {
        if (lastCallAt == null) return copy(lastCalledLabel = "")
        return copy(lastCalledLabel = formatRelative(lastCallAt, now, zone))
    }

    companion object {
        internal const val SEARCH_QUERY_KEY = "searchQuery"

        // M4 — SavedStateHandle keys for multi-select persistence so process
        // death mid-flow doesn't drop the user's selection.
        private const val KEY_SELECTED_IDS = "selectedIds"
        private const val KEY_IS_MULTI_SELECT = "isMultiSelect"
    }
}
