package app.orbit.domain.usecase

import app.orbit.data.db.TransactionRunner
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
 * CORE-03, CARD-02: [SurfaceSoonerUseCase] pulls a contact's `nextDueAt`
 * earlier by [moveAmountFor] the list's size (1 day on a list of up to 10
 * people, 3 days on 11 or more; until 2026-11-10 half the rule's skip
 * penalty, 12 hours on Keep in touch), writing via `updateNextDueAt` (NOT
 * incrementSkipCount, so `skipCount` is never bumped, H6) and never landing
 * before `now`.
 */
class SurfaceSoonerUseCaseTest {

    private val T0: Instant = Instant.parse("2026-01-01T12:00:00Z")

    private val passThruTx = object : TransactionRunner {
        override suspend fun <T> withTransaction(block: suspend () -> T): T = block()
    }

    private fun useCase(contactRepo: FakeContactRepository, listRepo: FakeListRepository) =
        SurfaceSoonerUseCase(passThruTx, contactRepo, listRepo, TestClock(T0))

    /** [others] more people beside contact 1 on [listId], so the list counts `others + 1`. */
    private fun othersOn(listId: Long, others: Int): List<ListMembershipEntity> =
        (1..others).map { membershipFixture(contactId = 1_000L + listId * 100 + it, listId = listId) }

    @Test
    fun `listId null pulls every membership sooner`() = runTest {
        val priorDue = T0.plus(Duration.ofDays(10)) // far future → no clamp
        val contactRepo = FakeContactRepository(listOf(contactFixture(id = 1L)))
        val listRepo = FakeListRepository(
            listOf(
                listFixture(id = 10L, ruleTemplateId = 1L),
                listFixture(id = 20L, ruleTemplateId = 1L),
                listFixture(id = 30L, ruleTemplateId = 1L),
            ),
        )
        listRepo.seedMemberships(
            listOf(
                membershipFixture(contactId = 1L, listId = 10L, nextDueAt = priorDue),
                membershipFixture(contactId = 1L, listId = 20L, nextDueAt = priorDue),
                membershipFixture(contactId = 1L, listId = 30L, nextDueAt = priorDue),
            ),
        )

        val result = useCase(contactRepo, listRepo)(contactId = 1L, listId = null)

        assertEquals(MutationResult.Success, result)
        assertEquals(3, listRepo.updateNextDueAtCalls.size, "sooner applied to every membership")
        assertEquals(setOf(10L, 20L, 30L), listRepo.updateNextDueAtCalls.map { it.listId }.toSet())
        // sooner never bumps skipCount.
        assertTrue(listRepo.incrementSkipCalls.isEmpty(), "sooner must not increment skipCount")
    }

    @Test
    fun `listId non-null pulls only that membership sooner`() = runTest {
        val priorDue = T0.plus(Duration.ofDays(10))
        val contactRepo = FakeContactRepository(listOf(contactFixture(id = 1L)))
        val listRepo = FakeListRepository(
            listOf(
                listFixture(id = 10L, ruleTemplateId = 1L),
                listFixture(id = 20L, ruleTemplateId = 1L),
            ),
        )
        listRepo.seedMemberships(
            listOf(
                membershipFixture(contactId = 1L, listId = 10L, nextDueAt = priorDue),
                membershipFixture(contactId = 1L, listId = 20L, nextDueAt = priorDue),
            ),
        )

        useCase(contactRepo, listRepo)(contactId = 1L, listId = 20L)

        val args = listRepo.updateNextDueAtCalls.single()
        assertEquals(20L, args.listId)
        assertEquals(1L, args.contactId)
    }

    /** Contact 1 due at [priorDue] on list 10 of [people] people; the time a Sooner writes. */
    private suspend fun soonerOnListOf(people: Int, priorDue: Instant?): Instant {
        val contactRepo = FakeContactRepository(listOf(contactFixture(id = 1L)))
        val listRepo = FakeListRepository(listOf(listFixture(id = 10L, ruleTemplateId = 1L)))
        listRepo.seedMemberships(
            listOf(membershipFixture(contactId = 1L, listId = 10L, nextDueAt = priorDue)) + othersOn(10L, people - 1),
        )
        assertEquals(MutationResult.Success, useCase(contactRepo, listRepo)(contactId = 1L, listId = 10L))
        return listRepo.updateNextDueAtCalls.single().newNextDueAt
    }

    @Test
    fun `CARD-02 - a list of 10 people moves Sooner by 1 day, 11 people by 3 days`() = runTest {
        val priorDue = T0.plus(Duration.ofDays(10))
        assertEquals(priorDue.minus(Duration.ofDays(1)), soonerOnListOf(people = 1, priorDue = priorDue), "1 person")
        assertEquals(priorDue.minus(Duration.ofDays(1)), soonerOnListOf(people = 10, priorDue = priorDue), "10 people")
        assertEquals(priorDue.minus(Duration.ofDays(3)), soonerOnListOf(people = 11, priorDue = priorDue), "11 people")
    }

    @Test
    fun `CARD-02 - Sooner never lands before now`() = runTest {
        // Due in 6 hours on a small list: a day earlier would be 18 hours ago.
        assertEquals(T0, soonerOnListOf(people = 4, priorDue = T0.plus(Duration.ofHours(6))), "clamped to now")
        // Never scheduled counts as now, and now less a day is clamped too.
        assertEquals(T0, soonerOnListOf(people = 4, priorDue = null), "cold start stays at now")
    }

    @Test
    fun `CARD-02 - Sooner on someone due in 2 days on a big list lands at now`() = runTest {
        // Three days earlier than 2 days out is yesterday; Sooner makes them
        // up now instead.
        assertEquals(T0, soonerOnListOf(people = 25, priorDue = T0.plus(Duration.ofDays(2))))
    }

    @Test
    fun `CARD-02 - on every list, each membership moves by its own list's amount`() = runTest {
        val priorDue = T0.plus(Duration.ofDays(10))
        val contactRepo = FakeContactRepository(listOf(contactFixture(id = 1L)))
        val listRepo = FakeListRepository(listOf(listFixture(id = 10L), listFixture(id = 20L)))
        listRepo.seedMemberships(
            listOf(
                membershipFixture(contactId = 1L, listId = 10L, nextDueAt = priorDue),
                membershipFixture(contactId = 1L, listId = 20L, nextDueAt = priorDue),
            ) + othersOn(10L, 9) + othersOn(20L, 10),
        )

        useCase(contactRepo, listRepo)(contactId = 1L, listId = null)

        val byList = listRepo.updateNextDueAtCalls.associate { it.listId to it.newNextDueAt }
        assertEquals(priorDue.minus(Duration.ofDays(1)), byList[10L], "10 people: a day")
        assertEquals(priorDue.minus(Duration.ofDays(3)), byList[20L], "11 people: three days")
    }

    @Test
    fun `a Sooner leaves skipCount as it was (H6)`() = runTest {
        val contactRepo = FakeContactRepository(listOf(contactFixture(id = 1L)))
        val listRepo = FakeListRepository(listOf(listFixture(id = 10L)))
        listRepo.seedMemberships(
            listOf(membershipFixture(contactId = 1L, listId = 10L, nextDueAt = T0.plus(Duration.ofDays(10)), skipCount = 2)),
        )

        useCase(contactRepo, listRepo)(contactId = 1L, listId = 10L)

        assertEquals(2, listRepo.observeMembershipsForContact(1L).first().single().skipCount)
    }

    @Test
    fun `missing contact returns MembershipMissing with no writes`() = runTest {
        val contactRepo = FakeContactRepository(emptyList())
        val listRepo = FakeListRepository(listOf(listFixture(id = 10L, ruleTemplateId = 1L)))
        listRepo.seedMemberships(listOf(membershipFixture(contactId = 1L, listId = 10L)))

        val result = useCase(contactRepo, listRepo)(contactId = 1L, listId = null)

        assertEquals(MutationResult.MembershipMissing, result)
        assertTrue(listRepo.updateNextDueAtCalls.isEmpty(), "missing contact → no writes")
    }

    @Test
    fun `a person no longer on the list returns MembershipMissing, not a silent success`() = runTest {
        val contactRepo = FakeContactRepository(listOf(contactFixture(id = 1L)))
        val listRepo = FakeListRepository(listOf(listFixture(id = 10L), listFixture(id = 20L)))
        listRepo.seedMemberships(listOf(membershipFixture(contactId = 1L, listId = 20L)))

        val result = useCase(contactRepo, listRepo)(contactId = 1L, listId = 10L)

        assertEquals(MutationResult.MembershipMissing, result)
        assertTrue(listRepo.updateNextDueAtCalls.isEmpty(), "nothing written")
    }
}
