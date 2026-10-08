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
import app.orbit.data.feed.BrowseFeedSnapshot
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
import app.orbit.domain.usecase.ReorderSequenceUseCase
import app.orbit.domain.usecase.ReorderSequenceUseCase.Companion.afterMove
import app.orbit.domain.usecase.SequencedContact
import app.orbit.ui.screens.picker.SnackbarEvent
import app.orbit.ui.util.ComesUp
import app.orbit.ui.util.UiText
import app.orbit.ui.util.comesUp
import app.orbit.ui.util.formatRelative
import app.orbit.ui.util.formatSpan
import app.orbit.ui.util.pausedPeopleSnackbar
import app.orbit.ui.util.pausedSnackbar
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 * Order (BROWSE-07): the sequence, everyone in the order the card brings
 * them up ([BrowseFeedSnapshot.sequence], the card's own ordering through
 * SurfaceQueueUseCase; ADR 0006 puts that composition in the feed singleton,
 * not the VM), each with when they come up; then anyone the rule cannot
 * place, alphabetically; then the paused people, soonest back first, with
 * when; then the ignored ones. Opened from the card, the card's person is
 * marked (BROWSE-09). A drag writes new times through
 * [ReorderSequenceUseCase] (BROWSE-08) and shows the dropped order at once
 * (see [pendingMove]).
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
    // BROWSE-08: a drag in the sequence.
    private val reorderSequence: ReorderSequenceUseCase,
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

    // BROWSE-09: the person on the card when its menu's "Browse people" opened
    // this screen (the route's optional `focus`); absent from "Browse this
    // list" on the All quiet deck, where the card shows nobody.
    private val focusContactId: Long? = savedStateHandle.get<String>(FOCUS_KEY)?.toLongOrNull()

    // BROWSE-09: the sequence's head the first time this screen had data.
    // While it is still the head, the card shows the person it passed
    // ([focusContactId]); once the head changes (a drag here, a call), the
    // card will show the new head, so the mark follows it. Written once, from
    // the state pipeline, which runs on one coroutine.
    private var headWhenOpened: Long? = null

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

    /**
     * BROWSE-08: the drop the user just made, shown at once while its write
     * lands. It applies only to the snapshot it was made against ([base]): the
     * write's own result arrives as a new snapshot, which already holds the
     * new order, so the overlay retires itself without a flash back to the old
     * order. A failed write clears it, which puts the row back where it was;
     * so does an Undo.
     */
    private val pendingMove = MutableStateFlow<PendingMove?>(null)

    private class PendingMove(val contactId: Long, val placeAfter: Long?, val base: BrowseFeedSnapshot)

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
                _callLogDenied,
                pendingMove
            ) { snapshot, query, filters, callLogDenied, pending ->
                if (snapshot.failed) return@combine BrowseUiState.Error()
                if (!snapshot.loaded) return@combine BrowseUiState.Loading
                // The drop, with the times its write will store, so the moved
                // row's "when" is right at once. A plan that cannot be made
                // shows the stored order: the write runs the same plan and
                // reports the failure (rules.md Code 3), so nothing is hidden.
                val sequence = pending?.takeIf { it.base === snapshot }
                    ?.let { move ->
                        runCatching {
                            snapshot.sequence.afterMove(move.contactId, move.placeAfter, clock.now())
                        }.getOrNull()
                    }
                    ?: snapshot.sequence
                buildState(
                    snapshot.memberships,
                    snapshot.allContacts,
                    snapshot.callEvents,
                    sequence,
                    query,
                    filters,
                    callLogDenied
                )
            }
                // combine arity caps at 5; chain three more for multi-select state.
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

    // ─── BROWSE-08: drag to reorder ─────────────────────────────────────────────

    // One reorder write at a time (the ListsManagerViewModel precedent): each
    // plan reads the sequence afresh, so a second drop must see the first
    // one's times, not race it.
    private val reorderMutex = Mutex()

    /**
     * A drop in the sequence, from the handle or TalkBack's Move up / Move
     * down: [contactId] now follows [placeAfter] (null: the top of the
     * sequence). The screen shows the dropped order at once ([pendingMove]);
     * the write goes through [ReorderSequenceUseCase], which says which way
     * they moved and hands back the exact inverse for Undo. A write that
     * fails, or a person who left the sequence meanwhile, says "Couldn't save
     * your change" with no Undo, and the row goes back (rules.md Code 3).
     * [name] is the row's name, for "Moved Kai earlier".
     */
    fun onReorder(contactId: Long, placeAfter: Long?, name: String) = viewModelScope.launch {
        val id = listId ?: return@launch
        pendingMove.value = PendingMove(contactId, placeAfter, browseFeed.forList(id).value)
        val saved = runMutation {
            val result = reorderMutex.withLock { reorderSequence(id, contactId, placeAfter) }
            when (result) {
                is ReorderSequenceUseCase.Result.Moved -> {
                    undoStack.put(UndoStack.PendingUndo(result.inverse))
                    val firstName = name.trim().substringBefore(' ').ifBlank { name }
                    _snackbarEvents.tryEmit(
                        SnackbarEvent.undoable(
                            UiText.res(
                                if (result.earlier) {
                                    R.string.browse_snackbar_moved_earlier
                                } else {
                                    R.string.browse_snackbar_moved_later
                                },
                                firstName
                            )
                        )
                    )
                }
                ReorderSequenceUseCase.Result.Unchanged -> pendingMove.value = null
                ReorderSequenceUseCase.Result.Missing -> {
                    pendingMove.value = null
                    _snackbarEvents.tryEmit(SnackbarEvent(UiText.res(R.string.components_snackbar_save_failed)))
                }
            }
        }
        if (!saved) pendingMove.value = null
    }

    fun onUndo() = viewModelScope.launch {
        // An Undo replaces whatever order a drop was still showing.
        pendingMove.value = null
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
        sequence: List<SequencedContact>,
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

        val memberIds = memberships.map { it.contactId }.toSet()
        val contactsHere = allContacts.filter { it.id in memberIds }
        val now = clock.now()

        val lastCallByContact: Map<Long, Instant?> =
            callEvents.groupBy { it.contactId }
                .mapValues { (_, events) -> events.maxByOrNull { it.occurredAt }?.occurredAt }

        // BROWSE-07: the groups, in the order the screen draws them. The
        // sequence is the card's own order (SurfaceOrder through the feed), so
        // its first row is the card's person and Later, Sooner and a call move
        // rows here exactly as they move the deck. Outside it: anyone the
        // rule cannot place (archived, or a list with no rule) by name; the
        // paused, soonest back first ("until you unpause" is the far-future
        // sentinel, so it sorts last); the ignored by name. Ignored wins over
        // paused, as on the row's word.
        val sequenceIds = sequence.map { it.contact.id }
        val inSequence = sequenceIds.toSet()
        val contactById = contactsHere.associateBy { it.id }
        val outside = contactsHere.filter { it.id !in inSequence }
        val isPaused = { c: ContactEntity -> !c.isIgnored && c.pausedUntil?.isAfter(now) == true }
        val byName = compareBy<ContactEntity> { it.displayName.lowercase() }
        val ordered: List<ContactEntity> =
            sequenceIds.mapNotNull { contactById[it] } +
                outside.filter { !it.isIgnored && !isPaused(it) }.sortedWith(byName) +
                outside.filter(isPaused).sortedWith(compareBy<ContactEntity> { it.pausedUntil }.then(byName)) +
                outside.filter { it.isIgnored }.sortedWith(byName)

        // Filter chips per the BROWSE-02 chip-composition rule: chip × chip is
        // a UNION (OR), since "called in the last 30 days" and "never called"
        // never overlap.
        val recentlyCalledThreshold = now.minus(Duration.ofDays(30))
        val afterFilter =
            if (filters.isEmpty()) {
                ordered
            } else {
                ordered.filter { entity ->
                    val lastCallAt = lastCallByContact[entity.id]
                    val matches = mutableSetOf<BrowseFilter>()
                    if (lastCallAt != null && lastCallAt.isAfter(recentlyCalledThreshold)) {
                        matches += BrowseFilter.CalledRecently
                    }
                    if (lastCallAt == null) matches += BrowseFilter.NotCalledYet
                    matches.any { it in filters }
                }
            }

        // #16: diacritic-folded, phone-aware matching via the shared domain
        // matcher; search × chip stays AND (BROWSE-02). A filter, not a rank:
        // the order is what this screen is about (BROWSE-07), so a match keeps
        // its place and its number. ContactSearch.filterRanked put word-start
        // matches first, which reordered the queue's numbers ("4, 2, 7") until
        // 2026-10-07.
        val q = query.trim()
        val afterSearch =
            if (q.isEmpty()) {
                afterFilter
            } else {
                afterFilter.filter { ContactSearch.match(q, it.displayName, it.normalizedPhone) != null }
            }

        return when {
            afterSearch.isNotEmpty() -> {
                val timeById: Map<Long, Instant> = sequence.associate { it.contact.id to it.nextDueAt }
                val membershipByContact = memberships.associateBy { it.contactId }
                val dueIds = mutableSetOf<String>()
                val rowStatus = mutableMapOf<String, BrowseRowStatus>()
                val whenLabels = mutableMapOf<String, UiText>()
                val untilLabels = mutableMapOf<String, UiText>()
                afterSearch.forEach { entity ->
                    val uiId = "c-${entity.id}"
                    val pausedUntil = entity.pausedUntil?.takeIf { it.isAfter(now) }
                    val status = when {
                        entity.isIgnored -> BrowseRowStatus.Ignored
                        pausedUntil != null -> BrowseRowStatus.Paused
                        else -> null
                    }
                    if (status != null) rowStatus[uiId] = status
                    val time = timeById[entity.id]
                    when {
                        time != null -> {
                            whenLabels[uiId] = whenLabel(time, now)
                            // The dot and "Up now" are one fact (BROWSE-07): a
                            // never-scheduled person whose rule puts them in the
                            // future said "worth a call now" beside "Thursday".
                            if (status == null && !time.isAfter(now)) dueIds += uiId
                        }
                        status == BrowseRowStatus.Paused && pausedUntil != null ->
                            untilLabels[uiId] = untilLabel(pausedUntil)
                        // Outside the sequence and not paused or ignored: the
                        // persisted time, null or past (recomputeDueCountForList's
                        // rule), as before the sequence.
                        status == null -> {
                            val nextDueAt = membershipByContact[entity.id]?.nextDueAt
                            if (nextDueAt == null || !nextDueAt.isAfter(now)) dueIds += uiId
                        }
                    }
                }
                BrowseUiState.Ready(
                    contacts = afterSearch.map { entity ->
                        entity.toUiContact().withLastCallLabel(lastCallByContact[entity.id], now)
                    },
                    searchQuery = q,
                    activeFilters = filters,
                    callLogPermissionDenied = callLogDenied,
                    dueIds = dueIds,
                    rowStatus = rowStatus,
                    queuePositions = sequenceIds.withIndex().associate { (idx, id) -> "c-$id" to idx + 1 },
                    whenLabels = whenLabels,
                    untilLabels = untilLabels,
                    onYourCardId = cardPersonRow(sequenceIds.firstOrNull(), memberIds)
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
     * BROWSE-09: the row marked "On your card". Opened from the card's menu,
     * that is the person the card passed, wherever the order puts them (the
     * head almost always; see [headWhenOpened]); once the head has changed
     * since, the card will show the new head, so that row is marked. Opened
     * any other way, no row is.
     */
    private fun cardPersonRow(head: Long?, memberIds: Set<Long>): String? {
        val focus = focusContactId ?: return null
        if (headWhenOpened == null) headWhenOpened = head
        val marked = if (head == null || head == headWhenOpened) focus else head
        return if (marked in memberIds) "c-$marked" else null
    }

    /**
     * BROWSE-07: when someone in the sequence comes up, in the words the rest
     * of the app uses: "Up now" once their time has come (the card's own
     * eyebrow), then [comesUp]'s buckets, the same ones the card's Later and
     * Sooner snackbars word, as labels that stand alone. They are days on the
     * phone's calendar ([zone]), counted from the one [now] the whole
     * emission is labelled from: a stored time keeps the hour of the call or
     * the move that set it, and counted in 24-hour spans (until 2026-10-08)
     * tomorrow at an earlier hour than now read "Later today".
     */
    private fun whenLabel(time: Instant, now: Instant): UiText {
        if (!time.isAfter(now)) return UiText.res(R.string.browse_when_up_now)
        return when (val bucket = comesUp(time, now, zone)) {
            ComesUp.LaterToday -> UiText.res(R.string.browse_when_later_today)
            ComesUp.Tomorrow -> UiText.res(R.string.browse_when_tomorrow)
            is ComesUp.OnDay -> UiText.res(
                R.string.browse_when_on_day,
                bucket.day.getDisplayName(TextStyle.FULL, Locale.getDefault())
            )
            is ComesUp.InDays -> UiText.res(R.string.browse_when_in_span, formatSpan(bucket.days))
        }
    }

    /** When a pause ends, under the row's "Paused": Contact detail's status line, as a label. */
    private fun untilLabel(pausedUntil: Instant): UiText =
        if (PauseContactUseCase.isIndefinite(pausedUntil)) {
            UiText.res(R.string.browse_row_until_unpause)
        } else {
            UiText.res(R.string.browse_row_until, pausedUntil.atZone(zone).format(PAUSED_UNTIL_FORMAT))
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

        /** BROWSE-09: the route's optional argument naming the card's person (`Routes.Browse`). */
        internal const val FOCUS_KEY = "focus"

        // Contact detail's "Paused until 12 Oct" date (ContactDetailViewModel),
        // so one pause reads the same on both screens.
        private val PAUSED_UNTIL_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())

        // M4 — SavedStateHandle keys for multi-select persistence so process
        // death mid-flow doesn't drop the user's selection.
        private const val KEY_SELECTED_IDS = "selectedIds"
        private const val KEY_IS_MULTI_SELECT = "isMultiSelect"
    }
}
