package app.orbit.data.feed

import app.orbit.data.entity.CallEventEntity
import app.orbit.data.entity.ContactEntity
import app.orbit.data.entity.ListEntity
import app.orbit.data.entity.ListMembershipEntity
import app.orbit.data.repository.CallEventRepository
import app.orbit.data.repository.ContactRepository
import app.orbit.data.repository.ListRepository
import app.orbit.di.ApplicationScope
import app.orbit.domain.usecase.SurfaceQueueUseCase
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Process-scoped BrowseFeed (ADR 0006 §Rule 1).
 *
 * Two surfaces:
 *  - `lists: StateFlow<List<ListEntity>>` — all lists (incl. archived,
 *    matching the current `listRepo.observeAll()` shape; the inline
 *    Move/Copy ListSelectorSheet filters archived at sheet level).
 *  - `forList(id): StateFlow<BrowseFeedSnapshot>` — lazy + memoized per
 *    listId. Each per-list flow is `Eagerly`-started on @ApplicationScope
 *    so navigating away from Browse and back reads the cached snapshot
 *    synchronously.
 *
 * Multi-select state (selectedIds, isMultiSelect, isCommitting) stays
 * VM-owned — screen-ephemeral, not navigation cache.
 *
 * `forList` uses [ConcurrentHashMap.computeIfAbsent] for atomic lazy
 * memoization — the previous `runBlocking { Mutex.withLock { getOrPut } }`
 * dance blocked the calling thread to protect a microsecond-cheap
 * insert. `computeIfAbsent` is itself atomic (per-bucket lock), so the
 * suspend dance is gone and the call site (`BrowseViewModel.init`) no
 * longer fights the no-`runBlocking`-on-Main discipline.
 *
 * `open class` mirrors [HomeFeed] — test fixtures subclass to inject
 * deterministic flows without satisfying `@ApplicationScope` construction.
 */
@Singleton
open class BrowseFeed @Inject constructor(
    private val listRepo: ListRepository,
    private val contactRepo: ContactRepository,
    private val callEventRepo: CallEventRepository,
    private val surfaceQueueUseCase: SurfaceQueueUseCase,
    @ApplicationScope private val scope: CoroutineScope,
) {

    open val lists: StateFlow<List<ListEntity>> =
        listRepo.observeAll().stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList(),
        )

    private val perListCache = ConcurrentHashMap<Long, StateFlow<BrowseFeedSnapshot>>()

    /**
     * Lazy + memoized per-list browse projection. First call for a given
     * listId builds the StateFlow and caches it; subsequent calls return
     * the same reference. The flow is started `Eagerly` on the singleton
     * scope so it survives BrowseViewModel destruction (rotation, nav).
     *
     * [ConcurrentHashMap.computeIfAbsent] is atomic (per-bucket
     * lock). No outer suspend, no `runBlocking`, no `Mutex.withLock`. The
     * lambda body itself is microsecond-cheap (`stateIn` registers the
     * upstream subscription synchronously and returns immediately —
     * `initialValue` covers first emission).
     */
    open fun forList(listId: Long): StateFlow<BrowseFeedSnapshot> =
        perListCache.computeIfAbsent(listId) { id ->
            combine(
                listRepo.observeMembersOfList(id),
                contactRepo.observeAll(),
                callEventRepo.observeRecentForListContacts(id),
                surfaceQueueUseCase(id).map { ordered -> ordered.map(ContactEntity::id) },
            ) { memberships, allContacts, callEvents, queueOrder ->
                BrowseFeedSnapshot(memberships, allContacts, callEvents, queueOrder)
            }.stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = BrowseFeedSnapshot(
                    memberships = emptyList(),
                    allContacts = emptyList(),
                    callEvents = emptyList(),
                    queueOrder = emptyList(),
                ),
            )
        }
}

/**
 * Snapshot of the four Browse-side data sources for one list. The first three
 * mirror the trio that BrowseViewModel.buildState consumed before the
 * feed-widening; the VM rewire is a 1:1 substitution.
 *
 * `queueOrder` carries the [SurfaceQueueUseCase]-ordered contact ids for the
 * list (queue head first). Non-queued members (paused / out-of-active-hours /
 * no-template / engine-null) are absent from this list — Browse computes the
 * set-difference against `memberships` and renders them in a separate "Other
 * members" section without a position number.
 */
data class BrowseFeedSnapshot(
    val memberships: List<ListMembershipEntity>,
    val allContacts: List<ContactEntity>,
    val callEvents: List<CallEventEntity>,
    val queueOrder: List<Long>,
)
