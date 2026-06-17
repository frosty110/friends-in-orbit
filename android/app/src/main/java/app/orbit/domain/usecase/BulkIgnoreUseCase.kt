package app.orbit.domain.usecase

import app.orbit.data.dao.ContactDao
import app.orbit.data.dao.IgnoredSnapshot
import app.orbit.data.db.TransactionRunner
import javax.inject.Inject

/**
 * MOVE-07: bulk-ignore a batch of contacts (destructive but recoverable).
 *
 * The load-bearing correctness invariant lives in the inverse: it uses
 * `groupBy { it.isIgnored }` to restore each row to its prior `isIgnored`
 * value. A blanket `setIgnoredBatch(ids, false)` would silently un-ignore
 * contacts that were already ignored before the batch.
 *
 * The forward batch is wrapped in a transaction so the snapshot read and the
 * `setIgnoredBatch(ids, true)` write are atomic — no row can flip its
 * `isIgnored` flag between the snapshot and the write.
 *
 * The [TransactionRunner.withTransaction] block calls only suspending DAO
 * methods; no dispatcher switch inside the block.
 */
class BulkIgnoreUseCase @Inject constructor(
    private val txRunner: TransactionRunner,
    private val contactDao: ContactDao,
) {
    /**
     * @property inverse Suspending closure that restores each contact's prior
     *                   `isIgnored` flag — grouped by prior value to dispatch
     *                   at most two `setIgnoredBatch` calls (one per group).
     * @property label Snackbar copy: "Ignored {N} contacts" ("1 contact"
     *                  when the batch is a single row).
     */
    data class Result(val inverse: suspend () -> Unit, val label: String)

    suspend operator fun invoke(contactIds: List<Long>): Result {
        val snapshot: List<IgnoredSnapshot> = txRunner.withTransaction {
            val before = contactDao.getIgnoredFlags(contactIds)
            contactDao.setIgnoredBatch(contactIds, ignored = true)
            before
        }
        return Result(
            inverse = {
                txRunner.withTransaction {
                    snapshot.groupBy { it.isIgnored }.forEach { (wasIgnored, rows) ->
                        contactDao.setIgnoredBatch(rows.map { it.id }, ignored = wasIgnored)
                    }
                }
            },
            label = if (contactIds.size == 1) {
                "Ignored 1 contact"
            } else {
                "Ignored ${contactIds.size} contacts"
            },
        )
    }
}
