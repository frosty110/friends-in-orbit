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
 * Returns the next contact to surface on [listId] as a cold Flow (DOM-05). Emits on
 * every upstream change — membership add/remove, contact ignore toggle, call-event
 * insertion, list config edit, rule-template update.
 *
 * Per-candidate filters (short-circuit on any failure):
 *   1. Ignored contacts excluded (IGNORE-04)
 *   2. Archived contacts excluded (CONTACT-06 archive is a separate hide
 *      mechanism from ignore — see ArchiveContactUseCase KDoc for the
 *      two-flag rationale)
 *   3. Paused contacts excluded while `pausedUntil > clock.now()`
 *   4. Engine's `nextDue` returns null → contact skipped
 *
 * **ADR 0008 (2026-06-12):** active hours are no longer a surfacing gate. A list
 * checked outside its `activeHoursStart/End` window used to surface nobody
 * ([SurfaceResult.NothingEligible]); now the window is a soft, list-level
 * preference that only nudges ranking *across* lists (see [WidgetSurfaceUseCase]
 * and [timeOfDayPenalty]). Within a single list the window is uniform, so this
 * use case simply ignores it and surfaces the due-ordered queue at any clock
 * time. (The window still bounds notification timing — that path is unchanged.)
 *
 * **Tide marker (2026-05-08):** future-due candidates are no longer dropped.
 * The previous `if (nextDue.isAfter(now)) skip` filter is gone — the queue is
 * an infinite rotation per the list rule, and the use case's job is to pick the
 * head of that rotation, not to declare "you're done." The UI reads the
 * resulting [SurfaceResult.Found.nextDueAt] vs `clock.now()` to label the card
 * eyebrow as either `due today` or `ahead of today`.
 *
 * Ordering among surviving candidates (unchanged):
 *   1. `nextDueAt` ASC (earliest due first — most overdue / closest-future first)
 *   2. `lastCalledAt` ASC (longer-silent first for ties)
 *   3. `contact.id` ASC (deterministic final tiebreak)
 *
 * Emits one of three [SurfaceResult] variants:
 *   - [SurfaceResult.Found] — head of queue + engine-computed `nextDueAt`
 *   - [SurfaceResult.NoMembers] — list has zero non-archived non-ignored
 *     memberships (user has not added anyone, or has archived/ignored everyone)
 *   - [SurfaceResult.NothingEligible] — list has visible members but none is
 *     surfaceable right now (every member is paused, the rule template is
 *     missing, or engine `nextDue` is null for all). Active hours are no longer
 *     a trigger (ADR 0008).
 *
 * IGNORE-07 (un-ignore preserves memberships): this use case never writes
 * memberships — it reads. When a contact transitions `isIgnored: true → false`,
 * their `ListMembership` rows are unchanged in the DB, so the next Flow emission
 * re-surfaces them with their prior state intact. The same logic holds for
 * `isArchived: true → false` (UnarchiveContactUseCase resurfaces the contact
 * without touching memberships).
 *
 * **Archive amendment (2026-04-26):** the original surface filtered only
 * `isIgnored == false`. The `isArchived == false` clause was added additively —
 * IGNORE-04 / IGNORE-05 ignored-exclusion behaviour is unchanged.
 */
class SurfaceNextUseCase @Inject constructor(
    private val contactRepo: ContactRepository,
    private val listRepo: ListRepository,
    private val callEventRepo: CallEventRepository,
    private val ruleTemplateRepo: RuleTemplateRepository,
    private val clock: Clock,
    private val json: Json,
) {

    operator fun invoke(listId: Long): Flow<SurfaceResult> =
        combine(
            listRepo.observeMembersOfList(listId),                  // list-scoped — for skipCount
            contactRepo.observeForListMembers(listId),              // H3 — list-scoped contact flow
            listRepo.observeById(listId),                           // H3 — single-list reactive observer
            callEventRepo.observeLatestPerContactInList(listId),    // M4 — per-contact MAX(occurredAt) aggregate
        ) { memberships, contacts, list, latestPerContact ->
            // Index contacts by id for O(1) membership-to-contact joins. The list-scoped
            // contact flow already filters down to members of `listId`, so this map only
            // ever holds the focused list's members.
            val contactsById: Map<Long, ContactEntity> = contacts.associateBy { it.id }

            // "Visible" members = memberships whose contact exists in the list-scoped
            // contact flow AND is neither archived nor ignored. The user's mental model
            // of "the people I put in this list" excludes archived/ignored contacts —
            // they are functionally invisible. Zero visible members → NoMembers.
            val visibleMembers = memberships.filter { membership ->
                val contact = contactsById[membership.contactId] ?: return@filter false
                !contact.isIgnored && !contact.isArchived
            }
            if (visibleMembers.isEmpty()) return@combine SurfaceResult.NoMembers

            // List/template/active-hours misconfiguration is "list is here but nothing
            // surfaces right now" — folds under NothingEligible per the user-visible
            // empty-state contract.
            list ?: return@combine SurfaceResult.NothingEligible
            val templateId = list.ruleTemplateId ?: return@combine SurfaceResult.NothingEligible
            val template = ruleTemplateRepo.getById(templateId)
                ?: return@combine SurfaceResult.NothingEligible

            val now = clock.now()

            val candidates = visibleMembers.mapNotNull { membership ->
                val contact = contactsById.getValue(membership.contactId)

                contact.pausedUntil?.let { if (it.isAfter(now)) return@mapNotNull null }

                // M4 — direct map lookup for the latest call event per contact, no
                // full event list groupBy + maxByOrNull. Members with zero events
                // return null. Full CallEventEntity (not just timestamp) so the rule
                // engines preserve their short-call / incoming-call signals.
                val lastCall = latestPerContact[contact.id]

                // SWIPE-FIX (2026-06-08) — surface by the PERSISTED
                // `membership.nextDueAt`, not a fresh engine recomputation. That
                // column is the source of truth for "when is this contact next due
                // on this list": MarkCalledUseCase, SkipContactUseCase (push later),
                // and SurfaceSoonerUseCase (pull earlier) all write it. The previous
                // code ignored it and recomputed `nextDue` purely from `lastCallAt`
                // + `skipCount` every emission — so swipe-right (Sooner, which never
                // touches skipCount/lastCallAt) had ZERO effect on ordering, and
                // swipe-left (Skip) had no effect on cold-start contacts (no call
                // history → engine returns `now` regardless of skipCount). Both
                // swipes re-surfaced the same head.
                //
                // The engine result is the COLD-START default, consulted ONLY when
                // the membership has never been scheduled (`nextDueAt == null`:
                // freshly added, never called/skipped/sooner'd). Once any mutation
                // writes a value, that persisted value drives surfacing.
                //
                // Tide marker (2026-05-08): future `nextDue` is no longer a drop — it
                // surfaces as the "ahead of today" tail of the queue. Only a null
                // engine result (reachable solely for ignored contacts, already
                // filtered out above) still drops the candidate.
                val nextDue: Instant = membership.nextDueAt ?: run {
                    val params = resolveParamsFor(contact, list, template, json)
                    val engine = engineFor(params)
                    val snapshot = ContactSnapshot(
                        id = contact.id,
                        isIgnored = contact.isIgnored,
                        pausedUntil = contact.pausedUntil,
                    )
                    val ctx = buildContext(membership, lastCall, params)
                    engine.nextDue(snapshot, ctx, clock) ?: return@mapNotNull null
                }

                Candidate(
                    contact = contact,
                    nextDue = nextDue,
                    lastCalledAt = lastCall?.occurredAt,
                )
            }

            val head = candidates
                .sortedWith(
                    compareBy<Candidate> { it.nextDue }
                        .thenBy { it.lastCalledAt ?: Instant.MIN }
                        // Deterministic final tiebreak — cold-start contacts all share
                        // Instant.MIN on the previous key; contact.id ASC matches the
                        // "no randomness" invariant.
                        .thenBy { it.contact.id }
                )
                .firstOrNull()
                ?: return@combine SurfaceResult.NothingEligible

            SurfaceResult.Found(contact = head.contact, nextDueAt = head.nextDue)
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
