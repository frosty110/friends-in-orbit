package app.orbit.data.repository

import app.orbit.data.dao.ContactDao
import app.orbit.data.dao.PreIgnoreSnapshot
import app.orbit.data.entity.ContactEntity
import app.orbit.data.entity.ContactPhoneEntity
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/**
 * Room-backed implementation of ContactRepository. Visibility is `internal` so UI code
 * cannot reference this class — only the public interface is reachable from outside the
 * app module. Construction is lifecycle-managed by Hilt (`@Singleton` in RepositoryModule).
 *
 * `setPausedUntil`, `markIgnored`, and `setRuleOverrideJson` delegate to single-column
 * `@Query` UPDATEs on the DAO — one atomic statement, no read-modify-write race against
 * concurrent writers to other columns on the same row.
 */
internal class ContactRepositoryImpl @Inject constructor(
    private val contactDao: ContactDao,
) : ContactRepository {

    override fun observeAll(): Flow<List<ContactEntity>> = contactDao.observeAll()

    override fun observeForListMembers(listId: Long): Flow<List<ContactEntity>> =
        contactDao.observeForListMembers(listId)

    override fun observeNeverCalled(): Flow<List<ContactEntity>> = contactDao.observeNeverCalled()

    override suspend fun snapshotNeverCalled(): List<ContactEntity> = contactDao.snapshotNeverCalled()

    override suspend fun snapshotAllPhones(): List<ContactPhoneEntity> =
        contactDao.getAllPhonesOnce()

    override fun observeById(id: Long): Flow<ContactEntity?> = contactDao.observeById(id)

    override suspend fun getById(id: Long): ContactEntity? = contactDao.get(id)

    override suspend fun setPausedUntil(id: Long, until: Instant?) {
        contactDao.setPausedUntil(id, until)
    }

    override fun observeIgnored(): Flow<List<ContactEntity>> = contactDao.observeIgnored()

    override suspend fun markIgnored(
        id: Long,
        isIgnored: Boolean,
        ignoredAt: Instant?,
        preIgnoreListMembershipsJson: String?,
    ) {
        contactDao.markIgnored(id, isIgnored, ignoredAt, preIgnoreListMembershipsJson)
    }

    override suspend fun getPreIgnoreSnapshot(id: Long): PreIgnoreSnapshot? =
        contactDao.getPreIgnoreSnapshot(id)

    override suspend fun setRuleOverrideJson(id: Long, json: String?) {
        contactDao.setRuleOverrideJson(id, json)
    }

    override suspend fun setArchived(id: Long, archived: Boolean) {
        contactDao.setArchived(id, archived)
    }
}
