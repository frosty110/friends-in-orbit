package app.orbit.data.repository

import app.orbit.data.dao.NoteDao
import app.orbit.data.entity.NoteEntity
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/**
 * Room-backed implementation of NoteRepository. Pass-through wiring to [NoteDao].
 * The surface covers the Notes journal UI (recent feed,
 * inline edit, swipe-to-delete with undo).
 */
internal class NoteRepositoryImpl @Inject constructor(
    private val noteDao: NoteDao,
) : NoteRepository {

    override fun observeByContactId(contactId: Long): Flow<List<NoteEntity>> =
        noteDao.observeByContactId(contactId)

    override suspend fun insert(note: NoteEntity): Long = noteDao.insert(note)

    override fun recentForContact(contactId: Long, since: Instant, limit: Int): Flow<List<NoteEntity>> =
        noteDao.recentForContact(contactId, since, limit)

    override suspend fun update(note: NoteEntity): Int = noteDao.update(note)

    override suspend fun delete(note: NoteEntity): Int = noteDao.delete(note)

    override suspend fun get(id: Long): NoteEntity? = noteDao.get(id)

    override suspend fun snapshotAll(): List<NoteEntity> = noteDao.snapshotAll()
}
