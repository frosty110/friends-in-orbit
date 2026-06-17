package app.orbit.domain.usecase

import app.orbit.data.entity.ContactEntity
import app.orbit.data.entity.ListEntity
import app.orbit.data.entity.ListMembershipEntity
import app.orbit.data.entity.RuleTemplateEntity
import app.orbit.domain.FakeCallEventRepository
import app.orbit.domain.FakeContactRepository
import app.orbit.domain.FakeListRepository
import app.orbit.domain.FakeRuleTemplateRepository
import app.orbit.domain.JsonProvider
import app.orbit.domain.clock.TestClock
import app.orbit.domain.contactFixture
import app.orbit.domain.listFixture
import app.orbit.domain.membershipFixture
import app.orbit.domain.ruleTemplateFixture
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Behavioral tests for [WidgetSurfaceUseCase] — the cross-list contact source
 * for widgets. Covers IGNORE-08 / archive exclusion.
 *
 * All tests use TestClock at T0 = 2026-01-01T12:00:00Z and ZoneOffset.UTC.
 * SurfaceNextUseCase is injected with fakes so the test runs JVM-only with
 * no Robolectric.
 */
class WidgetSurfaceUseCaseTest {

    private val T0: Instant = Instant.parse("2026-01-01T12:00:00Z")

    // The use case requires a populated ruleTemplateId on each list, and
    // a template in the repo, otherwise SurfaceNextUseCase returns NothingEligible.
    private val template = ruleTemplateFixture(id = 1L)

    // ─── Fixture builder ──────────────────────────────────────────────────────

    private fun buildUseCase(
        contacts: List<ContactEntity>,
        lists: List<ListEntity>,
        memberships: List<ListMembershipEntity>,
        clockAt: Instant = T0,
        templates: List<RuleTemplateEntity> = listOf(template),
    ): WidgetSurfaceUseCase {
        val contactRepo = FakeContactRepository(contacts)
        val listRepo = FakeListRepository(lists, memberships)
        val callEventRepo = FakeCallEventRepository()
        val templateRepo = FakeRuleTemplateRepository(templates)
        val clock = TestClock(clockAt)
        val surfaceNextUseCase = SurfaceNextUseCase(
            contactRepo = contactRepo,
            listRepo = listRepo,
            callEventRepo = callEventRepo,
            ruleTemplateRepo = templateRepo,
            clock = clock,
            json = JsonProvider.json,
        )
        return WidgetSurfaceUseCase(
            surfaceNext = surfaceNextUseCase,
            listRepo = listRepo,
            clock = clock,
            zoneId = ZoneOffset.UTC,
        )
    }

    // ─── Test 1: Cross-list primary + ordering ────────────────────────────────

    /**
     * With two active lists each surfacing a distinct Found contact, invoke()
     * returns primary = the contact with the earliest nextDueAt; alternatives =
     * the remaining contact(s) ordered by nextDueAt ASC, capped at 2.
     */
    @Test
    fun twoListsDistinctContacts_primaryIsEarliestDue() = runTest {
        val sooner = T0.minusSeconds(3600)   // overdue 1h ago
        val later  = T0.plusSeconds(3600)    // 1h ahead

        val contactA = contactFixture(id = 1L)
        val contactB = contactFixture(id = 2L)
        val listA = listFixture(id = 1L, ruleTemplateId = 1L)
        val listB = listFixture(id = 2L, ruleTemplateId = 1L)

        val useCase = buildUseCase(
            contacts    = listOf(contactA, contactB),
            lists       = listOf(listA, listB),
            memberships = listOf(
                membershipFixture(contactId = 1L, listId = 1L, nextDueAt = sooner),
                membershipFixture(contactId = 2L, listId = 2L, nextDueAt = later),
            ),
        )

        val result = useCase()
        assertEquals(1L, result.primary?.id, "primary should be earliest-due contact")
        assertEquals(listOf(2L), result.alternatives.map { it.id },
            "alternatives should contain the later contact")
    }

    // ─── Test 1b (ADR 0008): cross-list time-of-day reorders the primary ──────

    /**
     * ADR 0008 — the soft time-of-day weight only reorders ACROSS lists with
     * different windows, and this is the one place it does. Two equally-overdue
     * contacts, one on a daytime list (09:00–17:00) and one on a late-night list
     * (21:00–02:00). The same data surfaces a different primary depending only on
     * the clock: at noon the daytime contact wins (its list is in-window, penalty
     * 0; the night list is penalized); at 23:00 the late-night contact wins.
     * Neither is ever excluded — time-of-day only sinks, never gates.
     */
    @Test
    fun crossListTimeOfDay_primaryFlipsWithClock() = runTest {
        val due = T0.minusSeconds(3600) // both equally overdue → penalty is the decider
        val contactDay = contactFixture(id = 1L)
        val contactNight = contactFixture(id = 2L)
        val listDay = listFixture(
            id = 1L, ruleTemplateId = 1L,
            activeHoursStart = LocalTime.of(9, 0), activeHoursEnd = LocalTime.of(17, 0),
        )
        val listNight = listFixture(
            id = 2L, ruleTemplateId = 1L,
            activeHoursStart = LocalTime.of(21, 0), activeHoursEnd = LocalTime.of(2, 0),
        )
        val memberships = listOf(
            membershipFixture(contactId = 1L, listId = 1L, nextDueAt = due),
            membershipFixture(contactId = 2L, listId = 2L, nextDueAt = due),
        )

        val atNoon = buildUseCase(
            contacts = listOf(contactDay, contactNight),
            lists = listOf(listDay, listNight),
            memberships = memberships,
            clockAt = T0, // 12:00 UTC — daytime list in-window
        )
        assertEquals(
            1L, atNoon().primary?.id,
            "at noon the daytime-list contact ranks first (late-night list penalized)",
        )

        val atNight = buildUseCase(
            contacts = listOf(contactDay, contactNight),
            lists = listOf(listDay, listNight),
            memberships = memberships,
            clockAt = Instant.parse("2026-01-01T23:00:00Z"), // 23:00 UTC — late-night list in-window
        )
        assertEquals(
            2L, atNight().primary?.id,
            "at 23:00 the late-night-list contact ranks first (daytime list penalized)",
        )
    }

    // ─── Test 2: Deduplication by contactId ───────────────────────────────────

    /**
     * The same contact surfaced as Found on two different lists appears exactly
     * once in the combined (primary + alternatives) set (dedupe by ContactEntity.id).
     */
    @Test
    fun sameContactOnTwoLists_appearsOnlyOnce() = runTest {
        val contact = contactFixture(id = 1L)
        val listA = listFixture(id = 1L, ruleTemplateId = 1L)
        val listB = listFixture(id = 2L, ruleTemplateId = 1L)

        val useCase = buildUseCase(
            contacts    = listOf(contact),
            lists       = listOf(listA, listB),
            memberships = listOf(
                membershipFixture(contactId = 1L, listId = 1L, nextDueAt = T0.minusSeconds(100)),
                membershipFixture(contactId = 1L, listId = 2L, nextDueAt = T0.minusSeconds(200)),
            ),
        )

        val result = useCase()
        val allIds = listOfNotNull(result.primary?.id) + result.alternatives.map { it.id }
        assertEquals(1, allIds.distinct().size, "contact id 1 should appear exactly once")
        assertEquals(1, allIds.size, "total count should be 1 (deduplicated)")
    }

    /**
     * Review WR-01 regression: when the same contact surfaces on two lists,
     * the EARLIEST nextDueAt must survive deduplication and drive ordering.
     *
     * Contact 1 is head-of-queue on list 1 (due 10h ahead) and list 3
     * (overdue 5h). Contact 2 is on list 2 (overdue 2h). Lists iterate by
     * sortOrder (= id), so a dedup-before-sort bug would keep contact 1's
     * LATER nextDueAt from list 1 and wrongly promote contact 2 to primary.
     * The contract says contact 1 (earliest due across all lists) is primary.
     */
    @Test
    fun sameContactOnTwoLists_earliestNextDueAtWinsOrdering() = runTest {
        val contactX = contactFixture(id = 1L)
        val contactY = contactFixture(id = 2L)
        val listA = listFixture(id = 1L, ruleTemplateId = 1L)
        val listB = listFixture(id = 2L, ruleTemplateId = 1L)
        val listC = listFixture(id = 3L, ruleTemplateId = 1L)

        val useCase = buildUseCase(
            contacts    = listOf(contactX, contactY),
            lists       = listOf(listA, listB, listC),
            memberships = listOf(
                membershipFixture(contactId = 1L, listId = 1L, nextDueAt = T0.plusSeconds(36_000)),
                membershipFixture(contactId = 2L, listId = 2L, nextDueAt = T0.minusSeconds(7_200)),
                membershipFixture(contactId = 1L, listId = 3L, nextDueAt = T0.minusSeconds(18_000)),
            ),
        )

        val result = useCase()
        assertEquals(
            1L, result.primary?.id,
            "primary should be the contact whose earliest nextDueAt across all lists is the minimum",
        )
        assertEquals(
            listOf(2L), result.alternatives.map { it.id },
            "alternatives should hold the other contact exactly once",
        )
    }

    // ─── Test 3: No active contacts → empty result ────────────────────────────

    /**
     * When every active list returns NoMembers / NothingEligible, invoke()
     * returns WidgetSurfaceData(primary = null, alternatives = emptyList()).
     */
    @Test
    fun allListsEmpty_returnsNullPrimaryAndEmptyAlternatives() = runTest {
        val listA = listFixture(id = 1L, ruleTemplateId = 1L)

        val useCase = buildUseCase(
            contacts    = emptyList(),
            lists       = listOf(listA),
            memberships = emptyList(),
        )

        val result = useCase()
        assertNull(result.primary, "primary should be null when no contacts are due")
        assertTrue(result.alternatives.isEmpty(), "alternatives should be empty")
    }

    // ─── Test 4: Archived lists not consulted ─────────────────────────────────

    /**
     * Archived lists are not consulted — only ListRepository.observeActive() lists
     * feed the source. A contact reachable only via an archived list never appears.
     */
    @Test
    fun archivedListContact_doesNotAppear() = runTest {
        val contactOnArchived = contactFixture(id = 99L)
        val archivedList = listFixture(id = 1L, ruleTemplateId = 1L, isArchived = true)

        val useCase = buildUseCase(
            contacts    = listOf(contactOnArchived),
            lists       = listOf(archivedList),
            memberships = listOf(
                membershipFixture(contactId = 99L, listId = 1L, nextDueAt = T0.minusSeconds(100)),
            ),
        )

        val result = useCase()
        assertNull(result.primary, "archived-list contact should not appear as primary")
        assertTrue(result.alternatives.isEmpty(), "alternatives should be empty")
    }

    // ─── Test 5 (IGNORE-08): Ignored contacts never appear ───────────────────

    /**
     * An ignored contact never appears in primary or alternatives. This is
     * inherited from SurfaceNextUseCase's filter. WidgetSurfaceUseCase performs
     * NO independent contact query — it only consumes SurfaceNextUseCase output
     * (which already filters isIgnored/isArchived/paused).
     */
    @Test
    fun ignoredContact_neverAppearsInWidgetSurface() = runTest {
        val ignoredContact = contactFixture(id = 1L, isIgnored = true)
        val list = listFixture(id = 1L, ruleTemplateId = 1L)

        val useCase = buildUseCase(
            contacts    = listOf(ignoredContact),
            lists       = listOf(list),
            memberships = listOf(
                membershipFixture(contactId = 1L, listId = 1L, nextDueAt = T0.minusSeconds(100)),
            ),
        )

        val result = useCase()
        assertNull(result.primary, "ignored contact should not appear as primary")
        assertEquals(emptyList(), result.alternatives, "alternatives should be empty for ignored contact")
    }
}
