package app.orbit.domain.usecase

import app.orbit.data.entity.NoteEntity
import app.orbit.data.repository.NoteRepository
import javax.inject.Inject

/**
 * NOTE-01 inline edit — updates the note body via [NoteRepository.update].
 * Caller passes the full NoteEntity with the new body; createdAt and id stay
 * intact so list ordering and stable-key invariants hold.
 */
class EditNoteUseCase @Inject constructor(
    private val noteRepo: NoteRepository,
) {
    suspend operator fun invoke(note: NoteEntity): Int = noteRepo.update(note)
}
