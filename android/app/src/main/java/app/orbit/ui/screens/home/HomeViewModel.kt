package app.orbit.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.orbit.data.feed.HomeFeed
import app.orbit.data.repository.ListRepository
import app.orbit.domain.clock.Clock
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One-shot snackbar event for the home long-press quick-actions menu.
 *
 * Home owns its own event type (rather than reusing the picker's
 * `SnackbarEvent`) because it needs a [kind] to tell the screen-side collector
 * what an action tap and a dismissal MEAN: undo-archive, undo-delete, or
 * nothing. [payloadListId] carries the affected list across the snackbar
 * boundary so the collector can route back to a typed VM method.
 */
data class HomeSnackbarEvent(
    val message: String,
    val actionLabel: String? = null,
    val payloadListId: Long? = null,
    val kind: Kind = Kind.PLAIN,
) {
    enum class Kind { PLAIN, ARCHIVE_UNDO, DELETE_UNDO }
}

/**
 * Home VM is a thin subscriber to [HomeFeed].
 *
 * Tile state is owned by the process-scoped [HomeFeed] singleton (ADR 0006
 * §Rule 1) and read directly from `lists.dueCount` (Rule 2 column kept fresh
 * by the seven mutator use cases).
 *
 * `WhileSubscribed(5_000L)` stays at the VM layer as a per-screen cache
 * policy. The singleton is the source of truth; the VM's `stateIn` is a
 * pass-through that ensures Compose collects through the VM lifecycle
 * (ARCH-02 config-change survival).
 *
 * Initial value is cache-first: when [HomeFeed.tiles] already holds real
 * tiles, Home renders them synchronously (ADR 0006 — no loading state on
 * steady-state navigation). Only the pre-first-database-answer window —
 * where the feed still holds its `emptyList()` placeholder — maps to
 * [HomeUiState.Loading], rendered as quiet chrome. Mapping that window to
 * [HomeUiState.Empty] flashed the first-install CTA on slow SQLCipher cold
 * opens, which ADR 0006 reserves for the genuine no-lists case.
 *
 * **List-tile long-press quick actions** (features/home/README.md). Because
 * [HomeFeed.tiles] is a live `listRepo.observeActive()` projection, the
 * mute / archive / delete mutations dispatch straight to [ListRepository] and
 * the tile grid updates itself — no new use case or HomeFeed mutator needed
 * (those exist only to denormalize the `dueCount` column). Delete is deferred:
 * [requestDelete] hides the tile optimistically via [pendingDeletes] and the
 * actual purge runs in [commitDelete] once the Undo window closes, so the
 * snackbar's Undo can cancel it before any row is destroyed.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    homeFeed: HomeFeed,
    private val listRepo: ListRepository,
    private val clock: Clock,
) : ViewModel() {

    // Long-press menu snackbar surface (archive/delete Undo + mute confirmation
    // + mutation failures). extraBufferCapacity = 1 so an emit while the
    // screen's collector is suspended in a showSnackbar buffers instead of
    // dropping. The screen collects with collectLatest so the newest action's
    // snackbar supersedes any still-showing one — see features/home/README.md
    // "swallowed-toast trap".
    private val _snackbarEvents = MutableSharedFlow<HomeSnackbarEvent>(extraBufferCapacity = 1)
    val snackbarEvents: SharedFlow<HomeSnackbarEvent> = _snackbarEvents.asSharedFlow()

    // Lists pending a deferred delete — hidden from the grid immediately on
    // confirm, purged from Room in commitDelete when the Undo window closes.
    private val pendingDeletes = MutableStateFlow<Set<Long>>(emptySet())

    // Tiles the grid actually renders: HomeFeed's active-list projection minus
    // any list staged for a deferred delete.
    private val visibleTiles: Flow<List<ListTileState>> =
        combine(homeFeed.tiles, pendingDeletes) { tiles, pending ->
            if (pending.isEmpty()) tiles else tiles.filterNot { it.id in pending }
        }

    // Distinct contacts due across the visible lists, for
    // the "N people ready" header. Per-tile `dueCount` is a per-list column,
    // so summing it double-counts a contact due on two lists. This union count
    // rides the existing `observeMembersOfList` repository observer (one Room
    // query per visible list — list count is small by design; no new DAO
    // surface). The due predicate mirrors `ListDao.recomputeDueCount`:
    // `nextDueAt IS NULL OR nextDueAt <= now`. It is evaluated live against
    // `clock.now()`, so it can briefly lead the denormalized per-tile badges
    // (which refresh at mutation choke points + the 5-minute staleness pass) —
    // acceptable drift in the more-accurate direction.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val dueContactCount: Flow<Int> = visibleTiles.flatMapLatest { tiles ->
        if (tiles.isEmpty()) {
            flowOf(0)
        } else {
            combine(tiles.map { listRepo.observeMembersOfList(it.id) }) { perList ->
                val now = clock.now()
                perList.asSequence()
                    .flatMap { it.asSequence() }
                    .filter { m -> m.nextDueAt == null || !m.nextDueAt.isAfter(now) }
                    .map { it.contactId }
                    .toSet()
                    .size
            }
        }
    }

    // Member counts ride the same repository projection Lists Manager already
    // combines (`ListMembershipDao` GROUP BY — no new query). Zero-member
    // lists are absent from the map; default to 0 at the merge site.
    //
    // Loading semantics: `HomeFeed.tiles` starts at an `emptyList()`
    // placeholder that is indistinguishable by value from a genuinely empty
    // database, so the VM must not map "empty before the DB has answered" to
    // [HomeUiState.Empty] — that flashed the "Create your first list" CTA on
    // slow SQLCipher cold opens. The counts flow is cold Room: this combine
    // cannot emit until the database has answered at least one query, so
    // [HomeUiState.Loading] holds exactly the pre-first-answer window.
    // Cache-first (ADR 0006): when HomeFeed already holds real tiles, the
    // initial value renders them synchronously (member counts hydrate a frame
    // later) — no Loading on steady-state re-entry.
    val uiState: StateFlow<HomeUiState> =
        combine(
            visibleTiles,
            listRepo.observeMemberCountsByListId(),
            dueContactCount,
        ) { tiles, memberCounts, dueContacts ->
            val visible = tiles.map { it.copy(memberCount = memberCounts[it.id] ?: 0) }
            if (visible.isEmpty()) HomeUiState.Empty else readyState(visible, dueContacts)
        }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000L),
                // Cache-first frame approximates the header with the per-list
                // sum (it may over-count a multi-list contact for one frame);
                // the combine's union count corrects on its first emission.
                initialValue = homeFeed.tiles.value
                    .takeIf { it.isNotEmpty() }
                    ?.let { tiles -> readyState(tiles, tiles.sumOf { it.dueCount }) }
                    ?: HomeUiState.Loading,
            )

    private fun readyState(visible: List<ListTileState>, dueContacts: Int): HomeUiState.Ready =
        HomeUiState.Ready(
            lists = visible,
            hasPermissions = true,
            dueContactCount = dueContacts,
        )

    /**
     * Long-press → "Mute prompts" / "Unmute prompts". Flips the per-list
     * notifications flag in place; [currentlyEnabled] is the tile's current
     * value so the new state and the confirming copy are both derived from one
     * read. No Undo — re-tapping reverses it.
     */
    fun toggleNotifications(listId: Long, currentlyEnabled: Boolean) {
        val enable = !currentlyEnabled
        viewModelScope.launch {
            runMutation { listRepo.updateNotificationsEnabled(listId, enable) }
            _snackbarEvents.tryEmit(
                HomeSnackbarEvent(message = if (enable) "Prompts on." else "Prompts muted."),
            )
        }
    }

    /** Long-press → Archive. Reversible; the grid drops the tile via observeActive. */
    fun archiveList(listId: Long) {
        viewModelScope.launch {
            runMutation { listRepo.setArchived(listId, archived = true) }
            _snackbarEvents.tryEmit(
                HomeSnackbarEvent(
                    message = "List archived.",
                    actionLabel = "Undo",
                    payloadListId = listId,
                    kind = HomeSnackbarEvent.Kind.ARCHIVE_UNDO,
                ),
            )
        }
    }

    /** Undo of [archiveList] — re-surfaces the list on home. */
    fun undoArchive(listId: Long) {
        viewModelScope.launch {
            runMutation { listRepo.setArchived(listId, archived = false) }
        }
    }

    /**
     * Long-press → Delete (after the confirmation dialog). Deferred: hide the
     * tile now, purge later. The snackbar carries Undo; the screen calls
     * [undoDelete] on Undo or [commitDelete] when the window closes.
     */
    fun requestDelete(listId: Long) {
        pendingDeletes.update { it + listId }
        _snackbarEvents.tryEmit(
            HomeSnackbarEvent(
                message = "List deleted.",
                actionLabel = "Undo",
                payloadListId = listId,
                kind = HomeSnackbarEvent.Kind.DELETE_UNDO,
            ),
        )
    }

    /** Undo of [requestDelete] — the row was never purged, so just un-hide it. */
    fun undoDelete(listId: Long) {
        pendingDeletes.update { it - listId }
    }

    /**
     * Finalize a deferred delete once the Undo window closes (snackbar
     * dismissed, superseded, or the screen left). Idempotent: a no-op if the
     * list is no longer pending (already undone or already committed). The row
     * stays in [pendingDeletes] — and thus hidden — until Room confirms the
     * delete, so there is no reappear-then-vanish flicker.
     */
    fun commitDelete(listId: Long) {
        if (listId !in pendingDeletes.value) return
        viewModelScope.launch {
            runMutation { listRepo.delete(listId) }
            pendingDeletes.update { it - listId }
        }
    }

    /**
     * Uniform mutation wrapper: surfaces a failure toast instead of letting the
     * exception die silently on viewModelScope. Mirrors
     * `ListsManagerViewModel.runMutation`. CancellationException is rethrown so
     * structured-concurrency cancellation still propagates.
     */
    private suspend fun runMutation(block: suspend () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            _snackbarEvents.tryEmit(HomeSnackbarEvent(message = "Couldn't save your change"))
        }
    }
}
