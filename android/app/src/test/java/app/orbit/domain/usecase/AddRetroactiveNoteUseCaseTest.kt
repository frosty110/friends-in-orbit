package app.orbit.domain.usecase

import app.orbit.domain.FakeNoteRepository
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Exercises [AddRetroactiveNoteUseCase] against its
 * contract. Pure JVM (no Robolectric / no Room).
 *
 * Verifies LOG-03 silent back-date: createdAt = caller-supplied occurredAt,
 * NOT clock.now(). The use case has no Clock dependency by design — the call
 * event's occurredAt is the only timestamp the use case writes.
 */
class AddRetroactiveNoteUseCaseTest {

    @Test
    fun `invoke writes Note with createdAt equal to occurredAt argument (back-dated)`() = runTest {
        val occurredAt = Instant.parse("2026-04-25T13:50:00Z")
        val noteRepo = FakeNoteRepository()
        val useCase = AddRetroactiveNoteUseCase(noteRepo)

        useCase(contactId = 42L, body = "missed catching up — felt good", occurredAt = occurredAt)

        val inserted = noteRepo.insertCalls.single()
        assertEquals(42L, inserted.contactId)
        assertEquals("missed catching up — felt good", inserted.body)
        // CRITICAL: createdAt is the back-dated occurredAt, NOT a wall-clock or test-clock value.
        assertEquals(occurredAt, inserted.createdAt, "LOG-03: createdAt must equal occurredAt")
    }

    @Test
    fun `invoke trims whitespace from body before insert`() = runTest {
        val occurredAt = Instant.parse("2026-04-25T13:50:00Z")
        val noteRepo = FakeNoteRepository()
        val useCase = AddRetroactiveNoteUseCase(noteRepo)

        useCase(contactId = 42L, body = "   hi there   ", occurredAt = occurredAt)

        assertEquals("hi there", noteRepo.insertCalls.single().body)
    }

    @Test
    fun `invoke returns null and skips insert for blank body`() = runTest {
        val occurredAt = Instant.parse("2026-04-25T13:50:00Z")
        val noteRepo = FakeNoteRepository()
        val useCase = AddRetroactiveNoteUseCase(noteRepo)

        val result = useCase(contactId = 42L, body = "   ", occurredAt = occurredAt)

        assertNull(result, "blank body must return null (no insert)")
        assertEquals(0, noteRepo.insertCalls.size, "blank body must not write to repo")
    }
}
