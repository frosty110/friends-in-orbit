package app.orbit.ui.screens.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.orbit.R
import app.orbit.data.entity.ListEntity
import app.orbit.data.repository.ListRepository
import app.orbit.data.repository.RuleTemplateRepository
import app.orbit.domain.JsonProvider
import app.orbit.domain.rule.RuleParams
import app.orbit.domain.smart.SmartListRule
import app.orbit.notify.NudgeScheduler
import app.orbit.ui.screens.home.HomeSnackbarEvent
import app.orbit.ui.util.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

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
    private val nudgeScheduler: NudgeScheduler
) : ViewModel() {

    private val reorderMutex = Mutex()
    private val archivedExpanded = MutableStateFlow(false)
    private val json = JsonProvider.json

    // H4 fix — VM-owned snackbar surface so [runMutation] can emit a failure
    // toast when a mutation throws. The screen subscribes via [snackbarEvents].
    // Same event type as Home: both surfaces offer archive and delete with Undo,
    // and the spec requires them to behave identically (features/orbit-lists,
    // "the same delete-with-Undo behavior"). The kind tells the collector what an
    // Undo tap and a dismissal mean.
    private val _snackbarEvents = MutableSharedFlow<HomeSnackbarEvent>(extraBufferCapacity = 1)
    val snackbarEvents: SharedFlow<HomeSnackbarEvent> = _snackbarEvents.asSharedFlow()

    // Lists staged for a deferred delete: hidden immediately on confirm, purged
    // in [commitDelete] once the Undo window closes (mirrors HomeViewModel).
    private val pendingDeletes = MutableStateFlow<Set<Long>>(emptySet())

    // 2026-06-09 #26 — create no longer strands the user on the manager with a
    // "List created." toast. [createList] emits the new row id here; the screen
    // collector navigates straight to the new list's configuration screen
    // (naming, cadence, adding people — the work the user came to do).
    private val _createdListEvents = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val createdListEvents: SharedFlow<Long> = _createdListEvents.asSharedFlow()

    // LIST-22: bumped by [onRetry] to re-subscribe after a failure.
    private val retryCount = MutableStateFlow(0)

    /** The Error state's Try again. */
    fun onRetry() {
        retryCount.update { it + 1 }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<ListsManagerUiState> = retryCount.flatMapLatest {
        combine(
            listRepo.observeAll(),
            listRepo.observeMemberCountsByListId(),
            archivedExpanded,
            pendingDeletes
        ) { allRows, memberCountsByListId, expanded, pending ->
            val rows = allRows.filter { it.id !in pending }
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
                    archivedExpanded = expanded
                )
            }
        }.catch { t ->
            if (t is CancellationException) throw t
            emit(ListsManagerUiState.Error)
        }
    }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000L),
                initialValue = ListsManagerUiState.Loading
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
     * VM, NOT the screen, with the listId carried via [HomeSnackbarEvent.payloadListId].
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
                HomeSnackbarEvent(
                    message = UiText.res(R.string.lists_snackbar_archived),
                    actionLabel = UiText.res(R.string.components_action_undo),
                    payloadListId = listId,
                    kind = HomeSnackbarEvent.Kind.ARCHIVE_UNDO
                )
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
     * D-25: delete an archived list, reachable only from the archived section.
     * Memberships cascade via Room FK `ON DELETE CASCADE`.
     *
     * Deferred, with Undo, exactly like Home's delete: the row hides now and
     * the purge runs in [commitDelete] when the snackbar closes. This used to
     * delete immediately with no Undo on the grounds that archive was the
     * reversible step; once Home offered Delete with Undo, the spec required
     * this surface to match (features/orbit-lists, "Delete recoverability").
     */
    fun deleteList(listId: Long) {
        pendingDeletes.update { it + listId }
        _snackbarEvents.tryEmit(
            HomeSnackbarEvent(
                message = UiText.res(R.string.lists_snackbar_deleted),
                actionLabel = UiText.res(R.string.components_action_undo),
                payloadListId = listId,
                kind = HomeSnackbarEvent.Kind.DELETE_UNDO
            )
        )
    }

    /** Undo of [deleteList]: the row was never purged, so just un-hide it. */
    fun undoDelete(listId: Long) {
        pendingDeletes.update { it - listId }
    }

    /**
     * Finalize a deferred delete once the Undo window closes (snackbar
     * dismissed, superseded, or the screen left). Idempotent: a no-op when the
     * list is no longer pending. The row stays hidden until Room confirms.
     */
    fun commitDelete(listId: Long) {
        if (listId !in pendingDeletes.value) return
        viewModelScope.launch {
            runMutation {
                listRepo.delete(listId)
                // NOTIF-11 / folded D-25 todo: cancel the list's nudge chain on
                // hard-delete. The cancel is inside runMutation so the delete and
                // the WM cancel move together — a throw leaves no orphan chain.
                nudgeScheduler.cancel(listId)
            }
            pendingDeletes.update { it - listId }
        }
    }

    /**
     * LOW polish (Group 5): paired with [archiveList]'s snackbar event. The screen's
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
    fun createList(template: TemplateChoice, name: String): Job = viewModelScope.launch {
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
                // The template's own rhythm, encoded exactly as the interval
                // slider writes it (KeepInTouch.withIntervalHours keeps both
                // cooldown bounds consistent).
                ruleParamsOverrideJson = template.intervalDays?.let { days ->
                    json.encodeToString(
                        RuleParams.serializer(),
                        RuleParams.KeepInTouch().withIntervalHours(days * 24)
                    )
                }
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
        failureLabel: UiText = UiText.res(R.string.components_snackbar_save_failed),
        block: suspend () -> Unit
    ) {
        try {
            block()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            _snackbarEvents.tryEmit(HomeSnackbarEvent(message = failureLabel))
        }
    }

    private fun ListEntity.toTile(memberCountsByListId: Map<Long, Int>): ListTileState =
        ListTileState(
            id = id,
            name = name,
            // Per-list count via ListMembershipDao.observeMemberCountsByListId.
            // Empty lists are absent from the map; default to 0.
            memberCount = memberCountsByListId[id] ?: 0,
            type = type,
            ruleSummary = ruleSummary(smartRuleJson)
        )

    /**
     * Sentence-case rule-summary formatter for the Lists Manager copywriting
     * contract. Maps each [SmartListRule] subtype to its row subtitle
     * (strings_lists.xml, with plurals for the day counts). Returns null for
     * static lists or for malformed JSON: bad JSON should not crash the
     * screen; the row simply renders without a subtitle.
     */
    private fun ruleSummary(smartRuleJson: String?): UiText? {
        if (smartRuleJson.isNullOrBlank()) return null
        val rule = try {
            json.decodeFromString(SmartListRule.serializer(), smartRuleJson)
        } catch (_: Throwable) {
            return null
        }
        return when (rule) {
            is SmartListRule.RecentlyAddedNotCalled ->
                UiText.plural(R.plurals.lists_rule_summary_recently_added, rule.daysWindow, rule.daysWindow)
            is SmartListRule.LongGap ->
                UiText.plural(R.plurals.lists_rule_summary_long_gap, rule.daysThreshold, rule.daysThreshold)
            is SmartListRule.CommonlyCalled ->
                UiText.res(R.string.lists_rule_summary_commonly_called, rule.topPercent)
            is SmartListRule.RarelyCalled ->
                UiText.res(R.string.lists_rule_summary_rarely_called, rule.bottomPercent)
            SmartListRule.NeverCalled -> UiText.res(R.string.lists_rule_summary_never_called)
        }
    }
}
