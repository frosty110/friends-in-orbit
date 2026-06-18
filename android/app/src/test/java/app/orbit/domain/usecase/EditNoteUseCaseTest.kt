package app.orbit.domain.usecase

import app.orbit.data.entity.NoteEntity
import app.orbit.domain.FakeNoteRepository
import java.time.Instant
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * NOTE-01 — [EditNoteUseCase] updates the body via the repository and returns the
 * number of rows changed (1 when the note exists).
 */
class EditNoteUseCaseTest {

    private val note = NoteEntity(
        id = 5L,
        contactId = 1L,
        createdAt = Instant.parse("2026-01-01T12:00:00Z"),
        body = "old body",
    )

    @Test
    fun `updates the note body and reports one row changed`() = runTest {
        val repo = FakeNoteRepository(listOf(note))
        val edited = note.copy(body = "new body")

        val changed = EditNoteUseCase(repo)(edited)

        assertEquals(1, changed)
        assertEquals(edited, repo.updateCalls.single())
    }

    @Test
    fun `returns zero rows changed when the note is absent`() = runTest {
        val repo = FakeNoteRepository()

        val changed = EditNoteUseCase(repo)(note.copy(body = "whatever"))

        assertEquals(0, changed, "no matching row → zero rows changed")
    }
}
