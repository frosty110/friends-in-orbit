package app.orbit.domain.usecase

import app.orbit.domain.FakeContactRepository
import app.orbit.domain.contactFixture
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Symmetric to [ArchiveContactUseCase] — [UnarchiveContactUseCase] writes
 * `isArchived = false` for the given contact.
 */
class UnarchiveContactUseCaseTest {

    @Test
    fun `unarchives the contact`() = runTest {
        val repo = FakeContactRepository(listOf(contactFixture(id = 3L, isArchived = true)))

        UnarchiveContactUseCase(repo)(contactId = 3L)

        val args = repo.setArchivedCalls.single()
        assertEquals(3L, args.contactId)
        assertFalse(args.archived)
    }
}
