package app.orbit.data.dao

import app.orbit.data.entity.CallEventEntity
import app.orbit.data.entity.ContactEntity
import app.orbit.data.entity.ContactPhoneEntity
import app.orbit.data.entity.ListMembershipEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.time.Instant

/**
 * Test-only recording fake for [ContactDao]. Returns no-op defaults for
 * non-batch methods; records the four batch methods consumed by
 * [app.orbit.domain.usecase.BulkIgnoreUseCase] /
 * [app.orbit.domain.usecase.BulkPauseUseCase].
 *
 * Seed [ignoredSnapshots] / [pausedSnapshots] in the constructor to set the
 * `getIgnoredFlags` / `getPausedUntilSnapshot` return surfaces; the read is
 * filtered by the requested ids list so a single seed list can serve every
 * test in a class.
 */
open class RecordingContactDao(
    private val ignoredSnapshots: List<IgnoredSnapshot> = emptyList(),
    private val pausedSnapshots: List<PausedUntilSnapshot> = emptyList(),
) : ContactDao() {

    data class SetIgnoredCall(val ids: List<Long>, val ignored: Boolean)
    data class SetPausedUntilCall(val ids: List<Long>, val until: Instant?)

    val setIgnoredCalls: MutableList<SetIgnoredCall> = mutableListOf()
    val setPausedUntilCalls: MutableList<SetPausedUntilCall> = mutableListOf()

    // ── Non-batch abstracts: no-op defaults ────────────────────────────
    override fun observeAll(): Flow<List<ContactEntity>> = flowOf(emptyList())
    // Bulk paths don't exercise the list-scoped pipeline.
    override fun observeForListMembers(listId: Long): Flow<List<ContactEntity>> = flowOf(emptyList())
    // Bulk paths don't exercise the SmartListEngine NeverCalled path; default
    // to empty.
    override fun observeNeverCalled(): Flow<List<ContactEntity>> = flowOf(emptyList())
    override suspend fun snapshotNeverCalled(): List<ContactEntity> = emptyList()
    open override suspend fun getAllOnce(): List<ContactEntity> = emptyList()
    override fun observeById(id: Long): Flow<ContactEntity?> = flowOf(null)
    override suspend fun get(id: Long): ContactEntity? = null
    override suspend fun getByPhoneNumber(phoneNumber: String): ContactEntity? = null
    override suspend fun getByNormalizedPhone(normalizedPhone: String): ContactEntity? = null
    override suspend fun insert(contact: ContactEntity): Long = 1L
    open override suspend fun insertAll(contacts: List<ContactEntity>): List<Long> =
        contacts.map { 1L }
    override suspend fun update(contact: ContactEntity): Int = 1
    override suspend fun setPausedUntil(id: Long, until: Instant?): Int = 1
    override suspend fun setArchived(id: Long, archived: Boolean): Int = 1
    override suspend fun delete(contact: ContactEntity): Int = 1
    override suspend fun upsertByPhoneHash(contact: ContactEntity): Long = 1L
    override suspend fun addContactToList(membership: ListMembershipEntity): Long = 1L
    override suspend fun insertCallEvent(event: CallEventEntity): Long = 1L

    // ── Batch methods: record / read seeded snapshots ────────────
    override suspend fun getIgnoredFlags(ids: List<Long>): List<IgnoredSnapshot> =
        ignoredSnapshots.filter { it.id in ids }

    override suspend fun setIgnoredBatch(ids: List<Long>, ignored: Boolean): Int {
        setIgnoredCalls += SetIgnoredCall(ids.toList(), ignored)
        return ids.size
    }

    override suspend fun setPausedUntilBatch(ids: List<Long>, until: Instant?): Int {
        setPausedUntilCalls += SetPausedUntilCall(ids.toList(), until)
        return ids.size
    }

    override suspend fun getPausedUntilSnapshot(ids: List<Long>): List<PausedUntilSnapshot> =
        pausedSnapshots.filter { it.id in ids }

    // ── Single-contact ignore + override surface ────
    // Bulk paths don't exercise these; default to no-ops so the fake stays
    // permissive. Tests that need them should subclass and override.
    override fun observeIgnored(): Flow<List<ContactEntity>> = flowOf(emptyList())
    override suspend fun markIgnored(
        id: Long,
        isIgnored: Boolean,
        ignoredAt: Instant?,
        preIgnoreListMembershipsJson: String?,
    ): Int = 1

    override suspend fun getPreIgnoreSnapshot(id: Long): PreIgnoreSnapshot? = null

    override suspend fun setRuleOverrideJson(id: Long, json: String?): Int = 1

    // ── Delta-sync ingest + multi-number surface ───────────────
    // Permissive no-op defaults; ingest tests subclass with stateful overrides
    // (see InMemoryContactDao in IngestPhoneContactsUseCaseTest).
    open override suspend fun refreshMirrorFields(
        id: Long,
        displayName: String,
        photoUri: String?,
        phoneContactId: Long,
        isStarred: Boolean,
    ): Int = 1

    open override suspend fun setOrphanedBatch(ids: List<Long>, orphaned: Boolean): Int = ids.size

    open override suspend fun getAllPhonesOnce(): List<ContactPhoneEntity> = emptyList()

    open override suspend fun insertPhones(phones: List<ContactPhoneEntity>): List<Long> =
        phones.map { 1L }

    open override suspend fun deletePhonesForContact(contactId: Long): Int = 0
}
