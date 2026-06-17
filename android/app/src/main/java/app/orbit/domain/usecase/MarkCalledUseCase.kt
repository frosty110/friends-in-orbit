package app.orbit.domain.usecase

import app.orbit.data.entity.CallEventEntity
import app.orbit.data.repository.CallEventRepository
import app.orbit.data.repository.ContactRepository
import app.orbit.data.repository.ListRepository
import app.orbit.data.repository.RuleTemplateRepository
import app.orbit.domain.WidgetRefreshTrigger
import app.orbit.domain.clock.Clock
import app.orbit.domain.rule.ContactSnapshot
import app.orbit.domain.rule.RuleContext
import app.orbit.domain.rule.engineFor
import app.orbit.domain.rule.resolveParamsFor
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

/**
 * Records that a call occurred with the given contact and updates `nextDueAt`
 * across every list that contact is a member of — cross-list propagation (DOM-06).
 *
 * The writes land in a single Room `@Transaction`-backed repository call
 * (`CallEventRepository.markCalledAtomic`) so a crash mid-operation cannot leave
 * `CallEvent` inserted without the membership updates, or vice-versa.
 *
 * This use case does the per-membership cooldown computation on the JVM side
 * (pure engine call + params lookup), then hands the result map to the atomic
 * DAO method — the DAO does the actual DB writes.
 *
 * The per-membership `ruleTemplateRepo.getById(templateId)` call
 * inside the `memberships.associate { ... }` loop hits an in-memory
 * `StateFlow<Map<Long, RuleTemplateEntity>>` cache instead of issuing a DAO
 * query per membership. Templates are 3 rows seeded once at first DB open and
 * are immutable in v1, so the cache cannot drift. The N+1 against
 * `rule_templates` (one query per list a contact belongs to) is closed.
 */
// Widened to `open class` so test fixtures
// (`StubMarkCalledUseCase` in `Fakes.kt`) can subclass and intercept invoke()
// without spinning up the full engine chain. Production behavior is unchanged —
// Hilt resolves an `open class` identically to a `class`, and no `final` member
// is overridden by the production graph.
open class MarkCalledUseCase @Inject constructor(
    private val contactRepo: ContactRepository,
    private val listRepo: ListRepository,
    private val callEventRepo: CallEventRepository,
    private val ruleTemplateRepo: RuleTemplateRepository,
    private val clock: Clock,
    private val json: Json,
    private val widgetRefreshTrigger: WidgetRefreshTrigger = WidgetRefreshTrigger { },
) {

    open suspend operator fun invoke(contactId: Long, callEvent: CallEventEntity): MutationResult {
        // H7 fix — surface "contact vanished" structurally instead of swallowing
        // the race silently. Callers that don't inspect the return type still
        // compile (they ignore [MutationResult.Success]); future callers can
        // distinguish a true no-op from "the contact was deleted between
        // dispatch and write".
        val contact = contactRepo.observeById(contactId).first()
            ?: return MutationResult.MembershipMissing
        val memberships = listRepo.observeMembershipsForContact(contactId).first()

        val nextDueByListId: Map<Long, Instant?> = memberships.associate { membership ->
            val list = listRepo.getById(membership.listId)
            val templateId = list?.ruleTemplateId
            val nextDue: Instant? = if (list == null || templateId == null) {
                null
            } else {
                val template = ruleTemplateRepo.getById(templateId)
                if (template == null) {
                    null
                } else {
                    val params = resolveParamsFor(contact, list, template, json)
                    val engine = engineFor(params)
                    val snapshot = ContactSnapshot(
                        id = contact.id,
                        isIgnored = contact.isIgnored,
                        pausedUntil = contact.pausedUntil,
                    )
                    // Build RuleContext with the new call as lastCall — forces engine to
                    // compute the cooldown starting from *this* call.
                    val ctx = RuleContext(
                        lastCallAt = callEvent.occurredAt,
                        lastCallDurationSec = callEvent.durationSeconds,
                        lastCallDirection = callEvent.direction,
                        lastCallSource = callEvent.source,
                        skipCount = 0,                         // reset on mark-called
                        params = params,
                    )
                    engine.nextDue(snapshot, ctx, clock)
                }
            }
            membership.listId to nextDue
        }

        // Single atomic DAO call — insert CallEvent, touch contact, update every
        // ListMembership.nextDueAt. Lives on CallEventRepository (not Contact)
        // per the shipped interface; impl wraps the multi-table write
        // in db.withTransaction { … } (see CallEventRepositoryImpl).
        callEventRepo.markCalledAtomic(
            contactId = contactId,
            event = callEvent,
            nextDueByListId = nextDueByListId,
        )
        // Review follow-up #2 — H7 contract: if every membership vanished
        // between the contact read and the atomic write, the call event still
        // landed but no membership was rescheduled. Surface that as
        // MembershipMissing so the caller can distinguish a true cross-list
        // success from "the contact had no memberships left".
        return if (memberships.isEmpty()) {
            MutationResult.MembershipMissing
        } else {
            // WIDGET-06: at least one membership was rescheduled — who-is-due changed.
            widgetRefreshTrigger.scheduleRefresh()
            MutationResult.Success
        }
    }
}
