package app.orbit.domain.usecase

import app.orbit.data.entity.NoteEntity
import app.orbit.data.repository.NoteRepository
import java.time.Instant
import javax.inject.Inject

/**
 * LOG-03 — silently back-dates `createdAt` to `occurredAt` so the note appears
 * at the moment the call happened (not when the user typed it). The relative-
 * time formatter on the row will then read "called 14 min ago" — the visual
 * cue that disambiguates retroactive vs live notes.
 *
 * No confirmation dialog (08-RESEARCH §Open Q 3 — silent back-date locked).
 *
 * Trims input; empty/blank bodies return null (no insert) — same contract as
 * [AddNoteUseCase], so the UI can no-op on whitespace.
 */
class AddRetroactiveNoteUseCase @Inject constructor(
    private val noteRepo: NoteRepository,
) {
    suspend operator fun invoke(contactId: Long, body: String, occurredAt: Instant): Long? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null
        return noteRepo.insert(
            NoteEntity(contactId = contactId, createdAt = occurredAt, body = trimmed),
        )
    }
}
