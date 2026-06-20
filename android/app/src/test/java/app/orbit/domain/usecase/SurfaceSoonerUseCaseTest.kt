package app.orbit.domain.usecase

import app.orbit.data.db.TransactionRunner
import app.orbit.domain.FakeContactRepository
import app.orbit.domain.FakeListRepository
import app.orbit.domain.FakeRuleTemplateRepository
import app.orbit.domain.JsonProvider
import app.orbit.domain.clock.TestClock
import app.orbit.domain.contactFixture
import app.orbit.domain.listFixture
import app.orbit.domain.membershipFixture
import app.orbit.domain.ruleTemplateFixture
import app.orbit.domain.rule.RuleParams
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * CORE-03 — [SurfaceSoonerUseCase] pulls a contact's `nextDueAt` earlier by half
 * the rule template's skip penalty, writing via `updateNextDueAt` (NOT
 * incrementSkipCount, so `skipCount` is never bumped) and clamping so the result
 * never lands before `now`.
 *
 * KeepInTouch defaults: skipPenaltyHours = 24 → sooner delta = 12h.
 */
class SurfaceSoonerUseCaseTest {

    private val T0: Instant = Instant.parse("2026-01-01T12:00:00Z")
    private val params = RuleParams.KeepInTouch() // skipPenaltyHours = 24 → sooner delta 12h

    private val passThruTx = object : TransactionRunner {
        override suspend fun <T> withTransaction(block: suspend () -> T): T = block()
    }

    private fun useCase(
        contactRepo: FakeContactRepository,
        listRepo: FakeListRepository,
        templateRepo: FakeRuleTemplateRepository,
    ) = SurfaceSoonerUseCase(passThruTx, contactRepo, listRepo, templateRepo, TestClock(T0), JsonProvider.json)

    @Test
    fun `listId null pulls every membership sooner`() = runTest {
        val priorDue = T0.plus(Duration.ofHours(100)) // far future → no clamp
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
        val templateRepo = FakeRuleTemplateRepository(listOf(ruleTemplateFixture(id = 1L, params = params)))

        val result = useCase(contactRepo, listRepo, templateRepo)(contactId = 1L, listId = null)

        assertEquals(MutationResult.Success, result)
        assertEquals(3, listRepo.updateNextDueAtCalls.size, "sooner applied to every membership")
        assertEquals(setOf(10L, 20L, 30L), listRepo.updateNextDueAtCalls.map { it.listId }.toSet())
        // sooner never bumps skipCount.
        assertTrue(listRepo.incrementSkipCalls.isEmpty(), "sooner must not increment skipCount")
    }

    @Test
    fun `listId non-null pulls only that membership sooner`() = runTest {
        val priorDue = T0.plus(Duration.ofHours(100))
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
        val templateRepo = FakeRuleTemplateRepository(listOf(ruleTemplateFixture(id = 1L, params = params)))

        useCase(contactRepo, listRepo, templateRepo)(contactId = 1L, listId = 20L)

        val args = listRepo.updateNextDueAtCalls.single()
        assertEquals(20L, args.listId)
        assertEquals(1L, args.contactId)
    }

    @Test
    fun `new due equals basis minus half the skip penalty`() = runTest {
        val priorDue = T0.plus(Duration.ofHours(100))
        val contactRepo = FakeContactRepository(listOf(contactFixture(id = 1L)))
        val listRepo = FakeListRepository(listOf(listFixture(id = 10L, ruleTemplateId = 1L)))
        listRepo.seedMemberships(listOf(membershipFixture(contactId = 1L, listId = 10L, nextDueAt = priorDue)))
        val templateRepo = FakeRuleTemplateRepository(listOf(ruleTemplateFixture(id = 1L, params = params)))

        useCase(contactRepo, listRepo, templateRepo)(contactId = 1L, listId = 10L)

        // 24h skip penalty → 12h sooner delta.
        val expected = priorDue.minus(Duration.ofHours(12))
        assertEquals(expected, listRepo.updateNextDueAtCalls.single().newNextDueAt)
    }

    @Test
    fun `result is clamped to now, never before it`() = runTest {
        // nextDueAt only 6h out, sooner delta 12h → naive result would be 6h in the
        // past; must clamp up to now.
        val priorDue = T0.plus(Duration.ofHours(6))
        val contactRepo = FakeContactRepository(listOf(contactFixture(id = 1L)))
        val listRepo = FakeListRepository(listOf(listFixture(id = 10L, ruleTemplateId = 1L)))
        listRepo.seedMemberships(listOf(membershipFixture(contactId = 1L, listId = 10L, nextDueAt = priorDue)))
        val templateRepo = FakeRuleTemplateRepository(listOf(ruleTemplateFixture(id = 1L, params = params)))

        useCase(contactRepo, listRepo, templateRepo)(contactId = 1L, listId = 10L)

        assertEquals(T0, listRepo.updateNextDueAtCalls.single().newNextDueAt, "clamped to now")
    }

    @Test
    fun `missing contact returns MembershipMissing with no writes`() = runTest {
        val contactRepo = FakeContactRepository(emptyList())
        val listRepo = FakeListRepository(listOf(listFixture(id = 10L, ruleTemplateId = 1L)))
        listRepo.seedMemberships(listOf(membershipFixture(contactId = 1L, listId = 10L)))
        val templateRepo = FakeRuleTemplateRepository(listOf(ruleTemplateFixture(id = 1L, params = params)))

        val result = useCase(contactRepo, listRepo, templateRepo)(contactId = 1L, listId = null)

        assertEquals(MutationResult.MembershipMissing, result)
        assertTrue(listRepo.updateNextDueAtCalls.isEmpty(), "missing contact → no writes")
    }
}
