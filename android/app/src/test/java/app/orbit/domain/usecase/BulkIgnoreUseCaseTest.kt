package app.orbit.domain.usecase

import app.orbit.data.dao.IgnoredSnapshot
import app.orbit.data.dao.RecordingContactDao
import app.orbit.data.db.TransactionRunner
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for [BulkIgnoreUseCase] — MOVE-07.
 *
 * The load-bearing correctness invariant is the third test: the inverse must
 * preserve mixed pre-state via `groupBy { it.isIgnored }`. Without that, an
 * undo would silently un-ignore contacts that were already ignored before
 * the batch (T-07-12 in the plan threat model).
 *
 * Uses a passthrough [TransactionRunner] so the suspending block runs
 * directly — Room is not instantiated.
 */
class BulkIgnoreUseCaseTest {

    private val passThruTx = object : TransactionRunner {
        override suspend fun <T> withTransaction(block: suspend () -> T): T = block()
    }

    @Test
    fun invoke_dispatches_setIgnoredBatch_with_true() = runBlocking {
        val dao = RecordingContactDao(
            ignoredSnapshots = listOf(
                IgnoredSnapshot(1L, false),
                IgnoredSnapshot(2L, false),
                IgnoredSnapshot(3L, false),
            ),
        )
        val useCase = BulkIgnoreUseCase(passThruTx, dao)

        useCase(listOf(1L, 2L, 3L))

        assertEquals(1, dao.setIgnoredCalls.size)
        val call = dao.setIgnoredCalls[0]
        assertEquals(listOf(1L, 2L, 3L), call.ids)
        assertEquals(true, call.ignored)
    }

    @Test
    fun result_label_uses_count() = runBlocking {
        val dao = RecordingContactDao(
            ignoredSnapshots = listOf(
                IgnoredSnapshot(1L, false),
                IgnoredSnapshot(2L, false),
                IgnoredSnapshot(3L, false),
            ),
        )
        val useCase = BulkIgnoreUseCase(passThruTx, dao)

        val result = useCase(listOf(1L, 2L, 3L))

        assertEquals("Ignored 3 contacts", result.label)
    }

    @Test
    fun result_label_singularizes_for_one_contact() = runBlocking {
        // A single-row batch reads "1 contact", never "1 contacts".
        val dao = RecordingContactDao(
            ignoredSnapshots = listOf(IgnoredSnapshot(1L, false)),
        )
        val useCase = BulkIgnoreUseCase(passThruTx, dao)

        val result = useCase(listOf(1L))

        assertEquals("Ignored 1 contact", result.label)
    }

    @Test
    fun inverse_restores_mixed_pre_state_via_groupBy() = runBlocking {
        // id=1 was already ignored (true); ids=[2,3] were not (false).
        val dao = RecordingContactDao(
            ignoredSnapshots = listOf(
                IgnoredSnapshot(1L, true),
                IgnoredSnapshot(2L, false),
                IgnoredSnapshot(3L, false),
            ),
        )
        val useCase = BulkIgnoreUseCase(passThruTx, dao)

        val result = useCase(listOf(1L, 2L, 3L))
        dao.setIgnoredCalls.clear()  // discard the forward batch; assert inverse only
        result.inverse.invoke()

        // Inverse: groupBy{isIgnored} yields up to 2 calls (one per unique prior flag).
        assertEquals(2, dao.setIgnoredCalls.size, "inverse must group restorations by prior flag")
        val trueGroup = dao.setIgnoredCalls.firstOrNull { it.ignored }
        val falseGroup = dao.setIgnoredCalls.firstOrNull { !it.ignored }
        assertEquals(listOf(1L), trueGroup?.ids, "id=1 was ignored before — restore to true")
        assertEquals(setOf(2L, 3L), falseGroup?.ids?.toSet(), "ids=[2,3] were NOT ignored before — restore to false")
    }

    @Test
    fun empty_id_list_inverse_is_a_no_op() = runBlocking {
        val dao = RecordingContactDao()
        val useCase = BulkIgnoreUseCase(passThruTx, dao)

        val result = useCase(emptyList())
        dao.setIgnoredCalls.clear()
        result.inverse.invoke()

        assertEquals(0, dao.setIgnoredCalls.size, "no rows to restore = no inverse dispatches")
    }
}
