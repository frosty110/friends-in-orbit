package app.orbit.data.dao

import app.orbit.data.entity.ListEntity
import app.orbit.data.entity.ListType
import java.time.LocalTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Test-only minimal [ListDao] stub for use cases that only need to validate a
 * destination list exists and is non-archived (e.g. `MoveContactsUseCase`,
 * `CopyContactsUseCase`). Tests pass a list of [ListEntity] rows; `get()`
 * looks them up by id. All other DAO methods are no-ops returning trivial
 * defaults — call sites that exercise them should subclass and override.
 *
 * Open class so individual tests may further override `get(id)` to inject
 * archived destinations or absence semantics.
 */
open class TestListDaoStub(
    private val lists: List<ListEntity> = emptyList(),
) : ListDao {

    override fun observeActive(): Flow<List<ListEntity>> =
        flowOf(lists.filterNot { it.isArchived })

    override suspend fun getActive(): List<ListEntity> =
        lists.filterNot { it.isArchived }

    override fun observeAll(): Flow<List<ListEntity>> = flowOf(lists)

    override fun observeById(id: Long): Flow<ListEntity?> =
        flowOf(lists.firstOrNull { it.id == id })

    override suspend fun get(id: Long): ListEntity? =
        lists.firstOrNull { it.id == id }

    override suspend fun insert(list: ListEntity): Long = 1L
    override suspend fun update(list: ListEntity): Int = 1
    override suspend fun delete(list: ListEntity): Int = 1
    override suspend fun deleteList(listId: Long): Int = 1
    override suspend fun updateSortOrder(id: Long, sortOrder: Int) {}
    override suspend fun updateArchived(id: Long, archived: Boolean) {}
    override suspend fun updateSmartRuleJson(id: Long, json: String?) {}
    override suspend fun updateRuleParamsOverrideJson(id: Long, json: String?) {}
    override suspend fun updateTypeAndSmartRuleJson(
        id: Long,
        type: ListType,
        smartRuleJson: String?,
    ) {}
    override suspend fun updateRuleTemplate(id: Long, templateId: Long) {}
    override suspend fun updateActiveHours(id: Long, start: LocalTime?, end: LocalTime?) {}
    override suspend fun updateNotificationsEnabled(id: Long, enabled: Boolean) {}
    override suspend fun updateName(id: Long, name: String) {}

    // No-op default; tests that exercise dueCount keep-fresh
    // assert via FakeListRepository.recomputeDueCountCalls instead of the DAO.
    override suspend fun recomputeDueCount(listId: Long, nowMs: Long) {}

    // No-op default; tests that exercise the bulk active-list
    // recompute assert via FakeListRepository.recomputeDueCountForActiveCalls.
    override suspend fun recomputeDueCountForActive(nowMs: Long) {}

    // NOTIF-10/11 — no-op default; tests that exercise nudge schedule
    // persistence assert via FakeListRepository.setNudgeScheduleJsonCalls.
    override suspend fun updateNudgeScheduleJson(id: Long, json: String?) {}

    // NOTIF-12 — no-op default returns null (caller treats null as 0).
    override suspend fun dueCountForList(id: Long): Int? = null
}
