package app.orbit.domain.usecase

import app.orbit.data.entity.ListMembershipEntity
import app.orbit.domain.FakeContactRepository
import app.orbit.domain.FakeListRepository
import app.orbit.domain.clock.TestClock
import app.orbit.domain.contactFixture
import app.orbit.domain.listFixture
import app.orbit.domain.membershipFixture
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Behavioral tests for [SkipContactUseCase], Later (DOM-07, CARD-02).
 *
 * Invariants:
 *   - listId == null → applies to ALL memberships (loop), each by its own
 *     list's amount
 *   - listId != null → applies only to that membership
 *   - the amount is [moveAmountFor] the list's size: 1 day on a list of up
 *     to 10 people, 3 days on 11 or more (the owner's numbers, 2026-11-10;
 *     until then the list rule's `skipPenaltyHours`, 24 hours on Keep in
 *     touch)
 *   - newNextDueAt = max(nextDueAt ?: now, now) + amount: an overdue or
 *     never-scheduled person counts from now
 *   - skipCount goes up by one per Later, as before
 *   - nobody to move (no contact, no membership) → MembershipMissing, no writes
 */
class SkipContactUseCaseTest {

    private val T0: Instant = Instant.parse("2026-01-01T12:00:00Z")

    private fun useCase(contactRepo: FakeContactRepository, listRepo: FakeListRepository) =
        SkipContactUseCase(contactRepo, listRepo, TestClock(T0))

    /** [others] more people beside contact 1 on [listId], so the list counts `others + 1`. */
    private fun othersOn(listId: Long, others: Int): List<ListMembershipEntity> =
        (1..others).map { membershipFixture(contactId = 1_000L + listId * 100 + it, listId = listId) }

    // ============================================================================
    // Scope
    // ============================================================================

    @Test
    fun `listId null applies skip to every membership`() = runTest {
        val priorDue = T0.plus(Duration.ofHours(1))
        val contactRepo = FakeContactRepository(listOf(contactFixture(id = 1L)))
        val listRepo = FakeListRepository(listOf(
            listFixture(id = 10L, ruleTemplateId = 1L),
            listFixture(id = 20L, ruleTemplateId = 1L),
            listFixture(id = 30L, ruleTemplateId = 1L),
        ))
        listRepo.seedMemberships(listOf(
            membershipFixture(contactId = 1L, listId = 10L, nextDueAt = priorDue),
            membershipFixture(contactId = 1L, listId = 20L, nextDueAt = priorDue),
            membershipFixture(contactId = 1L, listId = 30L, nextDueAt = priorDue),
        ))

        val result = useCase(contactRepo, listRepo)(contactId = 1L, listId = null)

        assertEquals(MutationResult.Success, result)
        // Three memberships → three incrementSkipCount calls.
        assertEquals(3, listRepo.incrementSkipCalls.size, "skip applied to every membership when listId == null")
        val listIds = listRepo.incrementSkipCalls.map { it.listId }.toSet()
        assertEquals(setOf(10L, 20L, 30L), listIds, "every membership listId covered")
    }

    @Test
    fun `listId non-null applies skip only to that membership`() = runTest {
        val priorDue = T0.plus(Duration.ofHours(1))
        val contactRepo = FakeContactRepository(listOf(contactFixture(id = 1L)))
        val listRepo = FakeListRepository(listOf(
            listFixture(id = 10L, ruleTemplateId = 1L),
            listFixture(id = 20L, ruleTemplateId = 1L),
        ))
        listRepo.seedMemberships(listOf(
            membershipFixture(contactId = 1L, listId = 10L, nextDueAt = priorDue),
            membershipFixture(contactId = 1L, listId = 20L, nextDueAt = priorDue),
        ))

        useCase(contactRepo, listRepo)(contactId = 1L, listId = 20L)

        assertEquals(1, listRepo.incrementSkipCalls.size, "scoped skip → exactly one increment")
        val args = listRepo.incrementSkipCalls.single()
        assertEquals(20L, args.listId, "scoped skip targets the requested list")
        assertEquals(1L, args.contactId)
    }

    // ============================================================================
    // CARD-02: how far, by the list's size
    // ============================================================================

    /** Contact 1 due at [priorDue] on list 10 of [people] people; the time a Later writes. */
    private suspend fun laterOnListOf(people: Int, priorDue: Instant?): Instant {
        val contactRepo = FakeContactRepository(listOf(contactFixture(id = 1L)))
        val listRepo = FakeListRepository(listOf(listFixture(id = 10L, ruleTemplateId = 1L)))
        listRepo.seedMemberships(
            listOf(membershipFixture(contactId = 1L, listId = 10L, nextDueAt = priorDue)) + othersOn(10L, people - 1),
        )
        assertEquals(MutationResult.Success, useCase(contactRepo, listRepo)(contactId = 1L, listId = 10L))
        return listRepo.incrementSkipCalls.single().newNextDueAt
    }

    @Test
    fun `CARD-02 - a list of 10 people moves Later by 1 day, 11 people by 3 days`() = runTest {
        val priorDue = T0.plus(Duration.ofHours(5))
        assertEquals(priorDue.plus(Duration.ofDays(1)), laterOnListOf(people = 1, priorDue = priorDue), "1 person")
        assertEquals(priorDue.plus(Duration.ofDays(1)), laterOnListOf(people = 10, priorDue = priorDue), "10 people")
        assertEquals(priorDue.plus(Duration.ofDays(3)), laterOnListOf(people = 11, priorDue = priorDue), "11 people")
        assertEquals(priorDue.plus(Duration.ofDays(3)), laterOnListOf(people = 40, priorDue = priorDue), "40 people")
    }

    @Test
    fun `CARD-02 - the amount is the one moveAmountFor gives`() {
        assertEquals(Duration.ofDays(1), moveAmountFor(0))
        assertEquals(Duration.ofDays(1), moveAmountFor(10))
        assertEquals(Duration.ofDays(3), moveAmountFor(11))
    }

    @Test
    fun `CARD-02 - Later on someone overdue counts from now, not from the past time`() = runTest {
        // Due 5 days ago (the app was not opened): a day from that would still
        // be in the past and they would come straight back.
        assertEquals(T0.plus(Duration.ofDays(1)), laterOnListOf(people = 3, priorDue = T0.minus(Duration.ofDays(5))))
        assertEquals(T0.plus(Duration.ofDays(3)), laterOnListOf(people = 12, priorDue = T0.minus(Duration.ofDays(5))))
    }

    @Test
    fun `when previous nextDueAt is null use clock now as base`() = runTest {
        // Cold-start: membership has no nextDueAt yet → fallback to clock.now().
        assertEquals(T0.plus(Duration.ofDays(1)), laterOnListOf(people = 1, priorDue = null), "null nextDueAt → base = now")
    }

    @Test
    fun `CARD-02 - on every list, each membership moves by its own list's amount`() = runTest {
        val priorDue = T0.plus(Duration.ofHours(5))
        val contactRepo = FakeContactRepository(listOf(contactFixture(id = 1L)))
        val listRepo = FakeListRepository(listOf(listFixture(id = 10L), listFixture(id = 20L)))
        listRepo.seedMemberships(
            listOf(
                membershipFixture(contactId = 1L, listId = 10L, nextDueAt = priorDue),
                membershipFixture(contactId = 1L, listId = 20L, nextDueAt = priorDue),
            ) + othersOn(10L, 2) + othersOn(20L, 14),
        )

        useCase(contactRepo, listRepo)(contactId = 1L, listId = null)

        val byList = listRepo.incrementSkipCalls.associate { it.listId to it.newNextDueAt }
        assertEquals(priorDue.plus(Duration.ofDays(1)), byList[10L], "3 people: a day")
        assertEquals(priorDue.plus(Duration.ofDays(3)), byList[20L], "15 people: three days")
    }

    @Test
    fun `a Later still counts one skip, on the list it moved`() = runTest {
        val contactRepo = FakeContactRepository(listOf(contactFixture(id = 1L)))
        val listRepo = FakeListRepository(listOf(listFixture(id = 10L), listFixture(id = 20L)))
        listRepo.seedMemberships(
            listOf(
                membershipFixture(contactId = 1L, listId = 10L, skipCount = 2),
                membershipFixture(contactId = 1L, listId = 20L, skipCount = 0),
            ),
        )

        useCase(contactRepo, listRepo)(contactId = 1L, listId = 10L)

        val rows = listRepo.observeMembershipsForContact(1L).first().associateBy { it.listId }
        assertEquals(3, rows.getValue(10L).skipCount, "the Later's own list")
        assertEquals(0, rows.getValue(20L).skipCount, "another list is untouched")
    }

    // ============================================================================
    // Nobody to move (H7, rules.md Code 3)
    // ============================================================================

    @Test
    fun `missing contact returns MembershipMissing with no skip writes`() = runTest {
        val contactRepo = FakeContactRepository(emptyList())
        val listRepo = FakeListRepository(listOf(listFixture(id = 10L, ruleTemplateId = 1L)))
        listRepo.seedMemberships(listOf(membershipFixture(contactId = 1L, listId = 10L)))

        val result = useCase(contactRepo, listRepo)(contactId = 1L, listId = null)

        assertEquals(MutationResult.MembershipMissing, result)
        assertTrue(
            listRepo.incrementSkipCalls.isEmpty(),
            "missing contact → no incrementSkipCount calls (race-with-delete safety)",
        )
    }

    @Test
    fun `a person no longer on the list returns MembershipMissing, not a silent success`() = runTest {
        // They left list 10 between the card's render and the tap. Until
        // 2026-11-10 the loop ran zero times and reported Success, and the
        // card said they moved.
        val contactRepo = FakeContactRepository(listOf(contactFixture(id = 1L)))
        val listRepo = FakeListRepository(listOf(listFixture(id = 10L), listFixture(id = 20L)))
        listRepo.seedMemberships(listOf(membershipFixture(contactId = 1L, listId = 20L)))

        val result = useCase(contactRepo, listRepo)(contactId = 1L, listId = 10L)

        assertEquals(MutationResult.MembershipMissing, result)
        assertTrue(listRepo.incrementSkipCalls.isEmpty(), "nothing written")
    }
}
