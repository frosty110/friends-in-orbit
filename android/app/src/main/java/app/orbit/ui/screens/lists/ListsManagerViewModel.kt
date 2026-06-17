package app.orbit.ui.screens.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.orbit.data.entity.ListEntity
import app.orbit.data.repository.ListRepository
import app.orbit.data.repository.RuleTemplateRepository
import app.orbit.domain.JsonProvider
import app.orbit.domain.smart.SmartListRule
import app.orbit.notify.NudgeScheduler
import app.orbit.ui.screens.picker.SnackbarEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Lists Manager ViewModel (LIST-02 / LIST-07).
 *
 * Reactive projection: `combine(listRepo.observeAll(), archivedExpanded)`.
 *  - active   = `!isArchived` rows, sorted by `sortOrder` ASC
 *  - archived = `isArchived`  rows, sorted by `sortOrder` DESC
 *  - empty repo → `Empty`; otherwise → `Ready(active, archived, archivedExpanded)`
 *
 * `moveList` dispatches to `listRepo.reorder` under
 * a [Mutex] held in the VM. The repository implementation also wraps reorder
 * in `db.withTransaction`. Both layers are required — the VM
 * mutex serialises rapid drag emissions before they reach the DB, the DB
 * transaction guarantees the range-only `sortOrder` rewrite is atomic.
 *
 * No archived rows ever leak into Home — that
 * filter is enforced in `HomeViewModel`, not here. Lists Manager is the only
 * surface that sees archived lists.
 */
@HiltViewModel
class ListsManagerViewModel @Inject constructor(
    private val listRepo: ListRepository,
    private val ruleTemplateRepo: RuleTemplateRepository,
    private val nudgeScheduler: NudgeScheduler,
) : ViewModel() {

    private val reorderMutex = Mutex()
    private val archivedExpanded = MutableStateFlow(false)
    private val json = JsonProvider.json

    // H4 fix — VM-owned snackbar surface so [runMutation] can emit a failure
    // toast when a mutation throws. The screen subscribes via [snackbarEvents].
    private val _snackbarEvents = MutableSharedFlow<SnackbarEvent>(extraBufferCapacity = 1)
    val snackbarEvents: SharedFlow<SnackbarEvent> = _snackbarEvents.asSharedFlow()

    // 2026-06-09 #26 — create no longer strands the user on the manager with a
    // "List created." toast. [createList] emits the new row id here; the screen
    // collector navigates straight to the new list's configuration screen
    // (naming, cadence, adding people — the work the user came to do).
    private val _createdListEvents = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val createdListEvents: SharedFlow<Long> = _createdListEvents.asSharedFlow()

    val uiState: StateFlow<ListsManagerUiState> =
        combine(
            listRepo.observeAll(),
            listRepo.observeMemberCountsByListId(),
            archivedExpanded,
        ) { rows, memberCountsByListId, expanded ->
            if (rows.isEmpty()) {
                ListsManagerUiState.Empty
            } else {
                val active = rows.filter { !it.isArchived }
                    .sortedBy { it.sortOrder }
                    .map { it.toTile(memberCountsByListId) }
                val archived = rows.filter { it.isArchived }
                    .sortedByDescending { it.sortOrder }
                    .map { it.toTile(memberCountsByListId) }
                ListsManagerUiState.Ready(
                    active = active,
                    archived = archived,
                    archivedExpanded = expanded,
                )
            }
        }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000L),
                initialValue = ListsManagerUiState.Loading,
            )

    /** LIST-02 (reorder) — the mutex guards the dispatch. */
    fun moveList(fromIndex: Int, toIndex: Int) {
        viewModelScope.launch {
            runMutation {
                reorderMutex.withLock {
                    listRepo.reorder(fromIndex = fromIndex, toIndex = toIndex)
                }
            }
        }
    }

    /**
     * LIST-02 (archive) — flips `isArchived` to true; memberships untouched.
     *
     * LOW polish (Group 5) — emits the "List archived." snackbar event from the
     * VM, NOT the screen, with the listId carried via [SnackbarEvent.actionPayload].
     * The screen collector dispatches `onUndoArchive(payload)` when the user taps
     * Undo. Without this, the previous screen-side `scope.launch { showSnackbar }`
     * died if the user navigated away mid-snackbar — the Undo work never ran.
     */
    fun archiveList(listId: Long) {
        viewModelScope.launch {
            runMutation {
                listRepo.setArchived(listId, archived = true)
                // NOTIF-11: cancel the list's nudge chain when archived so no
                // nudge fires for a list the user has put away. The cancel is
                // inside runMutation so the repo write and the WM cancel move
                // together — a throw in either leaves no orphan chain.
                nudgeScheduler.cancel(listId)
            }
            _snackbarEvents.tryEmit(
                SnackbarEvent(
                    message = "List archived.",
                    actionLabel = "Undo",
                    actionPayload = listId,
                ),
            )
        }
    }

    /** LIST-02 (archive) — flips `isArchived` back to false; restores active set. */
    fun unarchiveList(listId: Long) {
        viewModelScope.launch {
            runMutation {
                listRepo.setArchived(listId, archived = false)
                // NOTIF-11: re-enqueue the list's nudge chain when unarchived.
                // scheduleFromEntity reads the entity's nudgeScheduleJson and
                // activeHoursStart (D-09 forwarding) so the schedule is authoritative.
                // Inside runMutation so the repo write and the WM enqueue move together.
                val entity = listRepo.getById(listId)
                if (entity != null) {
                    nudgeScheduler.scheduleFromEntity(entity)
                }
            }
        }
    }

    /**
     * D-25 — hard-delete an archived list. The PRD requires Delete to be
     * reachable only from the archived section, so this is invoked from
     * `ArchivedListRow` only. Memberships cascade via Room FK `ON DELETE
     * CASCADE`. The snackbar carries no Undo: archive already provides the
     * reversible path; once Delete is confirmed, the row is gone.
     */
    fun deleteList(listId: Long) {
        viewModelScope.launch {
            runMutation {
                listRepo.delete(listId)
                // NOTIF-11 / folded D-25 todo: cancel the list's nudge chain on
                // hard-delete. The cancel is inside runMutation so the delete and
                // the WM cancel move together — a throw leaves no orphan chain.
                nudgeScheduler.cancel(listId)
            }
            _snackbarEvents.tryEmit(SnackbarEvent(message = "List deleted."))
        }
    }

    /**
     * LOW polish (Group 5) — paired with [archiveList]'s SnackbarEvent. The screen's
     * snackbar collector invokes this when the user taps Undo on the
     * "List archived." event. Re-uses [unarchiveList] under the hood so the
     * mutation surface and runMutation error handling stay uniform.
     */
    fun onUndoArchive(listId: Long) {
        unarchiveList(listId)
    }

    /** UI toggle for the Archived (N) collapsible section. */
    fun toggleArchivedExpanded() {
        archivedExpanded.value = !archivedExpanded.value
    }

    /**
     * F-11 — quick rename from the Lists Manager overflow menu. Delegates to
     * [ListRepository.updateName] (the same setter F-12 uses for inline rename
     * inside List Configuration). The screen-side dialog blocks empty/blank
     * commits; this VM method double-guards by trimming and dropping blanks
     * so direct callers can't slip an invalid name past the validation.
     */
    fun renameList(listId: Long, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            runMutation { listRepo.updateName(listId, trimmed) }
        }
    }

    /**
     * LIST-01 / SMART-02 — persist a fresh [ListEntity] from a [TemplateChoice]
     * + user-typed [name].
     *
     * Behaviour:
     *  - Trims [name]; defensive no-op if empty (the bottom sheet blocks empty
     *    submission, but the VM double-guards in case future callers skip the
     *    sheet's validation).
     *  - Resolves the rule template id by [TemplateChoice.ruleKind] when set
     *    (the four named static templates and Start from blank all default to
     *    KEEP_IN_TOUCH so the new list is immediately surfaceable).
     *  - Encodes [TemplateChoice.smartRule] to JSON via
     *    [JsonProvider.json] + [SmartListRule.serializer] when set (only the
     *    "Recently added, not called" template carries one).
     *  - Computes the next `sortOrder` as `max(existing) + 1` over the current
     *    [listRepo] snapshot — keeps the new row at the bottom of the active
     *    list per LIST-02's stable ordering invariant.
     *  - Dispatches the insert via [listRepo.create] on [viewModelScope]; the
     *    returned [Job] lets the caller observe completion if it wants to.
     *  - 2026-06-09 #26 — emits the new row id on [createdListEvents] so the
     *    screen can navigate to the new list's configuration screen.
     */
    fun createList(template: TemplateChoice, name: String): Job =
        viewModelScope.launch {
            runMutation {
                val trimmed = name.trim()
                if (trimmed.isEmpty()) return@runMutation
                val ruleTemplateId: Long? = template.ruleKind
                    ?.let { ruleTemplateRepo.getByKind(it) }
                    ?.id
                val smartRuleJson: String? = template.smartRule
                    ?.let { json.encodeToString(SmartListRule.serializer(), it) }
                val nextSortOrder =
                    (listRepo.observeAll().first().maxOfOrNull { it.sortOrder } ?: -1) + 1
                val draft = ListEntity(
                    name = trimmed,
                    sortOrder = nextSortOrder,
                    isArchived = false,
                    type = template.type,
                    smartRuleJson = smartRuleJson,
                    ruleTemplateId = ruleTemplateId,
                    activeHoursStart = null,
                    activeHoursEnd = null,
                    notificationsEnabled = true,
                    ruleParamsOverrideJson = null,
                )
                val newListId = listRepo.create(draft)
                _createdListEvents.tryEmit(newListId)
            }
        }

    /**
     * H4 fix — wraps a mutation block with a uniform try/catch + snackbar
     * surface. Without this, an exception inside `viewModelScope.launch` is
     * silently dropped (the coroutine's uncaught handler on a viewModelScope
     * is a no-op for non-Throwable types) and the UI shows stale optimistic
     * state. `CancellationException` is rethrown so structured concurrency
     * cancellation still propagates correctly.
     */
    private suspend fun runMutation(
        failureLabel: String = "Couldn't save your change",
        block: suspend () -> Unit,
    ) {
        try {
            block()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            _snackbarEvents.tryEmit(SnackbarEvent(failureLabel))
        }
    }

    private fun ListEntity.toTile(memberCountsByListId: Map<Long, Int>): ListTileState = ListTileState(
        id = id,
        name = name,
        // Per-list count via ListMembershipDao.observeMemberCountsByListId.
        // Empty lists are absent from the map; default to 0.
        memberCount = memberCountsByListId[id] ?: 0,
        type = type,
        ruleSummary = ruleSummary(smartRuleJson),
    )

    /**
     * Sentence-case rule-summary formatter for the Lists Manager copywriting
     * contract. Maps each [SmartListRule] subtype to its
     * verbatim row subtitle. Returns null for static lists or for malformed JSON
     * — bad JSON should not crash the screen; the row simply renders without a
     * subtitle.
     */
    private fun ruleSummary(smartRuleJson: String?): String? {
        if (smartRuleJson.isNullOrBlank()) return null
        val rule = try {
            json.decodeFromString(SmartListRule.serializer(), smartRuleJson)
        } catch (_: Throwable) {
            return null
        }
        return when (rule) {
            is SmartListRule.RecentlyAddedNotCalled -> "Recently added · ${rule.daysWindow} days"
            is SmartListRule.LongGap -> "Long gap · ${rule.daysThreshold} days"
            is SmartListRule.CommonlyCalled -> "Commonly called · top ${rule.topPercent}%"
            is SmartListRule.RarelyCalled -> "Rarely called · bottom ${rule.bottomPercent}%"
            SmartListRule.NeverCalled -> "Never called"
        }
    }
}
