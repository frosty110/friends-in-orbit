package app.orbit.domain.usecase

import app.orbit.data.db.TransactionRunner
import app.orbit.data.repository.ContactRepository
import app.orbit.data.repository.ListRepository
import app.orbit.data.repository.RuleTemplateRepository
import app.orbit.domain.clock.Clock
import app.orbit.domain.rule.RuleParams
import app.orbit.domain.rule.resolveParamsFor
import java.time.Duration
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

/**
 * Negative-penalty analog of [SkipContactUseCase] — CORE-03 "swipe right brings
 * contact forward". Subtracts a sooner delta from `nextDueAt`, clamped to not
 * go below `now` (immediate re-surfacing is acceptable, but a negative
 * `nextDueAt` would corrupt cross-list membership math).
 *
 * The sooner delta uses half the rule template's `skipPenaltyHours` as a
 * symmetric-ish counterpart — if skip pushes N hours forward, sooner pulls
 * N/2 hours back. Clamped to at least 1 hour so the user always sees the
 * effect.
 *
 * Scope mirrors [SkipContactUseCase]:
 *   - `listId != null` — only that list's membership.
 *   - `listId == null` — every list the contact is on (cross-list propagation,
 *     DOM-06 intent).
 *
 * H6 fix — uses [ListRepository.updateNextDueAt] so a "sooner" (negative skip)
 * never bumps `skipCount`. The previous reuse of `incrementSkipCount` polluted
 * downstream consumers (badges, dampening, analytics) that read `skipCount`
 * semantically.
 */
class SurfaceSoonerUseCase @Inject constructor(
    private val txRunner: TransactionRunner,
    private val contactRepo: ContactRepository,
    private val listRepo: ListRepository,
    private val ruleTemplateRepo: RuleTemplateRepository,
    private val clock: Clock,
    private val json: Json,
) {

    /**
     * H7 fix — returns [MutationResult] so missing-row races are structurally
     * surfaced rather than silently swallowed. Aggregation logic mirrors
     * [SkipContactUseCase].
     */
    suspend operator fun invoke(contactId: Long, listId: Long? = null): MutationResult {
        val contact = contactRepo.observeById(contactId).first()
            ?: return MutationResult.MembershipMissing
        val memberships = listRepo.observeMembershipsForContact(contactId).first()
        val targets = if (listId == null) memberships else memberships.filter { it.listId == listId }
        val now = clock.now()

        var aggregate: MutationResult = MutationResult.Success
        for (membership in targets) {
            // Review follow-up #2 — H7 contract: a list/template that vanished
            // mid-flight is a precondition-missing race, not a silent success.
            // Flip the aggregate to MembershipMissing before continuing so the
            // caller can distinguish a true Success from "preconditions vanished
            // → zero side effects".
            val list = listRepo.getById(membership.listId) ?: run {
                aggregate = MutationResult.MembershipMissing
                continue
            }
            val templateId = list.ruleTemplateId ?: run {
                aggregate = MutationResult.MembershipMissing
                continue
            }
            val template = ruleTemplateRepo.getById(templateId) ?: run {
                aggregate = MutationResult.MembershipMissing
                continue
            }
            val params = resolveParamsFor(contact, list, template, json)

            val skipPenaltyHours = when (params) {
                is RuleParams.KeepInTouch -> params.skipPenaltyHours
                is RuleParams.LateNight -> params.skipPenaltyHours
                is RuleParams.Energize -> params.skipPenaltyHours
            }
            // Sooner delta = half the skip penalty, clamped to at least 1 hour
            // so the user always observes the effect.
            val soonerDeltaHours = (skipPenaltyHours / 2).coerceAtLeast(1)
            val basis = maxOf(membership.nextDueAt ?: now, now)
            val candidate = basis.minus(Duration.ofHours(soonerDeltaHours.toLong()))
            val newDue = if (candidate.isBefore(now)) now else candidate

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
        return aggregate
    }
}
