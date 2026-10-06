package app.orbit.ui.screens.picker

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.orbit.R
import app.orbit.data.dao.ListMembershipDao
import app.orbit.data.entity.ListEntity
import app.orbit.data.entity.ListMembershipEntity
import app.orbit.data.entity.ListType
import app.orbit.data.entity.RuleKind
import app.orbit.data.repository.ContactRepository
import app.orbit.data.repository.ListRepository
import app.orbit.data.repository.RuleTemplateRepository
import app.orbit.di.ApplicationScope
import app.orbit.domain.clock.Clock
import app.orbit.domain.undo.UndoStack
import app.orbit.ui.util.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Reverse picker (BULK-06): given a contact, lists are the rows the user
 * multi-selects.
 *
 * Sibling pattern of [ContactPickerViewModel]:
 *   - `@HiltViewModel` with constructor-injected dependencies.
 *   - Reads `contactId` from [SavedStateHandle] (Hilt cannot bind plain
 *     `String` types).
 *   - `combine(...).stateIn(WhileSubscribed(5_000L))` exposes a
 *     [ListPickerUiState] (ARCH-02 invariant).
 *   - Commit dispatches via [ListMembershipDao] directly (no
 *     `ListMembershipRepository` interface yet — same precedent the
 *     forward picker uses; widening the repo is deferred to the
 *     lower-level ListMembershipDao path).
 *   - On commit success the locked copy "Added to N list[s]" + Undo
 *     affordance (backed by [UndoStack]) is published on [PickerCommitBus] —
 *     the screen pops on commit, so the app-level [PickerCommitSnackbarHost]
 *     shows the result on the caller (picker-commit lifecycle).
 *
 * Selection invariants:
 *   - Picker shows only non-archived, non-smart lists. A smart list's rows
 *     are written by SmartListMembershipSync from its rule, so a row added
 *     here would be removed on the next reconcile with no message; ListRow
 *     hides "+" on smart lists for the same reason. A smart id that still
 *     reaches the commit is refused loudly (rules.md Code 3).
 *   - Lists the contact already belongs to stay in the row set, tagged
 *     "Already added", but cannot be picked: the row is disabled, the toggle
 *     refuses the id once the rows are known, the state drops a restored id,
 *     and the commit snapshots the memberships and writes only the rest.
 *     Until 2026-10-06 the DAO's `OnConflictStrategy.IGNORE` was relied on
 *     for idempotence: it made the insert a no-op, but the snackbar still
 *     counted the list and Undo's removeAll deleted the membership the
 *     person already had (G1: no lost work). The count and the inverse now
 *     come from the rows actually inserted, as CopyContactsUseCase does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ListPickerViewModel @Inject constructor(
    private val listRepo: ListRepository,
    private val contactRepo: ContactRepository,
    private val listMembershipDao: ListMembershipDao,
    private val undoStack: UndoStack,
    private val clock: Clock,
    private val commitBus: PickerCommitBus,
    // 2026-06-09 #26 — inline create resolves the KEEP_IN_TOUCH default so the
    // new list is immediately surfaceable (mirrors ListsManagerViewModel's
    // "Start from blank" path).
    private val ruleTemplateRepo: RuleTemplateRepository,
    @ApplicationScope private val appScope: CoroutineScope,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    // ─── Nav arg ────────────────────────────────────────────────────────────
    //
    // C6: parse defensively — `toLongOrNull()` over `toLong()` so a malformed
    // or missing arg routes to the [ListPickerUiState.Phase.NotFound] state instead
    // of crashing the VM at construction (Hilt creation failure → black screen).
    private val contactId: Long? =
        savedStateHandle.get<String>("contactId")?.removePrefix("c-")?.toLongOrNull()

    // ─── Mutable state ──────────────────────────────────────────────────────
    //
    // M4: selection state is seeded from SavedStateHandle so a process death
    // mid-flow doesn't drop the user's selection. The init collector mirrors
    // every value back via `LongArray` (Bundle-compatible).
    private val _selectedListIds = MutableStateFlow<Set<Long>>(
        savedStateHandle.get<LongArray>(KEY_SELECTED_LIST_IDS)?.toSet().orEmpty(),
    )
    private val _isCommitting = MutableStateFlow(false)

    // PICK-09: bumped by [onRetry]; flatMapLatest re-subscribes every source.
    private val retryCount = MutableStateFlow(0)

    init {
        viewModelScope.launch {
            _selectedListIds.collect {
                savedStateHandle[KEY_SELECTED_LIST_IDS] = it.toLongArray()
            }
        }
    }

    // ─── UiState ────────────────────────────────────────────────────────────
    // The contract lives in ListPickerUiState.kt, beside the other screens'.

    val uiState: StateFlow<ListPickerUiState> =
        if (contactId == null) {
            // Terminal NotFound for a missing or malformed id: the combine
            // pipeline never starts; emit a single static state. The screen
            // says the person is not in Orbit anymore and offers Go back.
            kotlinx.coroutines.flow.flowOf(
                ListPickerUiState(
                    phase = ListPickerUiState.Phase.NotFound,
                    contactName = "",
                    lists = emptyList(),
                    selectedListIds = emptySet(),
                ),
            ).stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000L),
                initialValue = ListPickerUiState(
                    phase = ListPickerUiState.Phase.NotFound,
                    contactName = "",
                    lists = emptyList(),
                    selectedListIds = emptySet(),
                ),
            )
        } else {
            retryCount.flatMapLatest { listPickerState(contactId) }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000L),
                initialValue = ListPickerUiState(
                    phase = ListPickerUiState.Phase.Loading,
                    contactName = "",
                    lists = emptyList(),
                    selectedListIds = emptySet(),
                ),
            )
        }

    /**
     * PICK-09: a failure in any source becomes [ListPickerUiState.Phase.Error] with
     * Retry instead of an uncaught exception in viewModelScope, which crashed
     * the app. No logging here (rules.md Code 4).
     */
    private fun listPickerState(contactId: Long): Flow<ListPickerUiState> =
        combine(
            listRepo.observeAll(),
            contactRepo.observeById(contactId),
            listRepo.observeMembershipsForContact(contactId),
            _selectedListIds,
            _isCommitting,
        ) { lists, contact, memberships, selected, committing ->
            val memberListIds: Set<Long> = memberships.map { it.listId }.toSet()
            if (contact == null) {
                // Room emits null only for a missing row (a stale deep link, a
                // person removed from another screen), never before the first
                // read, so this is terminal. Until 2026-10-06 the picker sat
                // Ready with a blank title and the commit failed late on the
                // membership's foreign key.
                ListPickerUiState(
                    phase = ListPickerUiState.Phase.NotFound,
                    contactName = "",
                    lists = emptyList(),
                    selectedListIds = emptySet(),
                )
            } else {
                ListPickerUiState(
                    phase = when {
                        committing -> ListPickerUiState.Phase.Committing
                        else -> ListPickerUiState.Phase.Ready
                    },
                    contactName = contact.displayName,
                    lists = lists
                        .filter { !it.isArchived && it.type != ListType.SMART }
                        .map {
                            ListPickerUiState.ListRow(
                                listId = it.id,
                                name = it.name,
                                isMember = it.id in memberListIds,
                            )
                        },
                    // A restored selection (SavedStateHandle) can carry a list
                    // the person has since been added to; it is not pickable,
                    // so it is not selected.
                    selectedListIds = selected - memberListIds,
                )
            }
        }.catch {
            emit(
                ListPickerUiState(
                    phase = ListPickerUiState.Phase.Error,
                    contactName = "",
                    lists = emptyList(),
                    selectedListIds = _selectedListIds.value,
                ),
            )
        }

    // ─── Public callbacks ───────────────────────────────────────────────────

    /** PICK-09: the Error state's Retry: re-subscribe every source. */
    fun onRetry() {
        retryCount.update { it + 1 }
    }

    fun onToggleListSelect(id: Long) {
        // A list the person is already on cannot be picked (its row is
        // disabled too); refusing here covers a stale tap. The rows are known
        // only while the screen collects uiState, so the commit re-checks
        // against a fresh membership snapshot for the restored-selection case.
        if (uiState.value.lists.any { it.listId == id && it.isMember }) return
        val current = _selectedListIds.value
        _selectedListIds.value = if (id in current) current - id else current + id
    }

    /**
     * 2026-06-09 #26 — inline create, killing the "Create a list first, then
     * come back" dead-end. Creates a STATIC list mirroring the Lists Manager
     * "Start from blank" shape (KEEP_IN_TOUCH cadence so the new list is
     * immediately surfaceable, sortOrder appended at the bottom) and selects
     * it, so the user's next tap is the commit CTA — no round trip.
     *
     * Runs on [viewModelScope] (the user stays on this screen, unlike
     * [onCommit]'s pop-then-write lifecycle). Failure surfaces on the
     * app-level [PickerCommitBus] snackbar — the same surface this screen
     * already relies on for commit outcomes.
     */
    fun onCreateList(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            try {
                val ruleTemplateId: Long? = ruleTemplateRepo.getByKind(RuleKind.KEEP_IN_TOUCH)?.id
                val nextSortOrder =
                    (listRepo.observeAll().first().maxOfOrNull { it.sortOrder } ?: -1) + 1
                val newListId = listRepo.create(
                    ListEntity(
                        name = trimmed,
                        sortOrder = nextSortOrder,
                        isArchived = false,
                        type = ListType.STATIC,
                        smartRuleJson = null,
                        ruleTemplateId = ruleTemplateId,
                        activeHoursStart = null,
                        activeHoursEnd = null,
                        notificationsEnabled = true,
                        ruleParamsOverrideJson = null,
                    ),
                )
                _selectedListIds.value = _selectedListIds.value + newListId
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                commitBus.publish(SnackbarEvent(UiText.res(R.string.picker_snackbar_create_list_failed)))
            }
        }
    }

    fun onClearSelection() {
        _selectedListIds.value = emptySet()
    }

    /**
     * Commit the selected lists. Picker-commit lifecycle — same shape
     * as [ContactPickerViewModel.onCommit]: the insert runs on [appScope], NOT
     * viewModelScope, because the caller pops this screen immediately after
     * invoking onCommit (which clears the VM and would cancel the write
     * mid-flight). The outcome, "Added to N list[s]" with Undo where N counts
     * the rows actually written, or "Couldn't save that" when nothing could
     * be (a failure, or only lists the person is already on), is published on
     * [PickerCommitBus] so the app-level [PickerCommitSnackbarHost] shows it on
     * the caller after the pop. [CancellationException] is rethrown per
     * codebase convention.
     */
    fun onCommit() {
        val ids = _selectedListIds.value.toList()
        if (ids.isEmpty()) return
        // C6: NotFound surface short-circuits commit — no contactId, no insert.
        val cId = contactId ?: return
        _isCommitting.value = true
        // Clear eagerly on the main thread — the selection belongs to the
        // dying back-stack entry (the init collector mirrors this write into
        // SavedStateHandle on viewModelScope, which dies with the screen).
        _selectedListIds.value = emptySet()
        appScope.launch {
            try {
                // Snapshot inside the write, as CopyContactsUseCase does: only
                // lists the person is NOT already on are inserted, so the count
                // says what was written and Undo removes only those rows. A
                // smart list (rule-derived rows), an archived list or one that
                // is gone is not a target either.
                val alreadyOn: Set<Long> =
                    listRepo.observeMembershipsForContact(cId).first().map { it.listId }.toSet()
                val toInsert = ids.filter { listId ->
                    val target = listRepo.getById(listId)
                    target != null && !target.isArchived && target.type != ListType.SMART && listId !in alreadyOn
                }
                if (toInsert.isEmpty()) {
                    // rules.md Code 3: a commit that writes nothing is a failed
                    // save, not "Added to 1 list".
                    commitBus.publish(SnackbarEvent(UiText.res(R.string.picker_snackbar_save_failed)))
                } else {
                    val now = clock.now()
                    listMembershipDao.insertAll(
                        toInsert.map { listId ->
                            ListMembershipEntity(
                                listId = listId,
                                contactId = cId,
                                addedAt = now,
                            )
                        },
                    )
                    val message = UiText.plural(R.plurals.picker_snackbar_added_to_lists, toInsert.size, toInsert.size)
                    val inverse: suspend () -> Unit = {
                        toInsert.forEach { listId ->
                            listMembershipDao.removeAll(listId, listOf(cId))
                        }
                    }
                    undoStack.put(UndoStack.PendingUndo(inverse = inverse))
                    commitBus.publish(SnackbarEvent.undoable(message))
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                commitBus.publish(SnackbarEvent(UiText.res(R.string.picker_snackbar_save_failed)))
            } finally {
                _isCommitting.value = false
            }
        }
    }

    private companion object {
        // M4 — SavedStateHandle key for selection persistence.
        const val KEY_SELECTED_LIST_IDS = "selectedListIds"
    }
}
