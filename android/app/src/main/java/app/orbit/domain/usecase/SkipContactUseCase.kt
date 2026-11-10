package app.orbit.domain.usecase

import app.orbit.data.db.TransactionRunner
import app.orbit.data.entity.ListMembershipEntity
import app.orbit.data.repository.ContactRepository
import app.orbit.data.repository.ListRepository
import app.orbit.domain.WidgetRefreshTrigger
import app.orbit.domain.clock.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/**
 * Later (DOM-07, CARD-02): moves the person further out on a list by
 * [moveAmountFor] that list's size (a day on a list of up to 10 people,
 * three days on a bigger one) and increments the membership's `skipCount`.
 *
 * Scope:
 *   - `listId != null`: apply only to that list's membership (the card's case)
 *   - `listId == null`: apply to every list the contact is on, each moved by
 *     its own list's amount
 *
 * The repository's `incrementSkipCount` does the row-level write atomically; the
 * use case computes the new `nextDueAt` on the JVM side and hands it in.
 *
 * Until 2026-11-10 the amount was the list rule's `skipPenaltyHours`, read
 * through the list's template; the rule and its template are no longer read
 * here. `skipPenaltyHours` stays in `RuleParams` for the engines.
 *
 * `skipCount` still counts the Laters: the card's Undo restores it, and the
 * engines add `skipPenaltyHours` per Later when they compute a time
 * (`KeepInTouchEngine`, step 4, and its two siblings). Nothing there
 * overrides this move: the queue surfaces by the stored `nextDueAt`
 * (`SurfaceOrder`, SWIPE-FIX) and asks an engine only for a membership that
 * has none, which a Later never leaves; a call recomputes the time with
 * `skipCount = 0` and resets the column (`MarkCalledUseCase`).
 */
class SkipContactUseCase @Inject constructor(
    private val txRunner: TransactionRunner,
    private val contactRepo: ContactRepository,
    private val listRepo: ListRepository,
    private val clock: Clock,
    private val widgetRefreshTrigger: WidgetRefreshTrigger = WidgetRefreshTrigger { },
) {

    /**
     * H7 fix: returns [MutationResult] so the contact-vanished race is
     * structurally surfaced. The previous `?: return` shape made the missing
     * contact case indistinguishable from a successful no-op. Per-membership
     * `MembershipMissing` results from the repository are folded into the
     * aggregate: if any single membership write reports missing, the overall
     * result reports missing; otherwise [MutationResult.Success]. The card
     * reads it: a Later that moved nobody says so (rules.md Code 3, CARD-02).
     *
     * A move that finds no membership to move (the person left the list
     * between the card's render and the tap) is [MutationResult.MembershipMissing]
     * too. Until 2026-11-10 it was a Success with no write, and the card
     * said "Kai moved to later." about a move that never happened.
     *
     * The new time is counted from one clock read. From 2026-10-08 (the
     * calendar-day fix) to the same day's owner round, the card passed its
     * own instant here so the "when" in its snackbar matched the move; the
     * snackbar no longer says when (CARD-02), so the parameter went with it.
     */
    suspend operator fun invoke(contactId: Long, listId: Long? = null): MutationResult {
        contactRepo.observeById(contactId).first()
            ?: return MutationResult.MembershipMissing
        val memberships = listRepo.observeMembershipsForContact(contactId).first()
        val targets = if (listId == null) memberships else memberships.filter { it.listId == listId }
        if (targets.isEmpty()) return MutationResult.MembershipMissing
        // CARD-02: each list's size, from the query the Lists row reads.
        val memberCounts = listRepo.observeMemberCountsByListId().first()
        val now = clock.now()

        var aggregate: MutationResult = MutationResult.Success
        // ListRepository.incrementSkipCount takes non-nullable listId —
        // we loop per-membership here for the listId == null ("skip on all lists") case.
        for (membership in targets) {
            // Review follow-up #2, the H7 contract: a list whose rows vanished
            // mid-flight is a precondition-missing race, not a silent success.
            // The membership was just read, so its list counts at least one
            // person; no count means the list's rows went between the reads.
            val memberCount = memberCounts[membership.listId] ?: run {
                aggregate = MutationResult.MembershipMissing
                continue
            }

            // R2.B, as in SurfaceSoonerUseCase: the write and the list's dueCount
            // recompute in one transaction. A Later can move the only due person
            // out of the due set; until 2026-11-10 it left `lists.dueCount` one
            // high, so the nudge worker's gate (ListDao.dueCountForList) could
            // post a nudge for a list with nobody due until another write
            // recomputed it.
            val result = txRunner.withTransaction {
                val r = listRepo.incrementSkipCount(
                    contactId = contactId,
                    listId = membership.listId,
                    newNextDueAt = nextDueAfterLater(membership, now, moveAmountFor(memberCount)),
                )
                listRepo.recomputeDueCountForList(membership.listId, now)
                r
            }
            if (result is MutationResult.MembershipMissing) aggregate = result
        }
        // WIDGET-06: skip changes who-is-due on the success path.
        if (aggregate == MutationResult.Success) {
            widgetRefreshTrigger.scheduleRefresh()
        }
        return aggregate
    }

    /** The one computation of a Later's new `nextDueAt`. */
    private fun nextDueAfterLater(membership: ListMembershipEntity, now: Instant, amount: Duration): Instant {
        // Clamp the basis to `now` so an overdue contact (nextDueAt already
        // in the past because the user hasn't opened the app in days) is
        // still pushed out by the amount FROM NOW, not from the stale past
        // value, which would leave the contact re-surfacing immediately after
        // a Later. DOM-07 intent: Later pushes the person down the queue.
        val basis = maxOf(membership.nextDueAt ?: now, now)
        return basis.plus(amount)
    }
}
