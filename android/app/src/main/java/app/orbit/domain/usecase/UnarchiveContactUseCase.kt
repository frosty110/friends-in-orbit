package app.orbit.domain.usecase

import app.orbit.data.repository.ContactRepository
import javax.inject.Inject

/**
 * Symmetric to [ArchiveContactUseCase] — writes `isArchived = false`. Programmatic
 * seam for v1.1 UI (no Settings → Archived screen ships yet). Useful for tests and
 * future affordances; the snackbar undo path used by
 * [ArchiveContactUseCase.Result.inverse] mirrors this semantic inline.
 */
class UnarchiveContactUseCase @Inject constructor(
    private val contactRepo: ContactRepository,
) {
    suspend operator fun invoke(contactId: Long) {
        contactRepo.setArchived(contactId, false)
    }
}
