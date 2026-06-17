package app.orbit.domain.usecase

import app.orbit.data.repository.ContactRepository
import javax.inject.Inject

/**
 * CONTACT-06 — archive an orphaned contact. Writes `isArchived = true` ONLY.
 *
 * Archive is a separate hide mechanism from ignore. This use case:
 *   - DOES write isArchived = true via ContactRepository.setArchived
 *   - DOES NOT delete ListMembership rows (membership preservation matches
 *     the un-ignore drift-restore policy and lets a future v1.1 unarchive
 *     surface restore visibility cleanly)
 *   - DOES NOT touch isIgnored (separate flag, separate UI flow)
 *
 * SurfaceNextUseCase filters archived contacts out of surfacing. CallLogScreen
 * still shows archived contacts because history-as-truth is the principle.
 * SettingsIgnoredScreen filters
 * `WHERE isArchived = 0 AND isIgnored = 1` so archived contacts don't appear
 * in the Ignored view (different mental model).
 *
 * Returns [Result] with an `inverse` closure for snackbar undo dispatch — the
 * inverse calls `setArchived(id, false)` (UnarchiveContactUseCase semantics).
 */
class ArchiveContactUseCase @Inject constructor(
    private val contactRepo: ContactRepository,
) {
    /**
     * @property inverse Suspending closure that flips `isArchived` back to
     *                   false — undo within the snackbar window.
     * @property label Snackbar copy: "Archived {contactName}".
     */
    data class Result(val inverse: suspend () -> Unit, val label: String)

    suspend operator fun invoke(contactId: Long, contactName: String): Result {
        contactRepo.setArchived(contactId, true)
        return Result(
            inverse = { contactRepo.setArchived(contactId, false) },
            label = "Archived $contactName",
        )
    }
}
