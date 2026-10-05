package app.orbit.domain.usecase

import app.orbit.domain.FakeContactRepository
import app.orbit.domain.contactFixture
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * CONTACT-06 — [ArchiveContactUseCase] writes `isArchived = true` only, and its
 * snackbar `inverse` flips it back to false (undo within the snackbar window).
 */
class ArchiveContactUseCaseTest {

    @Test
    fun `archives the contact`() = runTest {
        // The snackbar's "Archived Alex" is the caller's copy now
        // (strings_contact.xml; SnackbarCopyTest).
        val repo = FakeContactRepository(listOf(contactFixture(id = 7L)))

        ArchiveContactUseCase(repo)(contactId = 7L)

        val args = repo.setArchivedCalls.single()
        assertEquals(7L, args.contactId)
        assertTrue(args.archived)
    }

    @Test
    fun `inverse unarchives the contact`() = runTest {
        val repo = FakeContactRepository(listOf(contactFixture(id = 7L, isArchived = true)))

        val result = ArchiveContactUseCase(repo)(contactId = 7L)
        result.inverse()

        // Forward write (true) then the undo write (false).
        assertEquals(2, repo.setArchivedCalls.size)
        val last = repo.setArchivedCalls.last()
        assertEquals(7L, last.contactId)
        assertFalse(last.archived)
    }
}
