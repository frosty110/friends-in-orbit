package app.orbit.domain.usecase

import app.orbit.data.entity.CallEventEntity
import app.orbit.data.entity.ContactEntity
import app.orbit.data.entity.ListEntity
import app.orbit.data.entity.ListMembershipEntity
import app.orbit.data.entity.RuleTemplateEntity
import app.orbit.domain.clock.Clock
import app.orbit.domain.rule.ContactSnapshot
import app.orbit.domain.rule.RuleContext
import app.orbit.domain.rule.RuleParams
import app.orbit.domain.rule.engineFor
import app.orbit.domain.rule.resolveParamsFor
import java.time.Instant
import kotlinx.serialization.json.Json

/**
 * One person in a list's sequence: who comes up, in the order the card brings
 * them up (BROWSE-07).
 *
 * [nextDueAt] is the time the order is sorted by: the membership's persisted
 * `nextDueAt` when it has one, or the rule engine's cold-start answer when it
 * has never been scheduled. [membership] is the stored row, so a writer can
 * tell the two apart and an Undo can put back exactly what was there.
 */
data class SequencedContact(
    val contact: ContactEntity,
    val membership: ListMembershipEntity,
    val nextDueAt: Instant,
    val lastCalledAt: Instant?
) {
    /**
     * Never scheduled and never called: every engine answers "now" for this
     * person, so their time is re-read from the clock on every emission and
     * they sit after everyone whose time is fixed in the past. A fixed time
     * can be placed before them for good, never after them (BROWSE-08).
     */
    val movesWithClock: Boolean
        get() = membership.nextDueAt == null && lastCalledAt == null
}

/**
 * The one ordering of a list's people, shared by the card's head
 * ([SurfaceNextUseCase]) and the whole sequence ([SurfaceQueueUseCase], which
 * Browse and the card's queue size read). It lives here, once, because it
 * used to live in both use cases and they drifted: the SWIPE-FIX below
 * (2026-06-08) went into SurfaceNextUseCase only, so Browse's "Next up" kept
 * recomputing every time from the engine and ignored Later, Sooner and a
 * call's reschedule, and its first row was not the card's person (BROWSE-07,
 * found 2026-10-07). One function makes that drift unrepresentable.
 *
 * Filters, in order (short-circuit on the first that applies):
 *   1. Ignored contacts excluded (IGNORE-04)
 *   2. Archived contacts excluded (CONTACT-06)
 *   3. Paused contacts excluded while `pausedUntil > now`
 *   4. A never-scheduled member whose engine answers null is dropped
 *      (reachable only for ignored contacts, already excluded)
 *
 * Order ([COMPARATOR]): `nextDueAt` ASC, then `lastCalledAt` ASC with never
 * called first (`Instant.MIN`), then `contact.id` ASC as the deterministic
 * final tiebreak.
 */
internal object SurfaceOrder {

    val COMPARATOR: Comparator<SequencedContact> =
        compareBy<SequencedContact> { it.nextDueAt }
            .thenBy { it.lastCalledAt ?: Instant.MIN }
            // Deterministic final tiebreak: cold-start contacts all share
            // Instant.MIN on the previous key; contact.id ASC matches the
            // "no randomness" invariant.
            .thenBy { it.contact.id }

    /**
     * The list's sequence at [now]. [contactsById] must hold the members'
     * contacts; a membership whose contact is missing is skipped.
     *
     * [now] is read once by the caller and handed to the engines through a
     * fixed clock. The engines used to read the live clock per contact, so on
     * a millisecond boundary two cold-start people could get different "now"s
     * and swap places between the card's evaluation and Browse's, which then
     * disagreed about who is first (BROWSE-07, BROWSE-09).
     */
    fun sequence(
        memberships: List<ListMembershipEntity>,
        contactsById: Map<Long, ContactEntity>,
        list: ListEntity,
        template: RuleTemplateEntity,
        latestPerContact: Map<Long, CallEventEntity>,
        now: Instant,
        json: Json
    ): List<SequencedContact> {
        val fixedClock = object : Clock {
            override fun now(): Instant = now
        }
        return memberships.mapNotNull { membership ->
            val contact = contactsById[membership.contactId] ?: return@mapNotNull null

            if (contact.isIgnored) return@mapNotNull null // IGNORE-04
            if (contact.isArchived) return@mapNotNull null // CONTACT-06
            contact.pausedUntil?.let { if (it.isAfter(now)) return@mapNotNull null }

            // M4: the latest call event per contact, a direct map lookup. The
            // full CallEventEntity (not just its time) so the engines keep
            // their short-call / incoming-call / attempt signals.
            val lastCall = latestPerContact[contact.id]

            // SWIPE-FIX (2026-06-08): surface by the PERSISTED
            // `membership.nextDueAt`, not a fresh engine recomputation. That
            // column is the source of truth for "when is this contact next due
            // on this list": MarkCalledUseCase, SkipContactUseCase (Later),
            // SurfaceSoonerUseCase (Sooner) and ReorderSequenceUseCase (a drag
            // in Browse, BROWSE-08) all write it. Recomputing from `lastCallAt`
            // + `skipCount` on every emission made Sooner (which touches
            // neither) a no-op and Later a no-op for cold-start contacts.
            //
            // The engine result is the COLD-START default, consulted ONLY when
            // the membership has never been scheduled (`nextDueAt == null`:
            // freshly added, never called, moved or swiped).
            //
            // Tide marker (2026-05-08): a future time is not a drop; it is the
            // "ahead of today" tail of the sequence.
            val nextDue: Instant = membership.nextDueAt ?: run {
                val params = resolveParamsFor(contact, list, template, json)
                val engine = engineFor(params)
                val snapshot = ContactSnapshot(
                    id = contact.id,
                    isIgnored = contact.isIgnored,
                    pausedUntil = contact.pausedUntil
                )
                engine.nextDue(snapshot, contextFor(membership, lastCall, params), fixedClock)
                    ?: return@mapNotNull null
            }

            SequencedContact(
                contact = contact,
                membership = membership,
                nextDueAt = nextDue,
                lastCalledAt = lastCall?.occurredAt
            )
        }.sortedWith(COMPARATOR)
    }

    private fun contextFor(
        membership: ListMembershipEntity,
        lastCall: CallEventEntity?,
        params: RuleParams
    ): RuleContext = RuleContext(
        lastCallAt = lastCall?.occurredAt,
        lastCallDurationSec = lastCall?.durationSeconds ?: 0,
        lastCallDirection = lastCall?.direction,
        lastCallSource = lastCall?.source,
        skipCount = membership.skipCount,
        params = params,
        // Active hours are not a surfacing gate (ADR 0008).
        activeHoursStart = null,
        activeHoursEnd = null
    )
}
