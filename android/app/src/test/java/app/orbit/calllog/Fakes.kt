package app.orbit.calllog

import app.orbit.data.dao.CallEventDao
import app.orbit.data.entity.CallEventEntity
import app.orbit.data.entity.ContactEntity
import app.orbit.data.entity.ContactPhoneEntity
import app.orbit.data.entity.RuleKind
import app.orbit.data.repository.CallEventRepository
import app.orbit.data.repository.ContactRepository
import app.orbit.data.repository.ListRepository
import app.orbit.data.repository.RuleTemplateRepository
import app.orbit.domain.JsonProvider
import app.orbit.domain.clock.Clock
import app.orbit.domain.usecase.MarkCalledUseCase
import app.orbit.domain.usecase.MutationResult
import java.time.Instant
import java.time.LocalTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Hand-rolled test fakes for the `app.orbit.calllog` package.
 *
 * Pattern matches `app/orbit/domain/FakeRepositories.kt`:
 * MutableStateFlow-backed minimal fakes, optional argument-capture, no mockk
 * (keeps tests fast and avoids reflection).
 *
 * `CallLogSyncWorkerTest` also depends on `StubMarkCalledUseCase`
 * exported from this file — so its constructor shape and override semantics are
 * load-bearing across the call-log tests.
 */

// ============================================================================
// Minimal ContactRepository fake — observeAll only (the reconciler's only call).
// ============================================================================

internal class FakeContactRepository(
    initial: List<ContactEntity> = emptyList(),
    initialPhones: List<ContactPhoneEntity> = emptyList(),
) : ContactRepository {

    private val state = MutableStateFlow(initial)
    private val phones = MutableStateFlow(initialPhones)

    override fun observeAll(): Flow<List<ContactEntity>> = state.asStateFlow()

    // Reconciler builds its match index from the phone snapshot.
    override suspend fun snapshotAllPhones(): List<ContactPhoneEntity> = phones.value

    // Reconciler never exercises the list-scoped contact pipeline.
    override fun observeForListMembers(listId: Long): Flow<List<ContactEntity>> =
        throw NotImplementedError("not used by CallLogReconciler")

    // Reconciler never exercises smart-list NeverCalled; throw on access.
    override fun observeNeverCalled(): Flow<List<ContactEntity>> =
        throw NotImplementedError("not used by CallLogReconciler")

    override suspend fun snapshotNeverCalled(): List<ContactEntity> =
        throw NotImplementedError("not used by CallLogReconciler")

    override fun observeById(id: Long): Flow<ContactEntity?> =
        throw NotImplementedError("not used by CallLogReconciler")

    override suspend fun getById(id: Long): ContactEntity? =
        state.value.firstOrNull { it.id == id }

    override suspend fun setPausedUntil(id: Long, until: Instant?) {
        // no-op — reconciler never calls this
    }

    // Ignore surface — reconciler doesn't exercise it, throw on access.
    override fun observeIgnored(): Flow<List<ContactEntity>> =
        throw NotImplementedError("not used by CallLogReconciler")

    override suspend fun markIgnored(
        id: Long,
        isIgnored: Boolean,
        ignoredAt: Instant?,
        preIgnoreListMembershipsJson: String?,
    ): Unit = throw NotImplementedError("not used by CallLogReconciler")

    override suspend fun getPreIgnoreSnapshot(id: Long): app.orbit.data.dao.PreIgnoreSnapshot? =
        throw NotImplementedError("not used by CallLogReconciler")

    override suspend fun setRuleOverrideJson(id: Long, json: String?): Unit =
        throw NotImplementedError("not used by CallLogReconciler")

    override suspend fun setArchived(id: Long, archived: Boolean): Unit =
        throw NotImplementedError("not used by CallLogReconciler")

    fun setContacts(list: List<ContactEntity>) { state.value = list }
    fun setPhones(list: List<ContactPhoneEntity>) { phones.value = list }
}

// ============================================================================
// In-memory CallEventDao fake — supports insert + existsAt for the dedup test.
// ============================================================================

internal class FakeCallEventDao : CallEventDao {

    private val rows = mutableListOf<CallEventEntity>()

    override fun observeByContactId(contactId: Long): Flow<List<CallEventEntity>> =
        throw NotImplementedError("not used by CallLogReconciler")

    // Reconciler does not exercise the scoped variants.
    override fun observeForContact(contactId: Long, limit: Int): Flow<List<CallEventEntity>> =
        throw NotImplementedError("not used by CallLogReconciler")

    override fun observeAggregatesForContacts(
        ids: List<Long>,
    ): Flow<List<app.orbit.data.dao.CallAggRow>> =
        throw NotImplementedError("not used by CallLogReconciler")

    override fun observeRecent(limit: Int): Flow<List<CallEventEntity>> =
        throw NotImplementedError("not used by CallLogReconciler")

    override fun observeForListContacts(listId: Long): Flow<List<CallEventEntity>> =
        throw NotImplementedError("not used by CallLogReconciler")

    // Reconciler never exercises the latest-per-contact aggregate.
    override fun observeLatestPerContactInList(listId: Long): Flow<List<CallEventEntity>> =
        throw NotImplementedError("not used by CallLogReconciler")

    override suspend fun get(id: Long): CallEventEntity? =
        rows.firstOrNull { it.id == id }

    override suspend fun insert(event: CallEventEntity): Long {
        val withId = event.copy(id = (rows.size + 1).toLong())
        rows += withId
        return withId.id
    }

    override suspend fun update(event: CallEventEntity): Int {
        val idx = rows.indexOfFirst { it.id == event.id }
        if (idx < 0) return 0
        rows[idx] = event
        return 1
    }

    override suspend fun delete(event: CallEventEntity): Int =
        if (rows.removeAll { it.id == event.id }) 1 else 0

    override suspend fun existsAt(contactId: Long, occurredAt: Instant): Int =
        rows.count { it.contactId == contactId && it.occurredAt == occurredAt }

    // Reconciler never exercises any of these; throw on access.
    override suspend fun latestUnnotedOutgoing(since: Instant): CallEventEntity? =
        throw NotImplementedError("not used by CallLogReconciler")

    // Reconciler does not consume the call-log feed; throw on access.
    override fun observeForLog(limit: Int): Flow<List<CallEventEntity>> =
        throw NotImplementedError("not used by CallLogReconciler")

    override suspend fun getById(id: Long): CallEventEntity? =
        rows.firstOrNull { it.id == id }

    // EXPORT-01 — reconciler never exercises export; throw on access.
    override suspend fun snapshotAll(): List<CallEventEntity> =
        throw NotImplementedError("not used by CallLogReconciler")

    // ONB-19 — reconciler never exercises preview-VM aggregates; throw on access.
    override fun observeAggregatesAll(): Flow<List<app.orbit.data.dao.CallAggRow>> =
        throw NotImplementedError("not used by CallLogReconciler")

    fun insertedCount(): Int = rows.size
    fun allEvents(): List<CallEventEntity> = rows.toList()
}

// ============================================================================
// Throwing singletons for repository params we never exercise — they exist only
// to satisfy the MarkCalledUseCase superclass constructor in StubMarkCalledUseCase.
// The stub's invoke() override never delegates to super, so none of these
// methods are ever called.
// ============================================================================

internal object ThrowingContactRepository : ContactRepository {
    override fun observeAll(): Flow<List<ContactEntity>> =
        throw NotImplementedError()

    override suspend fun snapshotAllPhones(): List<ContactPhoneEntity> =
        throw NotImplementedError()

    override fun observeForListMembers(listId: Long): Flow<List<ContactEntity>> =
        throw NotImplementedError()

    override fun observeNeverCalled(): Flow<List<ContactEntity>> =
        throw NotImplementedError()

    override suspend fun snapshotNeverCalled(): List<ContactEntity> =
        throw NotImplementedError()

    override fun observeById(id: Long): Flow<ContactEntity?> =
        throw NotImplementedError()

    override suspend fun getById(id: Long): ContactEntity? =
        throw NotImplementedError()

    override suspend fun setPausedUntil(id: Long, until: Instant?): Unit =
        throw NotImplementedError()

    // Ignore surface — never exercised by StubMarkCalledUseCase; throw.
    override fun observeIgnored(): Flow<List<ContactEntity>> =
        throw NotImplementedError()

    override suspend fun markIgnored(
        id: Long,
        isIgnored: Boolean,
        ignoredAt: Instant?,
        preIgnoreListMembershipsJson: String?,
    ): Unit = throw NotImplementedError()

    override suspend fun getPreIgnoreSnapshot(id: Long): app.orbit.data.dao.PreIgnoreSnapshot? =
        throw NotImplementedError()

    override suspend fun setRuleOverrideJson(id: Long, json: String?): Unit =
        throw NotImplementedError()

    override suspend fun setArchived(id: Long, archived: Boolean): Unit =
        throw NotImplementedError()
}

internal object ThrowingListRepository : ListRepository {
    override fun observeAll() =
        throw NotImplementedError()

    override fun observeActive() =
        throw NotImplementedError()

    override suspend fun getById(id: Long) =
        throw NotImplementedError()

    override fun observeMembersOfList(listId: Long) =
        throw NotImplementedError()

    override fun observeMembershipsForContact(contactId: Long) =
        throw NotImplementedError()

    override suspend fun incrementSkipCount(contactId: Long, listId: Long, newNextDueAt: Instant): MutationResult =
        throw NotImplementedError()

    override suspend fun updateNextDueAt(contactId: Long, listId: Long, nextDueAt: Instant): MutationResult =
        throw NotImplementedError()

    override suspend fun restoreMembershipSchedule(
        contactId: Long,
        listId: Long,
        nextDueAt: Instant?,
        skipCount: Int,
    ): MutationResult = throw NotImplementedError()

    // ─── List write surface (unused — these stubs only need to compile) ─────────────────

    override suspend fun create(list: app.orbit.data.entity.ListEntity): Long =
        throw NotImplementedError()

    override suspend fun update(list: app.orbit.data.entity.ListEntity): Unit =
        throw NotImplementedError()

    override suspend fun setArchived(listId: Long, archived: Boolean): Unit =
        throw NotImplementedError()

    override suspend fun reorder(fromIndex: Int, toIndex: Int): Unit =
        throw NotImplementedError()

    override suspend fun setSmartRuleJson(listId: Long, json: String?): Unit =
        throw NotImplementedError()

    override suspend fun setRuleParamsOverrideJson(listId: Long, json: String?): Unit =
        throw NotImplementedError()

    override suspend fun convertSmartToStatic(listId: Long): Unit =
        throw NotImplementedError()

    override suspend fun delete(listId: Long): Unit =
        throw NotImplementedError()

    override suspend fun updateRuleTemplate(listId: Long, templateId: Long): Unit =
        throw NotImplementedError()

    override suspend fun updateActiveHours(listId: Long, start: LocalTime?, end: LocalTime?): Unit =
        throw NotImplementedError()

    override suspend fun updateNotificationsEnabled(listId: Long, enabled: Boolean): Unit =
        throw NotImplementedError()

    override suspend fun updateName(listId: Long, name: String): Unit =
        throw NotImplementedError()

    override suspend fun addMember(listId: Long, contactId: Long, addedAt: Instant): Boolean =
        throw NotImplementedError()

    override fun observeById(id: Long) =
        throw NotImplementedError()

    override fun observeMemberCountsByListId() =
        throw NotImplementedError()

    override suspend fun setNudgeScheduleJson(listId: Long, json: String?): Unit =
        throw NotImplementedError()

    override suspend fun dueCountForList(listId: Long): Int =
        throw NotImplementedError()

    override suspend fun recomputeDueCountForList(listId: Long, now: Instant): Unit =
        throw NotImplementedError()

    override suspend fun recomputeDueCountForActive(now: Instant): Unit =
        throw NotImplementedError()
}

internal object ThrowingCallEventRepository : CallEventRepository {
    override fun observeForContact(contactId: Long, limit: Int) =
        throw NotImplementedError()

    override fun observeAggregatesForContacts(ids: List<Long>) =
        throw NotImplementedError()

    override fun observeRecentForListContacts(listId: Long) =
        throw NotImplementedError()

    // Never exercised by StubMarkCalledUseCase; throw on access.
    override fun observeLatestPerContactInList(listId: Long) =
        throw NotImplementedError()

    override suspend fun insert(event: CallEventEntity): Long =
        throw NotImplementedError()

    override suspend fun markCalledAtomic(
        contactId: Long,
        event: CallEventEntity,
        nextDueByListId: Map<Long, Instant?>,
    ): Unit = throw NotImplementedError()

    // Never exercised; throw. (Widened to take a limit.)
    override fun observeForLog(limit: Int) =
        throw NotImplementedError()

    override suspend fun latestUnnotedOutgoing(since: Instant): CallEventEntity? =
        throw NotImplementedError()

    override suspend fun byId(id: Long): CallEventEntity? =
        throw NotImplementedError()

    override suspend fun snapshotAll(): List<CallEventEntity> =
        throw NotImplementedError()

    override fun observeAggregatesAll(): Flow<Map<Long, app.orbit.data.repository.CallAgg>> =
        throw NotImplementedError()
}

internal object ThrowingRuleTemplateRepository : RuleTemplateRepository {
    override suspend fun getByKind(kind: RuleKind) =
        throw NotImplementedError()

    override suspend fun getById(id: Long) =
        throw NotImplementedError()

    override fun observeAll() =
        throw NotImplementedError()

    override suspend fun snapshotAll(): List<app.orbit.data.entity.RuleTemplateEntity> =
        throw NotImplementedError()
}

/**
 * Test-only Clock pinned to Instant.EPOCH. The reconciler has a `Clock` parameter
 * but never reads it (it consumes `row.whenMs` directly); the param is plumbed
 * through for future-use and graph parity. No `zone()` member — the
 * production [Clock] interface only exposes `now()` (verified at HEAD 2026-04-23).
 */
internal val EpochClock: Clock = object : Clock {
    override fun now(): Instant = Instant.EPOCH
}

// ============================================================================
// StubMarkCalledUseCase — overrides invoke() fully; never delegates to super.
// ============================================================================
//
// Used by CallLogReconcilerTest AND CallLogSyncWorkerTest.
// `MarkCalledUseCase` was widened to `open class` + `open suspend operator fun
// invoke` — required for this subclass to compile.
//
// The five superclass-constructor params receive throwing singletons because
// the override never touches them; reusing the real `JsonProvider.json` keeps
// the constructor argument list shape consistent with the rest of the codebase
// (DatabaseModule.provideJson() returns the same instance at runtime).

internal open class StubMarkCalledUseCase(
    private val onInvoke: (contactId: Long, event: CallEventEntity) -> Unit = { _, _ -> },
) : MarkCalledUseCase(
    contactRepo = ThrowingContactRepository,
    listRepo = ThrowingListRepository,
    callEventRepo = ThrowingCallEventRepository,
    ruleTemplateRepo = ThrowingRuleTemplateRepository,
    clock = EpochClock,
    json = JsonProvider.json,
) {

    val invocations: MutableList<Pair<Long, CallEventEntity>> = mutableListOf()

    override suspend fun invoke(contactId: Long, callEvent: CallEventEntity): MutationResult {
        invocations += contactId to callEvent
        onInvoke(contactId, callEvent)
        return MutationResult.Success
    }
}
