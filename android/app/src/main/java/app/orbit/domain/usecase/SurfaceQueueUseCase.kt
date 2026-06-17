package app.orbit.domain.usecase

import app.orbit.data.entity.CallEventEntity
import app.orbit.data.entity.ContactEntity
import app.orbit.data.entity.ListMembershipEntity
import app.orbit.data.repository.CallEventRepository
import app.orbit.data.repository.ContactRepository
import app.orbit.data.repository.ListRepository
import app.orbit.data.repository.RuleTemplateRepository
import app.orbit.domain.clock.Clock
import app.orbit.domain.rule.ContactSnapshot
import app.orbit.domain.rule.RuleContext
import app.orbit.domain.rule.RuleParams
import app.orbit.domain.rule.engineFor
import app.orbit.domain.rule.resolveParamsFor
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.json.Json

/**
 * **Sibling — not a replacement — of [SurfaceNextUseCase].**
 *
 * Returns the *full* ordered candidate list that [SurfaceNextUseCase] would
 * walk through for [listId], as a cold `Flow<List<ContactEntity>>`. Same four
 * upstream observers, same per-candidate filter chain, same three-key
 * comparator. The only divergence is at the terminal step: this use case maps
 * to the full sorted list (`map { it.contact }`) instead of the head
 * (`firstOrNull()?.contact`).
 *
 * Tide marker (mirrors SurfaceNextUseCase): future `nextDue` is no longer a
 * drop. It surfaces as the "ahead of today" tail of the queue. Only a null
 * engine result still drops the candidate.
 *
 * Filter order (identical to [SurfaceNextUseCase] — divergence is a defect;
 * comment markers carried over verbatim so future audits grep cleanly):
 *   1. Ignored contacts excluded (IGNORE-04)
 *   2. Archived contacts excluded (CONTACT-06 contract amendment)
 *   3. Paused contacts excluded while `pausedUntil > clock.now()`
 *   4. Engine's `nextDue` returns null → contact skipped
 *
 * **ADR 0008 (2026-06-12):** active hours are no longer a queue gate. The list's
 * window is a soft, list-level preference that only nudges ranking *across*
 * lists ([WidgetSurfaceUseCase]); within this single-list queue it is uniform,
 * so the queue renders at any clock time. Parity with [SurfaceNextUseCase] holds.
 *
 * Ordering among survivors (identical comparator, three keys):
 *   1. `nextDueAt` ASC (earliest due first)
 *   2. `lastCalledAt` ASC NULLS LAST (longer-silent first; `Instant.MIN` for nulls)
 *   3. `contact.id` ASC (deterministic final tiebreak — no randomness)
 *
 * Emits `emptyList()` when the list is missing, has no rule template, the
 * template has been deleted, or no candidate survives filtering. UI maps the
 * empty case to a warm "all caught up"-style copy; that mapping is the screen's
 * concern, not this use case's.
 *
 * **Read-only.** Does not mutate `skipCount`, `pausedUntil`, or any
 * membership / contact state. The Card View loop (which DOES mutate via
 * `MarkSkippedUseCase` etc.) is unaffected by this use case's emissions.
 *
 * **Invariant:** [SurfaceNextUseCase] MUST NOT be modified by the plan that
 * introduces this file. The two use cases share the filter+order contract;
 * any divergence is fixed here, never there.
 */
class SurfaceQueueUseCase @Inject constructor(
    private val contactRepo: ContactRepository,
    private val listRepo: ListRepository,
    private val callEventRepo: CallEventRepository,
    private val ruleTemplateRepo: RuleTemplateRepository,
    private val clock: Clock,
    private val json: Json,
) {

    operator fun invoke(listId: Long): Flow<List<ContactEntity>> =
        combine(
            listRepo.observeMembersOfList(listId),                  // list-scoped — for skipCount
            contactRepo.observeForListMembers(listId),              // H3 — list-scoped contact flow
            listRepo.observeById(listId),                           // H3 — single-list reactive observer
            callEventRepo.observeLatestPerContactInList(listId),    // M4 — per-contact MAX(occurredAt) aggregate
        ) { memberships, contacts, list, latestPerContact ->
            list ?: return@combine emptyList()
            val templateId = list.ruleTemplateId ?: return@combine emptyList()
            val template = ruleTemplateRepo.getById(templateId) ?: return@combine emptyList()

            val now = clock.now()

            // Index contacts by id for O(1) membership-to-contact joins. The list-scoped
            // contact flow already filters down to members of `listId`, so this map only
            // ever holds the focused list's members.
            val contactsById: Map<Long, ContactEntity> = contacts.associateBy { it.id }

            val candidates = memberships.mapNotNull { membership ->
                val contact = contactsById[membership.contactId] ?: return@mapNotNull null

                if (contact.isIgnored) return@mapNotNull null                         // IGNORE-04
                if (contact.isArchived) return@mapNotNull null                        // CONTACT-06
                contact.pausedUntil?.let { if (it.isAfter(now)) return@mapNotNull null }

                val params = resolveParamsFor(contact, list, template, json)
                val engine = engineFor(params)
                val snapshot = ContactSnapshot(
                    id = contact.id,
                    isIgnored = contact.isIgnored,
                    pausedUntil = contact.pausedUntil,
                )
                // M4 — direct map lookup for the latest call event per contact, no
                // full event list groupBy + maxByOrNull. Members with zero events
                // return null. Full CallEventEntity (not just timestamp) so the rule
                // engines preserve their short-call / incoming-call signals.
                val lastCall = latestPerContact[contact.id]
                val ctx = buildContext(membership, lastCall, params)
                val nextDue = engine.nextDue(snapshot, ctx, clock) ?: return@mapNotNull null

                Candidate(
                    contact = contact,
                    nextDue = nextDue,
                    lastCalledAt = lastCall?.occurredAt,
                )
            }

            candidates
                .sortedWith(
                    compareBy<Candidate> { it.nextDue }
                        .thenBy { it.lastCalledAt ?: Instant.MIN }
                        // Deterministic final tiebreak — cold-start contacts all share
                        // Instant.MIN on the previous key; contact.id ASC matches the
                        // "no randomness" invariant.
                        .thenBy { it.contact.id }
                )
                .map { it.contact }
        }

    private data class Candidate(
        val contact: ContactEntity,
        val nextDue: Instant,
        val lastCalledAt: Instant?,
    )

    private fun buildContext(
        membership: ListMembershipEntity,
        lastCall: CallEventEntity?,
        params: RuleParams,
    ): RuleContext = RuleContext(
        lastCallAt = lastCall?.occurredAt,
        lastCallDurationSec = lastCall?.durationSeconds ?: 0,
        lastCallDirection = lastCall?.direction,
        lastCallSource = lastCall?.source,
        skipCount = membership.skipCount,
        params = params,
        activeHoursStart = null,                      // active-hours filter is the use-case's job
        activeHoursEnd = null,                        // not the engine's; engines stay cooldown-only
    )
}
