package app.orbit.domain.model

import app.orbit.data.entity.ListMembershipEntity
import app.orbit.domain.listFixture
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * LIST-24: an archived list is out of your orbit, so its memberships do not
 * say a person is on it. The rows are kept, so Restore brings them back.
 */
class ActiveListMembershipsTest {

    private fun on(contactId: Long, listId: Long) = ListMembershipEntity(
        contactId = contactId,
        listId = listId,
        addedAt = Instant.parse("2026-01-01T00:00:00Z")
    )

    private val memberships = listOf(on(1, 1), on(1, 2), on(2, 2))

    @Test
    fun `memberships on an archived list are left out`() {
        val lists = listOf(listFixture(id = 1L), listFixture(id = 2L, isArchived = true))
        assertEquals(listOf(on(1, 1)), memberships.onActiveLists(lists))
    }

    @Test
    fun `restoring the list makes them count again`() {
        val lists = listOf(listFixture(id = 1L), listFixture(id = 2L, isArchived = false))
        assertEquals(memberships, memberships.onActiveLists(lists))
    }

    @Test
    fun `a list the screen acts on is kept even when archived`() {
        val lists = listOf(listFixture(id = 1L), listFixture(id = 2L, isArchived = true))
        assertEquals(memberships, memberships.onActiveLists(lists, alsoKeep = setOf(2L)))
    }
}
