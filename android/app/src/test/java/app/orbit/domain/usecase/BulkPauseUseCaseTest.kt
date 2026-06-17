package app.orbit.domain.usecase

import app.orbit.data.dao.PausedUntilSnapshot
import app.orbit.data.dao.RecordingContactDao
import app.orbit.data.db.TransactionRunner
import app.orbit.domain.clock.TestClock
import app.orbit.domain.model.PauseDuration
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Label pluralization for [BulkPauseUseCase]. The inverse / snapshot
 * mechanics mirror [BulkIgnoreUseCaseTest]'s shape and are covered there for
 * the shared groupBy idiom; these tests pin the snackbar copy, which the
 * multi-select flow can emit for a single-row batch.
 */
class BulkPauseUseCaseTest {

    private val passThruTx = object : TransactionRunner {
        override suspend fun <T> withTransaction(block: suspend () -> T): T = block()
    }

    @Test
    fun result_label_uses_count_and_duration() = runBlocking {
        val dao = RecordingContactDao(
            pausedSnapshots = listOf(
                PausedUntilSnapshot(1L, null),
                PausedUntilSnapshot(2L, null),
            ),
        )
        val useCase = BulkPauseUseCase(passThruTx, dao, TestClock())

        val result = useCase(listOf(1L, 2L), PauseDuration.OneWeek)

        assertEquals("Paused 2 contacts for 1 week", result.label)
    }

    @Test
    fun result_label_singularizes_for_one_contact() = runBlocking {
        // "1 contact", never "1 contacts".
        val dao = RecordingContactDao(
            pausedSnapshots = listOf(PausedUntilSnapshot(1L, null)),
        )
        val useCase = BulkPauseUseCase(passThruTx, dao, TestClock())

        val result = useCase(listOf(1L), PauseDuration.OneMonth)

        assertEquals("Paused 1 contact for 1 month", result.label)
    }
}
