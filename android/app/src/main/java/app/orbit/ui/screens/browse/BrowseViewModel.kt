package app.orbit.ui.screens.browse

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.orbit.R
import app.orbit.data.Contact
import app.orbit.data.entity.CallEventEntity
import app.orbit.data.entity.ContactEntity
import app.orbit.data.entity.ListEntity
import app.orbit.data.entity.ListMembershipEntity
import app.orbit.data.entity.ListType
import app.orbit.data.feed.BrowseFeed
import app.orbit.data.mappers.toUiContact
import app.orbit.data.repository.ContactRepository
import app.orbit.data.repository.ListRepository
import app.orbit.domain.WidgetRefreshTrigger
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
import app.orbit.ui.util.UiText
import app.orbit.ui.util.formatRelative
import app.orbit.ui.util.pausedPeopleSnackbar
import app.orbit.ui.util.pausedSnackbar
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Browse ViewModel: wires per-list filtering + queue-order sort +
 * debounced search + 2 filter chips + curtain-aware UI.
 *
 * Multi-select widening: multi-select state + the dispatch methods + bulk
 * use-case injection (Move/Copy/BulkRemove/BulkIgnore/BulkPause) + UndoStack +
 * snackbar event flow. Every bulk handler takes `_isCommitting` with
 * `compareAndSet(false, true)` before it reads the selection, so a second tap
 * while one write is in flight returns at once (browse-2). Every write, bulk
 * or single-row, runs through [runMutation], so one that throws says
 * "Couldn't save your change" and announces nothing else (rules.md Code 3).
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
    // WIDGET-06 (wnl-6): the unpause and the pause-undo paths below write
    // `setPausedUntil` directly rather than through a use case, so they fire
    // the widget refresh themselves; otherwise a person the user just unpaused
    // stayed off the widget (or a re-paused one stayed on it, with a live Call
    // button) until the hourly sweep. Defaulted so JVM fixtures that construct
    // the VM positionally keep compiling (the use cases' precedent).
    private val widgetRefreshTrigger: WidgetRefreshTrigger = WidgetRefreshTrigger { },
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    // listId arrives as String; list ids are Longs (Routes.browse takes
    // ListEntity.id). A parse failure is a caller bug, so it is `Error`, not
    // `Empty` (rules.md Code 3): the Empty fallback used to offer "Add people"
    // for a list that does not exist (browse-19). That Error is a constant
    // with no feed behind it, so `canRetry = false`: Retry could change
    // nothing, and until 2026-10-06 it was the screen's accent anyway.
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
                initialValue = ""
            )

    /**
     * The browsed list's type, from the same source as [listName]; null until
     * the list row has emitted. A smart list's members are written by
     * `SmartListMembershipSync`, not by the user (ListRow.kt's "+" and
     * MembersPreview hide their add and remove controls for the same reason),
     * so the screen hides "+", the Empty state's "Add people", and the
     * selection bar's Remove and Move while this is [ListType.SMART]
     * (menus-1, browse-1). It rides beside the state contract, not on `Ready`,
     * because the app bar and the Empty state render outside `Ready`.
     */
    val listType: StateFlow<ListType?> =
        browseFeed.lists
            .map { lists -> lists.firstOrNull { it.id == listId }?.type }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000L),
                initialValue = null
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
                    ?.toSet().orEmpty()
            )

    private val _isCommitting = MutableStateFlow(false)

    private val _snackbarEvents = MutableSharedFlow<SnackbarEvent>(extraBufferCapacity = 1)
    val snackbarEvents: SharedFlow<SnackbarEvent> = _snackbarEvents.asSharedFlow()

    /**
     * Every list, for the inline Move/Copy `ListSelectorSheet`, which keeps the
     * regular (static), non-archived ones other than this list: a smart list
     * fills itself from its rule, so it is never a target, and copying people
     * onto the list they are already on would report a copy that did nothing.
     *
     * Sourced from process-scoped [BrowseFeed.lists]; the VM-owned
     * `stateIn(WhileSubscribed)` block is gone (ADR 0006 §Rule 1).
     */
    val lists: StateFlow<List<ListEntity>> = browseFeed.lists

    // BROWSE-06: bumped by [onRetry]; the feed evicts a failed list, so
    // re-calling forList subscribes afresh.
    private val retryCount = MutableStateFlow(0)

    /** The Error state's Retry. */
    fun onRetry() {
        retryCount.update { it + 1 }
    }

    /**
     * Browse VM is a thin subscriber to [BrowseFeed].
     *
     * The combine chain over `observeMembersOfList × observeAll × observeRecent`
     * lives in the singleton; the VM combines that singleton snapshot with
     * screen-ephemeral state (search query, filter chips, multi-select flags).
     *
     * BROWSE-06: the initial value is `Loading`, and stays so until the feed's
     * first real snapshot, so the screen never claims "No one here yet" for a
     * list whose members simply haven't arrived. (It started at `Empty` from
     * the feed-singleton refactor until 2026-10-05; the feed's placeholder
     * snapshot had no members, so Empty is what the first frame said.) A failed
     * feed is `Error`, with Retry.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<BrowseUiState> =
        if (listId == null) {
            // browse-19: a malformed route is a loud guard, not an empty list.
            flowOf<BrowseUiState>(BrowseUiState.Error(canRetry = false))
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000L),
                    initialValue = BrowseUiState.Error(canRetry = false)
                )
        } else {
            combine(
                retryCount.flatMapLatest { browseFeed.forList(listId) },
                searchQuery,
                _activeFilters,
                _callLogDenied
            ) { snapshot, query, filters, callLogDenied ->
                if (snapshot.failed) return@combine BrowseUiState.Error()
                if (!snapshot.loaded) return@combine BrowseUiState.Loading
                buildState(
                    snapshot.memberships,
                    snapshot.allContacts,
                    snapshot.callEvents,
                    snapshot.queueOrder,
                    query,
                    filters,
                    callLogDenied
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
                    if (state is BrowseUiState.Ready) {
                        state.copy(
                            isCommitting = committing
                        )
                    } else {
                        state
                    }
                }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000L),
                    initialValue = BrowseUiState.Loading
                )
        }

    // ─── Multi-select transitions (MOVE-01, MOVE-06) ────────────────────────────

    /** A row's "Select" (long-press): multi-select with that row selected. */
    fun onEnterMultiSelect(initialId: Long) {
        savedStateHandle[KEY_SELECTED_IDS] = longArrayOf(initialId)
        savedStateHandle[KEY_IS_MULTI_SELECT] = true
    }

    /**
     * The app bar's Select (vision BROWSE-2, browse-4): multi-select with
     * nothing selected yet. MOVE-06's auto-exit lives in [onToggleSelect]
     * only, so an empty entry stays until Back, the bar's close, or a
     * selection; the bar keeps its actions disabled meanwhile, so nothing
     * dispatches on an empty set (rules.md Code 3).
     */
    fun onEnterMultiSelect() {
        savedStateHandle[KEY_SELECTED_IDS] = LongArray(0)
        savedStateHandle[KEY_IS_MULTI_SELECT] = true
    }

    fun onToggleSelect(id: Long) {
        val current = selectedIdsFlow.value
        val next = if (id in current) current - id else current + id
        savedStateHandle[KEY_SELECTED_IDS] = next.toLongArray()
        if (next.isEmpty()) savedStateHandle[KEY_IS_MULTI_SELECT] = false // exit on empty (MOVE-06)
    }

    fun onExitMultiSelect() {
        savedStateHandle[KEY_SELECTED_IDS] = LongArray(0)
        savedStateHandle[KEY_IS_MULTI_SELECT] = false
    }

    /**
     * MOVE-05: the overflow's "Select all". The screen passes the ids of
     * `Ready.contacts`, which is already the searched and filtered set, so the
     * selection covers rows not yet on screen too. The VM operates on the id
     * set passed in and never walks UI nodes. (An `onSelectAllVisible` for the
     * rendered rows only existed beside this until 2026-10-06 with no caller.)
     */
    fun onSelectAllMatching(matchingIds: Set<Long>) {
        val next = selectedIdsFlow.value + matchingIds
        savedStateHandle[KEY_SELECTED_IDS] = next.toLongArray()
    }

    // ─── Bulk dispatch — Remove/Ignore/Pause use the bulk use cases ─────────────
    //
    // browse-2: each handler claims `_isCommitting` with compareAndSet BEFORE it
    // reads the selection. Two quick taps on Remove used to launch two
    // coroutines before onExitMultiSelect cleared the ids: the second found
    // nothing left to snapshot, so its inverse restored nothing, yet it
    // reported the full count, showed a second snackbar and, through
    // UndoStack.put (depth 1), replaced the only real Undo. The bar disables
    // its controls while this is true, so the guard is the backstop, not the
    // only line.
    //
    // The write, its Undo, its snackbar and the exit from multi-select all sit
    // inside runMutation, so a write that throws announces nothing and keeps
    // the selection for another try; `finally` still frees the bar.

    fun onBulkRemove() = viewModelScope.launch {
        if (!_isCommitting.compareAndSet(expect = false, update = true)) return@launch
        try {
            val ids = selectedIdsFlow.value.toList()
            if (ids.isEmpty()) return@launch
            val srcListId = listId ?: return@launch
            runMutation {
                val sourceListName = listRepo.getById(srcListId)?.name ?: ""
                val result = bulkRemoveFromListUseCase(srcListId, ids)
                undoStack.put(UndoStack.PendingUndo(result.inverse))
                _snackbarEvents.tryEmit(
                    SnackbarEvent.undoable(
                        UiText.plural(
                            R.plurals.browse_snackbar_removed,
                            result.count,
                            result.count,
                            sourceListName
                        )
                    )
                )
                onExitMultiSelect()
            }
        } finally {
            _isCommitting.value = false
        }
    }

    fun onBulkIgnore() = viewModelScope.launch {
        if (!_isCommitting.compareAndSet(expect = false, update = true)) return@launch
        try {
            val ids = selectedIdsFlow.value.toList()
            if (ids.isEmpty()) return@launch
            runMutation {
                val result = bulkIgnoreUseCase(ids)
                undoStack.put(UndoStack.PendingUndo(result.inverse))
                _snackbarEvents.tryEmit(
                    SnackbarEvent.undoable(
                        UiText.plural(R.plurals.browse_snackbar_ignored, result.count, result.count)
                    )
                )
                onExitMultiSelect()
            }
        } finally {
            _isCommitting.value = false
        }
    }

    fun onBulkPause(duration: PauseDuration) = viewModelScope.launch {
        if (!_isCommitting.compareAndSet(expect = false, update = true)) return@launch
        try {
            val ids = selectedIdsFlow.value.toList()
            if (ids.isEmpty()) return@launch
            runMutation {
                val result = bulkPauseUseCase(ids, duration)
                undoStack.put(UndoStack.PendingUndo(result.inverse))
                _snackbarEvents.tryEmit(
                    SnackbarEvent.undoable(pausedPeopleSnackbar(result.count, duration))
                )
                onExitMultiSelect()
            }
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
    // Those direct writes change who the widget may surface, so each fires
    // the widget refresh the use cases fire for theirs (WIDGET-06).

    fun onSingleRowIgnore(contactId: Long, contactName: String) = viewModelScope.launch {
        runMutation {
            val result = ignoreContactUseCase(contactId)
            undoStack.put(UndoStack.PendingUndo(result.inverse))
            _snackbarEvents.tryEmit(
                SnackbarEvent.undoable(
                    UiText.res(R.string.components_snackbar_ignored, contactName)
                )
            )
        }
    }

    /**
     * Long-press → Unpause on a paused row. Restores surfacing now; Undo puts
     * back the exact prior pause (indefinite or timed).
     */
    fun onSingleRowUnpause(contactId: Long, contactName: String) = viewModelScope.launch {
        runMutation {
            val prior = contactRepo.getById(contactId)?.pausedUntil
            contactRepo.setPausedUntil(contactId, null)
            widgetRefreshTrigger.scheduleRefresh()
            undoStack.put(
                UndoStack.PendingUndo(
                    inverse = {
                        contactRepo.setPausedUntil(contactId, prior)
                        widgetRefreshTrigger.scheduleRefresh()
                    }
                )
            )
            _snackbarEvents.tryEmit(
                SnackbarEvent.undoable(
                    UiText.res(R.string.components_snackbar_unpaused, contactName)
                )
            )
        }
    }

    fun onSingleRowPause(contactId: Long, contactName: String, duration: PauseDuration) =
        viewModelScope.launch {
            runMutation {
                val prior = contactRepo.getById(contactId)?.pausedUntil
                // The use case fires the widget refresh for the forward write.
                pauseContactUseCase(contactId, duration)
                undoStack.put(
                    UndoStack.PendingUndo(
                        inverse = {
                            contactRepo.setPausedUntil(contactId, prior)
                            widgetRefreshTrigger.scheduleRefresh()
                        }
                    )
                )
                _snackbarEvents.tryEmit(
                    SnackbarEvent.undoable(pausedSnackbar(contactName, duration))
                )
            }
        }

    // ─── Move/Copy via inline ListSelectorSheet ─────────────────────────────────
    //
    // Move/Copy dispatch through the use cases after the inline sheet
    // returns a target list. This sidesteps the LongArray-over-route-URL nav
    // complexity (BULK-05 picker still ships as full-screen for the no-selection
    // "Add" entry, which doesn't need to carry pre-selected ids).

    fun onBulkMove(targetListId: Long, targetListName: String) = viewModelScope.launch {
        if (!_isCommitting.compareAndSet(expect = false, update = true)) return@launch
        try {
            val ids = selectedIdsFlow.value.toList()
            val srcListId = listId ?: return@launch
            if (ids.isEmpty()) return@launch
            runMutation {
                val result = moveUseCase(srcListId, targetListId, ids)
                val moved = emitBatchResult(
                    result.count,
                    result.inverse,
                    UiText.plural(
                        R.plurals.components_snackbar_moved,
                        result.count,
                        result.count,
                        targetListName
                    ),
                )
                if (moved) onExitMultiSelect()
            }
        } finally {
            _isCommitting.value = false
        }
    }

    fun onBulkCopy(targetListId: Long, targetListName: String) = viewModelScope.launch {
        if (!_isCommitting.compareAndSet(expect = false, update = true)) return@launch
        try {
            val ids = selectedIdsFlow.value.toList()
            if (ids.isEmpty()) return@launch
            runMutation {
                val result = copyUseCase(targetListId, ids)
                val copied = emitBatchResult(
                    result.count,
                    result.inverse,
                    UiText.plural(
                        R.plurals.components_snackbar_copied,
                        result.count,
                        result.count,
                        targetListName
                    ),
                )
                if (copied) onExitMultiSelect()
            }
        } finally {
            _isCommitting.value = false
        }
    }

    /**
     * Move and Copy report a count of 0 when they short-circuit (a missing,
     * archived or smart destination; the sheet offers none of those, so this
     * is the backstop for a list that changed meanwhile). That used to put an
     * empty snackbar with Undo on screen, an Undo for nothing; it is now a
     * failed save (rules.md Code 3), the way the contact picker reports the
     * same case.
     */
    /**
     * Announces a Move or Copy and returns whether anything was written. A
     * count of 0 (the target was archived or became a smart list meanwhile)
     * says "Couldn't save your change" with no Undo and returns false, so the
     * caller keeps the selection for another target, exactly as a write that
     * throws does. Until 2026-10-07 a count-0 Move or Copy still left
     * multi-select, while a throw kept it: two failures, two behaviours.
     */
    private fun emitBatchResult(count: Int, inverse: suspend () -> Unit, message: UiText): Boolean {
        if (count == 0) {
            _snackbarEvents.tryEmit(SnackbarEvent(UiText.res(R.string.components_snackbar_save_failed)))
            return false
        }
        undoStack.put(UndoStack.PendingUndo(inverse))
        _snackbarEvents.tryEmit(SnackbarEvent.undoable(message))
        return true
    }

    fun onUndo() = viewModelScope.launch {
        runMutation { undoStack.take()?.inverse?.invoke() }
    }

    /**
     * Uniform mutation wrapper, `HomeViewModel.runMutation`'s shape: a write
     * that throws says "Couldn't save your change" and returns false, so the
     * caller announces nothing else (rules.md Code 3). Until 2026-10-06 the
     * bulk handlers were try/finally with no catch and the single-row ones
     * bare launches: with no CoroutineExceptionHandler anywhere in the app, a
     * throwing use case inside `viewModelScope.launch` reached the thread's
     * uncaught handler and took the process down, with no word to the user
     * either way. CancellationException is rethrown so structured concurrency
     * stays intact (rules.md Code 5).
     */
    private suspend fun runMutation(block: suspend () -> Unit): Boolean =
        try {
            block()
            true
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            _snackbarEvents.tryEmit(
                SnackbarEvent(UiText.res(R.string.components_snackbar_save_failed))
            )
            false
        }

    /**
     * LOW polish — surface a snackbar when a Browse row's UI id ("c-<long>") fails
     * to parse to a Long. Without this, the row long-press / tap falls into a
     * silent no-op that's invisible to the user. Mirrors the existing snackbar
     * surface used by bulk-action and single-row mutations.
     */
    fun onContactIdParseFail() {
        _snackbarEvents.tryEmit(SnackbarEvent(UiText.res(R.string.browse_snackbar_open_failed)))
    }

    private fun buildState(
        memberships: List<ListMembershipEntity>,
        allContacts: List<ContactEntity>,
        callEvents: List<CallEventEntity>,
        queueOrder: List<Long>,
        query: String,
        filters: Set<BrowseFilter>,
        callLogDenied: Boolean
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
                    phone = { (entity, _) -> entity.normalizedPhone }
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
                    queuePositions = queuePositionByEntityId.mapKeys { (entityId, _) -> "c-$entityId" }
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
     * Contact projection. A null label triggers "Never called" in BrowseRow.
     *
     * Delegates to the shared [formatRelative]
     * (`ui/util/RelativeTime.kt`): local calendar-day comparison + honest
     * singulars ("1 month ago", never "1 months ago"). Zone follows the
     * CallLogViewModel convention ([ZoneId.systemDefault] held in a field).
     */
    private fun Contact.withLastCallLabel(lastCallAt: Instant?, now: Instant): Contact {
        if (lastCallAt == null) return copy(lastCalledLabel = null)
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
