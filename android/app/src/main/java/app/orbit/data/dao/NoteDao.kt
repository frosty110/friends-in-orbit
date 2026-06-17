package app.orbit.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.orbit.data.entity.NoteEntity
import java.time.Instant
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {

    @Query("SELECT * FROM notes WHERE contactId = :contactId ORDER BY createdAt DESC")
    fun observeByContactId(contactId: Long): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun get(id: Long): NoteEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(note: NoteEntity): Long

    @Update
    suspend fun update(note: NoteEntity): Int

    @Delete
    suspend fun delete(note: NoteEntity): Int

    /**
     * NOTE-03 — recent notes for Card View summary. Newest-first, capped at `limit`,
     * filtered to `createdAt >= since` so old back-dated retroactive notes don't
     * pollute the recent feed. Voice rule: caller passes `since = now - 30 days`.
     */
    @Query(
        "SELECT * FROM notes " +
            "WHERE contactId = :contactId " +
            "  AND createdAt >= :since " +
            "ORDER BY createdAt DESC " +
            "LIMIT :limit",
    )
    fun recentForContact(contactId: Long, since: Instant, limit: Int): Flow<List<NoteEntity>>

    /** EXPORT-01 — one-shot read of every note for the export pipeline. */
    @Query("SELECT * FROM notes ORDER BY createdAt DESC")
    suspend fun snapshotAll(): List<NoteEntity>
}
