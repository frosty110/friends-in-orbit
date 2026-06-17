package app.orbit.data.repository

import app.orbit.data.dao.RuleTemplateDao
import app.orbit.data.entity.RuleKind
import app.orbit.data.entity.RuleTemplateEntity
import app.orbit.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Room-backed implementation of RuleTemplateRepository. Reads the three seed rows
 * (KEEP_IN_TOUCH, LATE_NIGHT, ENERGIZE) inserted by `DatabaseFactory.SeedCallback`
 * on first DB creation; `EngineFactory` consumes these via `getByKind`.
 *
 * In-memory cache via [cache], a `StateFlow<Map<Long, RuleTemplateEntity>>`
 * sourced from `dao.observeAll().stateIn(appScope, SharingStarted.Eagerly, ...)`.
 *
 * Why this is safe:
 *  - Templates are 3 rows seeded once at first DB open; immutable in v1.
 *    If a future change mutates templates, the StateFlow re-emits
 *    via Room's invalidation tracker.
 *  - `cache.first()` returns the very first emission (including the
 *    initialValue `emptyMap()`); the Elvis fallback to the DAO covers both
 *    the cache-miss-when-populated case (no template of the requested kind)
 *    AND the genuinely-empty cache case (test DBs that skip SeedCallback).
 *    A `first { it.isNotEmpty() }` predicate would suspend forever on the
 *    empty case and the Elvis fallback would never run.
 *  - The cache eliminates the N+1 in [app.orbit.domain.usecase.MarkCalledUseCase],
 *    which previously called `getById(templateId)` once per list-membership
 *    in the cross-list propagation loop. With the cache, each lookup is a
 *    Map access — zero DAO round-trips per call after first emission.
 *
 * @Singleton enforced by [app.orbit.di.RepositoryModule.bindRuleTemplateRepository];
 * the cache is shared across every consumer of the interface.
 */
@Singleton
internal class RuleTemplateRepositoryImpl @Inject constructor(
    private val ruleTemplateDao: RuleTemplateDao,
    @ApplicationScope private val appScope: CoroutineScope,
) : RuleTemplateRepository {

    /**
     * Hot in-memory cache keyed by primary key. Eager start so the cache is
     * populated before the first [getById] / [getByKind] read; subscribers
     * never block on a cold launch.
     */
    private val cache: StateFlow<Map<Long, RuleTemplateEntity>> =
        ruleTemplateDao.observeAll()
            .map { list -> list.associateBy { it.id } }
            .stateIn(appScope, SharingStarted.Eagerly, emptyMap())

    override suspend fun getByKind(kind: RuleKind): RuleTemplateEntity? {
        // `first()` returns the very first emission — including the initial
        // `emptyMap()`. Cache miss (populated cache without a matching kind,
        // OR genuinely empty cache pre-seed) falls through to the DAO so the
        // read still works for tests that wire an in-memory DB without
        // running SeedCallback. A `first { it.isNotEmpty() }` predicate
        // would suspend forever on the empty-cache case (CR-02 fix).
        val map = cache.first()
        return map.values.firstOrNull { it.kind == kind }
            ?: ruleTemplateDao.getByKind(kind)
    }

    override suspend fun getById(id: Long): RuleTemplateEntity? {
        val map = cache.first()
        return map[id] ?: ruleTemplateDao.get(id)
    }

    override fun observeAll(): Flow<List<RuleTemplateEntity>> = ruleTemplateDao.observeAll()

    override suspend fun snapshotAll(): List<RuleTemplateEntity> =
        ruleTemplateDao.observeAll().first()
}
