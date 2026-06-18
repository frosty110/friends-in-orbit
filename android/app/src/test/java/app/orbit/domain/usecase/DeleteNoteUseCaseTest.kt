package app.orbit.domain.usecase

import app.orbit.data.entity.NoteEntity
import app.orbit.domain.FakeNoteRepository
import java.time.Instant
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * NOTE-01 — [DeleteNoteUseCase]. Deletes immediately and exposes an `inverse`
 * that re-inserts the verbatim note (id + createdAt preserved) for snackbar undo.
 */
class DeleteNoteUseCaseTest {

    private val note = NoteEntity(
        id = 5L,
        contactId = 1L,
        createdAt = Instant.parse("2026-01-01T12:00:00Z"),
        body = "remember to call",
    )

    @Test
    fun `deletes the note and labels the snackbar`() = runTest {
        val repo = FakeNoteRepository(listOf(note))

        val result = DeleteNoteUseCase(repo)(note)

        assertEquals(note, repo.deleteCalls.single())
        assertEquals("Note deleted", result.label)
    }

    @Test
    fun `inverse re-inserts the verbatim note`() = runTest {
        val repo = FakeNoteRepository(listOf(note))

        val result = DeleteNoteUseCase(repo)(note)
        result.inverse()

        assertEquals(note, repo.insertCalls.single(), "undo must restore the exact note (id + createdAt)")
    }
}
