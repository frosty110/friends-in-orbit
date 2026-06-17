package app.orbit.data.repository

import app.orbit.data.entity.RuleKind
import app.orbit.data.entity.RuleTemplateEntity
import kotlinx.coroutines.flow.Flow

/**
 * Bridges DAO calls for the seeded `rule_templates` table.
 *
 * Three rows are seeded by `DatabaseFactory.SeedCallback` on first launch
 * (KEEP_IN_TOUCH, LATE_NIGHT, ENERGIZE); the `getByKind` lookup is the primary
 * read path for `EngineFactory`.
 *
 * [getById] and [getByKind] read from an in-memory cache seeded
 * by `dao.observeAll().stateIn(appScope)` in the impl. Templates are 3 rows
 * and immutable in v1, so the cache cannot drift. This eliminates the N+1
 * DAO query in [app.orbit.domain.usecase.MarkCalledUseCase], which previously
 * hit the DAO once per list-membership during cross-list propagation.
 */
interface RuleTemplateRepository {

    /** One-shot lookup by RuleKind enum; returns null if seed has not run yet. */
    suspend fun getByKind(kind: RuleKind): RuleTemplateEntity?

    /**
     * One-shot lookup by primary key; returns null if absent. Used by the
     * use cases that resolve a template from `ListEntity.ruleTemplateId: Long?`
     * (SurfaceNextUseCase, MarkCalledUseCase, SkipContactUseCase).
     */
    suspend fun getById(id: Long): RuleTemplateEntity?

    /** Observes every rule template row (3 after seed). */
    fun observeAll(): Flow<List<RuleTemplateEntity>>

    /**
     * EXPORT-01 — one-shot read of all rule templates for the export
     * envelope. Sibling of [observeAll] that doesn't require the
     * caller to take `.first()` on a hot Flow. Backed by the same in-memory
     * cache the impl maintains (3 immutable rows seeded at first launch —
     * cost is constant).
     */
    suspend fun snapshotAll(): List<RuleTemplateEntity>
}
