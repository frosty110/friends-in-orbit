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
 * **Sibling of [SurfaceNextUseCase]: the whole sequence, not just its head.**
 *
 * Returns everyone on [listId] in the order the card brings them up, as a cold
 * `Flow<List<SequencedContact>>`, each with the time they come up. Same four
 * upstream observers as [SurfaceNextUseCase] and, since 2026-10-07, the same
 * code for the filters and the order ([SurfaceOrder]): that use case emits this
 * sequence's head. Browse shows the sequence (BROWSE-07) and CardFeed counts it
 * (the card's queue size); [ReorderSequenceUseCase] reads it to place a
 * dragged person between their new neighbours (BROWSE-08).
 *
 * Until 2026-10-07 this file kept its own copy of the filters and the
 * comparator under an invariant that "divergence is a defect", and they
 * diverged anyway: the SWIPE-FIX (persisted `nextDueAt` first) reached only
 * [SurfaceNextUseCase], so this queue recomputed every member from the engine,
 * ignored Later, Sooner and a call's reschedule, and Browse's order was not the
 * card's. Sharing the code is the fix; see [SurfaceOrder] for the filters, the
 * tide marker and the three-key order.
 *
 * **ADR 0008 (2026-06-12):** active hours are not a queue gate. The list's
 * window only nudges ranking *across* lists ([WidgetSurfaceUseCase]).
 *
 * Emits `emptyList()` when the list is missing, has no rule template, the
 * template has been deleted, or nobody survives the filters (Browse then shows
 * its other groups only; the card says "All quiet for now").
 *
 * **Read-only.** Does not mutate `skipCount`, `pausedUntil`, or any
 * membership / contact state.
 */
class SurfaceQueueUseCase @Inject constructor(
    private val contactRepo: ContactRepository,
    private val listRepo: ListRepository,
    private val callEventRepo: CallEventRepository,
    private val ruleTemplateRepo: RuleTemplateRepository,
    private val clock: Clock,
    private val json: Json,
) {

    operator fun invoke(listId: Long): Flow<List<SequencedContact>> =
        combine(
            listRepo.observeMembersOfList(listId),                  // list-scoped — for skipCount
            contactRepo.observeForListMembers(listId),              // H3 — list-scoped contact flow
            listRepo.observeById(listId),                           // H3 — single-list reactive observer
            callEventRepo.observeLatestPerContactInList(listId),    // M4 — per-contact MAX(occurredAt) aggregate
        ) { memberships, contacts, list, latestPerContact ->
            list ?: return@combine emptyList()
            val templateId = list.ruleTemplateId ?: return@combine emptyList()
            val template = ruleTemplateRepo.getById(templateId) ?: return@combine emptyList()

            // The list-scoped contact flow already holds only this list's members.
            val contactsById: Map<Long, ContactEntity> = contacts.associateBy { it.id }
            SurfaceOrder.sequence(
                memberships = memberships,
                contactsById = contactsById,
                list = list,
                template = template,
                latestPerContact = latestPerContact,
                now = clock.now(),
                json = json,
            )
        }
}
