package app.orbit.domain

import app.orbit.data.dao.PreIgnoreSnapshot
import app.orbit.data.entity.CallDirection
import app.orbit.data.entity.CallEventEntity
import app.orbit.data.entity.CallSource
import app.orbit.data.entity.ContactEntity
import app.orbit.data.entity.ContactPhoneEntity
import app.orbit.data.entity.ListEntity
import app.orbit.data.entity.ListMembershipEntity
import app.orbit.data.entity.ListType
import app.orbit.data.entity.NoteEntity
import app.orbit.data.entity.RuleKind
import app.orbit.data.entity.RuleTemplateEntity
import app.orbit.data.repository.CallAgg
import app.orbit.data.repository.CallEventRepository
import app.orbit.data.repository.ContactRepository
import app.orbit.data.repository.ListRepository
import app.orbit.data.repository.NoteRepository
import app.orbit.data.repository.RuleTemplateRepository
import app.orbit.domain.rule.RuleParams
import app.orbit.domain.usecase.MutationResult
import java.time.Instant
import java.time.LocalTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json

// ============================================================================
// Argument-capture data classes
// ============================================================================
//
// Tests assert against these instead of mocking — every fake records each
// mutating call and stores the args verbatim. Tests then read `.single()` to
// fail loudly if the exactly-once contract is violated.

/** Captured args for [CallEventRepository.markCalledAtomic] (DOM-06 atomic boundary). */
data class MarkCalledArgs(
    val contactId: Long,
    val event: CallEventEntity,
    val nextDueByListId: Map<Long, Instant?>,
)

/** Captured args for [ListRepository.incrementSkipCount] (DOM-07). */
data class IncrementSkipArgs(
    val contactId: Long,
    val listId: Long,
    val newNextDueAt: Instant,
)

/** Captured args for [ContactRepository.setPausedUntil] (DOM-08). */
data class SetPausedArgs(
    val contactId: Long,
    val pausedUntil: Instant?,
)

/** Captured args for [CallEventRepository.insert]. */
data class InsertCallEventArgs(val event: CallEventEntity)

/** Captured args for [ListRepository.reorder] (LIST-02). */
data class ReorderArgs(val fromIndex: Int, val toIndex: Int)

// ============================================================================
// FakeContactRepository
// ============================================================================

/** Captured args for [ContactRepository.markIgnored] (IGNORE-02). */
data class MarkIgnoredArgs(
    val contactId: Long,
    val isIgnored: Boolean,
    val ignoredAt: Instant?,
    val preIgnoreListMembershipsJson: String?,
)

/** Captured args for [ContactRepository.setRuleOverrideJson] (CONTACT-03). */
data class SetRuleOverrideArgs(val contactId: Long, val json: String?)

/** Captured args for [ContactRepository.setArchived] (CONTACT-06). */
data class SetArchivedArgs(val contactId: Long, val archived: Boolean)

class FakeContactRepository(initial: List<ContactEntity> = emptyList()) : ContactRepository {

    private val state = MutableStateFlow(initial)
    val setPausedCalls: MutableList<SetPausedArgs> = mutableListOf()
    val markIgnoredCalls: MutableList<MarkIgnoredArgs> = mutableListOf()
    val setRuleOverrideCalls: MutableList<SetRuleOverrideArgs> = mutableListOf()
    val setArchivedCalls: MutableList<SetArchivedArgs> = mutableListOf()

    override fun observeAll(): Flow<List<ContactEntity>> = state.asStateFlow()

    /**
     * List-scoped contact observer. The fake has no awareness of
     * `list_memberships` (memberships live on [FakeListRepository]); without a
     * cross-fake handle, the simplest behavior-preserving stub returns every
     * seeded contact (matches the production query when every contact is on
     * the focused list, which is the common test fixture). Tests that need
     * membership-aware filtering should subclass and override.
     */
    override fun observeForListMembers(listId: Long): Flow<List<ContactEntity>> = state.asStateFlow()

    /**
     * Fake mirrors the production SQL semantics:
     * `LEFT JOIN call_events GROUP BY contact HAVING COUNT = 0`. The fake has
     * no awareness of call events, so it returns every contact (matches the
     * production query when `call_events` is empty, which is the common test
     * fixture). Tests that need NeverCalled-aware behavior alongside seeded
     * events should override via subclass.
     */
    override fun observeNeverCalled(): Flow<List<ContactEntity>> = state.asStateFlow()

    override suspend fun snapshotNeverCalled(): List<ContactEntity> = state.value

    /**
     * Derives a primary-only phone snapshot from the seeded contacts (matches
     * production state right after MIGRATION_9_10's backfill, before any
     * multi-number ingest). Tests that need secondary
     * numbers should subclass and override.
     */
    override suspend fun snapshotAllPhones(): List<ContactPhoneEntity> =
        state.value
            .filter { it.normalizedPhone.isNotEmpty() }
            .map {
                ContactPhoneEntity(
                    id = it.id,
                    contactId = it.id,
                    phoneNumber = it.phoneNumber,
                    normalizedPhone = it.normalizedPhone,
                    isPrimary = true,
                )
            }

    override fun observeById(id: Long): Flow<ContactEntity?> =
        state.map { list -> list.firstOrNull { it.id == id } }

    override suspend fun getById(id: Long): ContactEntity? =
        state.value.firstOrNull { it.id == id }

    override suspend fun setPausedUntil(id: Long, until: Instant?) {
        setPausedCalls += SetPausedArgs(id, until)
        state.update { contacts ->
            contacts.map { if (it.id == id) it.copy(pausedUntil = until) else it }
        }
    }

    override fun observeIgnored(): Flow<List<ContactEntity>> =
        state.map { all ->
            all.filter { it.isIgnored }
                .sortedByDescending { it.ignoredAt ?: Instant.EPOCH }
        }

    override suspend fun markIgnored(
        id: Long,
        isIgnored: Boolean,
        ignoredAt: Instant?,
        preIgnoreListMembershipsJson: String?,
    ) {
        markIgnoredCalls += MarkIgnoredArgs(id, isIgnored, ignoredAt, preIgnoreListMembershipsJson)
        state.update { contacts ->
            contacts.map {
                if (it.id == id) {
                    it.copy(
                        isIgnored = isIgnored,
                        ignoredAt = ignoredAt,
                        preIgnoreListMembershipsJson = preIgnoreListMembershipsJson,
                    )
                } else {
                    it
                }
            }
        }
    }

    override suspend fun getPreIgnoreSnapshot(id: Long): PreIgnoreSnapshot? =
        state.value.firstOrNull { it.id == id }
            ?.let { PreIgnoreSnapshot(it.id, it.preIgnoreListMembershipsJson) }

    override suspend fun setRuleOverrideJson(id: Long, json: String?) {
        setRuleOverrideCalls += SetRuleOverrideArgs(id, json)
        state.update { contacts ->
            contacts.map { if (it.id == id) it.copy(ruleOverrideJson = json) else it }
        }
    }

    override suspend fun setArchived(id: Long, archived: Boolean) {
        setArchivedCalls += SetArchivedArgs(id, archived)
        state.update { contacts ->
            contacts.map { if (it.id == id) it.copy(isArchived = archived) else it }
        }
    }

    // Test helpers
    fun seed(contacts: List<ContactEntity>) { state.value = contacts }
    fun update(transform: (List<ContactEntity>) -> List<ContactEntity>) { state.update(transform) }
}

// ============================================================================
// FakeListRepository (ListMembershipRepository is collapsed in here)
// ============================================================================

class FakeListRepository(
    initialLists: List<ListEntity> = emptyList(),
    initialMemberships: List<ListMembershipEntity> = emptyList(),
) : ListRepository {

    private val lists = MutableStateFlow(initialLists)
    private val memberships = MutableStateFlow(initialMemberships)

    // Argument-capture lists — match the fakes' capture convention. VM tests assert on these.
    val incrementSkipCalls: MutableList<IncrementSkipArgs> = mutableListOf()
    val updateNextDueAtCalls: MutableList<IncrementSkipArgs> = mutableListOf()
    val createCalls: MutableList<ListEntity> = mutableListOf()
    val updateCalls: MutableList<ListEntity> = mutableListOf()
    val setArchivedCalls: MutableList<Pair<Long, Boolean>> = mutableListOf()
    val reorderCalls: MutableList<ReorderArgs> = mutableListOf()
    val setSmartRuleJsonCalls: MutableList<Pair<Long, String?>> = mutableListOf()
    val setRuleParamsOverrideJsonCalls: MutableList<Pair<Long, String?>> = mutableListOf()
    val convertSmartToStaticCalls: MutableList<Long> = mutableListOf()
    val updateRuleTemplateCalls: MutableList<Pair<Long, Long>> = mutableListOf()
    val updateActiveHoursCalls: MutableList<Triple<Long, LocalTime?, LocalTime?>> = mutableListOf()
    val updateNotificationsEnabledCalls: MutableList<Pair<Long, Boolean>> = mutableListOf()

    override fun observeAll(): Flow<List<ListEntity>> = lists.asStateFlow()

    /**
     * Mirrors `ListDao.observeActive()` filter semantics: non-archived rows
     * ordered by `sortOrder` ASC. Tests that seed archived rows and assert
     * HomeFeed shape rely on this filter being honored here.
     */
    override fun observeActive(): Flow<List<ListEntity>> =
        lists.map { rows -> rows.filter { !it.isArchived }.sortedBy { it.sortOrder } }

    override suspend fun getById(id: Long): ListEntity? =
        lists.value.firstOrNull { it.id == id }

    override fun observeMembersOfList(listId: Long): Flow<List<ListMembershipEntity>> =
        memberships.map { all -> all.filter { it.listId == listId } }

    override fun observeMembershipsForContact(contactId: Long): Flow<List<ListMembershipEntity>> =
        memberships.map { all -> all.filter { it.contactId == contactId } }

    override suspend fun incrementSkipCount(
        contactId: Long,
        listId: Long,
        newNextDueAt: Instant,
    ): MutationResult {
        incrementSkipCalls += IncrementSkipArgs(contactId, listId, newNextDueAt)
        var matched = false
        memberships.update { rows ->
            rows.map { row ->
                if (row.contactId == contactId && row.listId == listId) {
                    matched = true
                    row.copy(skipCount = row.skipCount + 1, nextDueAt = newNextDueAt)
                } else row
            }
        }
        return if (matched) MutationResult.Success else MutationResult.MembershipMissing
    }

    override suspend fun updateNextDueAt(
        contactId: Long,
        listId: Long,
        nextDueAt: Instant,
    ): MutationResult {
        updateNextDueAtCalls += IncrementSkipArgs(contactId, listId, nextDueAt)
        var matched = false
        memberships.update { rows ->
            rows.map { row ->
                if (row.contactId == contactId && row.listId == listId) {
                    matched = true
                    row.copy(nextDueAt = nextDueAt)
                } else row
            }
        }
        return if (matched) MutationResult.Success else MutationResult.MembershipMissing
    }

    // Card-loop undo (2026-06-09) — mirrors ListRepositoryImpl: exact
    // nextDueAt (nullable) + skipCount restore on the matching row.
    override suspend fun restoreMembershipSchedule(
        contactId: Long,
        listId: Long,
        nextDueAt: Instant?,
        skipCount: Int,
    ): MutationResult {
        var matched = false
        memberships.update { rows ->
            rows.map { row ->
                if (row.contactId == contactId && row.listId == listId) {
                    matched = true
                    row.copy(nextDueAt = nextDueAt, skipCount = skipCount)
                } else row
            }
        }
        return if (matched) MutationResult.Success else MutationResult.MembershipMissing
    }

    // ─── List write surface ─────────────────────────────────────────────────────────

    override suspend fun create(list: ListEntity): Long {
        createCalls += list
        val newId = (lists.value.maxOfOrNull { it.id } ?: 0L) + 1L
        val withId = list.copy(id = newId)
        lists.update { it + withId }
        return newId
    }

    override suspend fun update(list: ListEntity) {
        updateCalls += list
        lists.update { rows -> rows.map { if (it.id == list.id) list else it } }
    }

    override suspend fun setArchived(listId: Long, archived: Boolean) {
        setArchivedCalls += listId to archived
        lists.update { rows ->
            rows.map { if (it.id == listId) it.copy(isArchived = archived) else it }
        }
    }

    override suspend fun reorder(fromIndex: Int, toIndex: Int) {
        reorderCalls += ReorderArgs(fromIndex, toIndex)
        if (fromIndex == toIndex) return
        lists.update { rows ->
            val active = rows.filter { !it.isArchived }.sortedBy { it.sortOrder }
            if (fromIndex !in active.indices || toIndex !in active.indices) return@update rows
            val moved = active.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
            val renumbered = moved.mapIndexed { i, e -> e.copy(sortOrder = i) }
            val archivedRows = rows.filter { it.isArchived }
            renumbered + archivedRows
        }
    }

    override suspend fun setSmartRuleJson(listId: Long, json: String?) {
        setSmartRuleJsonCalls += listId to json
        lists.update { rows ->
            rows.map { if (it.id == listId) it.copy(smartRuleJson = json) else it }
        }
    }

    override suspend fun setRuleParamsOverrideJson(listId: Long, json: String?) {
        setRuleParamsOverrideJsonCalls += listId to json
        lists.update { rows ->
            rows.map { if (it.id == listId) it.copy(ruleParamsOverrideJson = json) else it }
        }
    }

    override suspend fun convertSmartToStatic(listId: Long) {
        convertSmartToStaticCalls += listId
        // Simple semantic: flip type to STATIC and clear smartRuleJson. Tests asserting
        // the snapshot+insert path use ListRepositoryConvertTest with the real engine.
        lists.update { rows ->
            rows.map {
                if (it.id == listId) it.copy(type = ListType.STATIC, smartRuleJson = null) else it
            }
        }
    }

    /**
     * D-25 — hard-delete by primary key. Idempotent: silently no-ops when the
     * row is absent. The fake also drops every membership keyed to [listId]
     * to mirror the production FK CASCADE behavior so downstream observers
     * see consistent state.
     */
    val deleteCalls: MutableList<Long> = mutableListOf()
    override suspend fun delete(listId: Long) {
        deleteCalls += listId
        lists.update { rows -> rows.filterNot { it.id == listId } }
        memberships.update { rows -> rows.filterNot { it.listId == listId } }
    }

    override suspend fun updateRuleTemplate(listId: Long, templateId: Long) {
        updateRuleTemplateCalls += listId to templateId
        lists.update { rows ->
            rows.map { if (it.id == listId) it.copy(ruleTemplateId = templateId) else it }
        }
    }

    override suspend fun updateActiveHours(listId: Long, start: LocalTime?, end: LocalTime?) {
        updateActiveHoursCalls += Triple(listId, start, end)
        lists.update { rows ->
            rows.map {
                if (it.id == listId) it.copy(activeHoursStart = start, activeHoursEnd = end) else it
            }
        }
    }

    override suspend fun updateNotificationsEnabled(listId: Long, enabled: Boolean) {
        updateNotificationsEnabledCalls += listId to enabled
        lists.update { rows ->
            rows.map { if (it.id == listId) it.copy(notificationsEnabled = enabled) else it }
        }
    }

    val updateNameCalls: MutableList<Pair<Long, String>> = mutableListOf()
    override suspend fun updateName(listId: Long, name: String) {
        updateNameCalls += listId to name
        lists.update { rows ->
            rows.map { if (it.id == listId) it.copy(name = name) else it }
        }
    }

    /**
     * ONB-19 — single-row membership insert mirroring
     * [app.orbit.data.dao.ListMembershipDao.insertOrIgnore] semantics: returns
     * false (no-op) when a row for `(listId, contactId)` already exists, true
     * when a new row was appended.
     */
    data class AddMemberArgs(val listId: Long, val contactId: Long, val addedAt: Instant)
    val addMemberCalls: MutableList<AddMemberArgs> = mutableListOf()
    override suspend fun addMember(listId: Long, contactId: Long, addedAt: Instant): Boolean {
        addMemberCalls += AddMemberArgs(listId, contactId, addedAt)
        val exists = memberships.value.any { it.listId == listId && it.contactId == contactId }
        if (exists) return false
        memberships.update {
            it + ListMembershipEntity(listId = listId, contactId = contactId, addedAt = addedAt)
        }
        return true
    }

    override fun observeById(id: Long): Flow<ListEntity?> =
        lists.map { rows -> rows.firstOrNull { it.id == id } }

    /**
     * Derives counts from the seeded `memberships` flow so tests that seed
     * memberships see real counts. Empty-list semantic matches
     * production: lists with zero memberships are absent from the map.
     */
    override fun observeMemberCountsByListId(): Flow<Map<Long, Int>> =
        memberships.map { rows ->
            rows.groupingBy { it.listId }.eachCount()
        }

    // ─── NOTIF-10/11 — nudge schedule persistence ──────────────────────

    val setNudgeScheduleJsonCalls: MutableList<Pair<Long, String?>> = mutableListOf()
    override suspend fun setNudgeScheduleJson(listId: Long, json: String?) {
        setNudgeScheduleJsonCalls += listId to json
        lists.update { rows ->
            rows.map { if (it.id == listId) it.copy(nudgeScheduleJson = json) else it }
        }
    }

    var stubbedDueCount: Int = 0
    override suspend fun dueCountForList(listId: Long): Int = stubbedDueCount

    /**
     * Captured args for every `recomputeDueCountForList` call the production
     * code dispatches inside a `withTransaction` block. Tests
     * assert against this list to verify the keep-fresh contract — every
     * affected listId must be recomputed before the transaction closes.
     */
    data class RecomputeDueCountArgs(val listId: Long, val now: Instant)
    val recomputeDueCountCalls: MutableList<RecomputeDueCountArgs> = mutableListOf()
    override suspend fun recomputeDueCountForList(listId: Long, now: Instant) {
        recomputeDueCountCalls += RecomputeDueCountArgs(listId, now)
        // Mirror the production SQL: dueCount = COUNT(memberships where nextDueAt IS NULL
        // OR nextDueAt <= now). Tests that seed memberships + assert on
        // observed `lists` state see the same numeric result the real Room query
        // would produce.
        val count = memberships.value
            .filter { it.listId == listId }
            .count { it.nextDueAt == null || !it.nextDueAt.isAfter(now) }
        lists.update { rows ->
            rows.map { if (it.id == listId) it.copy(dueCount = count) else it }
        }
    }

    /**
     * WR-02 — captured args for every `recomputeDueCountForActive` call. Tests
     * assert against this list to verify HomeFeed dispatches a single bulk
     * recompute per ON_START past the TTL, not N per-list calls.
     */
    val recomputeDueCountForActiveCalls: MutableList<Instant> = mutableListOf()
    override suspend fun recomputeDueCountForActive(now: Instant) {
        recomputeDueCountForActiveCalls += now
        // Mirror the production SQL: every non-archived list's dueCount is
        // recomputed in one statement. Tests that seed memberships + assert on
        // observed `lists` state see the same numeric result the real Room
        // query would produce.
        val activeIds = lists.value.filterNot { it.isArchived }.map { it.id }.toSet()
        lists.update { rows ->
            rows.map { row ->
                if (row.id in activeIds) {
                    val count = memberships.value
                        .filter { it.listId == row.id }
                        .count { it.nextDueAt == null || !it.nextDueAt.isAfter(now) }
                    row.copy(dueCount = count)
                } else {
                    row
                }
            }
        }
    }

    // Test helpers
    fun seed(lists: List<ListEntity>) { this.lists.value = lists }
    fun seedMemberships(memberships: List<ListMembershipEntity>) { this.memberships.value = memberships }
    fun updateLists(transform: (List<ListEntity>) -> List<ListEntity>) { lists.update(transform) }
    fun updateMemberships(transform: (List<ListMembershipEntity>) -> List<ListMembershipEntity>) {
        memberships.update(transform)
    }
}

// ============================================================================
// FakeCallEventRepository (markCalledAtomic lives here)
// ============================================================================

class FakeCallEventRepository(initial: List<CallEventEntity> = emptyList()) : CallEventRepository {

    private val state = MutableStateFlow(initial)
    val insertCalls: MutableList<InsertCallEventArgs> = mutableListOf()
    val markCalledAtomicCalls: MutableList<MarkCalledArgs> = mutableListOf()

    /**
     * Optional contact-id allowlist for [observeRecentForListContacts]. Test sets
     * this when it wants the "scoped to list members" semantic; otherwise the fake
     * returns all events (matches the bounded-volume contract well enough for unit
     * tests, which seed targeted call-event sets).
     */
    var listContactAllowlistByListId: Map<Long, Set<Long>> = emptyMap()

    /**
     * Contact-scoped feed. Filters seeded events by contactId, sorts DESC by
     * occurredAt, and applies the explicit `limit`. Mirrors the production DAO
     * @Query so tests can assert against the same shape.
     */
    override fun observeForContact(contactId: Long, limit: Int): Flow<List<CallEventEntity>> =
        state.map { events ->
            events.filter { it.contactId == contactId }
                .sortedByDescending { it.occurredAt }
                .take(limit)
        }

    /**
     * Folds seeded events into the aggregate Map<Long, CallAgg> shape the
     * production query returns from SQL (GROUP BY contactId, count +
     * max-occurredAt). Ids with no events are absent from the map (matches
     * the @Query's GROUP BY behavior).
     */
    override fun observeAggregatesForContacts(ids: List<Long>): Flow<Map<Long, CallAgg>> =
        state.map { events ->
            events.filter { it.contactId in ids }
                .groupBy { it.contactId }
                .mapValues { (_, evs) ->
                    CallAgg(
                        count = evs.size,
                        lastAt = evs.maxOfOrNull { it.occurredAt },
                    )
                }
        }

    override fun observeRecentForListContacts(listId: Long): Flow<List<CallEventEntity>> =
        state.map { events ->
            val allow = listContactAllowlistByListId[listId]
            if (allow == null) events else events.filter { it.contactId in allow }
        }

    /**
     * List-scoped per-contact "latest call" aggregate. Mirrors the production
     * SQL: filter to events for contacts on [listId] (using the optional
     * [listContactAllowlistByListId]; absent allowlist means "all seeded
     * events"), group by contactId, and keep the row with the highest
     * `occurredAt`. Deterministic tiebreak by event `id` matches the WR-01 SQL
     * fix so tests match the production behavior exactly.
     */
    override fun observeLatestPerContactInList(listId: Long): Flow<Map<Long, CallEventEntity>> =
        state.map { events ->
            val allow = listContactAllowlistByListId[listId]
            val scoped = if (allow == null) events else events.filter { it.contactId in allow }
            scoped.groupBy { it.contactId }
                .mapValues { (_, evs) ->
                    evs.sortedWith(
                        compareByDescending<CallEventEntity> { it.occurredAt }.thenByDescending { it.id },
                    ).first()
                }
        }

    override suspend fun insert(event: CallEventEntity): Long {
        insertCalls += InsertCallEventArgs(event)
        state.update { it + event }
        return event.id
    }

    override suspend fun markCalledAtomic(
        contactId: Long,
        event: CallEventEntity,
        nextDueByListId: Map<Long, Instant?>,
    ) {
        markCalledAtomicCalls += MarkCalledArgs(contactId, event, nextDueByListId)
        // Faithful semantic: the atomic write inserts the event AND (in the real impl)
        // touches the contact + memberships. Tests don't assert on the membership
        // side-effects (those are CallEventRepositoryImpl's @Transaction concern); they
        // assert on the captured args. We still append the event so any downstream
        // observeAll / observeRecentForListContacts emissions reflect reality.
        state.update { it + event }
    }

    /**
     * Bounded by `limit`. The production query orders DESC and applies LIMIT in
     * SQL; the fake mirrors that contract by sorting then
     * taking. `Int.MAX_VALUE` (the "Show 200 more" path) returns the full
     * sorted list.
     */
    override fun observeForLog(limit: Int): Flow<List<CallEventEntity>> =
        state.map { events ->
            // Production query is `WHERE contactId IS NOT NULL ORDER BY occurredAt DESC`.
            // Schema currently makes `contactId` non-nullable, so the filter is a no-op
            // here — sort + bounded `take(limit)` is the load-bearing semantic for tests.
            events.sortedByDescending { it.occurredAt }.take(limit)
        }

    /**
     * NOTE-02 — simplified fake. Returns the latest OUTGOING event within
     * `since`; does NOT cross-check against [FakeNoteRepository] for "unnoted"
     * status (the production query joins notes — this fake covers the most-recent
     * window-and-direction filter). Tests that need real correlation should use
     * the in-memory Room DAO test fixture or override this method via
     * subclassing.
     */
    override suspend fun latestUnnotedOutgoing(since: Instant): CallEventEntity? =
        state.value
            .filter {
                it.direction == CallDirection.OUTGOING && !it.occurredAt.isBefore(since)
            }
            .maxByOrNull { it.occurredAt }

    override suspend fun byId(id: Long): CallEventEntity? =
        state.value.firstOrNull { it.id == id }

    /**
     * EXPORT-01 — one-shot snapshot mirroring the production query
     * `ORDER BY occurredAt DESC`. Tests that seed events see them returned in
     * descending chronology.
     */
    override suspend fun snapshotAll(): List<CallEventEntity> =
        state.value.sortedByDescending { it.occurredAt }

    /**
     * ONB-19 — sibling of [observeAggregatesForContacts] without the `ids`
     * filter. Folds every seeded event into the per-contact aggregate map
     * (count + lastAt). Contacts with zero events are absent.
     */
    override fun observeAggregatesAll(): Flow<Map<Long, CallAgg>> =
        state.map { events ->
            events.groupBy { it.contactId }
                .mapValues { (_, evs) ->
                    CallAgg(
                        count = evs.size,
                        lastAt = evs.maxOfOrNull { it.occurredAt },
                    )
                }
        }

    // Test helpers
    fun seed(events: List<CallEventEntity>) { state.value = events }
    fun update(transform: (List<CallEventEntity>) -> List<CallEventEntity>) { state.update(transform) }
}

// ============================================================================
// FakeRuleTemplateRepository
// ============================================================================

class FakeRuleTemplateRepository(initial: List<RuleTemplateEntity> = emptyList()) : RuleTemplateRepository {

    private val state = MutableStateFlow(initial)

    override suspend fun getByKind(kind: RuleKind): RuleTemplateEntity? =
        state.value.firstOrNull { it.kind == kind }

    override suspend fun getById(id: Long): RuleTemplateEntity? =
        state.value.firstOrNull { it.id == id }

    override fun observeAll(): Flow<List<RuleTemplateEntity>> = state.asStateFlow()

    override suspend fun snapshotAll(): List<RuleTemplateEntity> = state.value.toList()

    // Test helpers
    fun seed(templates: List<RuleTemplateEntity>) { state.value = templates }
}

// ============================================================================
// FakeNoteRepository (B3 — added for ContactDetailViewModelTest)
// ============================================================================
//
// Mirrors the existing FakeX pattern — MutableStateFlow<List<NoteEntity>> +
// `insertCalls` argument-capture list. Tests assert against captured args
// (no mocking), per the fakes' argument-capture convention.

class FakeNoteRepository(initial: List<NoteEntity> = emptyList()) : NoteRepository {

    private val state = MutableStateFlow(initial)
    val insertCalls: MutableList<NoteEntity> = mutableListOf()
    val updateCalls: MutableList<NoteEntity> = mutableListOf()
    val deleteCalls: MutableList<NoteEntity> = mutableListOf()

    override fun observeByContactId(contactId: Long): Flow<List<NoteEntity>> =
        state.map { notes -> notes.filter { it.contactId == contactId } }

    override suspend fun insert(note: NoteEntity): Long {
        insertCalls += note
        state.update { it + note }
        // If the caller provided an id=0L (auto-generate convention), synthesise
        // a deterministic row-id from the current list size for test assertions.
        return note.id.takeIf { it != 0L } ?: state.value.size.toLong()
    }

    override fun recentForContact(contactId: Long, since: Instant, limit: Int): Flow<List<NoteEntity>> =
        state.map { notes ->
            notes.filter { it.contactId == contactId && !it.createdAt.isBefore(since) }
                .sortedByDescending { it.createdAt }
                .take(limit)
        }

    override suspend fun update(note: NoteEntity): Int {
        updateCalls += note
        var changed = 0
        state.update { rows ->
            rows.map {
                if (it.id == note.id) {
                    changed = 1
                    note
                } else {
                    it
                }
            }
        }
        return changed
    }

    override suspend fun delete(note: NoteEntity): Int {
        deleteCalls += note
        var removed = 0
        state.update { rows ->
            val before = rows.size
            val after = rows.filterNot { it.id == note.id }
            removed = before - after.size
            after
        }
        return removed
    }

    override suspend fun get(id: Long): NoteEntity? =
        state.value.firstOrNull { it.id == id }

    override suspend fun snapshotAll(): List<NoteEntity> = state.value.toList()

    // Test helpers
    fun seed(notes: List<NoteEntity>) { state.value = notes }
    fun update(transform: (List<NoteEntity>) -> List<NoteEntity>) { state.update(transform) }
}

// ============================================================================
// Entity factory helpers
// ============================================================================
//
// One factory per entity type. Every parameter has a sensible default so tests
// override only the fields they care about. Synthetic phone numbers (no real
// PII) — `+15555550001` style.

fun contactFixture(
    id: Long,
    isIgnored: Boolean = false,
    pausedUntil: Instant? = null,
    firstSeenByAppAt: Instant = Instant.parse("2026-01-01T00:00:00Z"),
    ruleOverrideJson: String? = null,
    displayName: String = "Contact $id",
    phoneNumber: String = "+1555555${id.toString().padStart(4, '0')}",
    normalizedPhone: String = phoneNumber,
    isOrphaned: Boolean = false,
    photoUri: String? = null,
    phoneContactId: Long? = null,
    isArchived: Boolean = false,
    isStarred: Boolean = false,
): ContactEntity = ContactEntity(
    id = id,
    phoneContactId = phoneContactId,
    phoneNumber = phoneNumber,
    normalizedPhone = normalizedPhone,
    displayName = displayName,
    photoUri = photoUri,
    isStarred = isStarred,
    firstSeenByAppAt = firstSeenByAppAt,
    isIgnored = isIgnored,
    isOrphaned = isOrphaned,
    pausedUntil = pausedUntil,
    ruleOverrideJson = ruleOverrideJson,
    isArchived = isArchived,
)

fun listFixture(
    id: Long = 1L,
    ruleTemplateId: Long? = 1L,
    activeHoursStart: LocalTime? = null,
    activeHoursEnd: LocalTime? = null,
    smartRuleJson: String? = null,
    name: String = "List $id",
    sortOrder: Int = id.toInt(),
    isArchived: Boolean = false,
    type: ListType = ListType.STATIC,
    notificationsEnabled: Boolean = true,
    ruleParamsOverrideJson: String? = null,
): ListEntity = ListEntity(
    id = id,
    name = name,
    sortOrder = sortOrder,
    isArchived = isArchived,
    type = type,
    smartRuleJson = smartRuleJson,
    ruleTemplateId = ruleTemplateId,
    activeHoursStart = activeHoursStart,
    activeHoursEnd = activeHoursEnd,
    notificationsEnabled = notificationsEnabled,
    ruleParamsOverrideJson = ruleParamsOverrideJson,
)

fun membershipFixture(
    contactId: Long,
    listId: Long,
    nextDueAt: Instant? = null,
    skipCount: Int = 0,
    addedAt: Instant = Instant.parse("2026-01-01T00:00:00Z"),
): ListMembershipEntity = ListMembershipEntity(
    contactId = contactId,
    listId = listId,
    addedAt = addedAt,
    nextDueAt = nextDueAt,
    skipCount = skipCount,
)

fun callEventFixture(
    id: Long,
    contactId: Long,
    occurredAt: Instant,
    direction: CallDirection = CallDirection.OUTGOING,
    durationSeconds: Int = 300,
    source: CallSource = CallSource.CALL_LOG,
): CallEventEntity = CallEventEntity(
    id = id,
    contactId = contactId,
    occurredAt = occurredAt,
    direction = direction,
    durationSeconds = durationSeconds,
    source = source,
)

/**
 * Factory for `RuleTemplateEntity`. Encodes the supplied [params] to JSON via the
 * shared `JsonProvider.json` so tests don't have to hand-roll JSON strings, but
 * the encoded output is what production code will read back via
 * `resolveParamsFor` / `engineFor`.
 */
fun ruleTemplateFixture(
    id: Long = 1L,
    kind: RuleKind = RuleKind.KEEP_IN_TOUCH,
    params: RuleParams = RuleParams.KeepInTouch(),
    name: String = "Template $id",
    json: Json = JsonProvider.json,
): RuleTemplateEntity = RuleTemplateEntity(
    id = id,
    name = name,
    kind = kind,
    paramsJson = json.encodeToString(RuleParams.serializer(), params),
)
