package app.orbit.domain.usecase

import app.orbit.data.entity.ContactEntity
import app.orbit.data.entity.ListMembershipEntity
import app.orbit.data.repository.ContactRepository
import app.orbit.data.repository.ListRepository
import app.orbit.data.repository.RuleTemplateRepository
import app.orbit.domain.WidgetRefreshTrigger
import app.orbit.domain.clock.Clock
import app.orbit.domain.rule.RuleParams
import app.orbit.domain.rule.resolveParamsFor
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

/**
 * Applies a skip to the given contact (DOM-07). Increments the target
 * `ListMembership.skipCount` and bumps `nextDueAt` by the resolved template's
 * `skipPenaltyHours`.
 *
 * Scope:
 *   - `listId != null` — apply only to that list's membership
 *   - `listId == null` — apply to every list the contact is on
 *
 * The repository's `incrementSkipCount` does the row-level write atomically; the
 * use case computes the new `nextDueAt` on the JVM side and hands it in.
 */
class SkipContactUseCase @Inject constructor(
    private val contactRepo: ContactRepository,
    private val listRepo: ListRepository,
    private val ruleTemplateRepo: RuleTemplateRepository,
    private val clock: Clock,
    private val json: Json,
    private val widgetRefreshTrigger: WidgetRefreshTrigger = WidgetRefreshTrigger { },
) {

    /**
     * H7 fix — returns [MutationResult] so the contact-vanished race is
     * structurally surfaced. The previous `?: return` shape made the missing
     * contact case indistinguishable from a successful no-op. Per-membership
     * `MembershipMissing` results from the repository are folded into the
     * aggregate: if any single membership write reports missing, the overall
     * result reports missing; otherwise [MutationResult.Success]. Callers that
     * ignore the return value still compile.
     *
     * The new time is counted from one clock read. From 2026-10-08 (the
     * calendar-day fix) to the same day's owner round, the card passed its
     * own instant here so the "when" in its snackbar matched the move; the
     * snackbar no longer says when (CARD-02), so the parameter went with it.
     */
    suspend operator fun invoke(contactId: Long, listId: Long? = null): MutationResult {
        val contact = contactRepo.observeById(contactId).first()
            ?: return MutationResult.MembershipMissing
        val memberships = listRepo.observeMembershipsForContact(contactId).first()
        val targets = if (listId == null) memberships else memberships.filter { it.listId == listId }
        val now = clock.now()

        var aggregate: MutationResult = MutationResult.Success
        // ListRepository.incrementSkipCount takes non-nullable listId —
        // we loop per-membership here for the listId == null ("skip on all lists") case.
        for (membership in targets) {
            // Review follow-up #2 — H7 contract: a list/template that vanished
            // mid-flight is a precondition-missing race, not a silent success.
            // Flip the aggregate to MembershipMissing before continuing so the
            // caller can distinguish a true Success from "preconditions vanished
            // → zero side effects".
            val newDue = nextDueAfterLater(contact, membership, now) ?: run {
                aggregate = MutationResult.MembershipMissing
                continue
            }

            val result = listRepo.incrementSkipCount(
                contactId = contactId,
                listId = membership.listId,
                newNextDueAt = newDue,
            )
            if (result is MutationResult.MembershipMissing) aggregate = result
        }
        // WIDGET-06: skip changes who-is-due on the success path.
        if (aggregate == MutationResult.Success) {
            widgetRefreshTrigger.scheduleRefresh()
        }
        return aggregate
    }

    /** The one computation of a Later's new `nextDueAt`; null when its list or template is gone. */
    private suspend fun nextDueAfterLater(
        contact: ContactEntity,
        membership: ListMembershipEntity,
        now: Instant,
    ): Instant? {
        val list = listRepo.getById(membership.listId) ?: return null
        val templateId = list.ruleTemplateId ?: return null
        val template = ruleTemplateRepo.getById(templateId) ?: return null
        val params = resolveParamsFor(contact, list, template, json)

        val skipPenaltyHours = when (params) {
            is RuleParams.KeepInTouch -> params.skipPenaltyHours
            is RuleParams.LateNight   -> params.skipPenaltyHours
            is RuleParams.Energize    -> params.skipPenaltyHours
        }
        // Clamp the skip basis to `now` so an overdue contact (nextDueAt already
        // in the past because the user hasn't opened the app in days) still gets
        // pushed forward by skipPenaltyHours FROM NOW, not from the stale past
        // value, which would leave the contact re-surfacing immediately after a
        // skip. DOM-07 intent: Skip pushes the person down the queue.
        val basis = maxOf(membership.nextDueAt ?: now, now)
        return basis.plus(Duration.ofHours(skipPenaltyHours.toLong()))
    }
}
