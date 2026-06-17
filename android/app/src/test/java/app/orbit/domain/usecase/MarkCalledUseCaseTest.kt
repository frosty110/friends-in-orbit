package app.orbit.domain.usecase

import app.orbit.data.entity.CallDirection
import app.orbit.data.entity.CallSource
import app.orbit.data.entity.RuleKind
import app.orbit.domain.FakeCallEventRepository
import app.orbit.domain.FakeContactRepository
import app.orbit.domain.FakeListRepository
import app.orbit.domain.FakeRuleTemplateRepository
import app.orbit.domain.JsonProvider
import app.orbit.domain.callEventFixture
import app.orbit.domain.clock.TestClock
import app.orbit.domain.contactFixture
import app.orbit.domain.listFixture
import app.orbit.domain.membershipFixture
import app.orbit.domain.ruleTemplateFixture
import app.orbit.domain.rule.EnergizeEngine
import app.orbit.domain.rule.KeepInTouchEngine
import app.orbit.domain.rule.RuleContext
import app.orbit.domain.rule.RuleParams
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Behavioral tests for [MarkCalledUseCase] — DOM-06 atomic cross-list propagation.
 *
 * The key invariant: `callEventRepo.markCalledAtomic(...)` is called EXACTLY ONCE
 * with a `nextDueByListId` map covering every list the contact is a member of.
 * If a future refactor introduces a write loop instead of the single atomic call,
 * the `markCalledAtomicCalls.size == 1` assertion fails immediately.
 */
class MarkCalledUseCaseTest {

    private val T0: Instant = Instant.parse("2026-01-01T12:00:00Z")

    // ============================================================================
    // Test 1 — DOM-06: exactly-one atomic call
    // ============================================================================

    @Test
    fun `calls markCalledAtomic exactly once with full nextDueByListId map`() = runTest {
        val contactRepo = FakeContactRepository(listOf(contactFixture(id = 1L)))
        val listRepo = FakeListRepository(listOf(
            listFixture(id = 10L, ruleTemplateId = 1L),
            listFixture(id = 20L, ruleTemplateId = 1L),
        ))
        listRepo.seedMemberships(listOf(
            membershipFixture(contactId = 1L, listId = 10L),
            membershipFixture(contactId = 1L, listId = 20L),
        ))
        val callEventRepo = FakeCallEventRepository()
        val templateRepo = FakeRuleTemplateRepository(listOf(ruleTemplateFixture(id = 1L)))
        val useCase = MarkCalledUseCase(
            contactRepo, listRepo, callEventRepo, templateRepo,
            TestClock(T0), JsonProvider.json,
        )
        val event = callEventFixture(id = 100L, contactId = 1L, occurredAt = T0)

        useCase(contactId = 1L, callEvent = event)

        // DOM-06 atomic guard: exactly ONE call to markCalledAtomic.
        assertEquals(1, callEventRepo.markCalledAtomicCalls.size, "atomic call must happen exactly once")
        val args = callEventRepo.markCalledAtomicCalls.single()

        // Map must cover EVERY list this contact is on (cross-list propagation).
        assertEquals(setOf(10L, 20L), args.nextDueByListId.keys, "map must cover every list membership (DOM-06)")
        assertEquals(1L, args.contactId)
        assertEquals(event, args.event)
    }

    // ============================================================================
    // Test 2 — every list value is the engine-computed nextDueAt
    // ============================================================================

    @Test
    fun `nextDueByListId values match engine output for OUTGOING call`() = runTest {
        val params = RuleParams.KeepInTouch()
        val contactRepo = FakeContactRepository(listOf(contactFixture(id = 1L)))
        val listRepo = FakeListRepository(listOf(listFixture(id = 10L, ruleTemplateId = 1L)))
        listRepo.seedMemberships(listOf(membershipFixture(contactId = 1L, listId = 10L)))
        val callEventRepo = FakeCallEventRepository()
        val templateRepo = FakeRuleTemplateRepository(listOf(ruleTemplateFixture(id = 1L, params = params)))
        val useCase = MarkCalledUseCase(
            contactRepo, listRepo, callEventRepo, templateRepo,
            TestClock(T0), JsonProvider.json,
        )
        // 5-minute outgoing call (well above shortCallThreshold=60s)
        val event = callEventFixture(
            id = 100L, contactId = 1L, occurredAt = T0,
            direction = CallDirection.OUTGOING, durationSeconds = 300,
            source = CallSource.CALL_LOG,
        )

        useCase(contactId = 1L, callEvent = event)

        // Recompute the engine output by hand and compare to the captured map value.
        val expectedNextDue = KeepInTouchEngine(params).nextDue(
            contact = app.orbit.domain.rule.ContactSnapshot(
                id = 1L, isIgnored = false, pausedUntil = null,
            ),
            ctx = RuleContext(
                lastCallAt = T0,
                lastCallDurationSec = 300,
                lastCallDirection = CallDirection.OUTGOING,
                lastCallSource = CallSource.CALL_LOG,
                skipCount = 0,
                params = params,
            ),
            clock = TestClock(T0),
        )
        assertNotNull(expectedNextDue, "engine should compute a nextDue for normal outgoing call")

        val args = callEventRepo.markCalledAtomicCalls.single()
        assertEquals(expectedNextDue, args.nextDueByListId[10L], "map value must match engine output")
    }

    // ============================================================================
    // Test 3 — Different lists with different templates produce different nextDue
    // ============================================================================

    @Test
    fun `cross-list propagation respects per-list rule templates`() = runTest {
        // Two lists with DIFFERENT rule templates → engine outputs different nextDue.
        val keepInTouchParams = RuleParams.KeepInTouch()
        val energizeParams = RuleParams.Energize()
        val contactRepo = FakeContactRepository(listOf(contactFixture(id = 1L)))
        val listRepo = FakeListRepository(listOf(
            listFixture(id = 10L, ruleTemplateId = 1L),  // KeepInTouch
            listFixture(id = 20L, ruleTemplateId = 2L),  // Energize
        ))
        listRepo.seedMemberships(listOf(
            membershipFixture(contactId = 1L, listId = 10L),
            membershipFixture(contactId = 1L, listId = 20L),
        ))
        val callEventRepo = FakeCallEventRepository()
        val templateRepo = FakeRuleTemplateRepository(listOf(
            ruleTemplateFixture(id = 1L, kind = RuleKind.KEEP_IN_TOUCH, params = keepInTouchParams),
            ruleTemplateFixture(id = 2L, kind = RuleKind.ENERGIZE, params = energizeParams),
        ))
        val useCase = MarkCalledUseCase(
            contactRepo, listRepo, callEventRepo, templateRepo,
            TestClock(T0), JsonProvider.json,
        )
        val event = callEventFixture(
            id = 100L, contactId = 1L, occurredAt = T0,
            direction = CallDirection.OUTGOING, durationSeconds = 300,
        )

        useCase(contactId = 1L, callEvent = event)

        val args = callEventRepo.markCalledAtomicCalls.single()
        val nextDue10 = args.nextDueByListId[10L]
        val nextDue20 = args.nextDueByListId[20L]
        assertNotNull(nextDue10)
        assertNotNull(nextDue20)
        // KeepInTouch min cooldown 48h vs Energize min cooldown 24h → different nextDue.
        assertTrue(
            nextDue10 != nextDue20,
            "KeepInTouch (48h min) and Energize (24h min) must produce different nextDue",
        )

        // Sanity-check: both match their respective engines.
        val snapshot = app.orbit.domain.rule.ContactSnapshot(
            id = 1L, isIgnored = false, pausedUntil = null,
        )
        val ctx10 = RuleContext(T0, 300, CallDirection.OUTGOING, CallSource.CALL_LOG, 0, keepInTouchParams)
        val ctx20 = RuleContext(T0, 300, CallDirection.OUTGOING, CallSource.CALL_LOG, 0, energizeParams)
        assertEquals(KeepInTouchEngine(keepInTouchParams).nextDue(snapshot, ctx10, TestClock(T0)), nextDue10)
        assertEquals(EnergizeEngine(energizeParams).nextDue(snapshot, ctx20, TestClock(T0)), nextDue20)
    }

    // ============================================================================
    // Test 4 — missing contact returns cleanly without atomic call (race-with-delete)
    // ============================================================================

    @Test
    fun `missing contact returns cleanly with no atomic call`() = runTest {
        // Empty contact repo → observeById emits null → use case returns early.
        val contactRepo = FakeContactRepository(emptyList())
        val listRepo = FakeListRepository(listOf(listFixture(id = 10L, ruleTemplateId = 1L)))
        listRepo.seedMemberships(listOf(membershipFixture(contactId = 1L, listId = 10L)))
        val callEventRepo = FakeCallEventRepository()
        val templateRepo = FakeRuleTemplateRepository(listOf(ruleTemplateFixture(id = 1L)))
        val useCase = MarkCalledUseCase(
            contactRepo, listRepo, callEventRepo, templateRepo,
            TestClock(T0), JsonProvider.json,
        )
        val event = callEventFixture(id = 100L, contactId = 1L, occurredAt = T0)

        useCase(contactId = 1L, callEvent = event)

        assertEquals(
            0,
            callEventRepo.markCalledAtomicCalls.size,
            "missing contact must NOT trigger atomic call (race-with-delete safety)",
        )
    }
}
