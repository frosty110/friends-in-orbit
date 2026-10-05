package app.orbit.domain.usecase

import app.orbit.data.entity.NoteEntity
import app.orbit.data.repository.NoteRepository
import javax.inject.Inject

/**
 * NOTE-01 swipe-to-delete with snackbar undo. Deletes immediately; the
 * Result.inverse re-inserts the original NoteEntity (preserving id and
 * createdAt so the row reappears in its original LazyColumn position).
 *
 * Mirrors [BulkIgnoreUseCase.Result] shape so the consumer ViewModel can
 * dispatch through the existing UndoStack singleton.
 */
class DeleteNoteUseCase @Inject constructor(
    private val noteRepo: NoteRepository,
) {
    /**
     * @property inverse Re-inserts the note. The snackbar's words ("Note
     *                   deleted") are the caller's, from string resources.
     */
    data class Result(val inverse: suspend () -> Unit)

    suspend operator fun invoke(note: NoteEntity): Result {
        noteRepo.delete(note)
        return Result(
            inverse = { noteRepo.insert(note) },
        )
    }
}
