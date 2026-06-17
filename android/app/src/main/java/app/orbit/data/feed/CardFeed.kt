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
import app.orbit.domain.usecase.SurfaceNextUseCase
import app.orbit.domain.usecase.SurfaceQueueUseCase
import app.orbit.domain.usecase.SurfaceResult
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
            }.stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = CardSnapshot(
                    surface = SurfaceResult.NothingEligible,
                    listEntity = null,
                    recentNotes = emptyList(),
                    recentCalls = emptyList(),
                    queueSize = 0,
                    upNext = null,
                ),
            )
        }

    /**
     * Soonest future-due visible member of [listId] — the NothingEligible
     * empty state's "{name} comes up {when}" hint. Visible = neither archived
     * nor ignored (matches [SurfaceNextUseCase]'s membership filter). Members
     * with no persisted `nextDueAt` are cold-start (due now), so they never
     * feed the future hint. Emits null when nobody has a future due.
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
                val due = membership.nextDueAt ?: return@mapNotNull null
                if (!due.isAfter(now)) return@mapNotNull null
                UpNextHint(displayName = contact.displayName, dueAt = due)
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
 */
data class CardSnapshot(
    val surface: SurfaceResult,
    val listEntity: ListEntity?,
    val recentNotes: List<NoteEntity>,
    val recentCalls: List<CallEventEntity>,
    val queueSize: Int,
    val upNext: UpNextHint?,
)

/** Soonest future-due visible member of the list — see [CardFeed.upNextFor]. */
data class UpNextHint(
    val displayName: String,
    val dueAt: Instant,
)
