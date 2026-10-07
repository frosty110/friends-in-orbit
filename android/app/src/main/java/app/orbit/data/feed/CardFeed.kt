package app.orbit.data.feed

import app.orbit.data.entity.CallEventEntity
import app.orbit.data.entity.ListEntity
import app.orbit.data.entity.NoteEntity
import app.orbit.data.repository.CallEventRepository
import app.orbit.data.repository.ContactRepository
import app.orbit.data.repository.ListRepository
import app.orbit.data.repository.NoteRepository
import app.orbit.di.ApplicationScope
import app.orbit.domain.clock.Clock
import app.orbit.domain.usecase.PauseContactUseCase
import app.orbit.domain.usecase.SurfaceNextUseCase
import app.orbit.domain.usecase.SurfaceQueueUseCase
import app.orbit.domain.usecase.SurfaceResult
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Process-scoped CardFeed (ADR 0006 §Rule 1).
 *
 * `forList(id)` — lazy + memoized per listId. Each per-list flow is
 * `Eagerly`-started on @ApplicationScope so re-entering Card View within
 * a session reads the cached snapshot synchronously.
 *
 * Snapshot composition (card-hydration revision, 2026-06-09) — six upstream
 * flows folded into one [CardSnapshot]:
 *   - `surface` from [SurfaceNextUseCase] (the card's head contact),
 *   - `listEntity` from [ListRepository.observeById] (app-bar name),
 *   - `recentNotes` from [NoteRepository.recentForContact] (NOTE-03 peek),
 *   - `recentCalls` from [CallEventRepository.observeForContact] for the
 *     surfaced contact — feeds the `withCallStats` overlay and the 24-hour
 *     call-pattern histogram that the card's "Usually answers" panel renders.
 *     Pre-revision the card mapped a bare `toUiContact()` and every contact
 *     showed empty stats + an all-zero heat strip,
 *   - `queueSize` from [SurfaceQueueUseCase] — the real due-now count for the
 *     list (replaces the dead `queueSize = 1` constant the VM used to emit),
 *   - `upNext` — soonest future-due visible member, so the NothingEligible
 *     empty state can say who comes up next instead of a false
 *     "paused or out of reach" line.
 *
 * Two flags on the snapshot keep the UI honest about what the feed knows
 * (2026-10-06):
 *   - `loaded` is false only on the `stateIn` placeholder, so the ViewModel
 *     can hold Loading until the combine has answered. The placeholder used
 *     to be a real-looking NothingEligible, and a cold first open flashed
 *     "All quiet for now." before the data arrived (CARD-05 says that only
 *     when it is true).
 *   - `error` carries a failed upstream read. The per-list flow catches it
 *     here, before `stateIn`, because an exception that escapes an Eagerly
 *     started flow on @ApplicationScope has no handler and crashes the app;
 *     a StateFlow never throws to its collectors, so the ViewModel's own
 *     `.catch` could not see it (CARD-07). The cache entry is evicted in the
 *     same breath so Try again rebuilds the subscription (the BrowseFeed /
 *     BROWSE-06 precedent); no logging, the snapshot is the report
 *     (rules.md Code 4).
 *
 * **Layering note (Option B fallback chosen):** the
 * `NoteEntity → NoteRow` mapping that needs `formatRelative` /
 * `formatAbsolute` stays in [app.orbit.ui.screens.card.CardViewViewModel].
 * CardFeed stays pure data-layer; the VM does presentation-side formatting
 * (including the `withCallStats` / `withCallPatterns` contact overlays).
 *
 * `forList` uses [ConcurrentHashMap.computeIfAbsent] for atomic lazy
 * memoization (WR-03 — see git history for the runBlocking dance it replaced).
 *
 * `open class` mirrors [HomeFeed] / [BrowseFeed] for the test seam.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
open class CardFeed @Inject constructor(
    private val surfaceNext: SurfaceNextUseCase,
    private val surfaceQueue: SurfaceQueueUseCase,
    private val listRepo: ListRepository,
    private val noteRepo: NoteRepository,
    private val contactRepo: ContactRepository,
    private val callEventRepo: CallEventRepository,
    private val clock: Clock,
    @ApplicationScope private val scope: CoroutineScope,
) {

    private val perListCache = ConcurrentHashMap<Long, StateFlow<CardSnapshot>>()

    /**
     * WR-03 — [ConcurrentHashMap.computeIfAbsent] is atomic (per-bucket
     * lock). No outer suspend, no `runBlocking`, no `Mutex.withLock`. The
     * lambda body itself is microsecond-cheap (`stateIn` registers the
     * upstream subscription synchronously and returns immediately —
     * `initialValue` covers first emission).
     */
    open fun forList(listId: Long): StateFlow<CardSnapshot> =
        perListCache.computeIfAbsent(listId) { id ->
            val source = surfaceNext(id)
            val list = listRepo.observeById(id)
            val notes = source.flatMapLatest { result ->
                when (result) {
                    is SurfaceResult.Found -> {
                        val since = clock.now().minus(Duration.ofDays(30))
                        noteRepo.recentForContact(result.contact.id, since = since, limit = 2)
                    }
                    SurfaceResult.NoMembers,
                    SurfaceResult.NothingEligible,
                    -> flowOf(emptyList())
                }
            }
            // Card hydration (2026-06-09) — recent call events for the surfaced
            // contact. Same flatMapLatest shape as `notes`; the 50-row cap
            // matches ContactDetail's RECENT_EVENTS_LIMIT and is plenty for the
            // stats overlay + hour histogram.
            val calls = source.flatMapLatest { result ->
                when (result) {
                    is SurfaceResult.Found ->
                        callEventRepo.observeForContact(result.contact.id, limit = RECENT_CALLS_LIMIT)
                    SurfaceResult.NoMembers,
                    SurfaceResult.NothingEligible,
                    -> flowOf(emptyList())
                }
            }
            val queueSize = surfaceQueue(id).map { it.size }
            combine(source, list, notes, calls, queueSize) {
                    surfaceResult, listEntity, noteEntities, callEvents, dueNowCount ->
                CardSnapshot(
                    surface = surfaceResult,
                    listEntity = listEntity,
                    recentNotes = noteEntities,
                    recentCalls = callEvents,
                    queueSize = dueNowCount,
                    upNext = null,
                )
            }.combine(upNextFor(id)) { snapshot, hint ->
                snapshot.copy(upNext = hint)
            }.catch { t ->
                // rules.md Code 5: cancellation is structured concurrency's,
                // never an error state.
                if (t is CancellationException) throw t
                perListCache.remove(id)
                emit(CardSnapshot.placeholder(error = t))
            }.stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                // Distinguishable from any real snapshot by `loaded = false`, so
                // the ViewModel renders Loading, never a false empty deck.
                initialValue = CardSnapshot.placeholder(),
            )
        }

    /**
     * The visible member of [listId] who comes back soonest, which is the
     * NothingEligible empty state's "{name} comes up {when}" hint. Visible =
     * neither archived nor ignored, the same membership filter as
     * [SurfaceNextUseCase].
     *
     * A paused person is announced for when the pause lifts, not for their
     * stale `nextDueAt`: since the tide marker, NothingEligible is reached
     * almost only when everyone is paused, and the hint used to say "Sarah
     * comes up on Tuesday" while her pause ran for weeks (CARD-05 names who
     * comes up next *and when*; a wrong "when" is worse than none). A pause
     * "until you unpause" is the sentinel in [PauseContactUseCase], not a
     * date, so that person is skipped rather than announced for the year
     * 9999. A paused member with no schedule yet is due the moment the pause
     * ends, so the pause end is their date. Members with no `nextDueAt` and
     * no pause are cold-start (due now) and never feed the future hint.
     * Emits null when nobody has a date to give.
     */
    private fun upNextFor(listId: Long): Flow<UpNextHint?> =
        combine(
            listRepo.observeMembersOfList(listId),
            contactRepo.observeForListMembers(listId),
        ) { memberships, contacts ->
            val now = clock.now()
            val contactsById = contacts.associateBy { it.id }
            memberships.mapNotNull { membership ->
                val contact = contactsById[membership.contactId] ?: return@mapNotNull null
                if (contact.isIgnored || contact.isArchived) return@mapNotNull null
                val pausedUntil = contact.pausedUntil?.takeIf { it.isAfter(now) }
                if (pausedUntil != null && PauseContactUseCase.isIndefinite(pausedUntil)) return@mapNotNull null
                val due = membership.nextDueAt ?: pausedUntil ?: return@mapNotNull null
                val comesBack = if (pausedUntil != null) maxOf(due, pausedUntil) else due
                if (!comesBack.isAfter(now)) return@mapNotNull null
                UpNextHint(displayName = contact.displayName, dueAt = comesBack)
            }.minByOrNull { it.dueAt }
        }

    private companion object {
        /** Mirrors ContactDetailViewModel.RECENT_EVENTS_LIMIT. */
        private const val RECENT_CALLS_LIMIT: Int = 50
    }
}

/**
 * Snapshot of one Card View context at one moment. Card-hydration revision
 * (2026-06-09) widens the tide-marker shape (2026-05-08) with the surfaced
 * contact's `recentCalls` (stats overlay + hour histogram inputs), the list's
 * real due-now `queueSize`, and the `upNext` hint for the NothingEligible
 * empty state. `recentNotes` / `recentCalls` are raw entity rows; the VM does
 * all presentation-side formatting (Option B layering — see CardFeed KDoc).
 *
 * [loaded] is false only on the pre-emission placeholder and [error] is set
 * only on a failed read (CardFeed KDoc, "Two flags"); the other fields of
 * those two snapshots carry nothing and the VM must not read them as data.
 */
data class CardSnapshot(
    val surface: SurfaceResult,
    val listEntity: ListEntity?,
    val recentNotes: List<NoteEntity>,
    val recentCalls: List<CallEventEntity>,
    val queueSize: Int,
    val upNext: UpNextHint?,
    val loaded: Boolean = true,
    val error: Throwable? = null,
) {
    companion object {
        /** The not-yet-loaded snapshot, or with [error], the failed one. */
        fun placeholder(error: Throwable? = null): CardSnapshot = CardSnapshot(
            surface = SurfaceResult.NothingEligible,
            listEntity = null,
            recentNotes = emptyList(),
            recentCalls = emptyList(),
            queueSize = 0,
            upNext = null,
            loaded = false,
            error = error,
        )
    }
}

/** Soonest future-due visible member of the list — see [CardFeed.upNextFor]. */
data class UpNextHint(
    val displayName: String,
    val dueAt: Instant,
)
