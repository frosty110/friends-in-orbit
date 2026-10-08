package app.orbit.domain.model

import app.orbit.data.entity.ListEntity
import app.orbit.data.entity.ListMembershipEntity

/**
 * The memberships that say a person is "on" a list (LIST-24): the ones whose
 * list is not archived. An archived list is out of your orbit (it never
 * surfaces anyone), so no screen names it beside a person, counts it as a
 * list they are on, or reads a rhythm from it. Search across everyone left
 * archived lists out from the start; the contact picker, Contact detail and
 * the call log did not, so a person on an archived list read "On Old
 * friends" while adding people, and someone on no other list was missing
 * from "Not on a list".
 *
 * The rows themselves stay: archive is reversible, and Restore brings every
 * membership back whole (LIST-02). [alsoKeep] re-admits lists a screen is
 * acting on directly, whatever their state: the contact picker's target
 * list, which can be archived and must still hide its own members.
 */
fun List<ListMembershipEntity>.onActiveLists(
    lists: List<ListEntity>,
    alsoKeep: Set<Long> = emptySet(),
): List<ListMembershipEntity> {
    val active = lists.filterNot { it.isArchived }.mapTo(HashSet()) { it.id }
    return filter { it.listId in active || it.listId in alsoKeep }
}
