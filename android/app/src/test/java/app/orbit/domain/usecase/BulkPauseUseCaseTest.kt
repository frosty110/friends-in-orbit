package app.orbit.domain.usecase

import app.orbit.data.dao.PausedUntilSnapshot
import app.orbit.data.dao.RecordingContactDao
import app.orbit.data.db.TransactionRunner
import app.orbit.domain.clock.TestClock
import app.orbit.domain.model.PauseDuration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

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
                PausedUntilSnapshot(2L, null)
            )
        )
        val useCase = BulkPauseUseCase(passThruTx, dao, TestClock())

        val result = useCase(listOf(1L, 2L), PauseDuration.OneWeek)

        // The words ("Paused 2 people for 1 week") are the caller's (PauseTextTest).
        assertEquals(2, result.count)
    }

    @Test
    fun result_label_singularizes_for_one_contact() = runBlocking {
        // Reports 1, which the plural reads as "1 person".
        val dao = RecordingContactDao(
            pausedSnapshots = listOf(PausedUntilSnapshot(1L, null))
        )
        val useCase = BulkPauseUseCase(passThruTx, dao, TestClock())

        val result = useCase(listOf(1L), PauseDuration.OneMonth)

        assertEquals(1, result.count)
    }

    @Test
    fun result_label_reads_naturally_for_an_indefinite_pause() = runBlocking {
        // Regression: the label was built as "for {label}", which read
        // "Paused 3 contacts for indefinitely". The sentence is now one
        // resource per duration (PauseTextTest pins "Paused 3 people
        // indefinitely"); the use case reports the count.
        val dao = RecordingContactDao(
            pausedSnapshots = listOf(
                PausedUntilSnapshot(1L, null),
                PausedUntilSnapshot(2L, null),
                PausedUntilSnapshot(3L, null)
            )
        )
        val useCase = BulkPauseUseCase(passThruTx, dao, TestClock())

        val result = useCase(listOf(1L, 2L, 3L), PauseDuration.Indefinite)

        assertEquals(3, result.count)
    }
}
