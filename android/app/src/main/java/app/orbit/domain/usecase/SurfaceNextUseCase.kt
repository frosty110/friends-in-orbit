package app.orbit.domain.usecase

import app.orbit.data.entity.ContactEntity
import app.orbit.data.repository.CallEventRepository
import app.orbit.data.repository.ContactRepository
import app.orbit.data.repository.ListRepository
import app.orbit.data.repository.RuleTemplateRepository
import app.orbit.domain.clock.Clock
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
 * The filters and the order are [SurfaceOrder]'s, shared with
 * [SurfaceQueueUseCase] since 2026-10-07 (BROWSE-07): this use case is that
 * sequence's head, so the card's person is always Browse's first row. The two
 * use cases kept separate copies until then and drifted (SurfaceOrder KDoc).
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

            // BROWSE-07: the one shared ordering (SurfaceOrder). Its head is the
            // card's person and the first row of Browse's sequence; the SWIPE-FIX
            // and tide-marker notes that lived here moved there with the code.
            val head = SurfaceOrder.sequence(
                memberships = visibleMembers,
                contactsById = contactsById,
                list = list,
                template = template,
                latestPerContact = latestPerContact,
                now = clock.now(),
                json = json,
            ).firstOrNull() ?: return@combine SurfaceResult.NothingEligible

            SurfaceResult.Found(contact = head.contact, nextDueAt = head.nextDueAt)
        }
}
