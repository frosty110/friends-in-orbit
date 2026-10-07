package app.orbit.domain.usecase

import app.orbit.data.db.TransactionRunner
import app.orbit.data.repository.ListRepository
import app.orbit.domain.WidgetRefreshTrigger
import app.orbit.domain.clock.Clock
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/**
 * BROWSE-08: a drag in Browse's sequence. Moves one person to a new place in
 * the order the card brings people up, by writing new `nextDueAt` times
 * through [ListRepository.updateNextDueAt], the column the shared order sorts
 * by ([SurfaceOrder]). Nothing else is stored: the card, Home's "Next up" and
 * the nudge read the new order on their next emission, and the next call,
 * Later or Sooner moves the person again, as it moves everyone. That is the
 * owner's "not permanent" (vision/flows/owner-review-2026-10-07.md, 6 and 7).
 *
 * [placeAfter] is the person the moved one should follow, or null for the top
 * of the sequence. Browse passes the row now above the dropped one; with a
 * filter on, people the filter hides may sit between them, and the moved
 * person lands right after [placeAfter], so the visible order is the order the
 * user dropped. The top is the top of the whole sequence.
 *
 * Which times are written ([planMove]):
 *  - **To the top:** one write, no later than now and earlier than the old
 *    first person, so they are "Up now" and first.
 *  - **After someone:** one write strictly between that person's time and the
 *    next person's. The moved person takes the "Up now" of the one they follow:
 *    placed after someone who is up now, they are up now too (between the last
 *    up-now person and the first future one, they stay up now, last).
 *  - **No room for one write:** two neighbours with the same time (a smart
 *    list stamps everyone it adds with the same instant), times a millisecond
 *    apart, or a neighbour who has never been scheduled and never called (their
 *    time is "now" at every read, [SequencedContact.movesWithClock], so nothing
 *    fixed can follow them for long). Among up-now people the run from the top
 *    down to the moved person is re-spaced a millisecond apart, each keeping
 *    their own time where it already fits and only ever moving earlier, so
 *    everyone stays up now and the order is exact. Among future people the run
 *    after the moved person is pushed a millisecond later each until the order
 *    holds. Either way the fewest rows that make the order exact are written.
 *
 * All writes and the `lists.dueCount` recompute share one transaction, so a
 * failed write leaves every row as it was (ADR 0006 Rule 2). [Result.Moved]
 * carries an inverse that restores every written row's exact prior
 * `nextDueAt` (null included) and `skipCount`, the card-loop Undo's
 * [ListRepository.restoreMembershipSchedule] precedent.
 */
class ReorderSequenceUseCase @Inject constructor(
    private val txRunner: TransactionRunner,
    private val listRepo: ListRepository,
    private val surfaceQueue: SurfaceQueueUseCase,
    private val clock: Clock,
    // WIDGET-06: a move can change who leads the deck the widgets show.
    // Defaulted so JVM fixtures can construct the use case positionally.
    private val widgetRefreshTrigger: WidgetRefreshTrigger = WidgetRefreshTrigger { }
) {

    sealed interface Result {
        /** Written. [earlier] says which way they moved; [inverse] undoes every write exactly. */
        data class Moved(val earlier: Boolean, val inverse: suspend () -> Unit) : Result

        /** They are already there: nothing written. */
        data object Unchanged : Result

        /**
         * The person, or the one to follow, is no longer in the sequence
         * (paused, ignored, removed meanwhile), or their row vanished at write
         * time: nothing written. The caller reports a failed save (rules.md
         * Code 3).
         */
        data object Missing : Result
    }

    suspend operator fun invoke(listId: Long, contactId: Long, placeAfter: Long?): Result {
        val sequence = surfaceQueue(listId).first()
        val oldIndex = sequence.indexOfFirst { it.contact.id == contactId }
        val newOrder = sequence.withMove(contactId, placeAfter) ?: return Result.Missing
        val newIndex = newOrder.indexOfFirst { it.contact.id == contactId }
        if (newIndex == oldIndex) return Result.Unchanged

        val now = clock.now()
        val writes = planMove(newOrder, newIndex, now)
        val priors = newOrder.filter { it.contact.id in writes }.map { it.membership }

        val written = try {
            txRunner.withTransaction {
                writes.forEach { (id, time) ->
                    // A row that vanished between the read and the write rolls
                    // the whole move back rather than leaving half of it.
                    val result = listRepo.updateNextDueAt(id, listId, time)
                    if (result is MutationResult.MembershipMissing) throw RowVanished()
                }
                listRepo.recomputeDueCountForList(listId, now)
            }
            true
        } catch (vanished: RowVanished) {
            false
        }
        if (!written) return Result.Missing
        widgetRefreshTrigger.scheduleRefresh()

        return Result.Moved(
            earlier = newIndex < oldIndex,
            inverse = {
                txRunner.withTransaction {
                    priors.forEach { prior ->
                        listRepo.restoreMembershipSchedule(
                            contactId = prior.contactId,
                            listId = listId,
                            nextDueAt = prior.nextDueAt,
                            skipCount = prior.skipCount
                        )
                    }
                    listRepo.recomputeDueCountForList(listId, clock.now())
                }
                widgetRefreshTrigger.scheduleRefresh()
            }
        )
    }

    private class RowVanished : RuntimeException()

    companion object {
        /**
         * This sequence with [contactId] taken out and put right after
         * [placeAfter] (null: first), or null when either is not in it. The
         * one definition of where a drop lands: the use case plans its writes
         * from it, and Browse shows it while those writes land.
         */
        fun List<SequencedContact>.withMove(
            contactId: Long,
            placeAfter: Long?
        ): List<SequencedContact>? {
            val moving = firstOrNull { it.contact.id == contactId } ?: return null
            val others = filter { it.contact.id != contactId }
            val index = if (placeAfter == null) {
                0
            } else {
                val anchor = others.indexOfFirst { it.contact.id == placeAfter }
                if (anchor < 0) return null
                anchor + 1
            }
            return others.toMutableList().apply { add(index, moving) }
        }

        /**
         * The sequence as it will read once a drop's writes land: [withMove]'s
         * order with [planMove]'s times on the people it writes. Browse shows
         * this the moment a row is dropped, so the moved person's "when" is
         * right from the first frame instead of their old one for the length
         * of a database write. Null when [withMove] is, or when the drop
         * changes nothing.
         */
        fun List<SequencedContact>.afterMove(
            contactId: Long,
            placeAfter: Long?,
            now: Instant
        ): List<SequencedContact>? {
            val newOrder = withMove(contactId, placeAfter) ?: return null
            val index = newOrder.indexOfFirst { it.contact.id == contactId }
            if (index == indexOfFirst { it.contact.id == contactId }) return null
            return newOrder.applying(planMove(newOrder, index, now))
        }

        private const val STEP_MS = 1L

        /**
         * The times to write so that [newOrder] (the whole sequence with the
         * moved person already at [index]) is exactly the order the shared
         * comparator produces, keyed by contact id. Pure, so every case is
         * unit-tested without a database. See the class KDoc for the rules.
         *
         * Throws [IllegalStateException] if the plan would not produce
         * [newOrder]: by construction it cannot happen, and a loud guard is
         * better than a silent wrong order (rules.md Code 3).
         */
        internal fun planMove(
            newOrder: List<SequencedContact>,
            index: Int,
            now: Instant
        ): Map<Long, Instant> {
            val moving = newOrder[index]
            val prev = newOrder.getOrNull(index - 1)
            val next = newOrder.getOrNull(index + 1)
            // At the top they are up now; otherwise they take the state of
            // the person they follow.
            val upNow = prev == null || !prev.nextDueAt.isAfter(now)

            val single = singleWrite(prev, next, upNow, now)
            if (single != null && single.keepsOrder(newOrder, index, upNow, now)) {
                return mapOf(moving.contact.id to single)
            }
            val plan = when {
                upNow -> respaceUpNow(newOrder, index, now)
                else -> pushFutureLater(newOrder, index)
            }
            check(plan.produces(newOrder)) { "reorder plan does not produce the dropped order" }
            return plan
        }

        /** One time strictly between the neighbours, or null when there is no room for one. */
        private fun singleWrite(
            prev: SequencedContact?,
            next: SequencedContact?,
            upNow: Boolean,
            now: Instant
        ): Instant? {
            if (prev == null) {
                val ceiling = if (next == null) now else minOf(now, next.nextDueAt)
                return ceiling.minusMillis(STEP_MS)
            }
            // Nothing fixed stays behind a time that moves with the clock.
            if (prev.movesWithClock) return null
            val lower = prev.nextDueAt
            if (next == null) return lower.plusMillis(STEP_MS)
            var upper = next.nextDueAt
            if (upNow) upper = minOf(upper, now.plusMillis(STEP_MS))
            val gap = upper.toEpochMilli() - lower.toEpochMilli()
            if (gap < 2 * STEP_MS) return null
            return Instant.ofEpochMilli(lower.toEpochMilli() + gap / 2)
        }

        private fun Instant.keepsOrder(
            newOrder: List<SequencedContact>,
            index: Int,
            upNow: Boolean,
            now: Instant
        ): Boolean {
            if (upNow && isAfter(now)) return false
            return mapOf(newOrder[index].contact.id to this).produces(newOrder)
        }

        /**
         * Up-now people from the top down to the moved one: the moved person
         * gets the latest time that still sits before the next person (and
         * no later than now); each person above keeps their own time where it
         * is already a millisecond earlier than the one below, and moves just
         * earlier otherwise. People who move with the clock get a fixed time,
         * since the moved person may not follow them otherwise. Everyone below
         * the moved person keeps their time.
         */
        private fun respaceUpNow(
            newOrder: List<SequencedContact>,
            index: Int,
            now: Instant
        ): Map<Long, Instant> {
            val next = newOrder.getOrNull(index + 1)
            val cap = if (next == null) now else minOf(next.nextDueAt.minusMillis(STEP_MS), now)
            val writes = linkedMapOf(newOrder[index].contact.id to cap)
            var below = cap
            for (j in index - 1 downTo 0) {
                val person = newOrder[j]
                val time = minOf(person.nextDueAt, below.minusMillis(STEP_MS))
                if (person.membership.nextDueAt != time) writes[person.contact.id] = time
                below = time
            }
            return writes
        }

        /**
         * Future people: the moved one a millisecond after the person they
         * follow, then each following person who no longer sorts after the
         * one above a millisecond later, until the order holds again.
         */
        private fun pushFutureLater(
            newOrder: List<SequencedContact>,
            index: Int
        ): Map<Long, Instant> {
            val prev = newOrder[index - 1]
            var above = prev.nextDueAt.plusMillis(STEP_MS)
            val writes = linkedMapOf(newOrder[index].contact.id to above)
            for (j in index + 1 until newOrder.size) {
                val person = newOrder[j]
                if (person.nextDueAt.isAfter(above)) break
                above = above.plusMillis(STEP_MS)
                writes[person.contact.id] = above
            }
            return writes
        }

        /** Whether [newOrder], with these times written, sorts back into itself. */
        private fun Map<Long, Instant>.produces(newOrder: List<SequencedContact>): Boolean =
            newOrder.applying(this).sortedWith(SurfaceOrder.COMPARATOR).map { it.contact.id } ==
                newOrder.map { it.contact.id }

        /** These people with [writes]' times stored, as the next read will see them. */
        private fun List<SequencedContact>.applying(
            writes: Map<Long, Instant>
        ): List<SequencedContact> = map { person ->
            val written = writes[person.contact.id] ?: return@map person
            person.copy(
                nextDueAt = written,
                membership = person.membership.copy(nextDueAt = written)
            )
        }
    }
}
