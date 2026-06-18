package app.orbit.domain.usecase

import app.orbit.domain.FakeNoteRepository
import app.orbit.domain.clock.TestClock
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * NOTE-01 — [AddNoteUseCase]. Trims the body, stamps it with `clock.now()`, and
 * no-ops (returns null, no insert) on blank input.
 */
class AddNoteUseCaseTest {

    private val T0: Instant = Instant.parse("2026-01-01T12:00:00Z")

    @Test
    fun `inserts a trimmed note stamped at clock now`() = runTest {
        val repo = FakeNoteRepository()

        val id = AddNoteUseCase(repo, TestClock(T0))(contactId = 1L, body = "  call mum  ")

        assertNotNull(id)
        val note = repo.insertCalls.single()
        assertEquals(1L, note.contactId)
        assertEquals("call mum", note.body, "body must be trimmed")
        assertEquals(T0, note.createdAt, "createdAt must be clock.now()")
    }

    @Test
    fun `blank body is a no-op`() = runTest {
        val repo = FakeNoteRepository()

        val id = AddNoteUseCase(repo, TestClock(T0))(contactId = 1L, body = "   ")

        assertNull(id, "blank body must return null")
        assertTrue(repo.insertCalls.isEmpty(), "blank body must not insert")
    }
}
