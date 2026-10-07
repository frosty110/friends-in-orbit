package app.orbit.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.orbit.R
import app.orbit.data.feed.HomeFeed
import app.orbit.data.feed.ListEnrichment
import app.orbit.data.repository.ListRepository
import app.orbit.domain.WidgetRefreshTrigger
import app.orbit.domain.clock.Clock
import app.orbit.notify.NudgeScheduler
import app.orbit.ui.util.UiText
import app.orbit.ui.util.formatAgo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
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
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject

/**
 * One-shot snackbar event for the home long-press quick-actions menu.
 *
 * Home owns its own event type (rather than reusing the picker's
 * `SnackbarEvent`) because it needs a [kind] to tell the screen-side collector
 * what an action tap and a dismissal MEAN: undo-archive, undo-delete, or
 * nothing. [payloadListId] carries the affected list across the snackbar
 * boundary so the collector can route back to a typed VM method.
 *
 * Lists Manager emits this same type: its archive and delete must behave
 * exactly like Home's (features/orbit-lists), so the two share one contract.
 * [message] and [actionLabel] are [UiText]: the screen resolves them when it
 * shows the snackbar, so the copy lives in string resources.
 */
data class HomeSnackbarEvent(
    val message: UiText,
    val actionLabel: UiText? = null,
    val payloadListId: Long? = null,
    val kind: Kind = Kind.PLAIN
) {
    enum class Kind { PLAIN, ARCHIVE_UNDO, DELETE_UNDO }
}

// Events that can queue while the screen's collector is between snackbars.
// One slot was enough for a single success, but a failure that follows an
// action in the same turn must never be the event that gets dropped
// (rules.md Code 3: a failed write always tells the user).
private const val SNACKBAR_EVENT_BUFFER = 4

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
 * tiles, Home renders them synchronously (ADR 0006: no loading state on
 * steady-state navigation). Only the pre-first-database-answer window,
 * where the feed still holds its `emptyList()` placeholder, maps to
 * [HomeUiState.Loading], rendered as quiet chrome. Mapping that window to
 * [HomeUiState.Empty] flashed the first-install CTA on slow SQLCipher cold
 * opens, which ADR 0006 reserves for the genuine no-lists case.
 *
 * **List-tile long-press quick actions** (features/home/README.md). Because
 * [HomeFeed.tiles] is a live `listRepo.observeActive()` projection, the
 * pause / archive / delete mutations dispatch straight to [ListRepository]
 * and the cards update themselves; no new use case or HomeFeed mutator is
 * needed (those exist only to denormalize the `dueCount` column). Each
 * mutation mirrors `ListsManagerViewModel`'s, nudge chain included (NOTIF-11):
 * the two surfaces must stay consistent (features/orbit-lists). Delete is
 * deferred: [requestDelete] hides the tile optimistically via [pendingDeletes]
 * and the actual purge runs in [commitDelete] once the Undo window closes, so
 * the snackbar's Undo can cancel it before any row is destroyed. Archive, its
 * Undo and a committed delete each ask the widget to refresh once the write
 * landed (WIDGET-06, as `ListsManagerViewModel` does): the widget reads the
 * active lists and would otherwise keep offering an archived list's lead,
 * with a live Call button, until its hourly sweep. Undo of a delete changes
 * nothing in the database, so it asks for nothing.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val homeFeed: HomeFeed,
    private val listRepo: ListRepository,
    private val nudgeScheduler: NudgeScheduler,
    private val clock: Clock,
    // Trailing, with the no-op default the use cases use, so callers and tests
    // that build the VM without it keep compiling; Hilt binds the real one
    // (WidgetModule). The ListsManagerViewModel precedent.
    private val widgetRefreshTrigger: WidgetRefreshTrigger = WidgetRefreshTrigger { }
) : ViewModel() {

    // Long-press menu snackbar surface (archive/delete Undo + nudge
    // confirmation + mutation failures). Buffered so an emit while the
    // screen's collector is suspended in a showSnackbar queues instead of
    // dropping. The screen collects with collectLatest so the newest action's
    // snackbar supersedes any still-showing one; see features/home/README.md
    // "swallowed-toast trap".
    private val _snackbarEvents = MutableSharedFlow<HomeSnackbarEvent>(extraBufferCapacity = SNACKBAR_EVENT_BUFFER)
    val snackbarEvents: SharedFlow<HomeSnackbarEvent> = _snackbarEvents.asSharedFlow()

    // Lists pending a deferred delete: hidden from the grid immediately on
    // confirm, purged from Room in commitDelete when the Undo window closes.
    private val pendingDeletes = MutableStateFlow<Set<Long>>(emptySet())

    // Tiles the grid actually renders: HomeFeed's active-list projection minus
    // any list staged for a deferred delete.
    private val visibleTiles: Flow<List<ListTileState>> =
        combine(homeFeed.tiles, pendingDeletes) { tiles, pending ->
            if (pending.isEmpty()) tiles else tiles.filterNot { it.id in pending }
        }

    // Member counts ride the same repository projection Lists Manager already
    // combines (`ListMembershipDao` GROUP BY, no new query). Zero-member
    // lists are absent from the map; default to 0 at the merge site.
    //
    // Loading semantics: `HomeFeed.tiles` starts at an `emptyList()`
    // placeholder that is indistinguishable by value from a genuinely empty
    // database, so the VM must not map "empty before the DB has answered" to
    // [HomeUiState.Empty]: that flashed the "Create your first list" CTA on
    // slow SQLCipher cold opens. The counts flow is cold Room: this combine
    // cannot emit until the database has answered at least one query, so
    // [HomeUiState.Loading] holds exactly the pre-first-answer window.
    // Cache-first (ADR 0006): when HomeFeed already holds real tiles, the
    // initial value renders them synchronously (member counts hydrate a frame
    // later), so there is no Loading on steady-state re-entry.
    // HOME-10: bumped by [onRetry] to re-subscribe after a failure.
    private val retryCount = MutableStateFlow(0)

    /**
     * The Error state's Try again. Re-subscribes this VM's own sources (the
     * member counts) and, through [HomeFeed.retry], the feed's two
     * projections, whichever of them failed.
     */
    fun onRetry() {
        homeFeed.retry()
        retryCount.update { it + 1 }
    }

    /**
     * HOME-12: the screen resumed. Tells the feed today's date so the rhythm
     * strip re-buckets after a midnight spent in the background; the screen
     * derives its own `today` from the same resume, so the two agree.
     */
    fun onResumed() {
        homeFeed.noteToday(clock.now().atZone(ZoneId.systemDefault()).toLocalDate())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<HomeUiState> = retryCount.flatMapLatest {
        combine(
            visibleTiles,
            listRepo.observeMemberCountsByListId(),
            homeFeed.enrichment,
            homeFeed.failed
        ) { tiles, memberCounts, enrichment, feedFailed ->
            // HOME-10: a source behind the feed threw. Its StateFlow still
            // holds the last good value, so the flag is checked first.
            if (feedFailed) {
                HomeUiState.Error
            } else {
                val now = clock.now()
                val visible = tiles.map { tile ->
                    withEnrichment(
                        tile.copy(memberCount = memberCounts[tile.id] ?: 0),
                        enrichment[tile.id],
                        now
                    )
                }
                if (visible.isEmpty()) HomeUiState.Empty else HomeUiState.Ready(visible)
            }
        }.catch { t ->
            if (t is CancellationException) throw t
            emit(HomeUiState.Error)
        }
    }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000L),
                // The process-cached enrichment is read synchronously here too,
                // so "Next up" is present on the very first frame (no blink).
                initialValue = when {
                    homeFeed.failed.value -> HomeUiState.Error
                    homeFeed.tiles.value.isEmpty() -> HomeUiState.Loading
                    else -> {
                        val enrichment = homeFeed.enrichment.value
                        val now = clock.now()
                        HomeUiState.Ready(homeFeed.tiles.value.map { withEnrichment(it, enrichment[it.id], now) })
                    }
                }
            )

    /** Folds the per-list [ListEnrichment] (Next up + rhythm) into a tile. */
    private fun withEnrichment(
        tile: ListTileState,
        e: ListEnrichment?,
        now: Instant
    ): ListTileState = tile.copy(
        nextUp = e?.nextUp?.let { raw ->
            NextUp(
                contactId = raw.contactId,
                name = raw.name,
                photoUri = raw.photoUri,
                why = homeRecencyWhy(raw.lastCalledAt, now),
                phone = raw.phone?.takeIf { it.isNotBlank() }
            )
        },
        rhythm = e?.rhythm ?: emptyList()
    )

    /**
     * Long-press → "Pause nudges" / "Resume nudges". Flips the per-list
     * notifications flag in place; [currentlyEnabled] is the tile's current
     * value so the new state and the confirming copy are both derived from one
     * read. No Undo: re-tapping reverses it. The confirmation is sent only
     * when the write landed: a failure already said "Couldn't save your
     * change", and a "Nudges paused." after it would be a lie (Code 3).
     */
    fun toggleNotifications(listId: Long, currentlyEnabled: Boolean) {
        val enable = !currentlyEnabled
        viewModelScope.launch {
            val saved = runMutation { listRepo.updateNotificationsEnabled(listId, enable) }
            if (saved) {
                _snackbarEvents.tryEmit(
                    HomeSnackbarEvent(
                        message = UiText.res(
                            if (enable) R.string.home_snackbar_nudges_on else R.string.home_snackbar_nudges_paused,
                        ),
                    )
                )
            }
        }
    }

    /**
     * Long-press → Archive. Reversible; the grid drops the tile via
     * observeActive. "List archived." with Undo goes out only when the write
     * landed; an Undo offered after a failed archive would unarchive nothing.
     */
    fun archiveList(listId: Long) {
        viewModelScope.launch {
            val saved = runMutation {
                listRepo.setArchived(listId, archived = true)
                // NOTIF-11: cancel the list's nudge chain when archived so no
                // nudge fires for a list the user has put away. Inside
                // runMutation so the repo write and the WorkManager cancel
                // move together, as in ListsManagerViewModel.archiveList.
                nudgeScheduler.cancel(listId)
            }
            if (saved) {
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
    }

    /** Undo of [archiveList]: re-surfaces the list on home, and on the widget. */
    fun undoArchive(listId: Long) {
        viewModelScope.launch {
            val saved = runMutation {
                listRepo.setArchived(listId, archived = false)
                // NOTIF-11: the chain [archiveList] cancelled comes back with
                // the list. scheduleFromEntity reads the entity's own schedule
                // and active hours, so the re-enqueued slot is authoritative.
                listRepo.getById(listId)?.let { nudgeScheduler.scheduleFromEntity(it) }
            }
            if (saved) widgetRefreshTrigger.scheduleRefresh()
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
                message = UiText.res(R.string.lists_snackbar_deleted),
                actionLabel = UiText.res(R.string.components_action_undo),
                payloadListId = listId,
                kind = HomeSnackbarEvent.Kind.DELETE_UNDO
            )
        )
    }

    /** Undo of [requestDelete]: the row was never purged, so just un-hide it. */
    fun undoDelete(listId: Long) {
        pendingDeletes.update { it - listId }
    }

    /**
     * Finalize a deferred delete once the Undo window closes (snackbar
     * dismissed, superseded, or the screen left). Idempotent: a no-op if the
     * list is no longer pending (already undone or already committed). The row
     * stays in [pendingDeletes], and thus hidden, until Room confirms the
     * delete, so there is no reappear-then-vanish flicker.
     */
    fun commitDelete(listId: Long) {
        if (listId !in pendingDeletes.value) return
        viewModelScope.launch {
            val saved = runMutation {
                listRepo.delete(listId)
                // NOTIF-11: a deleted list's chain dies with it; otherwise the
                // worker fires once more and finds the row gone.
                nudgeScheduler.cancel(listId)
            }
            if (saved) widgetRefreshTrigger.scheduleRefresh()
            pendingDeletes.update { it - listId }
        }
    }

    /**
     * Uniform mutation wrapper: surfaces a failure toast instead of letting the
     * exception die silently on viewModelScope. Mirrors
     * `ListsManagerViewModel.runMutation`. Returns true when [block] completed,
     * so a caller sends its success snackbar only then. CancellationException
     * is rethrown so structured-concurrency cancellation still propagates.
     */
    private suspend fun runMutation(block: suspend () -> Unit): Boolean =
        try {
            block()
            true
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            _snackbarEvents.tryEmit(HomeSnackbarEvent(message = UiText.res(R.string.components_snackbar_save_failed)))
            false
        }
}

/**
 * Warm, neutral recency line for the Next-up person (HOME-3): context, not
 * shame ("you haven't called X in N days" is forbidden; voice.md). A null
 * last-call reads as a gentle "You haven't spoken yet"; then "You spoke
 * today", "You spoke yesterday", "You spoke 3 weeks ago", with [formatAgo]'s
 * one wording as the argument so Home and the card never word the same gap
 * two ways. Until 2026-10-06 the span filled "%1$s since you last spoke",
 * which read "3 days since you last spoke" for the most common gaps: the
 * framing voice.md never says and `VoiceRules` forbids ("days since"), hidden
 * from the string audit because the span arrived as an argument. Top-level,
 * like `resolvePickerPhase`, so `WhyLineVoiceTest` renders it for every
 * bucket without the feed.
 */
internal fun homeRecencyWhy(lastCalledAt: Instant?, now: Instant): UiText {
    if (lastCalledAt == null) return UiText.res(R.string.home_why_never)
    val days = ChronoUnit.DAYS.between(lastCalledAt, now)
    return when {
        days <= 0L -> UiText.res(R.string.home_why_today)
        days == 1L -> UiText.res(R.string.home_why_yesterday)
        else -> UiText.res(R.string.home_why_ago, formatAgo(days))
    }
}
