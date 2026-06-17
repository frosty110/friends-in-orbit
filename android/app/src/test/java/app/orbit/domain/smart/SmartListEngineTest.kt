package app.orbit.domain.smart

import app.cash.turbine.test
import app.orbit.data.entity.CallEventEntity
import app.orbit.data.entity.ContactEntity
import app.orbit.domain.FakeCallEventRepository
import app.orbit.domain.FakeContactRepository
import app.orbit.domain.callEventFixture
import app.orbit.domain.clock.TestClock
import app.orbit.domain.contactFixture
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Behavioral tests for [SmartListEngine] — covers SMART-03 (reactive Flow),
 * SMART-05 (ignore filter applies to every rule type), SMART-07 (zero-call
 * percentile invariant), SMART-08 (firstSeenByAppAt source-of-truth).
 *
 * Two interfaces exercised:
 *   - `engine.computeFromEvents(...)` — internal pure dispatcher (same module, so visible)
 *   - `engine.membership(rule)` — public Flow entry, tested via Turbine
 */
class SmartListEngineTest {

    private val T0: Instant = Instant.parse("2026-01-01T12:00:00Z")

    // -------- Test helpers --------

    private fun engine(
        contacts: List<ContactEntity> = emptyList(),
        events: List<CallEventEntity> = emptyList(),
        clockAt: Instant = T0,
    ): Triple<SmartListEngine, FakeContactRepository, FakeCallEventRepository> {
        val contactRepo = FakeContactRepository(contacts)
        val callEventRepo = FakeCallEventRepository(events)
        val eng = SmartListEngine(contactRepo, callEventRepo, TestClock(clockAt))
        return Triple(eng, contactRepo, callEventRepo)
    }

    // ============================================================================
    // SMART-08: RecentlyAddedNotCalled — firstSeenByAppAt window + zero-call gate
    // ============================================================================

    @Test
    fun `recentlyAddedNotCalled within window returns matching contacts`() {
        val (eng, _, _) = engine(
            contacts = listOf(
                contactFixture(id = 1L, firstSeenByAppAt = T0.minus(Duration.ofDays(10))),  // in window, no calls
                contactFixture(id = 2L, firstSeenByAppAt = T0.minus(Duration.ofDays(60))),  // OUT window
                contactFixture(id = 3L, firstSeenByAppAt = T0.minus(Duration.ofDays(5))),   // in window, HAS call
            ),
            events = listOf(
                callEventFixture(id = 100L, contactId = 3L, occurredAt = T0.minus(Duration.ofDays(1))),
            ),
        )
        val result = eng.computeFromEvents(
            rule = SmartListRule.RecentlyAddedNotCalled(daysWindow = 30),
            contacts = listOf(
                contactFixture(id = 1L, firstSeenByAppAt = T0.minus(Duration.ofDays(10))),
                contactFixture(id = 2L, firstSeenByAppAt = T0.minus(Duration.ofDays(60))),
                contactFixture(id = 3L, firstSeenByAppAt = T0.minus(Duration.ofDays(5))),
            ),
            events = listOf(
                callEventFixture(id = 100L, contactId = 3L, occurredAt = T0.minus(Duration.ofDays(1))),
            ),
            now = T0,
        )
        assertEquals(listOf(1L), result.map { it.id })
    }

    @Test
    fun `recentlyAddedNotCalled excludes contacts with any call event (SMART-08)`() {
        // Contact 1 is in window AND has 1 call event → must be excluded.
        val contacts = listOf(
            contactFixture(id = 1L, firstSeenByAppAt = T0.minus(Duration.ofDays(5))),
        )
        val events = listOf(
            callEventFixture(id = 100L, contactId = 1L, occurredAt = T0.minus(Duration.ofDays(1))),
        )
        val (eng, _, _) = engine(contacts = contacts, events = events)
        val result = eng.computeFromEvents(
            rule = SmartListRule.RecentlyAddedNotCalled(daysWindow = 30),
            contacts = contacts,
            events = events,
            now = T0,
        )
        assertEquals(emptyList(), result, "contact with calls must NOT appear in RecentlyAddedNotCalled")
    }

    // ============================================================================
    // LongGap — never-called contacts excluded by invariant
    // ============================================================================

    @Test
    fun `longGap excludes contacts with no call history`() {
        // Two contacts, both with no calls → longGap output must be empty
        // (gap is not measurable without a prior call; use NeverCalled instead).
        val contacts = listOf(
            contactFixture(id = 1L),
            contactFixture(id = 2L),
        )
        val (eng, _, _) = engine(contacts = contacts)
        val result = eng.computeFromEvents(
            rule = SmartListRule.LongGap(daysThreshold = 7),
            contacts = contacts,
            events = emptyList(),
            now = T0,
        )
        assertEquals(emptyList(), result)
    }

    @Test
    fun `longGap returns contacts whose last call is older than threshold`() {
        val contacts = listOf(
            contactFixture(id = 1L),  // last call 30d ago — over threshold
            contactFixture(id = 2L),  // last call 3d ago  — under threshold
        )
        val events = listOf(
            callEventFixture(id = 100L, contactId = 1L, occurredAt = T0.minus(Duration.ofDays(30))),
            callEventFixture(id = 101L, contactId = 2L, occurredAt = T0.minus(Duration.ofDays(3))),
        )
        val (eng, _, _) = engine(contacts = contacts, events = events)
        val result = eng.computeFromEvents(
            rule = SmartListRule.LongGap(daysThreshold = 7),
            contacts = contacts,
            events = events,
            now = T0,
        )
        assertEquals(listOf(1L), result.map { it.id })
    }

    // ============================================================================
    // SMART-07: percentile invariants (CommonlyCalled / RarelyCalled)
    // ============================================================================

    @Test
    fun `commonlyCalled with topPercent 20 picks top 20 percent of callers (ceil)`() {
        // 10 contacts, callCounts 1..10 → topPercent=20 → ceil(10*20/100.0) = 2
        // Sort callers ASC by callCount → callers = [c1,c2,...,c10] with counts [1..10]
        // takeLast(2) → c9 (count=9), c10 (count=10) → final sortedBy id ASC stays {9, 10}.
        val contacts = (1L..10L).map { contactFixture(id = it) }
        val events = mutableListOf<CallEventEntity>()
        var nextEventId = 1000L
        for (i in 1..10) {
            repeat(i) { events += callEventFixture(id = nextEventId++, contactId = i.toLong(), occurredAt = T0.minus(Duration.ofDays(1))) }
        }
        val (eng, _, _) = engine(contacts = contacts, events = events)
        val result = eng.computeFromEvents(
            rule = SmartListRule.CommonlyCalled(topPercent = 20),
            contacts = contacts,
            events = events,
            now = T0,
        )
        assertEquals(listOf(9L, 10L), result.map { it.id })
    }

    @Test
    fun `commonlyCalled excludes zero-call contacts even when topPercent is large (SMART-07)`() {
        // 2 contacts: one with 100 calls, one with 0 calls. Even at topPercent=100, the
        // zero-call contact must NOT appear — it falls to NeverCalled by invariant.
        val contacts = listOf(
            contactFixture(id = 1L),
            contactFixture(id = 2L),  // zero calls
        )
        val events = (1..100).map {
            callEventFixture(id = (1000 + it).toLong(), contactId = 1L, occurredAt = T0.minus(Duration.ofDays(1)))
        }
        val (eng, _, _) = engine(contacts = contacts, events = events)
        val result = eng.computeFromEvents(
            rule = SmartListRule.CommonlyCalled(topPercent = 100),
            contacts = contacts,
            events = events,
            now = T0,
        )
        assertEquals(listOf(1L), result.map { it.id })
        assertFalse(result.any { it.id == 2L }, "zero-call contact leaked into CommonlyCalled")
    }

    @Test
    fun `rarelyCalled with bottomPercent 50 picks bottom 50 percent (floor)`() {
        // 10 callers with counts 1..10 → bottomPercent=50 → floor(10*50/100.0) = 5
        // Sort ASC by count → take(5) → c1..c5 (counts 1..5) → final sortedBy id ASC = {1..5}.
        val contacts = (1L..10L).map { contactFixture(id = it) }
        val events = mutableListOf<CallEventEntity>()
        var nextEventId = 2000L
        for (i in 1..10) {
            repeat(i) { events += callEventFixture(id = nextEventId++, contactId = i.toLong(), occurredAt = T0.minus(Duration.ofDays(1))) }
        }
        val (eng, _, _) = engine(contacts = contacts, events = events)
        val result = eng.computeFromEvents(
            rule = SmartListRule.RarelyCalled(bottomPercent = 50),
            contacts = contacts,
            events = events,
            now = T0,
        )
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), result.map { it.id })
    }

    // ============================================================================
    // NeverCalled — zero-call cohort
    // ============================================================================

    @Test
    fun `neverCalled returns only contacts with zero call events`() {
        val contacts = listOf(
            contactFixture(id = 1L),  // zero calls → in
            contactFixture(id = 2L),  // 1 call → out
            contactFixture(id = 3L),  // zero calls → in
        )
        val events = listOf(
            callEventFixture(id = 100L, contactId = 2L, occurredAt = T0.minus(Duration.ofDays(1))),
        )
        val (eng, _, _) = engine(contacts = contacts, events = events)
        val result = eng.computeFromEvents(
            rule = SmartListRule.NeverCalled,
            contacts = contacts,
            events = events,
            now = T0,
        )
        assertEquals(listOf(1L, 3L), result.map { it.id })
    }

    // ============================================================================
    // SMART-05: ignore filter applies to ALL 5 rule types
    // ============================================================================

    @Test
    fun `all 5 rules filter out ignored contacts (SMART-05)`() {
        // contact 1 is ignored. contact 2 is normal. Set up data so contact 1 would
        // qualify under EVERY rule type if the filter weren't applied, then assert
        // contact 1 is missing from every result.
        //
        // Setup:
        //   - Both have firstSeenByAppAt 5d ago (RecentlyAddedNotCalled candidate)
        //   - Both have NO calls → would normally appear in NeverCalled
        //   - For LongGap/CommonlyCalled/RarelyCalled, we need calls; create variants per rule.

        val rules = listOf(
            SmartListRule.RecentlyAddedNotCalled(daysWindow = 30),
            SmartListRule.LongGap(daysThreshold = 1),
            SmartListRule.CommonlyCalled(topPercent = 100),
            SmartListRule.RarelyCalled(bottomPercent = 100),
            SmartListRule.NeverCalled,
        )

        for (rule in rules) {
            val contactsForRule: List<ContactEntity>
            val eventsForRule: List<CallEventEntity>
            when (rule) {
                is SmartListRule.RecentlyAddedNotCalled, SmartListRule.NeverCalled -> {
                    contactsForRule = listOf(
                        contactFixture(id = 1L, isIgnored = true, firstSeenByAppAt = T0.minus(Duration.ofDays(5))),
                        contactFixture(id = 2L, firstSeenByAppAt = T0.minus(Duration.ofDays(5))),
                    )
                    eventsForRule = emptyList()
                }
                is SmartListRule.LongGap, is SmartListRule.CommonlyCalled, is SmartListRule.RarelyCalled -> {
                    contactsForRule = listOf(
                        contactFixture(id = 1L, isIgnored = true, firstSeenByAppAt = T0.minus(Duration.ofDays(5))),
                        contactFixture(id = 2L, firstSeenByAppAt = T0.minus(Duration.ofDays(5))),
                    )
                    // Both have OLD calls so LongGap qualifies and both are in the
                    // callers universe (so CommonlyCalled/RarelyCalled at 100% catch them).
                    eventsForRule = listOf(
                        callEventFixture(id = 100L, contactId = 1L, occurredAt = T0.minus(Duration.ofDays(30))),
                        callEventFixture(id = 101L, contactId = 2L, occurredAt = T0.minus(Duration.ofDays(30))),
                    )
                }
            }
            val (eng, _, _) = engine(contacts = contactsForRule, events = eventsForRule)
            val result = eng.computeFromEvents(rule, contactsForRule, eventsForRule, T0)
            assertFalse(
                result.any { it.id == 1L },
                "ignored contact leaked into $rule output: ${result.map { it.id }}",
            )
        }
    }

    // ============================================================================
    // SMART-03: reactive Flow re-emission
    // ============================================================================

    @Test
    fun `flow re-emits when underlying contact flow updates (SMART-03)`() = runTest {
        val (eng, contactRepo, _) = engine(
            contacts = listOf(
                contactFixture(id = 1L, firstSeenByAppAt = T0.minus(Duration.ofDays(10))),
            ),
        )
        eng.membership(SmartListRule.RecentlyAddedNotCalled(daysWindow = 30)).test(timeout = 2.seconds) {
            assertEquals(listOf(1L), awaitItem().map { it.id })
            // Mutate: empty out contacts → membership recomputes empty.
            contactRepo.seed(emptyList())
            assertEquals(emptyList(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `flow re-emits when underlying call-event flow updates (SMART-03)`() = runTest {
        // Start with a contact in the RecentlyAddedNotCalled window with NO calls
        // → contact in result. Then add a call event → contact must drop out.
        val (eng, _, callEventRepo) = engine(
            contacts = listOf(
                contactFixture(id = 1L, firstSeenByAppAt = T0.minus(Duration.ofDays(5))),
            ),
        )
        eng.membership(SmartListRule.RecentlyAddedNotCalled(daysWindow = 30)).test(timeout = 2.seconds) {
            assertEquals(listOf(1L), awaitItem().map { it.id })
            callEventRepo.seed(listOf(
                callEventFixture(id = 100L, contactId = 1L, occurredAt = T0.minus(Duration.ofDays(1))),
            ))
            assertEquals(emptyList(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ============================================================================
    // Deterministic ordering
    // ============================================================================

    @Test
    fun `result is deterministically ordered by contact id ASC`() {
        // Seed contacts in id 3, 1, 2 order — final result must be {1, 2, 3}.
        val contacts = listOf(
            contactFixture(id = 3L, firstSeenByAppAt = T0.minus(Duration.ofDays(5))),
            contactFixture(id = 1L, firstSeenByAppAt = T0.minus(Duration.ofDays(5))),
            contactFixture(id = 2L, firstSeenByAppAt = T0.minus(Duration.ofDays(5))),
        )
        val (eng, _, _) = engine(contacts = contacts)
        val result = eng.computeFromEvents(
            rule = SmartListRule.RecentlyAddedNotCalled(daysWindow = 30),
            contacts = contacts,
            events = emptyList(),
            now = T0,
        )
        assertEquals(listOf(1L, 2L, 3L), result.map { it.id })
    }

    // ============================================================================
    // Empty input edge case
    // ============================================================================

    @Test
    fun `empty contact list returns empty for every rule`() {
        val (eng, _, _) = engine()
        val rules = listOf(
            SmartListRule.RecentlyAddedNotCalled(daysWindow = 30),
            SmartListRule.LongGap(daysThreshold = 1),
            SmartListRule.CommonlyCalled(topPercent = 50),
            SmartListRule.RarelyCalled(bottomPercent = 50),
            SmartListRule.NeverCalled,
        )
        for (rule in rules) {
            assertTrue(eng.computeFromEvents(rule, emptyList(), emptyList(), T0).isEmpty(), "non-empty for $rule")
        }
    }
}
