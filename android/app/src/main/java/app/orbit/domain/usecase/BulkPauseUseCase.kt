package app.orbit.domain.usecase

import app.orbit.data.dao.ContactDao
import app.orbit.data.dao.PausedUntilSnapshot
import app.orbit.data.db.TransactionRunner
import app.orbit.domain.clock.Clock
import app.orbit.domain.model.PauseDuration
import java.time.Instant
import javax.inject.Inject

/**
 * MOVE-07: bulk-pause a batch of contacts for a [PauseDuration]. Reuses
 * [PauseContactUseCase.INDEFINITE_PAUSE_SENTINEL] for indefinite pause — do NOT
 * duplicate the sentinel.
 *
 * Snapshots each contact's prior `pausedUntil` so the inverse can restore it
 * exactly (some contacts may already have been paused with a different
 * duration; an undo MUST not blanket-clear them).
 *
 * The [TransactionRunner.withTransaction] block calls only suspending DAO
 * methods; no dispatcher switch inside the block.
 */
class BulkPauseUseCase @Inject constructor(
    private val txRunner: TransactionRunner,
    private val contactDao: ContactDao,
    private val clock: Clock,
) {
    /**
     * @property inverse Suspending closure that restores each contact's prior
     *                   `pausedUntil` value — grouped by prior value so the DAO
     *                   dispatches at most one `setPausedUntilBatch` call per
     *                   distinct prior `pausedUntil` (mirrors [BulkIgnoreUseCase]'s
     *                   shape; M8 fix avoids N round trips).
     * @property label Snackbar copy: "Paused {N} contacts for
     *                  {duration.displayLabel}" ("1 contact" when the batch is
     *                  a single row).
     */
    data class Result(val inverse: suspend () -> Unit, val label: String)

    suspend operator fun invoke(
        contactIds: List<Long>,
        duration: PauseDuration,
    ): Result {
        // PauseDuration.duration is null only for Indefinite; reuse the shared
        // sentinel rather than duplicating it (Instant.MAX would round-trip
        // badly through Room's Long-based InstantTypeConverter).
        val pausedUntil: Instant = duration.duration
            ?.let { clock.now().plus(it) }
            ?: PauseContactUseCase.INDEFINITE_PAUSE_SENTINEL
        val snapshot: List<PausedUntilSnapshot> = txRunner.withTransaction {
            val before = contactDao.getPausedUntilSnapshot(contactIds)
            contactDao.setPausedUntilBatch(contactIds, pausedUntil)
            before
        }
        return Result(
            inverse = {
                txRunner.withTransaction {
                    // M8 — group rows by prior `pausedUntil` so we issue at most
                    // one `setPausedUntilBatch` per distinct prior value, instead
                    // of one round trip per contact. Mirrors BulkIgnoreUseCase.
                    snapshot.groupBy { it.pausedUntil }.forEach { (prior, rows) ->
                        contactDao.setPausedUntilBatch(rows.map { it.id }, prior)
                    }
                }
            },
            label = run {
                val noun = if (contactIds.size == 1) "contact" else "contacts"
                "Paused ${contactIds.size} $noun for ${duration.displayLabel}"
            },
        )
    }
}
