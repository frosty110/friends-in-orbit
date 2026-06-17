package app.orbit.data.repository

import app.orbit.data.entity.NoteEntity
import java.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * Bridges DAO calls for the `notes` table.
 *
 * Method set scoped to what `ContactDetailScreen`'s note rendering and the
 * `AddNoteUseCase` insertion path require. Extended with the recent-feed query
 * (NOTE-03) and the edit/delete/get triplet for inline-edit and
 * swipe-to-delete with snackbar undo.
 */
interface NoteRepository {

    /** Observes every note for a contact, newest-first per DAO ordering. */
    fun observeByContactId(contactId: Long): Flow<List<NoteEntity>>

    /** One-shot insert; returns the generated rowId. */
    suspend fun insert(note: NoteEntity): Long

    /** NOTE-03 — recent notes feed for Card View. Caller passes `since = now - 30 days`. */
    fun recentForContact(contactId: Long, since: Instant, limit: Int): Flow<List<NoteEntity>>

    /** Edit an existing note (NOTE-01 inline edit). */
    suspend fun update(note: NoteEntity): Int

    /** Delete a note (NOTE-01 swipe-to-delete with snackbar undo). */
    suspend fun delete(note: NoteEntity): Int

    /** One-shot read for delete-undo (re-insert path needs the original row). */
    suspend fun get(id: Long): NoteEntity?

    /**
     * EXPORT-01 — one-shot read of every note for the export envelope.
     * Returns `List<NoteEntity>` (not a Flow); same rationale as
     * [CallEventRepository.snapshotAll].
     */
    suspend fun snapshotAll(): List<NoteEntity>
}
