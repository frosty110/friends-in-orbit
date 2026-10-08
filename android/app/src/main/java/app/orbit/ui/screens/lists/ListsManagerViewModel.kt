package app.orbit.ui.screens.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.orbit.R
import app.orbit.data.entity.ListEntity
import app.orbit.data.entity.ListType
import app.orbit.data.repository.ListRepository
import app.orbit.data.repository.RuleTemplateRepository
import app.orbit.domain.JsonProvider
import app.orbit.domain.WidgetRefreshTrigger
import app.orbit.domain.rule.baseIntervalHours
import app.orbit.domain.smart.SmartListRule
import app.orbit.notify.NudgeScheduler
import app.orbit.ui.screens.home.HomeSnackbarEvent
import app.orbit.ui.util.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
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
 *
 * Every success snackbar here is gated on the write succeeding: [runMutation]
 * returns whether the block completed, and the caller announces only on
 * `true`. A failed write says "Couldn't save your change" and nothing else
 * (rules.md Code 3). Archive, restore and delete also ask the widget to
 * refresh on success (WIDGET-06: refreshes follow the data), since the widget
 * reads the active lists and would otherwise keep offering an archived list's
 * lead until the hourly sweep.
 */
@HiltViewModel
class ListsManagerViewModel @Inject constructor(
    private val listRepo: ListRepository,
    private val ruleTemplateRepo: RuleTemplateRepository,
    private val nudgeScheduler: NudgeScheduler,
    // Trailing, with the no-op default the use cases use, so existing callers
    // and tests that build the VM positionally keep compiling; Hilt binds the
    // real one (WidgetModule).
    private val widgetRefreshTrigger: WidgetRefreshTrigger = WidgetRefreshTrigger { }
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
    //
    // More than one slot, so an event emitted while the collector has not yet
    // taken the previous one is kept rather than dropped by tryEmit. With one
    // slot a failure followed in the same turn by a success message lost the
    // second silently, which is what hid the ungated success emit (and would
    // hide a failure that follows a confirmation). The screen collects with
    // collectLatest, so the newest event still supersedes the one showing.
    private val _snackbarEvents = MutableSharedFlow<HomeSnackbarEvent>(extraBufferCapacity = SNACKBAR_EVENT_BUFFER)
    val snackbarEvents: SharedFlow<HomeSnackbarEvent> = _snackbarEvents.asSharedFlow()

    // Lists staged for a deferred delete: hidden immediately on confirm, purged
    // in [commitDelete] once the Undo window closes (mirrors HomeViewModel).
    private val pendingDeletes = MutableStateFlow<Set<Long>>(emptySet())

    // Creating a list is New list's (LIST-28: NewListViewModel and
    // CreateListUseCase); this screen only opens it. Until 2026-10-07 a
    // create sheet here called a createList of this ViewModel, and the new
    // list's id was emitted for the screen to open its settings.

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
     *
     * The success event is emitted only when the write went through. Until
     * 2026-10-06 it was emitted unconditionally after [runMutation] returned,
     * so a failed archive queued "Couldn't save your change" and then "List
     * archived." with an Undo that would unarchive nothing; in practice the
     * one-slot buffer dropped the second event, which is an accident, not a
     * design.
     */
    fun archiveList(listId: Long) {
        viewModelScope.launch {
            val saved = runMutation {
                listRepo.setArchived(listId, archived = true)
                // NOTIF-11: cancel the list's nudge chain when archived so no
                // nudge fires for a list the user has put away. The cancel is
                // inside runMutation so the repo write and the WM cancel move
                // together — a throw in either leaves no orphan chain.
                nudgeScheduler.cancel(listId)
            }
            if (!saved) return@launch
            widgetRefreshTrigger.scheduleRefresh()
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

    /**
     * LIST-02 (archive): the Archived section's Restore. Flips `isArchived`
     * back to false and announces "List restored." once the write is in; the
     * screen used to show that message itself, before the write resolved and
     * whether or not it succeeded.
     */
    fun unarchiveList(listId: Long) {
        viewModelScope.launch {
            if (!restoreList(listId)) return@launch
            _snackbarEvents.tryEmit(HomeSnackbarEvent(message = UiText.res(R.string.lists_snackbar_restored)))
        }
    }

    /**
     * The shared restore write behind [unarchiveList] and [onUndoArchive].
     * Returns whether it succeeded, so each caller decides what to announce:
     * Restore confirms, Undo stays quiet, as Home's undo does.
     */
    private suspend fun restoreList(listId: Long): Boolean {
        val saved = runMutation {
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
        if (saved) widgetRefreshTrigger.scheduleRefresh()
        return saved
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

    /**
     * Undo of [deleteList]: the row was never purged, so just un-hide it.
     * Nothing in Room changed, so the widget has nothing to catch up on.
     */
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
            val saved = runMutation {
                listRepo.delete(listId)
                // NOTIF-11 / folded D-25 todo: cancel the list's nudge chain on
                // hard-delete. The cancel is inside runMutation so the delete and
                // the WM cancel move together — a throw leaves no orphan chain.
                nudgeScheduler.cancel(listId)
            }
            if (saved) widgetRefreshTrigger.scheduleRefresh()
            pendingDeletes.update { it - listId }
        }
    }

    /**
     * LOW polish (Group 5): paired with [archiveList]'s snackbar event. The screen's
     * snackbar collector invokes this when the user taps Undo on the
     * "List archived." event. Re-uses [restoreList] under the hood so the
     * mutation surface and runMutation error handling stay uniform; unlike
     * Restore it confirms nothing, since the list simply reappears.
     */
    fun onUndoArchive(listId: Long) {
        viewModelScope.launch { restoreList(listId) }
    }

    /**
     * The row menu's "Pause nudges" / "Resume nudges" (LIST-23: the same entry
     * Home's long-press menu offers, with the same words). Reads the list's
     * current flag inside the write so the new state and the confirming copy
     * ("Nudges paused." / "Nudges on.") derive from one read; no Undo, since
     * re-tapping reverses it. The list itself is not paused and its people
     * still surface, which is why the glossary says "Pause nudges" and not
     * "Pause" here.
     */
    fun toggleNudges(listId: Long) {
        viewModelScope.launch {
            var enable = false
            val saved = runMutation {
                val current = checkNotNull(listRepo.getById(listId)) { "list $listId is gone" }
                enable = !current.notificationsEnabled
                listRepo.updateNotificationsEnabled(listId, enable)
            }
            if (!saved) return@launch
            _snackbarEvents.tryEmit(
                HomeSnackbarEvent(
                    message = UiText.res(
                        if (enable) R.string.home_snackbar_nudges_on else R.string.home_snackbar_nudges_paused,
                    ),
                )
            )
        }
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
     * H4 fix — wraps a mutation block with a uniform try/catch + snackbar
     * surface. Without it an exception inside `viewModelScope.launch` is not
     * dropped: viewModelScope installs no CoroutineExceptionHandler, so the
     * exception reaches the thread's uncaught handler and crashes the app.
     * The wrapper exists so a failed write tells the user instead (rules.md
     * Code 3). `CancellationException` is rethrown so structured concurrency
     * cancellation still propagates correctly.
     *
     * Returns true when [block] completed, false when it threw and the failure
     * snackbar was emitted, so callers announce success only when there was
     * one (rules.md Code 3: a failed write surfaces as a failure, never as a
     * success message).
     */
    private suspend fun runMutation(
        failureLabel: UiText = UiText.res(R.string.components_snackbar_save_failed),
        block: suspend () -> Unit
    ): Boolean {
        return try {
            block()
            true
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            _snackbarEvents.tryEmit(HomeSnackbarEvent(message = failureLabel))
            false
        }
    }

    private suspend fun ListEntity.toTile(memberCountsByListId: Map<Long, Int>): ListTileState =
        ListTileState(
            id = id,
            name = name,
            // Per-list count via ListMembershipDao.observeMemberCountsByListId.
            // Empty lists are absent from the map; default to 0.
            memberCount = memberCountsByListId[id] ?: 0,
            type = type,
            ruleSummary = when (type) {
                ListType.SMART -> ruleSummary(smartRuleJson)
                ListType.STATIC -> rhythmSummary(this)
            },
            notificationsEnabled = notificationsEnabled
        )

    /**
     * Sentence-case rule-summary formatter for the Lists Manager copywriting
     * contract. Maps each [SmartListRule] subtype to its row subtitle
     * (strings_lists.xml, with plurals for the day counts). Returns null for
     * malformed JSON: bad JSON should not crash the screen; the row simply
     * renders without a subtitle.
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

    /**
     * A regular list's rhythm as its row subtitle, as its interval whichever
     * rule it runs (LIST-30): "Every 14 days" for Keep in touch, "Every 3
     * days" for Late night, "Every day" for Energize, in the words How often
     * uses ([howOftenEveryLabel]). Until 2026-10-07 the last two read "Late
     * night rhythm" and "Energize rhythm", names List settings no longer
     * shows anywhere. The parameters resolve through
     * the resolver List settings shares ([resolveRuleParams]): the per-list
     * override wins, else the template's defaults. A "Start from blank" list
     * has no override, so its rhythm lives only in the seeded
     * `RuleTemplateEntity`, which is why this takes the template lookup and
     * not the override JSON alone. Null when neither exists (a partially
     * configured row). A blob that does not decode says so
     * (`lists_rhythm_unreadable`) instead of passing for a list with no
     * rhythm: both are Orbit's own writes, so a failed decode is a bug the
     * user should see where the deck fails on the same data (rules.md Code
     * 3). Until 2026-10-06 both cases rendered the same empty line.
     */
    private suspend fun rhythmSummary(entity: ListEntity): UiText? {
        // One cached lookup per row per emission: the template table holds
        // three immutable rows, so this never reaches the database.
        val template = entity.ruleTemplateId?.let { ruleTemplateRepo.getById(it) }
        val resolved = resolveRuleParams(entity.ruleParamsOverrideJson, template?.paramsJson, json)
        val params = when (resolved) {
            RuleParamsResolution.None -> return null
            RuleParamsResolution.Unreadable -> return UiText.res(R.string.lists_rhythm_unreadable)
            is RuleParamsResolution.Decoded -> resolved.params
        }
        // Whole days, as the interval slider shows them (48h reads "Every 2 days").
        return howOftenEveryLabel(intervalDaysFor(params.baseIntervalHours))
    }

    private companion object {
        /** Snackbar events a turn can queue before tryEmit drops one; see [_snackbarEvents]. */
        const val SNACKBAR_EVENT_BUFFER = 8
    }
}
