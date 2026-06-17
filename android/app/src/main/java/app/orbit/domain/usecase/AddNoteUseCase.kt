package app.orbit.domain.usecase

import app.orbit.data.entity.NoteEntity
import app.orbit.data.repository.NoteRepository
import app.orbit.domain.clock.Clock
import javax.inject.Inject

/**
 * NOTE-01 — add a note to a contact at the current clock time. Trims input;
 * empty/blank bodies return null (no insert) so the UI can no-op the "Add"
 * button on whitespace.
 *
 * Replaces the inline `addNote()` that was once stubbed in
 * `ContactDetailViewModel`.
 */
class AddNoteUseCase @Inject constructor(
    private val noteRepo: NoteRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(contactId: Long, body: String): Long? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null
        return noteRepo.insert(
            NoteEntity(contactId = contactId, createdAt = clock.now(), body = trimmed),
        )
    }
}
