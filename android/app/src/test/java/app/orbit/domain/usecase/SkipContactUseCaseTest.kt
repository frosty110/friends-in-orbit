package app.orbit.domain.usecase

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
 * Behavioral tests for [SkipContactUseCase] — DOM-07 skip penalty application.
 *
 * Invariants:
 *   - listId == null → applies to ALL memberships (loop)
 *   - listId != null → applies only to that membership
 *   - newNextDueAt = currentDue.plus(skipPenaltyHours)
 *   - currentDue == null → use clock.now() as base (cold start case)
 */
class SkipContactUseCaseTest {

    private val T0: Instant = Instant.parse("2026-01-01T12:00:00Z")
    private val params = RuleParams.KeepInTouch()  // skipPenaltyHours = 24

    // ============================================================================
    // Test 1 — listId == null applies to all memberships
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
        val templateRepo = FakeRuleTemplateRepository(listOf(ruleTemplateFixture(id = 1L, params = params)))
        val useCase = SkipContactUseCase(
            contactRepo, listRepo, templateRepo,
            TestClock(T0), JsonProvider.json,
        )

        useCase(contactId = 1L, listId = null)

        // Three memberships → three incrementSkipCount calls.
        assertEquals(3, listRepo.incrementSkipCalls.size, "skip applied to every membership when listId == null")
        val listIds = listRepo.incrementSkipCalls.map { it.listId }.toSet()
        assertEquals(setOf(10L, 20L, 30L), listIds, "every membership listId covered")
    }

    // ============================================================================
    // Test 2 — listId != null applies only to that membership
    // ============================================================================

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
        val templateRepo = FakeRuleTemplateRepository(listOf(ruleTemplateFixture(id = 1L, params = params)))
        val useCase = SkipContactUseCase(
            contactRepo, listRepo, templateRepo,
            TestClock(T0), JsonProvider.json,
        )

        useCase(contactId = 1L, listId = 20L)

        assertEquals(1, listRepo.incrementSkipCalls.size, "scoped skip → exactly one increment")
        val args = listRepo.incrementSkipCalls.single()
        assertEquals(20L, args.listId, "scoped skip targets the requested list")
        assertEquals(1L, args.contactId)
    }

    // ============================================================================
    // Test 3 — newNextDueAt = previous nextDueAt + skipPenaltyHours
    // ============================================================================

    @Test
    fun `newNextDueAt equals previous nextDueAt plus skipPenaltyHours`() = runTest {
        val priorDue = T0.plus(Duration.ofHours(5))
        val contactRepo = FakeContactRepository(listOf(contactFixture(id = 1L)))
        val listRepo = FakeListRepository(listOf(listFixture(id = 10L, ruleTemplateId = 1L)))
        listRepo.seedMemberships(listOf(
            membershipFixture(contactId = 1L, listId = 10L, nextDueAt = priorDue),
        ))
        val templateRepo = FakeRuleTemplateRepository(listOf(ruleTemplateFixture(id = 1L, params = params)))
        val useCase = SkipContactUseCase(
            contactRepo, listRepo, templateRepo,
            TestClock(T0), JsonProvider.json,
        )

        useCase(contactId = 1L, listId = 10L)

        val args = listRepo.incrementSkipCalls.single()
        // KeepInTouch.skipPenaltyHours = 24h
        val expected = priorDue.plus(Duration.ofHours(params.skipPenaltyHours.toLong()))
        assertEquals(expected, args.newNextDueAt)
    }

    // ============================================================================
    // Test 4 — when previous nextDueAt is null use clock.now as base
    // ============================================================================

    @Test
    fun `when previous nextDueAt is null use clock now as base`() = runTest {
        // Cold-start: membership has no nextDueAt yet → fallback to clock.now().
        val contactRepo = FakeContactRepository(listOf(contactFixture(id = 1L)))
        val listRepo = FakeListRepository(listOf(listFixture(id = 10L, ruleTemplateId = 1L)))
        listRepo.seedMemberships(listOf(
            membershipFixture(contactId = 1L, listId = 10L, nextDueAt = null),
        ))
        val templateRepo = FakeRuleTemplateRepository(listOf(ruleTemplateFixture(id = 1L, params = params)))
        val useCase = SkipContactUseCase(
            contactRepo, listRepo, templateRepo,
            TestClock(T0), JsonProvider.json,
        )

        useCase(contactId = 1L, listId = 10L)

        val args = listRepo.incrementSkipCalls.single()
        val expected = T0.plus(Duration.ofHours(params.skipPenaltyHours.toLong()))
        assertEquals(expected, args.newNextDueAt, "null nextDueAt → base = clock.now()")
    }

    // ============================================================================
    // Test 5 — missing contact returns cleanly with no skip writes
    // ============================================================================

    @Test
    fun `missing contact returns cleanly with no skip writes`() = runTest {
        val contactRepo = FakeContactRepository(emptyList())
        val listRepo = FakeListRepository(listOf(listFixture(id = 10L, ruleTemplateId = 1L)))
        listRepo.seedMemberships(listOf(membershipFixture(contactId = 1L, listId = 10L)))
        val templateRepo = FakeRuleTemplateRepository(listOf(ruleTemplateFixture(id = 1L, params = params)))
        val useCase = SkipContactUseCase(
            contactRepo, listRepo, templateRepo,
            TestClock(T0), JsonProvider.json,
        )

        useCase(contactId = 1L, listId = null)

        assertTrue(
            listRepo.incrementSkipCalls.isEmpty(),
            "missing contact → no incrementSkipCount calls (race-with-delete safety)",
        )
    }
}
