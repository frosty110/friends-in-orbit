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
 * MOVE-04: additively copy a batch of contacts INTO a target list.
 *
 * Separate class from [MoveContactsUseCase] — do not share implementation with a
 * boolean param. Copy is non-destructive: the source list (if any) is never read
 * or touched.
 *
 * Guards:
 *  - empty `contactIds`           → no-op result
 *  - missing/archived destination → no-op result (mirrors UnignoreContactUseCase)
 *
 * The `dao.insertAll` is `OnConflictStrategy.IGNORE`, but rather than rely on
 * that for inverse correctness we snapshot pre-existing target memberships
 * inside the transaction and only insert the `notYetMembers` subset. The
 * inverse therefore removes only the rows we actually added — never long-
 * standing memberships that happened to overlap with this batch.
 *
 * Note: `addedAt` takes [Clock.now] directly (an [java.time.Instant]) — NOT
 * `clock.now().toEpochMilli()`. [ListMembershipEntity.addedAt] is typed
 * `Instant` and Room's TypeConverter handles the round-trip.
 */
class CopyContactsUseCase @Inject constructor(
    private val txRunner: TransactionRunner,
    private val listMembershipDao: ListMembershipDao,
    private val listDao: ListDao,
    private val listRepo: ListRepository,
    private val clock: Clock,
    private val widgetRefreshTrigger: WidgetRefreshTrigger = WidgetRefreshTrigger { },
) {
    /**
     * @property inverse Suspending closure the snackbar's "Undo" runs to revert
     *                   the copy by removing only the rows this call inserted.
     * @property label Snackbar copy: "Copied {N} to {targetListName}". Empty
     *                 when the use case short-circuits (caller should suppress UI).
     */
    data class Result(val inverse: suspend () -> Unit, val label: String)

    suspend operator fun invoke(
        toListId: Long,
        contactIds: List<Long>,
        targetListName: String,
    ): Result {
        if (contactIds.isEmpty()) return Result(inverse = {}, label = "")

        val result = txRunner.withTransaction {
            val target = listDao.get(toListId)
            if (target == null || target.isArchived) {
                return@withTransaction Result(inverse = {}, label = "")
            }

            val preExistingIds: Set<Long> = contactIds
                .mapNotNull { listMembershipDao.get(it, toListId)?.contactId }
                .toSet()
            val notYetMembers = contactIds.filterNot { it in preExistingIds }

            if (notYetMembers.isNotEmpty()) {
                val now = clock.now()
                listMembershipDao.insertAll(
                    notYetMembers.map { contactId ->
                        ListMembershipEntity(listId = toListId, contactId = contactId, addedAt = now)
                    },
                )
                // Destination-only recompute; Copy doesn't touch source.
                listRepo.recomputeDueCountForList(toListId, now)
            }

            Result(
                inverse = {
                    // Wrap in withTransaction so the membership
                    // removal AND the dueCount recompute land atomically. The
                    // forward call's withTransaction is the outer block here;
                    // the inverse runs detached so it needs its own.
                    if (notYetMembers.isNotEmpty()) {
                        txRunner.withTransaction {
                            listMembershipDao.removeAll(toListId, notYetMembers)
                            listRepo.recomputeDueCountForList(toListId, clock.now())
                        }
                        // WIDGET-06: undo removed the copied rows —
                        // who-is-due changed back, so the widget refresh must
                        // mirror the forward path. Debounced by the 30s KEEP work.
                        widgetRefreshTrigger.scheduleRefresh()
                    }
                },
                label = "Copied ${contactIds.size} to $targetListName",
            )
        }
        // WIDGET-06: membership copied — who-is-due changed. Only fire on the
        // success path (non-empty label signals the copy actually happened).
        if (result.label.isNotEmpty()) {
            widgetRefreshTrigger.scheduleRefresh()
        }
        return result
    }
}
