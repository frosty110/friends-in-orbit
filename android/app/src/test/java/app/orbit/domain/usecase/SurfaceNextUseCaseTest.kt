package app.orbit.domain.usecase

import app.cash.turbine.test
import app.orbit.data.entity.CallEventEntity
import app.orbit.data.entity.ContactEntity
import app.orbit.data.entity.ListEntity
import app.orbit.data.entity.ListMembershipEntity
import app.orbit.data.entity.RuleTemplateEntity
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
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Behavioral tests for [SurfaceNextUseCase] — the central orchestrator that
 * decides "who should I surface right now?" Covers DOM-05, IGNORE-04, IGNORE-07,
 * the ordering invariant (DOM-05), the ADR-0008 contract (active hours no longer
 * gate single-list surfacing), and the tide-marker contract (2026-05-08):
 * future-due heads are surfaced as [SurfaceResult.Found] rather than dropped, and
 * [SurfaceResult.NoMembers] vs [SurfaceResult.NothingEligible] distinguish the
 * two genuine empty cases.
 *
 * Every test uses TestClock at T0 = 2026-01-01T12:00:00Z.
 */
class SurfaceNextUseCaseTest {

    private val T0: Instant = Instant.parse("2026-01-01T12:00:00Z")

    // -------- Test helpers --------

    private fun fixture(
        contacts: List<ContactEntity>,
        lists: List<ListEntity>,
        memberships: List<ListMembershipEntity>,
        callEvents: List<CallEventEntity> = emptyList(),
        templates: List<RuleTemplateEntity> = listOf(ruleTemplateFixture(id = 1L)),
        clockAt: Instant = T0,
    ): Quintuple {
        val contactRepo = FakeContactRepository(contacts)
        val listRepo = FakeListRepository(lists)
        val callEventRepo = FakeCallEventRepository(callEvents)
        val templateRepo = FakeRuleTemplateRepository(templates)
        listRepo.seedMemberships(memberships)
        val useCase = SurfaceNextUseCase(
            contactRepo = contactRepo,
            listRepo = listRepo,
            callEventRepo = callEventRepo,
            ruleTemplateRepo = templateRepo,
            clock = TestClock(clockAt),
            json = JsonProvider.json,
        )
        return Quintuple(useCase, contactRepo, listRepo, callEventRepo, templateRepo)
    }

    private data class Quintuple(
        val useCase: SurfaceNextUseCase,
        val contactRepo: FakeContactRepository,
        val listRepo: FakeListRepository,
        val callEventRepo: FakeCallEventRepository,
        val templateRepo: FakeRuleTemplateRepository,
    )

    private fun SurfaceResult.expectFound(): SurfaceResult.Found {
        assertTrue(this is SurfaceResult.Found, "expected SurfaceResult.Found, got $this")
        return this
    }

    // ============================================================================
    // Test 1 — initial subscription emits the due contact (DOM-05)
    // ============================================================================

    @Test
    fun `emits next due contact on initial subscription`() = runTest {
        val (useCase, _, _, _, _) = fixture(
            contacts = listOf(contactFixture(id = 1L)),
            lists = listOf(listFixture(id = 1L, ruleTemplateId = 1L)),
            memberships = listOf(
                // No prior call → engine cooldown N/A; cold-start surfaces immediately.
                membershipFixture(contactId = 1L, listId = 1L, nextDueAt = null),
            ),
        )
        useCase(listId = 1L).test(timeout = 2.seconds) {
            assertEquals(1L, awaitItem().expectFound().contact.id)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ============================================================================
    // Test 2 — IGNORE-04: ignored contact excluded; toggle drops them mid-flow
    // ============================================================================

    @Test
    fun `excludes ignored contacts (IGNORE-04)`() = runTest {
        val (useCase, contactRepo, _, _, _) = fixture(
            contacts = listOf(contactFixture(id = 1L, isIgnored = false)),
            lists = listOf(listFixture(id = 1L, ruleTemplateId = 1L)),
            memberships = listOf(membershipFixture(contactId = 1L, listId = 1L, nextDueAt = null)),
        )
        useCase(listId = 1L).test(timeout = 2.seconds) {
            assertEquals(1L, awaitItem().expectFound().contact.id)
            // flip to ignored
            contactRepo.update { list -> list.map { it.copy(isIgnored = true) } }
            assertEquals(
                SurfaceResult.NoMembers,
                awaitItem(),
                "ignored contact must NOT surface (IGNORE-04); list is now empty of visible members",
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ============================================================================
    // Test 3 — IGNORE-07: un-ignore preserves membership and resurfaces
    // ============================================================================

    @Test
    fun `unignore preserves membership and resurfaces (IGNORE-07)`() = runTest {
        // Pre-ignored contact: starts hidden. Then flip isIgnored=false → must
        // re-surface with their original membership intact (use case reads, never writes
        // memberships, so prior state is preserved by construction).
        val (useCase, contactRepo, listRepo, _, _) = fixture(
            contacts = listOf(contactFixture(id = 1L, isIgnored = true)),
            lists = listOf(listFixture(id = 1L, ruleTemplateId = 1L)),
            memberships = listOf(membershipFixture(contactId = 1L, listId = 1L, nextDueAt = null)),
        )
        useCase(listId = 1L).test(timeout = 2.seconds) {
            assertEquals(SurfaceResult.NoMembers, awaitItem(), "starts ignored → NoMembers")
            contactRepo.update { list -> list.map { it.copy(isIgnored = false) } }
            assertEquals(1L, awaitItem().expectFound().contact.id, "resurfaces after un-ignore")
            cancelAndIgnoreRemainingEvents()
        }
        // Sanity: membership still present in the repo (use case never wrote).
        listRepo.observeMembershipsForContact(1L).test {
            val rows = awaitItem()
            assertEquals(1, rows.size, "membership preserved across ignore toggle (IGNORE-07)")
            assertEquals(1L, rows.single().listId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ============================================================================
    // Test 4 — paused contact excluded while pausedUntil is in the future
    // ============================================================================

    @Test
    fun `excludes paused contacts while pausedUntil is in the future`() = runTest {
        val pausedUntil = T0.plus(Duration.ofDays(7))
        val (useCase, _, _, _, _) = fixture(
            contacts = listOf(contactFixture(id = 1L, pausedUntil = pausedUntil)),
            lists = listOf(listFixture(id = 1L, ruleTemplateId = 1L)),
            memberships = listOf(membershipFixture(contactId = 1L, listId = 1L, nextDueAt = null)),
        )
        useCase(listId = 1L).test(timeout = 2.seconds) {
            assertEquals(
                SurfaceResult.NothingEligible,
                awaitItem(),
                "paused contact must NOT surface; list has visible members so empty is NothingEligible",
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ============================================================================
    // Test 5 — ADR 0008: active hours are a weight, not a gate. Outside the
    // window a single list still surfaces its head (the per-list penalty is
    // uniform within one list, so it never empties the queue). The cross-list
    // ranking effect of the penalty is covered in WidgetSurfaceUseCaseTest.
    // ============================================================================

    @Test
    fun `surfaces a contact even outside active hours (soft weight, not a gate)`() = runTest {
        // T0 = 12:00 UTC. Window = 21:00..02:00 (wraps midnight). 12:00 is OUTSIDE.
        // Pre-ADR-0008 this returned NothingEligible; now the member surfaces.
        val (useCase, _, _, _, _) = fixture(
            contacts = listOf(contactFixture(id = 1L)),
            lists = listOf(
                listFixture(
                    id = 1L,
                    ruleTemplateId = 1L,
                    activeHoursStart = LocalTime.of(21, 0),
                    activeHoursEnd = LocalTime.of(2, 0),
                ),
            ),
            memberships = listOf(membershipFixture(contactId = 1L, listId = 1L, nextDueAt = null)),
        )
        useCase(listId = 1L).test(timeout = 2.seconds) {
            assertEquals(
                1L,
                awaitItem().expectFound().contact.id,
                "outside active hours the list still has a head (active hours no longer gate)",
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `respects active hours — inside window surfaces normally`() = runTest {
        // T0 = 12:00 UTC. Window = 09:00..17:00 (same-day). 12:00 is INSIDE.
        val (useCase, _, _, _, _) = fixture(
            contacts = listOf(contactFixture(id = 1L)),
            lists = listOf(
                listFixture(
                    id = 1L,
                    ruleTemplateId = 1L,
                    activeHoursStart = LocalTime.of(9, 0),
                    activeHoursEnd = LocalTime.of(17, 0),
                ),
            ),
            memberships = listOf(membershipFixture(contactId = 1L, listId = 1L, nextDueAt = null)),
        )
        useCase(listId = 1L).test(timeout = 2.seconds) {
            assertEquals(1L, awaitItem().expectFound().contact.id, "inside active hours → contact surfaces")
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ============================================================================
    // Test 6 — ordering: nextDueAt ASC then lastCalledAt ASC
    // ============================================================================

    @Test
    fun `orders by nextDueAt ASC then lastCalledAt ASC`() = runTest {
        // Two contacts, both with the SAME engine-computed nextDue (because both have
        // no prior calls → cold-start "due now"). The tiebreaker is lastCalledAt ASC.
        // Contact 1: last call 30 days ago (older → should win tiebreak).
        // Contact 2: last call 1 day ago.
        // BUT: with prior calls present, the engine recomputes nextDue. To isolate the
        // tiebreak, set BOTH calls 30+ days ago (well past max cooldown of 336h = 14d)
        // so both nextDue resolve to "in the past". Contact 1 = 60d ago, Contact 2 = 30d ago.
        // Engine nextDue = lastCall + cooldown — for 60d ago lastCall: nextDue is older
        // than for 30d ago lastCall. So Contact 1 has earlier nextDue → wins.
        val (useCase, _, _, _, _) = fixture(
            contacts = listOf(
                contactFixture(id = 1L),
                contactFixture(id = 2L),
            ),
            lists = listOf(listFixture(id = 1L, ruleTemplateId = 1L)),
            memberships = listOf(
                membershipFixture(contactId = 1L, listId = 1L),
                membershipFixture(contactId = 2L, listId = 1L),
            ),
            callEvents = listOf(
                callEventFixture(id = 100L, contactId = 1L, occurredAt = T0.minus(Duration.ofDays(60))),
                callEventFixture(id = 101L, contactId = 2L, occurredAt = T0.minus(Duration.ofDays(30))),
            ),
        )
        useCase(listId = 1L).test(timeout = 2.seconds) {
            assertEquals(
                1L,
                awaitItem().expectFound().contact.id,
                "contact with older lastCall (earlier nextDue) surfaces first",
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ============================================================================
    // Test 7 — ADR 0008: within a single list, the active-hours window no longer
    // changes whether a contact surfaces — it surfaces at any clock time. (The
    // clock-dependent CROSS-list ordering the window drives now lives in
    // WidgetSurfaceUseCaseTest.crossListTimeOfDay_primaryFlipsWithClock.)
    // ============================================================================

    @Test
    fun `active hours no longer gate single-list surfacing at any clock time`() = runTest {
        // Window 13:00..14:00. The same member surfaces both outside (12:00) and
        // inside (13:30) the window — pre-ADR-0008 the 12:00 case was NothingEligible.
        val baseContacts = listOf(contactFixture(id = 1L))
        val baseLists = listOf(
            listFixture(
                id = 1L,
                ruleTemplateId = 1L,
                activeHoursStart = LocalTime.of(13, 0),
                activeHoursEnd = LocalTime.of(14, 0),
            ),
        )
        val baseMemberships = listOf(membershipFixture(contactId = 1L, listId = 1L))

        val (useCaseAtNoon, _, _, _, _) = fixture(
            contacts = baseContacts,
            lists = baseLists,
            memberships = baseMemberships,
            clockAt = T0, // 12:00 UTC — outside window, still surfaces
        )
        useCaseAtNoon(listId = 1L).test(timeout = 2.seconds) {
            assertEquals(
                1L,
                awaitItem().expectFound().contact.id,
                "at 12:00 UTC (outside window) the contact still surfaces",
            )
            cancelAndIgnoreRemainingEvents()
        }

        val (useCaseAt1330, _, _, _, _) = fixture(
            contacts = baseContacts,
            lists = baseLists,
            memberships = baseMemberships,
            clockAt = T0.plus(Duration.ofMinutes(90)), // 13:30 UTC — inside window
        )
        useCaseAt1330(listId = 1L).test(timeout = 2.seconds) {
            assertEquals(
                1L,
                awaitItem().expectFound().contact.id,
                "at 13:30 UTC (inside window) the contact surfaces",
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ============================================================================
    // Test 8 — tide marker: future-due contact still surfaces (no longer dropped)
    // ============================================================================

    @Test
    fun `surfaces future-due contact as Found rather than dropping it`() = runTest {
        // Recently-called contact: KeepInTouchEngine cooldown is in the future.
        // Pre-tide-marker (2026-05-08) the use case dropped these candidates
        // entirely. Post-change they surface as Found with nextDueAt > now so
        // the UI can label the card eyebrow `ahead of today`.
        val recentCall = T0.minus(Duration.ofHours(1))
        val (useCase, _, _, _, _) = fixture(
            contacts = listOf(contactFixture(id = 1L)),
            lists = listOf(listFixture(id = 1L, ruleTemplateId = 1L)),
            memberships = listOf(membershipFixture(contactId = 1L, listId = 1L)),
            callEvents = listOf(callEventFixture(id = 100L, contactId = 1L, occurredAt = recentCall)),
        )
        useCase(listId = 1L).test(timeout = 2.seconds) {
            val found = awaitItem().expectFound()
            assertEquals(1L, found.contact.id)
            assertTrue(
                found.nextDueAt.isAfter(T0),
                "engine-computed nextDueAt is in the future (recent call + cooldown); " +
                    "tide marker preserves the candidate, got $found",
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ============================================================================
    // Test 8b — SWIPE-FIX (2026-06-08): persisted membership.nextDueAt drives
    // surfacing, not a fresh engine recomputation. This is the regression guard
    // for "swiping left/right re-surfaces the same contact": skip/sooner write
    // membership.nextDueAt, so the surface MUST honor it.
    // ============================================================================

    @Test
    fun `surfaces by persisted nextDueAt — skipped contact yields to an earlier one`() = runTest {
        // Two cold-start contacts (no call history). Pre-fix both compute engine
        // nextDue = `now`, tie-break to contact 1 by id ASC. Here contact 1 has
        // been skipped (persisted nextDueAt = T0 + 10d), so contact 2 (null →
        // engine cold-start "now") must surface instead. Proves the persisted
        // column overrides the engine's cold-start value.
        val (useCase, _, _, _, _) = fixture(
            contacts = listOf(
                contactFixture(id = 1L),
                contactFixture(id = 2L),
            ),
            lists = listOf(listFixture(id = 1L, ruleTemplateId = 1L)),
            memberships = listOf(
                membershipFixture(contactId = 1L, listId = 1L, nextDueAt = T0.plus(Duration.ofDays(10))),
                membershipFixture(contactId = 2L, listId = 1L, nextDueAt = null),
            ),
        )
        useCase(listId = 1L).test(timeout = 2.seconds) {
            assertEquals(
                2L,
                awaitItem().expectFound().contact.id,
                "contact 1 was skipped (nextDueAt 10d out); contact 2 (cold-start, due now) surfaces",
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Found nextDueAt reflects the persisted value, not the engine recomputation`() = runTest {
        // Single skipped contact: persisted nextDueAt = T0 + 3d. Pre-fix the
        // engine would recompute `now` (cold start), so the eyebrow read
        // `due today` and the same card re-surfaced after every swipe. Post-fix
        // the persisted future value flows through to Found.nextDueAt.
        val skippedTo = T0.plus(Duration.ofDays(3))
        val (useCase, _, _, _, _) = fixture(
            contacts = listOf(contactFixture(id = 1L)),
            lists = listOf(listFixture(id = 1L, ruleTemplateId = 1L)),
            memberships = listOf(membershipFixture(contactId = 1L, listId = 1L, nextDueAt = skippedTo)),
        )
        useCase(listId = 1L).test(timeout = 2.seconds) {
            val found = awaitItem().expectFound()
            assertEquals(1L, found.contact.id)
            assertEquals(
                skippedTo,
                found.nextDueAt,
                "persisted nextDueAt drives Found.nextDueAt (swipe-fix), not engine cold-start `now`",
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ============================================================================
    // Test 9 — empty list emits NoMembers (distinct from NothingEligible)
    // ============================================================================

    @Test
    fun `empty list emits NoMembers`() = runTest {
        val (useCase, _, _, _, _) = fixture(
            contacts = emptyList(),
            lists = listOf(listFixture(id = 1L, ruleTemplateId = 1L)),
            memberships = emptyList(),
        )
        useCase(listId = 1L).test(timeout = 2.seconds) {
            assertEquals(
                SurfaceResult.NoMembers,
                awaitItem(),
                "list with zero memberships → NoMembers (UI: 'Add people to this list.')",
            )
            cancelAndIgnoreRemainingEvents()
        }
    }
}
