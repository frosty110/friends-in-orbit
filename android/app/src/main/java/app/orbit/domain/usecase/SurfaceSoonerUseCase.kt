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
 * Sooner, the counterpart of [SkipContactUseCase] (CORE-03 "swipe right
 * brings contact forward", CARD-02): pulls `nextDueAt` earlier by
 * [moveAmountFor] the list's size, a day on a list of up to 10 people,
 * three days on a bigger one, and never earlier than now. On a small list
 * that can make the person the next one up.
 *
 * Until 2026-11-10 the amount was half the list rule's `skipPenaltyHours`
 * (12 hours on a Keep in touch list), at least an hour; the owner found
 * that "very short", and the one amount Later uses replaced it.
 *
 * Scope mirrors [SkipContactUseCase]:
 *   - `listId != null`: only that list's membership.
 *   - `listId == null`: every list the contact is on (cross-list propagation,
 *     DOM-06 intent), each moved by its own list's amount.
 *
 * H6 fix — uses [ListRepository.updateNextDueAt] so a "sooner" (negative skip)
 * never bumps `skipCount`. The previous reuse of `incrementSkipCount` polluted
 * what reads `skipCount` semantically (the rule engines' skip penalty).
 */
class SurfaceSoonerUseCase @Inject constructor(
    private val txRunner: TransactionRunner,
    private val contactRepo: ContactRepository,
    private val listRepo: ListRepository,
    private val clock: Clock,
    private val widgetRefreshTrigger: WidgetRefreshTrigger = WidgetRefreshTrigger { },
) {

    /**
     * H7 fix: returns [MutationResult] so missing-row races are structurally
     * surfaced rather than silently swallowed. Aggregation logic mirrors
     * [SkipContactUseCase], including a move that finds no membership to
     * move ([MutationResult.MembershipMissing], so the card says the Sooner
     * failed instead of "Kai moved sooner.", rules.md Code 3).
     *
     * One clock read, as in [SkipContactUseCase.invoke] (whose KDoc says why
     * the card no longer hands in an instant).
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
        for (membership in targets) {
            // The H7 contract, as in SkipContactUseCase: no count means the
            // list's rows went between the reads, so there is nobody to move.
            val memberCount = memberCounts[membership.listId] ?: run {
                aggregate = MutationResult.MembershipMissing
                continue
            }
            val newDue = nextDueAfterSooner(membership, now, moveAmountFor(memberCount))

            // H6 — direct nextDueAt write, no skipCount mutation. Sooner is a
            // negative skip; bumping skipCount on every sooner would corrupt
            // downstream consumers that read it semantically.
            //
            // WR-01 — wrap the per-membership pair (updateNextDueAt +
            // recomputeDueCountForList) in `txRunner.withTransaction` so the
            // dueCount column cannot drift from the membership's nextDueAt
            // across a process kill or a concurrent mutation that lands its
            // own recompute between the two writes. Closes the only ADR 0006
            // Rule 2 atomicity hole among the seven mutator use cases.
            // Pitfall 3: the block calls only suspending DAO/Repo methods —
            // no dispatcher switch inside the transaction.
            val result = txRunner.withTransaction {
                val r = listRepo.updateNextDueAt(
                    contactId = contactId,
                    listId = membership.listId,
                    nextDueAt = newDue,
                )
                // R2.B — keep `lists.dueCount` fresh after the
                // nextDueAt shift. SurfaceSooner pulls nextDueAt earlier;
                // can flip a membership from not-due → due. The recompute
                // is idempotent on no-change lists, so it's safe to run
                // even when `updateNextDueAt` returned MembershipMissing
                // (the membership vanished mid-flight, and the column
                // should reflect post-vanish state).
                listRepo.recomputeDueCountForList(membership.listId, now)
                r
            }
            if (result is MutationResult.MembershipMissing) aggregate = result
        }
        // WIDGET-06: a Sooner moves the person forward and can change who leads
        // the cross-list deck the widgets show; refresh on the success path,
        // as Later does (SkipContactUseCase). Without this the placed widgets
        // kept the old lead until an unrelated write or the hourly sweep.
        if (aggregate == MutationResult.Success) {
            widgetRefreshTrigger.scheduleRefresh()
        }
        return aggregate
    }

    /** The one computation of a Sooner's new `nextDueAt`. */
    private fun nextDueAfterSooner(membership: ListMembershipEntity, now: Instant, amount: Duration): Instant =
        // Never earlier than now: a person due in 2 days on a big list lands
        // at now, as does a cold-start one (no time yet counts as now).
        maxOf(now, (membership.nextDueAt ?: now).minus(amount))
}
