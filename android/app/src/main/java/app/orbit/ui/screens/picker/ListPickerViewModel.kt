package app.orbit.ui.screens.picker

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Reverse picker (BULK-06): given a contact, lists are the rows the user
 * multi-selects.
 *
 * Sibling pattern of [ContactPickerViewModel]:
 *   - `@HiltViewModel` with constructor-injected dependencies.
 *   - Reads `contactId` from [SavedStateHandle] (Hilt cannot bind plain
 *     `String` types).
 *   - `combine(...).stateIn(WhileSubscribed(5_000L))` exposes a [UiState]
 *     (ARCH-02 invariant).
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
 *   - Picker shows only non-archived lists (filtered post-collect).
 *   - Lists the contact already belongs to are NOT excluded from the row set
 *     — the user can re-tap and the DAO's `OnConflictStrategy.IGNORE` keeps
 *     the operation idempotent (the list of lists is small).
 */
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
    // or missing arg routes to the [UiState.Phase.NotFound] empty state instead
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

    init {
        viewModelScope.launch {
            _selectedListIds.collect {
                savedStateHandle[KEY_SELECTED_LIST_IDS] = it.toLongArray()
            }
        }
    }

    // ─── UiState ────────────────────────────────────────────────────────────
    data class UiState(
        val phase: Phase,
        val contactName: String,
        val lists: List<ListRow>,
        val selectedListIds: Set<Long>,
    ) {
        enum class Phase { Loading, Ready, Committing, NotFound }

        data class ListRow(
            val listId: Long,
            val name: String,
            val isMember: Boolean,
        )

        val canCommit: Boolean get() = selectedListIds.isNotEmpty() && phase != Phase.Committing
        val selectionCount: Int get() = selectedListIds.size
    }

    val uiState: StateFlow<UiState> =
        if (contactId == null) {
            // C6: terminal NotFound — combine pipeline never starts; emit a
            // single static state. The screen renders a "Contact not found"
            // empty surface in this branch.
            kotlinx.coroutines.flow.flowOf(
                UiState(
                    phase = UiState.Phase.NotFound,
                    contactName = "",
                    lists = emptyList(),
                    selectedListIds = emptySet(),
                ),
            ).stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000L),
                initialValue = UiState(
                    phase = UiState.Phase.NotFound,
                    contactName = "",
                    lists = emptyList(),
                    selectedListIds = emptySet(),
                ),
            )
        } else {
            combine(
                listRepo.observeAll(),
                contactRepo.observeById(contactId),
                listRepo.observeMembershipsForContact(contactId),
                _selectedListIds,
                _isCommitting,
            ) { lists, contact, memberships, selected, committing ->
                val memberListIds: Set<Long> = memberships.map { it.listId }.toSet()
                UiState(
                    phase = when {
                        committing -> UiState.Phase.Committing
                        else -> UiState.Phase.Ready
                    },
                    contactName = contact?.displayName.orEmpty(),
                    lists = lists
                        .filter { !it.isArchived }
                        .map {
                            UiState.ListRow(
                                listId = it.id,
                                name = it.name,
                                isMember = it.id in memberListIds,
                            )
                        },
                    selectedListIds = selected,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000L),
                initialValue = UiState(
                    phase = UiState.Phase.Loading,
                    contactName = "",
                    lists = emptyList(),
                    selectedListIds = emptySet(),
                ),
            )
        }

    // ─── Public callbacks ───────────────────────────────────────────────────
    fun onToggleListSelect(id: Long) {
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
                commitBus.publish(SnackbarEvent("Couldn't create the list"))
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
     * mid-flight). The outcome — "Added to N list[s]" with Undo, or "Couldn't
     * save that" on failure — is published on [PickerCommitBus] so the
     * app-level [PickerCommitSnackbarHost] shows it on the caller after the
     * pop. [CancellationException] is rethrown per codebase convention.
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
                val now = clock.now()
                val memberships = ids.map { listId ->
                    ListMembershipEntity(
                        listId = listId,
                        contactId = cId,
                        addedAt = now,
                    )
                }
                listMembershipDao.insertAll(memberships)
                val label = if (ids.size == 1) {
                    "Added to 1 list"
                } else {
                    "Added to ${ids.size} lists"
                }
                val inverse: suspend () -> Unit = {
                    ids.forEach { listId ->
                        listMembershipDao.removeAll(listId, listOf(cId))
                    }
                }
                undoStack.put(UndoStack.PendingUndo(inverse = inverse, label = label))
                commitBus.publish(SnackbarEvent(label, "Undo"))
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                commitBus.publish(SnackbarEvent("Couldn't save that"))
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
