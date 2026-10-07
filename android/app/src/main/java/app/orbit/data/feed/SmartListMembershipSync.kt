package app.orbit.data.feed

import app.orbit.data.dao.ListMembershipDao
import app.orbit.data.entity.ListEntity
import app.orbit.data.entity.ListMembershipEntity
import app.orbit.data.entity.ListType
import app.orbit.data.entity.RuleKind
import app.orbit.data.repository.ListRepository
import app.orbit.data.repository.RuleTemplateRepository
import app.orbit.di.ApplicationScope
import app.orbit.domain.JsonProvider
import app.orbit.domain.clock.Clock
import app.orbit.domain.smart.SmartListEngine
import app.orbit.domain.smart.SmartListRule
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Keeps each smart list's stored membership equal to what its rule matches.
 *
 * Why it exists: every surface (Card view, Browse's queue, Home's "Next up",
 * the due count, nudges) reads stored membership rows plus a cadence. Smart
 * lists had neither. Their members were only ever projected inside List
 * settings, so "Recently added, not called" listed matches there and surfaced
 * no one anywhere else. Writing the rule's matches as ordinary rows lets smart
 * lists ride the same surfacing engine as static ones instead of growing a
 * second surfacing path.
 *
 * Semantics:
 *  - A contact that starts matching gets a row, due now: the same shape a
 *    convert-to-static snapshot writes.
 *  - A contact that stops matching loses its row. For "Recently added, not
 *    called" that is the moment you call them, which is the rule doing its job.
 *  - A smart list with no cadence gets the seeded Keep in touch template, so
 *    smart lists created before they carried one start surfacing too.
 *  - Archived and static lists are left alone. Convert-to-static ends syncing
 *    for that list because it stops being SMART; its rows stay as the snapshot.
 *
 * Started once from `OrbitApp.onCreate`; runs on @ApplicationScope because it
 * must outlive every screen (rules.md Code 6).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class SmartListMembershipSync @Inject constructor(
    private val listRepo: ListRepository,
    private val ruleTemplateRepo: RuleTemplateRepository,
    private val smartListEngine: SmartListEngine,
    private val listMembershipDao: ListMembershipDao,
    private val clock: Clock,
    @ApplicationScope private val scope: CoroutineScope
) {
    private val json = JsonProvider.json
    private var job: Job? = null

    /** Idempotent: a second call while running is a no-op. */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { run() }
    }

    /** The reconcile loop; `internal` so tests drive it on a test scheduler. */
    internal suspend fun run() {
        listRepo.observeAll()
            .map { lists -> lists.filter { it.type == ListType.SMART && !it.isArchived } }
            .onEach { smart -> backfillCadence(smart) }
            .map { smart ->
                smart.mapNotNull { list ->
                    decode(
                        list
                    )?.let { rule -> list.id to rule }
                }
            }
            .distinctUntilChanged()
            .flatMapLatest { rules ->
                if (rules.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    combine(
                        rules.map { (listId, rule) ->
                            smartListEngine.membership(
                                rule
                            ).map { matches -> listId to matches.map { it.id }.toSet() }
                        }
                    ) { it.toList() }
                }
            }
            .collect { targets -> targets.forEach { (listId, ids) -> reconcile(listId, ids) } }
    }

    internal suspend fun reconcile(listId: Long, target: Set<Long>) {
        guarded {
            val current = listMembershipDao.getMembersOfList(listId).map { it.contactId }.toSet()
            val toAdd = target - current
            val toRemove = current - target
            if (toAdd.isEmpty() && toRemove.isEmpty()) return@guarded
            val now = clock.now()
            if (toAdd.isNotEmpty()) {
                listMembershipDao.insertAll(
                    toAdd.map {
                        ListMembershipEntity(
                            contactId = it,
                            listId = listId,
                            addedAt = now,
                            nextDueAt = now
                        )
                    }
                )
            }
            if (toRemove.isNotEmpty()) {
                listMembershipDao.removeAll(
                    fromListId = listId,
                    ids = toRemove.toList()
                )
            }
            listRepo.recomputeDueCountForList(listId, now)
        }
    }

    private suspend fun backfillCadence(smart: List<ListEntity>) {
        val missing = smart.filter { it.ruleTemplateId == null }
        if (missing.isEmpty()) return
        guarded {
            val keepInTouch = ruleTemplateRepo.getByKind(RuleKind.KEEP_IN_TOUCH) ?: return@guarded
            missing.forEach { listRepo.updateRuleTemplate(it.id, keepInTouch.id) }
        }
    }

    private fun decode(list: ListEntity): SmartListRule? = list.smartRuleJson?.let { raw ->
        runCatching { json.decodeFromString(SmartListRule.serializer(), raw) }.getOrNull()
    }

    /**
     * A failed write here has no screen to report to (rules.md Code 3 covers
     * user-initiated writes). It must not end the loop either: the next upstream
     * emission (any contact, call or list change) reconciles again, so a
     * transient failure heals itself. Cancellation still propagates (Code 5).
     */
    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
        }
    }
}
