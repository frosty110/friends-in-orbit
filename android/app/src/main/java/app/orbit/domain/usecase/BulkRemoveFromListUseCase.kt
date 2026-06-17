package app.orbit.domain.usecase

import app.orbit.data.dao.ListMembershipDao
import app.orbit.data.db.TransactionRunner
import app.orbit.data.entity.ListMembershipEntity
import app.orbit.data.repository.ListRepository
import app.orbit.domain.WidgetRefreshTrigger
import app.orbit.domain.clock.Clock
import javax.inject.Inject

/**
 * MOVE-07: bulk-remove a batch of contacts from a list (destructive but recoverable).
 *
 * Snapshots the prior membership rows inside one transaction so the inverse
 * undo lambda can re-insert them verbatim. `mapNotNull { dao.get(id, listId) }`
 * silently skips ids that no longer have a row at snapshot time — the inverse
 * restores only what was actually removed (T-07-13 mitigation).
 *
 * Note: the [TransactionRunner.withTransaction] block calls only suspending
 * DAO methods; no dispatcher switch inside the block.
 */
class BulkRemoveFromListUseCase @Inject constructor(
    private val txRunner: TransactionRunner,
    private val dao: ListMembershipDao,
    private val listRepo: ListRepository,
    private val clock: Clock,
    private val widgetRefreshTrigger: WidgetRefreshTrigger = WidgetRefreshTrigger { },
) {
    /**
     * @property inverse Suspending closure the snackbar's "Undo" runs to
     *                   re-insert the removed memberships.
     * @property label Snackbar copy: "Removed {N} from {sourceListName}".
     */
    data class Result(val inverse: suspend () -> Unit, val label: String)

    suspend operator fun invoke(
        listId: Long,
        contactIds: List<Long>,
        sourceListName: String,
    ): Result {
        if (contactIds.isEmpty()) return Result(inverse = {}, label = "")
        val snapshot: List<ListMembershipEntity> = txRunner.withTransaction {
            val priors = contactIds.mapNotNull { id -> dao.get(id, listId) }
            dao.removeAll(listId, contactIds)
            // Source-list dueCount recompute (destructive write).
            listRepo.recomputeDueCountForList(listId, clock.now())
            priors
        }
        // WIDGET-06: bulk-remove changes who-is-due on the success path.
        widgetRefreshTrigger.scheduleRefresh()
        return Result(
            inverse = {
                // Wrap re-insert + recompute atomically.
                txRunner.withTransaction {
                    dao.insertAll(snapshot)
                    listRepo.recomputeDueCountForList(listId, clock.now())
                }
                // WIDGET-06 (review WR-02): undo restores memberships — who-is-due
                // changed back, so the widget refresh must mirror the forward path.
                widgetRefreshTrigger.scheduleRefresh()
            },
            label = "Removed ${contactIds.size} from $sourceListName",
        )
    }
}
