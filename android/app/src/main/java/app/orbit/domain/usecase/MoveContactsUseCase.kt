package app.orbit.domain.usecase

import app.orbit.data.dao.ListDao
import app.orbit.data.dao.ListMembershipDao
import app.orbit.data.db.TransactionRunner
import app.orbit.data.entity.ListMembershipEntity
import app.orbit.data.repository.ListRepository
import app.orbit.domain.WidgetRefreshTrigger
import app.orbit.domain.clock.Clock
import javax.inject.Inject

/**
 * MOVE-03: atomically move a batch of contacts from one list to another.
 *
 * Atomicity is wrapped via [TransactionRunner.withTransaction] so the destination
 * validation, pre-state snapshots, and the move itself land in one SQLite
 * transaction. The inverse closure runs its own transaction and restores the
 * source-side rows verbatim (preserving `addedAt` / `nextDueAt` / `skipCount`)
 * while only removing target-side rows that this call actually inserted.
 *
 * Guards (review-fixes C4 + M2 + M3):
 *  - empty `contactIds`           → no-op result
 *  - same-list move               → no-op result (would otherwise destroy `addedAt`)
 *  - missing/archived destination → no-op result (mirrors UnignoreContactUseCase)
 *
 * The [TransactionRunner.withTransaction] body calls only suspending
 * DAO methods; no dispatcher switch inside.
 */
class MoveContactsUseCase @Inject constructor(
    private val txRunner: TransactionRunner,
    private val listMembershipDao: ListMembershipDao,
    private val listDao: ListDao,
    private val listRepo: ListRepository,
    private val clock: Clock,
    private val widgetRefreshTrigger: WidgetRefreshTrigger = WidgetRefreshTrigger { },
) {
    /**
     * @property inverse Suspending closure the snackbar's "Undo" runs to revert.
     * @property label Snackbar copy: "Moved {N} to {targetListName}". Empty when
     *                 the use case short-circuits (caller should suppress UI).
     */
    data class Result(val inverse: suspend () -> Unit, val label: String)

    suspend operator fun invoke(
        fromListId: Long,
        toListId: Long,
        contactIds: List<Long>,
        targetListName: String,
    ): Result {
        if (contactIds.isEmpty()) return Result(inverse = {}, label = "")
        if (fromListId == toListId) return Result(inverse = {}, label = "")

        val result = txRunner.withTransaction {
            val target = listDao.get(toListId)
            if (target == null || target.isArchived) {
                return@withTransaction Result(inverse = {}, label = "")
            }

            // Snapshot source-side rows so the inverse can restore addedAt /
            // nextDueAt / skipCount verbatim instead of stamping clock.now().
            val sourceSnapshot: List<ListMembershipEntity> = contactIds
                .mapNotNull { listMembershipDao.get(it, fromListId) }

            // Track which contactIds were ALREADY on the target before this
            // move. moveAll's insert leg uses IGNORE, so those rows are
            // untouched here — the inverse must mirror that and never delete
            // pre-existing target memberships.
            val targetPreExisting: Set<Long> = contactIds
                .mapNotNull { listMembershipDao.get(it, toListId)?.contactId }
                .toSet()

            listMembershipDao.moveAll(fromListId, toListId, contactIds, clock.now().toEpochMilli())

            // Keep dueCount fresh on both source and target lists.
            val now = clock.now()
            listRepo.recomputeDueCountForList(fromListId, now)
            listRepo.recomputeDueCountForList(toListId, now)

            Result(
                inverse = {
                    txRunner.withTransaction {
                        val newlyAdded = contactIds.filterNot { it in targetPreExisting }
                        if (newlyAdded.isNotEmpty()) {
                            listMembershipDao.removeAll(toListId, newlyAdded)
                        }
                        if (sourceSnapshot.isNotEmpty()) {
                            listMembershipDao.insertAll(sourceSnapshot)
                        }
                        // Undo also restores dueCount.
                        val undoNow = clock.now()
                        listRepo.recomputeDueCountForList(fromListId, undoNow)
                        listRepo.recomputeDueCountForList(toListId, undoNow)
                    }
                    // WIDGET-06 (review WR-02): undo moved the memberships back —
                    // who-is-due changed again, so the widget refresh must mirror
                    // the forward path. Debounced by the 30s KEEP work.
                    widgetRefreshTrigger.scheduleRefresh()
                },
                label = "Moved ${contactIds.size} to $targetListName",
            )
        }
        // WIDGET-06: membership moved — who-is-due changed. Only fire on the
        // success path (non-empty label signals the move actually happened).
        if (result.label.isNotEmpty()) {
            widgetRefreshTrigger.scheduleRefresh()
        }
        return result
    }
}
