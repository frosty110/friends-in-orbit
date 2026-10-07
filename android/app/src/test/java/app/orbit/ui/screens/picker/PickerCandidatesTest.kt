package app.orbit.ui.screens.picker

import app.orbit.domain.contactFixture
import app.orbit.domain.listFixture
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The candidate-set rules that decide who the contact picker shows and which
 * lists its "On a list" filter offers. Until 2026-10-06 they lived inside
 * `ContactPickerViewModel.buildPickerContacts` behind a dispatcher-hopping
 * pipeline and were pinned nowhere; they are pure top-level functions now,
 * tested on the plain JVM.
 */
class PickerCandidatesTest {

    private fun row(id: Long, listIds: Set<Long> = emptySet()) = PickerContact(
        contactId = id,
        displayName = "Person $id",
        phone = "+1555000$id",
        photoUri = null,
        isIgnored = false,
        callCount = 0,
        lastCallAt = null,
        firstSeenByAppAt = Instant.parse("2026-01-01T00:00:00Z"),
        listIds = listIds,
        listNames = emptyList(),
        isCommonlyCalled = false,
        isRarelyCalled = false,
        isRecentlyAdded = false,
        isLongGap = false
    )

    private val built = listOf(
        row(1, listIds = setOf(1L)),
        row(2),
        row(3, listIds = setOf(2L)),
        row(4, listIds = setOf(1L, 2L))
    )

    // ─── The mode's narrowing ───────────────────────────────────────────────

    @Test
    fun `Add mode hides people already on the target list`() {
        assertEquals(
            listOf(2L, 3L),
            pickerCandidates(built, PickerMode.Add, targetListId = 1L, sourceListId = null).map {
                it.contactId
            }
        )
    }

    @Test
    fun `Add mode without a target keeps everyone`() {
        // A malformed route lands on NotFound; the candidate rule itself does
        // not guess a list.
        assertEquals(
            built,
            pickerCandidates(built, PickerMode.Add, targetListId = null, sourceListId = null)
        )
    }

    @Test
    fun `Move mode offers only members of the source list`() {
        assertEquals(
            listOf(3L, 4L),
            pickerCandidates(
                built,
                PickerMode.Move,
                targetListId = 1L,
                sourceListId = 2L
            ).map { it.contactId }
        )
    }

    @Test
    fun `Copy and Relink keep the full set`() {
        assertEquals(
            built,
            pickerCandidates(built, PickerMode.Copy, targetListId = 1L, sourceListId = null)
        )
        assertEquals(
            built,
            pickerCandidates(built, PickerMode.Relink, targetListId = null, sourceListId = null)
        )
    }

    // ─── Who may be offered at all ──────────────────────────────────────────

    @Test
    fun `orphans are never candidates outside Relink`() {
        val contacts = listOf(
            contactFixture(id = 1L, isOrphaned = true),
            contactFixture(id = 2L)
        )
        assertEquals(
            listOf(
                2L
            ),
            pickerCandidateEntities(contacts, PickerMode.Add, relinkContactId = null).map {
                it.id
            }
        )
        assertEquals(
            listOf(
                2L
            ),
            pickerCandidateEntities(contacts, PickerMode.Copy, relinkContactId = null).map {
                it.id
            }
        )
    }

    @Test
    fun `Relink offers only live phone contacts other than the orphan`() {
        val contacts = listOf(
            contactFixture(id = 10L, phoneContactId = 100L, isOrphaned = true), // the orphan itself
            contactFixture(id = 20L, phoneContactId = 200L), // the one acceptable pick
            contactFixture(id = 21L), // no device contact behind it
            contactFixture(id = 22L, phoneContactId = 220L, isIgnored = true),
            contactFixture(id = 23L, phoneContactId = 230L, isArchived = true),
            contactFixture(id = 24L, phoneContactId = 240L, isOrphaned = true)
        )
        assertEquals(
            listOf(20L),
            pickerCandidateEntities(
                contacts,
                PickerMode.Relink,
                relinkContactId = 10L
            ).map { it.id },
            "the same rule the merge applies (RelinkContactUseCase.isRelinkTarget)"
        )
    }

    @Test
    fun `Relink without the orphan id falls back to the live set`() {
        val contacts = listOf(contactFixture(id = 1L, isOrphaned = true), contactFixture(id = 2L))
        assertEquals(
            listOf(
                2L
            ),
            pickerCandidateEntities(contacts, PickerMode.Relink, relinkContactId = null).map {
                it.id
            }
        )
    }

    // ─── PICK-01: the "On a list" filter's lists ────────────────────────────

    @Test
    fun `the On a list filter leaves out the target, the source and archived lists`() {
        val lists = listOf(
            listFixture(id = 1L, name = "Target"),
            listFixture(id = 2L, name = "Source"),
            listFixture(id = 3L, name = "Old crew", isArchived = true),
            listFixture(id = 4L, name = "Family")
        )
        assertEquals(
            listOf(PickerListSummary(id = 4L, name = "Family")),
            availablePickerLists(lists, targetListId = 1L, sourceListId = 2L)
        )
    }

    @Test
    fun `without a source list only the target is left out`() {
        val lists =
            listOf(listFixture(id = 1L, name = "Target"), listFixture(id = 2L, name = "Family"))
        assertEquals(
            listOf(
                2L
            ),
            availablePickerLists(lists, targetListId = 1L, sourceListId = null).map {
                it.id
            }
        )
    }
}
