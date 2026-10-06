package app.orbit.domain.usecase

import app.orbit.data.dao.PausedUntilSnapshot
import app.orbit.data.dao.RecordingContactDao
import app.orbit.data.db.TransactionRunner
import app.orbit.domain.clock.TestClock
import app.orbit.domain.model.PauseDuration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

/**
 * [BulkPauseUseCase]: the count the caller's plural reads, and the inverse,
 * which restores each person's prior `pausedUntil` exactly (someone already
 * paused with another length must not be blanket-cleared by Undo). Until
 * 2026-10-06 the inverse had no test anywhere (browse-10); it was assumed to
 * mirror [BulkIgnoreUseCaseTest]'s groupBy idiom.
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

    @Test
    fun inverse_restores_each_prior_pause_grouped_by_value() = runBlocking {
        // 1 was not paused, 2 was already paused until another date, 3 was
        // not paused. Undo must put each back as it was, not clear all three.
        val priorOfTwo = Instant.parse("2026-03-01T00:00:00Z")
        val dao = RecordingContactDao(
            pausedSnapshots = listOf(
                PausedUntilSnapshot(1L, null),
                PausedUntilSnapshot(2L, priorOfTwo),
                PausedUntilSnapshot(3L, null)
            )
        )
        val clock = TestClock()
        val useCase = BulkPauseUseCase(passThruTx, dao, clock)

        val result = useCase(listOf(1L, 2L, 3L), PauseDuration.OneWeek)
        val forward = dao.setPausedUntilCalls.single()
        assertEquals(listOf(1L, 2L, 3L), forward.ids)
        assertEquals(clock.now().plus(PauseDuration.OneWeek.duration), forward.until)

        dao.setPausedUntilCalls.clear()
        result.inverse()

        // One batch per distinct prior value (the M8 shape), each restoring
        // exactly that value.
        val restored = dao.setPausedUntilCalls.associate { it.until to it.ids.toSet() }
        assertEquals(mapOf(null to setOf(1L, 3L), priorOfTwo to setOf(2L)), restored)
    }
}
