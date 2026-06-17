package app.orbit.ui.screens.picker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.orbit.data.AppPrefs
import app.orbit.data.PickerThresholds
import app.orbit.data.android.ContactsReader
import app.orbit.data.dao.ListMembershipDao
import app.orbit.data.entity.ContactEntity
import app.orbit.data.entity.ListEntity
import app.orbit.data.entity.ListMembershipEntity
import app.orbit.data.repository.CallAgg
import app.orbit.data.repository.CallEventRepository
import app.orbit.data.repository.ContactRepository
import app.orbit.data.repository.ListRepository
import app.orbit.di.ApplicationScope
import app.orbit.domain.clock.Clock
import app.orbit.domain.undo.UndoStack
import app.orbit.domain.usecase.CopyContactsUseCase
import app.orbit.domain.usecase.IgnoreContactUseCase
import app.orbit.domain.usecase.MoveContactsUseCase
import app.orbit.domain.usecase.UnignoreContactUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ContactPicker ViewModel (PICK-01..08, BULK-02, BULK-04).
 *
 * Architecture:
 *  - `@HiltViewModel` with constructor-injected dependencies.
 *  - Reads `targetListId` and `mode` nav args via [SavedStateHandle] (Hilt
 *    cannot bind plain `String` types; nav args MUST flow through
 *    SavedStateHandle).
 *  - Uses [ContextCompat.checkSelfPermission] directly on the @ApplicationContext
 *    to read READ_CONTACTS state — same pattern as
 *    [app.orbit.ui.screens.onboarding.OnboardingPermissionsViewModel]
 *    (no PermissionSource interface, no test seam — refresh on init + on result).
 *  - Exposes [uiState] as a `StateFlow<ContactPickerUiState>` via
 *    `combine(...).stateIn(WhileSubscribed(5_000L))` — ARCH-02 invariant.
 *  - Search is VM-side debounced 150ms — VM-side debounce keeps the screen
 *    stateless wrt the debouncer.
 *
 * Selection invariants:
 *  - Selection / search / filter state are backed directly by
 *    [SavedStateHandle.getStateFlow]; the bundle IS the writable backing store.
 *    Rotation AND process death both restore the user's selection. Setters
 *    write `savedStateHandle[K] = value`; downstream consumers observe the
 *    [StateFlow] returned by `getStateFlow`.
 *  - [onSelectAllMatching] operates on the filtered DOMAIN set (the IDs flow in
 *    from the screen which already has [filteredContacts]), NEVER walks UI nodes.
 *    The screen passes `state.filteredContacts.map { it.contactId }.toSet()`
 *    so unrendered rows in the LazyColumn are still included in the selection.
 *
 * Move dispatch:
 *  - [PickerMode.Move] requires a `sourceListId` nav arg (the list contacts
 *    leave). A move route without it is structurally invalid and lands on the
 *    terminal [ContactPickerUiState.Phase.NotFound] at init — same shape as
 *    a malformed `targetListId` — so the no-source case can never reach
 *    [onCommit]. With a source, the commit dispatches [MoveContactsUseCase]
 *    on [appScope] with the same [UndoStack] + [PickerCommitBus] lifecycle as
 *    Add/Copy. In Move mode the candidate list is restricted to members of
 *    the source list — moving someone who isn't on the source list is not a
 *    move.
 *
 * No `Log.*` / `println` / `Timber` calls in this VM (rules.md Code 4);
 * contact names + numbers must never reach a log line.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class ContactPickerViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val contactRepo: ContactRepository,
    private val listRepo: ListRepository,
    private val listMembershipDao: ListMembershipDao,
    private val callEventRepo: CallEventRepository,
    private val appPrefs: AppPrefs,
    private val moveUseCase: MoveContactsUseCase,
    private val copyUseCase: CopyContactsUseCase,
    private val ignoreUseCase: IgnoreContactUseCase,
    private val unignoreUseCase: UnignoreContactUseCase,
    private val undoStack: UndoStack,
    private val contactsReader: ContactsReader,
    private val clock: Clock,
    private val commitBus: PickerCommitBus,
    @ApplicationScope private val appScope: CoroutineScope,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    // ─── Nav args ───────────────────────────────────────────────────────────────────────

    // Nav args via SavedStateHandle subscript form — equivalent to .get<T>(key).
    // Nav args MUST flow through SavedStateHandle (Hilt cannot bind plain String
    // types).
    //
    // Parse defensively — `toLongOrNull()` over `toLong()` so a malformed
    // or missing arg routes to the [ContactPickerUiState.Phase.NotFound] empty
    // state instead of crashing the VM at construction (Hilt creation failure
    // → black screen).
    private val targetListId: Long? =
        savedStateHandle.get<String>("targetListId")?.toLongOrNull()

    private val mode: PickerMode =
        when (savedStateHandle["mode"] ?: "") {
            "move" -> PickerMode.Move
            "copy" -> PickerMode.Copy
            else -> PickerMode.Add
        }

    // Move mode's source list. Defensive parse mirrors
    // targetListId; init routes a Move route without a valid source to the
    // terminal NotFound phase so the Move commit can never silently no-op.
    private val sourceListId: Long? =
        savedStateHandle.get<String>("sourceListId")?.toLongOrNull()

    // ─── Mutable state flows ───────────────────────────────────────────────────────────
    //
    // Search / filter / selection use `SavedStateHandle.getStateFlow()`
    // as the upstream source-of-truth — the bundle IS the writable backing store.
    // Setters write `savedStateHandle[K] = value` directly; the StateFlow returned
    // by `getStateFlow` re-emits to downstream consumers. This eliminates the
    // mirror-chain pattern (three `init { _x.collect { savedStateHandle[K] = it } }`
    // launches) which wrote a Bundle on Main per emission — selecting 100 contacts
    // would issue ~100 Main-thread Bundle writes.
    //
    // SavedStateHandle only persists Bundle-compatible types — `Set<Long>` is
    // round-tripped through `LongArray`, `Set<PickerFilter>` through
    // `Array<String>` of enum-style names (encoded via `encodePickerFilter`).

    private val savedStateHandleRef: SavedStateHandle = savedStateHandle

    private val searchQueryFlow: StateFlow<String> =
        savedStateHandle.getStateFlow(KEY_SEARCH_QUERY, "")

    // Filter persistence is encoded as Array<String> at the bundle layer; the
    // domain `Set<PickerFilter>` is exposed as a StateFlow built from the
    // encoded form via `.map { ... }.stateIn(...)`.
    private val activeFiltersFlow: StateFlow<Set<PickerFilter>> =
        savedStateHandle.getStateFlow<Array<String>?>(KEY_ACTIVE_FILTERS, null)
            .map { encoded ->
                encoded?.mapNotNull(::decodePickerFilter)?.toSet().orEmpty()
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = savedStateHandle.get<Array<String>>(KEY_ACTIVE_FILTERS)
                    ?.mapNotNull(::decodePickerFilter)?.toSet().orEmpty(),
            )

    private val selectedIdsFlow: StateFlow<Set<Long>> =
        savedStateHandle.getStateFlow<LongArray?>(KEY_SELECTED_IDS, null)
            .map { it?.toSet().orEmpty() }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = savedStateHandle.get<LongArray>(KEY_SELECTED_IDS)
                    ?.toSet().orEmpty(),
            )

    private val _showIgnored = MutableStateFlow(false)
    private val _permissionPhase =
        MutableStateFlow<ContactPickerUiState.Phase>(ContactPickerUiState.Phase.LoadingPermission)
    private val _isCommitting = MutableStateFlow(false)

    // ONB-21 — UI-side sort mode. Default ByName matches DAO order;
    // onboarding flips to ByRecency via [setSortBy]. Persists across process
    // death via SavedStateHandle (KEY_SORT_BY) so the user's chosen order is
    // preserved through Android's "low memory" reclamation.
    private val _sortBy: MutableStateFlow<PickerSort> = MutableStateFlow(
        when (savedStateHandle.get<String>(KEY_SORT_BY)) {
            SORT_RECENCY -> PickerSort.ByRecency
            SORT_MOST_CALLED -> PickerSort.ByMostCalled
            SORT_RECENTLY_SAVED -> PickerSort.ByRecentlySaved
            else -> PickerSort.ByName
        },
    )

    init {
        // Route malformed/missing targetListId to a terminal NotFound phase.
        // The downstream combine pipeline still emits, but every code path that
        // would touch `targetListId` guards on `?: return@launch` below.
        // A Move route without a sourceListId is equally malformed: there is no
        // list to move FROM, so the commit could only be a silent no-op. Make
        // the invalid state unreachable instead.
        if (targetListId == null || (mode == PickerMode.Move && sourceListId == null)) {
            _permissionPhase.value = ContactPickerUiState.Phase.NotFound
        } else {
            refreshPermission()
        }
    }

    // ─── Permission handling ────────────────────────────────────────────────────────────

    /**
     * Re-reads the READ_CONTACTS grant via [ContextCompat.checkSelfPermission].
     * Called on init AND from screen-side `DisposableEffect`/`ON_RESUME`
     * so the picker recovers if the user flips the permission in Settings → Apps
     * and returns to the flow.
     */
    fun refreshPermission() {
        val granted = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.READ_CONTACTS,
        ) == PackageManager.PERMISSION_GRANTED
        _permissionPhase.value = if (granted) {
            ContactPickerUiState.Phase.Ready
        } else {
            ContactPickerUiState.Phase.PermissionRationale
        }
    }

    fun onPermissionResult(granted: Boolean) {
        _permissionPhase.value = if (granted) {
            ContactPickerUiState.Phase.Ready
        } else {
            ContactPickerUiState.Phase.PermissionDenied
        }
    }

    // ─── Public callbacks (9 — one per UI event) ──────────────────────────────────────

    fun onSearchChanged(q: String) {
        savedStateHandleRef[KEY_SEARCH_QUERY] = q
    }

    fun onToggleFilter(filter: PickerFilter) {
        val next = activeFiltersFlow.value.toMutableSet().also { current ->
            if (filter in current) {
                current.remove(filter)
            } else {
                // Commonly/Rarely/NeverCalled all derive from callCount and are
                // mutually exclusive — activating one clears the others so the
                // chips behave as a single-select toggle group (one tap to
                // switch, vs. tap-off then tap-on).
                if (filter in CALL_FREQUENCY_FILTERS) current.removeAll(CALL_FREQUENCY_FILTERS)
                current.add(filter)
            }
        }
        savedStateHandleRef[KEY_ACTIVE_FILTERS] =
            next.map(::encodePickerFilter).toTypedArray()
    }

    fun onToggleSelect(id: Long) {
        val current = selectedIdsFlow.value
        val next = if (id in current) current - id else current + id
        savedStateHandleRef[KEY_SELECTED_IDS] = next.toLongArray()
    }

    /**
     * Selection over the FILTERED DOMAIN set. The screen
     * computes `state.filteredContacts.map { it.contactId }.toSet()` and passes
     * it here; we union into the existing selection. Never walks UI nodes — that
     * would miss rows the LazyColumn hasn't materialized yet.
     */
    fun onSelectAllMatching(filteredIds: Set<Long>) {
        val next = selectedIdsFlow.value + filteredIds
        savedStateHandleRef[KEY_SELECTED_IDS] = next.toLongArray()
    }

    fun onClearSelection() {
        savedStateHandleRef[KEY_SELECTED_IDS] = LongArray(0)
    }

    fun onShowIgnoredToggle(value: Boolean) {
        _showIgnored.value = value
    }

    /**
     * ONB-21 — change the picker's sort mode. The onboarding flow
     * calls this once with `PickerSort.ByRecency` after the call-log sync
     * completes so freshly-synced contacts surface first; standard nav
     * paths leave it at the default `PickerSort.ByName`.
     */
    fun setSortBy(sort: PickerSort) {
        _sortBy.value = sort
        savedStateHandleRef[KEY_SORT_BY] = when (sort) {
            PickerSort.ByName -> SORT_NAME
            PickerSort.ByRecency -> SORT_RECENCY
            PickerSort.ByMostCalled -> SORT_MOST_CALLED
            PickerSort.ByRecentlySaved -> SORT_RECENTLY_SAVED
        }
    }

    /**
     * Commit the current selection per [mode]. Adds insert via the DAO
     * directly (there's no AddContactsToListUseCase yet); Copy dispatches
     * [CopyContactsUseCase]; Move dispatches [MoveContactsUseCase] with the
     * route's `sourceListId` (previously a silent no-op). On
     * success, the inverse closure is recorded on [UndoStack] (depth-1) and
     * the outcome published on [PickerCommitBus].
     *
     * Picker-commit lifecycle — the write runs on [appScope], NOT
     * viewModelScope: the caller pops this screen immediately after invoking
     * onCommit, which clears the VM and would cancel a viewModelScope launch
     * mid-write (the insert previously survived only by transition-timing
     * accident). `@ApplicationScope` is the codebase's established home for
     * work that must outlive a screen (feeds, rule-template cache); it also
     * keeps the post-write snackbar publish alive so the destination screen's
     * [PickerCommitSnackbarHost] can show "Added N · Undo" after the pop.
     *
     * Failure path: a failed insert (e.g. SQLCipher I/O error) publishes
     * "Couldn't save that" instead of crashing; [CancellationException] is
     * rethrown per codebase convention (HomeViewModel.runMutation).
     */
    fun onCommit() {
        val ids = selectedIdsFlow.value.toList()
        if (ids.isEmpty()) return
        // NotFound surface short-circuits commit. The action bar isn't
        // rendered in NotFound, but a race where the surface re-enters Ready
        // momentarily before settling shouldn't dispatch on a null id.
        val listId = targetListId ?: return
        _isCommitting.value = true
        // Clear the selection eagerly, on the main thread: the screen pops on
        // commit so the selection belongs to the dying back-stack entry, and
        // SavedStateHandle writes are main-thread-only — clearing from
        // appScope would touch the bundle off-main.
        savedStateHandleRef[KEY_SELECTED_IDS] = LongArray(0)
        appScope.launch {
            try {
                val targetName = listRepo.getById(listId)?.name.orEmpty()
                val dispatched: Pair<suspend () -> Unit, String>? = when (mode) {
                    PickerMode.Add -> {
                        val now = clock.now()
                        listMembershipDao.insertAll(
                            ids.map { id ->
                                ListMembershipEntity(
                                    listId = listId,
                                    contactId = id,
                                    addedAt = now,
                                )
                            },
                        )
                        val inverse: suspend () -> Unit = { listMembershipDao.removeAll(listId, ids) }
                        inverse to "Added ${ids.size} to $targetName"
                    }

                    PickerMode.Copy -> {
                        val r = copyUseCase(listId, ids, targetName)
                        r.inverse to r.label
                    }

                    PickerMode.Move -> {
                        // init routes a Move route without a
                        // sourceListId to NotFound, so `from` is non-null on
                        // every reachable commit; the guard keeps the failure
                        // loud if a future nav change breaks that invariant.
                        // The use case returns an empty label when it
                        // short-circuited (missing/archived destination,
                        // same-list move) — surfaced below, never swallowed.
                        val from = sourceListId
                        if (from == null) {
                            null
                        } else {
                            val r = moveUseCase(from, listId, ids, targetName)
                            if (r.label.isEmpty()) null else r.inverse to r.label
                        }
                    }
                }

                if (dispatched != null) {
                    val (inverse, label) = dispatched
                    undoStack.put(UndoStack.PendingUndo(inverse = inverse, label = label))
                    commitBus.publish(SnackbarEvent(label, "Undo"))
                } else if (mode == PickerMode.Move) {
                    // Per project convention "no silent fallbacks": a Move that
                    // dispatched nothing is a failed save, not a quiet exit.
                    commitBus.publish(SnackbarEvent("Couldn't save that"))
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                commitBus.publish(SnackbarEvent("Couldn't save that"))
            } finally {
                _isCommitting.value = false
            }
        }
    }

    /**
     * Ignore a single contact from the picker (long-press →
     * "Ignore"). Dispatches through the existing [IgnoreContactUseCase]
     * (atomic four-column flip + pre-ignore membership snapshot; never
     * deletes memberships).
     *
     * Runs on [appScope] for the same reason [onCommit] does: the user may
     * pop the picker while the write is in flight, and the confirmation
     * ("Ignored {name} · Undo") is published on [PickerCommitBus] so the
     * app-level [PickerCommitSnackbarHost] can show it wherever the user
     * lands. Undo replays the use case's inverse via [UndoStack] — the exact
     * shape the commit Undo already uses.
     */
    fun onIgnore(contactId: Long, displayName: String) {
        // Drop the row from any in-flight selection first, on Main —
        // SavedStateHandle writes are main-thread-only, and an ignored
        // contact must not ride along into a later commit.
        val current = selectedIdsFlow.value
        if (contactId in current) {
            savedStateHandleRef[KEY_SELECTED_IDS] = (current - contactId).toLongArray()
        }
        appScope.launch {
            try {
                val result = ignoreUseCase(contactId, displayName)
                undoStack.put(UndoStack.PendingUndo(inverse = result.inverse, label = result.label))
                commitBus.publish(SnackbarEvent(result.label, "Undo"))
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                commitBus.publish(SnackbarEvent("Couldn't save that"))
            }
        }
    }

    /**
     * Unignore from the picker's "Show ignored" view. Mirrors
     * [app.orbit.ui.screens.settings.ignored.SettingsIgnoredViewModel.onUnignore]:
     * the forward write is [UnignoreContactUseCase] (drift-aware membership
     * restore), and the Undo intent is to RE-ignore, so the inverse dispatches
     * [IgnoreContactUseCase] rather than reusing a stale `Result.inverse`.
     */
    fun onUnignore(contactId: Long, displayName: String) {
        appScope.launch {
            try {
                unignoreUseCase(contactId)
                undoStack.put(
                    UndoStack.PendingUndo(
                        inverse = { ignoreUseCase(contactId, displayName) },
                        label = "Restored $displayName",
                    ),
                )
                commitBus.publish(SnackbarEvent("Restored $displayName", "Undo"))
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                commitBus.publish(SnackbarEvent("Couldn't save that"))
            }
        }
    }

    // ─── State combine pipeline ────────────────────────────────────────────────────────
    //
    // Two staged combines because Kotlin stdlib `combine` arity caps at 5:
    //
    //  Stage A: pickerContactsFlow — folds the 5 reactive data sources + the
    //   cached address-book read (`phoneByContactIdFlow`) into a domain
    //   List<PickerContact>: contacts, all-memberships, call events, lists,
    //   thresholds, phone-by-contactId.
    //
    //  Stage B: uiState — folds (pickerContactsFlow, search.debounce,
    //   filterConfigFlow, selectedIds, chromeFlow) into ContactPickerUiState
    //   in ONE construction per emission (no chained .copy() stages; see the
    //   pipeline comment above `filterConfigFlow`).
    //
    // `phoneByContactIdFlow` reads the device address book and caches the
    // result via `shareIn(replay = 1)`. The previous implementation called
    // `contactsReader.readAll()` inside `buildPickerContacts` on every combine
    // emission, which re-walked the ContactsContract provider for every Room
    // write. That read was later moved off the Main thread
    // (suspend + @WorkerThread); this caching closes the I/O efficiency gap.
    //
    // Re-key on `_permissionPhase` so the read re-fires
    // when the user flips READ_CONTACTS via Settings → Apps and returns. The
    // VM is NOT recreated by Hilt on permission grant (only on nav-arg change),
    // so without `flatMapLatest` here, a denied-then-granted flow would keep
    // serving an empty cached map for the VM's lifetime.

    private val phoneByContactIdFlow: SharedFlow<Map<Long, app.orbit.data.android.PhoneContact>> =
        _permissionPhase
            .map { it == ContactPickerUiState.Phase.Ready }
            .distinctUntilChanged()
            .flatMapLatest { granted ->
                if (granted) {
                    flow { emit(contactsReader.readAll().associateBy { it.contactId }) }
                        .flowOn(Dispatchers.IO)
                } else {
                    flowOf(emptyMap())
                }
            }
            .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), replay = 1)

    // Push the per-contact COUNT + lastAt down into SQL via
    // `observeAggregatesForContacts(ids)`. The id set is derived from the
    // upstream contacts flow; we re-key only when the ids actually change
    // (`distinctUntilChanged`) so transient column writes on `contacts` don't
    // fire a fresh aggregate query. Empty-ids short-circuits to an empty map
    // — Room's `IN (:ids)` would otherwise fail validation.
    private val callAggregatesFlow: Flow<Map<Long, CallAgg>> =
        contactRepo.observeAll()
            .map { contacts -> contacts.map(ContactEntity::id) }
            .distinctUntilChanged()
            .flatMapLatest { ids ->
                if (ids.isEmpty()) flowOf(emptyMap())
                else callEventRepo.observeAggregatesForContacts(ids)
            }

    private val rawSourcesFlow = combine(
        contactRepo.observeAll(),
        listMembershipDao.observeAll(),
        callAggregatesFlow,
        listRepo.observeAll(),
        appPrefs.pickerThresholds,
    ) { contacts, memberships, callAggregates, lists, thresholds ->
        RawPickerSources(contacts, memberships, callAggregates, lists, thresholds)
    }

    private val pickerContactsFlow: Flow<List<PickerContact>> =
        rawSourcesFlow.combine(phoneByContactIdFlow) { raw, phoneById ->
            buildPickerContacts(
                contacts = raw.contacts,
                memberships = raw.memberships,
                callAggregates = raw.callAggregates,
                lists = raw.lists,
                thresholds = raw.thresholds,
                phoneByContactId = phoneById,
            )
        }
            // Run the percentile bucketing + cross-ref reduction off
            // Main. Mirrors `phoneByContactIdFlow` which already chains it via the
            // `.flowOn(Dispatchers.IO)` block on the inner `flow { ... }`. For
            // 1k+ contacts the `buildPickerContacts` reduction is non-trivial
            // (quantile sort + per-contact membership join) and was previously
            // running on the combine-emitting dispatcher (Main).
            .flowOn(Dispatchers.Default)

    // When targetListId is null the picker is terminal-NotFound; surface an
    // empty target name and avoid wiring the observe Flow to a missing id.
    private val targetListNameFlow: Flow<String> =
        targetListId?.let { id -> listRepo.observeById(id).map { it?.name.orEmpty() } }
            ?: kotlinx.coroutines.flow.flowOf("")

    /**
     * PICK-01 — non-archived lists for the "In list…"
     * DropdownMenu chip. Projected to [PickerListSummary] (id + name); the
     * filter chip never sees the full [app.orbit.data.entity.ListEntity].
     */
    private val availableListsFlow: Flow<List<PickerListSummary>> =
        listRepo.observeAll().map { lists ->
            lists.filter { !it.isArchived }
                .map { PickerListSummary(id = it.id, name = it.name) }
        }

    // Single state construction per emission. The previous
    // pipeline chained five `.combine { state.copy(...) }` stages around the
    // arity-5 cap; every `copy()` re-runs the UiState body initializers
    // (filteredContacts, filterCounts), so one upstream tick paid for the
    // filter+sort up to six times. Folding the filter inputs and the
    // low-churn chrome into two intermediate records keeps the final combine
    // at arity 5 with exactly one `ContactPickerUiState(...)` call per
    // emission — which is what makes the UiState's construction-time
    // `val filteredContacts` a once-per-emission computation.

    private val filterConfigFlow: Flow<FilterConfig> =
        combine(activeFiltersFlow, _showIgnored, _sortBy) { filters, showIg, sort ->
            FilterConfig(filters, showIg, sort)
        }

    // "Device truly has zero contacts" signal for the
    // EmptyDevice phase. Null until the first address-book read completes
    // (the combine below treats null as "unknown — stay on the base phase"),
    // then true iff the read returned zero phone-bearing contacts. Derived
    // from the same cached read the contact join uses, so the signal and the
    // contact list always describe the same device snapshot.
    private val deviceEmptyFlow: Flow<Boolean?> =
        phoneByContactIdFlow
            .map<Map<Long, app.orbit.data.android.PhoneContact>, Boolean?> { it.isEmpty() }
            .onStart { emit(null) }
            .distinctUntilChanged()

    private val chromeFlow: Flow<PickerChrome> =
        combine(
            _permissionPhase,
            _isCommitting,
            targetListNameFlow,
            availableListsFlow,
            deviceEmptyFlow,
        ) { phase, committing, name, lists, deviceEmpty ->
            PickerChrome(phase, committing, name, lists, deviceEmpty)
        }

    val uiState: StateFlow<ContactPickerUiState> =
        combine(
            pickerContactsFlow,
            searchQueryFlow.debounce(150L),
            filterConfigFlow,
            selectedIdsFlow,
            chromeFlow,
        ) { allContacts, query, config, selected, chrome ->
            ContactPickerUiState(
                phase = resolvePickerPhase(
                    basePhase = chrome.phase,
                    isCommitting = chrome.isCommitting,
                    deviceEmpty = chrome.deviceEmpty,
                    hasAnyContacts = allContacts.isNotEmpty(),
                ),
                mode = mode,
                targetListName = chrome.targetListName,
                searchQuery = query,
                activeFilters = config.activeFilters,
                showIgnored = config.showIgnored,
                allContacts = allContacts,
                selectedIds = selected,
                availableLists = chrome.availableLists,
                sortBy = config.sortBy,
            )
        }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000L),
                initialValue = ContactPickerUiState(
                    phase = ContactPickerUiState.Phase.LoadingPermission,
                    mode = mode,
                    targetListName = "",
                    searchQuery = "",
                    activeFilters = emptySet(),
                    showIgnored = false,
                    allContacts = emptyList(),
                    selectedIds = emptySet(),
                    availableLists = emptyList(),
                ),
            )

    // ─── Domain projection ─────────────────────────────────────────────────────────────

    /**
     * Folds the 6 data sources into a List<PickerContact>. Joins Room contacts
     * to the cached device address book (`phoneByContactId`, supplied by
     * [phoneByContactIdFlow]) by `phoneContactId`, computes call-stat aggregates
     * + percentile flags + recency/long-gap flags from [PickerThresholds], and
     * excludes orphaned rows (strict contact creation).
     *
     * Percentile semantics (SMART-07 invariant): `isCommonlyCalled` and
     * `isRarelyCalled` are computed against contacts with at least one call.
     * Zero-call contacts fall into [PickerFilter.NeverCalled] bucket only.
     */
    private fun buildPickerContacts(
        contacts: List<ContactEntity>,
        memberships: List<ListMembershipEntity>,
        callAggregates: Map<Long, CallAgg>,
        lists: List<ListEntity>,
        thresholds: PickerThresholds,
        phoneByContactId: Map<Long, app.orbit.data.android.PhoneContact>,
    ): List<PickerContact> {
        // Skip orphans — strict contact creation.
        val live = contacts.filterNot { it.isOrphaned }
        if (live.isEmpty()) return emptyList()

        // Per-contact aggregates come from SQL push-down. Ids
        // missing from the map are zero-call contacts (the GROUP BY only emits
        // a row when at least one event matches); default-coalesce here.

        // Membership cross-ref.
        val listsByContactId: Map<Long, List<Long>> =
            memberships.groupBy { it.contactId }
                .mapValues { (_, rows) -> rows.map { it.listId } }
        val listNameById: Map<Long, String> = lists.associate { it.id to it.name }

        // Percentile bucketing — only contacts WITH at least one call.
        val calledIds = live.mapNotNull { c -> if ((callAggregates[c.id]?.count ?: 0) > 0) c.id else null }
        val countsAsc = calledIds.map { id -> callAggregates[id]?.count ?: 0 }.sorted()
        val nCalled = countsAsc.size
        // top X% — high-count cutoff. Bottom Y% — low-count cutoff (still ≥1 call).
        val commonlyCutoff: Int? =
            if (nCalled == 0) null
            else countsAsc[(nCalled - (nCalled * thresholds.commonlyTopPct) / 100).coerceIn(0, nCalled - 1)]
        val rarelyCutoff: Int? =
            if (nCalled == 0) null
            else countsAsc[((nCalled * thresholds.rarelyBottomPct) / 100).coerceIn(0, nCalled - 1)]

        val now = clock.now()
        val recentlyAddedThreshold = now.minus(Duration.ofDays(thresholds.recentlyAddedDays.toLong()))
        val longGapThreshold = now.minus(Duration.ofDays(thresholds.longGapDays.toLong()))

        val built = live.map { c ->
            val agg = callAggregates[c.id]
            val callCount = agg?.count ?: 0
            val lastCallAt = agg?.lastAt
            val phone = c.phoneContactId?.let { phoneByContactId[it] }
            val contactListIds = (listsByContactId[c.id] ?: emptyList()).toSet()
            val contactListNames = contactListIds.mapNotNull { listNameById[it] }

            val isCommonly = callCount > 0 && commonlyCutoff != null && callCount >= commonlyCutoff
            val isRarely = callCount > 0 && rarelyCutoff != null && callCount <= rarelyCutoff && !isCommonly
            val isRecentlyAdded = c.firstSeenByAppAt.isAfter(recentlyAddedThreshold)
            val isLongGap = lastCallAt != null && lastCallAt.isBefore(longGapThreshold)

            PickerContact(
                contactId = c.id,
                displayName = c.displayName,
                phone = phone?.phone ?: c.phoneNumber,
                photoUri = c.photoUri,
                isIgnored = c.isIgnored,
                callCount = callCount,
                lastCallAt = lastCallAt,
                firstSeenByAppAt = c.firstSeenByAppAt,
                listIds = contactListIds,
                listNames = contactListNames,
                isCommonlyCalled = isCommonly,
                isRarelyCalled = isRarely,
                isRecentlyAdded = isRecentlyAdded,
                isLongGap = isLongGap,
                isStarred = c.isStarred,
            )
        }
        // In Add mode, a contact already on the target list isn't a candidate to
        // add — exclude it so the picker shows only people you can actually add.
        // In Move mode, only members of the source list are candidates — moving
        // someone who isn't on the source list is not a move.
        // Copy keeps the full set (idempotent).
        return when {
            mode == PickerMode.Add && targetListId != null ->
                built.filterNot { targetListId in it.listIds }
            mode == PickerMode.Move && sourceListId != null ->
                built.filter { sourceListId in it.listIds }
            else -> built
        }
    }

    private companion object {
        // SavedStateHandle keys for selection/search/filter persistence so
        // a process death mid-flow doesn't drop the user's selection.
        const val KEY_SELECTED_IDS = "selectedIds"
        const val KEY_SEARCH_QUERY = "searchQuery"
        const val KEY_ACTIVE_FILTERS = "activeFilters"

        // ONB-21 — sort-mode persistence key + tokens. Round-trip
        // through SavedStateHandle so the picker remembers the user's
        // chosen order across process death.
        const val KEY_SORT_BY = "sortBy"
        const val SORT_NAME = "ByName"
        const val SORT_RECENCY = "ByRecency"
        const val SORT_MOST_CALLED = "ByMostCalled"
        const val SORT_RECENTLY_SAVED = "ByRecentlySaved"

        // Call-frequency filters derive from callCount and are mutually
        // exclusive — onToggleFilter treats them as a single-select group.
        val CALL_FREQUENCY_FILTERS: Set<PickerFilter> = setOf(
            PickerFilter.CommonlyCalled,
            PickerFilter.RarelyCalled,
            PickerFilter.NeverCalled,
        )
    }

    /**
     * Internal tuple for the staged combine — Kotlin stdlib `combine` arity
     * caps at 5 so the 5 reactive sources are first folded into this record,
     * which is then `.combine`'d with [phoneByContactIdFlow] to produce the
     * final [pickerContactsFlow]. Lets us keep the address-book read cached
     * (LOW polish, Group 5) without exceeding combine's arity.
     */
    private data class RawPickerSources(
        val contacts: List<ContactEntity>,
        val memberships: List<ListMembershipEntity>,
        val callAggregates: Map<Long, CallAgg>,
        val lists: List<ListEntity>,
        val thresholds: PickerThresholds,
    )

    /**
     * The three filter-shaping inputs folded into one record so
     * the final uiState combine stays at arity 5 (see pipeline comment).
     */
    private data class FilterConfig(
        val activeFilters: Set<PickerFilter>,
        val showIgnored: Boolean,
        val sortBy: PickerSort,
    )

    /**
     * Low-churn "chrome" inputs (phase, commit flag, target
     * name, list summaries) folded into one record for the same arity reason.
     * [deviceEmpty] (null until the first address-book read completes) lets the
     * final combine resolve the EmptyDevice phase.
     */
    private data class PickerChrome(
        val phase: ContactPickerUiState.Phase,
        val isCommitting: Boolean,
        val targetListName: String,
        val availableLists: List<PickerListSummary>,
        val deviceEmpty: Boolean?,
    )
}

/**
 * Resolves the surface phase from the permission/commit base
 * phase plus the device-emptiness signal. [ContactPickerUiState.Phase.EmptyDevice]
 * was declared but once never assigned; the picker showed an eternal
 * skeleton/empty list when the device truly had zero contacts.
 *
 * EmptyDevice requires ALL of:
 *  - base phase Ready (permission granted, route valid),
 *  - the address-book read completed AND returned zero phone-bearing contacts
 *    (`deviceEmpty == true`; null = read not finished yet — stay on base),
 *  - the local store projects zero pickable contacts (`hasAnyContacts ==
 *    false`) — call-log-only rows can outlive an emptied address book and
 *    remain honestly pickable.
 *
 * Top-level (not VM-private) so the unit test can pin the predicate without
 * collecting the dispatcher-hopping uiState pipeline — same rationale as
 * [countFor].
 */
internal fun resolvePickerPhase(
    basePhase: ContactPickerUiState.Phase,
    isCommitting: Boolean,
    deviceEmpty: Boolean?,
    hasAnyContacts: Boolean,
): ContactPickerUiState.Phase = when {
    isCommitting -> ContactPickerUiState.Phase.Committing
    basePhase == ContactPickerUiState.Phase.Ready &&
        deviceEmpty == true &&
        !hasAnyContacts -> ContactPickerUiState.Phase.EmptyDevice
    else -> basePhase
}

/**
 * Encode a [PickerFilter] to a Bundle-compatible String. The 5 stateless
 * data-objects round-trip via their simple name; the parameterised
 * [PickerFilter.InList] encodes its `listId` after a colon. Used by
 * [ContactPickerViewModel] to mirror selection state into [SavedStateHandle].
 */
private fun encodePickerFilter(filter: PickerFilter): String = when (filter) {
    PickerFilter.CommonlyCalled -> "CommonlyCalled"
    PickerFilter.RarelyCalled -> "RarelyCalled"
    PickerFilter.NeverCalled -> "NeverCalled"
    PickerFilter.RecentlyAdded -> "RecentlyAdded"
    PickerFilter.LongGap -> "LongGap"
    PickerFilter.Unsorted -> "Unsorted"
    PickerFilter.Starred -> "Starred"
    is PickerFilter.InList -> "InList:${filter.listId}"
}

/**
 * Inverse of [encodePickerFilter]. Returns null for malformed entries so
 * a corrupted SavedStateHandle bundle (older app version, manual edit) drops
 * the bad chip rather than crashing the VM.
 */
private fun decodePickerFilter(token: String): PickerFilter? = when {
    token == "CommonlyCalled" -> PickerFilter.CommonlyCalled
    token == "RarelyCalled" -> PickerFilter.RarelyCalled
    token == "NeverCalled" -> PickerFilter.NeverCalled
    token == "RecentlyAdded" -> PickerFilter.RecentlyAdded
    token == "LongGap" -> PickerFilter.LongGap
    token == "Unsorted" -> PickerFilter.Unsorted
    token == "Starred" -> PickerFilter.Starred
    token.startsWith("InList:") ->
        token.removePrefix("InList:").toLongOrNull()?.let(PickerFilter::InList)
    else -> null
}
