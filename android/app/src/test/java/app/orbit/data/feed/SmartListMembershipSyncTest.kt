package app.orbit.data.feed

import app.orbit.data.dao.RecordingListMembershipDao
import app.orbit.data.entity.ListMembershipEntity
import app.orbit.data.entity.ListType
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
import app.orbit.domain.ruleTemplateFixture
import app.orbit.domain.smart.SmartListEngine
import app.orbit.domain.smart.SmartListRule
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Drives [SmartListMembershipSync.run] in `backgroundScope` with `runCurrent()`:
 * `advanceUntilIdle()` stops as soon as no FOREGROUND work is queued, so it
 * never runs a background-only loop and every assertion would see nothing.
 *
 * Smart lists surface through stored membership rows. Regression: their
 * members were only projected inside List settings, so a smart list showed
 * matches there and no one on the card, in the queue, or on Home.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SmartListMembershipSyncTest {

    private val t0: Instant = Instant.parse("2026-01-01T12:00:00Z")
    private val recentRule = JsonProvider.json.encodeToString(
        SmartListRule.serializer(),
        SmartListRule.RecentlyAddedNotCalled(daysWindow = 30)
    )

    /** Membership DAO that keeps rows, so reconcile can be observed end to end. */
    private class InMemoryMembershipDao : RecordingListMembershipDao() {
        val rows = mutableListOf<ListMembershipEntity>()
        override suspend fun getMembersOfList(listId: Long) = rows.filter { it.listId == listId }
        override suspend fun insertAll(memberships: List<ListMembershipEntity>) {
            memberships.forEach { m ->
                if (rows.none {
                        it.listId == m.listId && it.contactId == m.contactId
                    }
                ) {
                    rows += m
                }
            }
        }
        override suspend fun removeAll(fromListId: Long, ids: List<Long>) {
            rows.removeAll { it.listId == fromListId && it.contactId in ids }
        }
    }

    private class Setup(
        val sync: SmartListMembershipSync,
        val listRepo: FakeListRepository,
        val callEventRepo: FakeCallEventRepository,
        val dao: InMemoryMembershipDao
    )

    private fun kotlinx.coroutines.test.TestScope.setup(
        lists: List<app.orbit.data.entity.ListEntity>
    ): Setup {
        val contactRepo = FakeContactRepository(
            listOf(
                // Matches: added 10 days ago, never called.
                contactFixture(
                    id = 1L,
                    firstSeenByAppAt = t0.minus(Duration.ofDays(10))
                ),
                // Outside the 30-day window.
                contactFixture(
                    id = 2L,
                    firstSeenByAppAt = t0.minus(Duration.ofDays(60))
                ),
                // Already called (see the call event below).
                contactFixture(
                    id = 3L,
                    firstSeenByAppAt = t0.minus(Duration.ofDays(5))
                )
            )
        )
        val callEventRepo = FakeCallEventRepository(
            listOf(
                callEventFixture(
                    id = 100L,
                    contactId = 3L,
                    occurredAt = t0.minus(Duration.ofDays(1))
                )
            )
        )
        val listRepo = FakeListRepository(initialLists = lists)
        val templates =
            FakeRuleTemplateRepository(
                listOf(ruleTemplateFixture(id = 1L, kind = RuleKind.KEEP_IN_TOUCH))
            )
        val dao = InMemoryMembershipDao()
        val clock = TestClock(t0)
        val sync = SmartListMembershipSync(
            listRepo = listRepo,
            ruleTemplateRepo = templates,
            smartListEngine = SmartListEngine(contactRepo, callEventRepo, clock),
            listMembershipDao = dao,
            clock = clock,
            scope = backgroundScope
        )
        return Setup(sync, listRepo, callEventRepo, dao)
    }

    @Test
    fun `matches become due members and leave when they stop matching`() = runTest {
        val s =
            setup(
                listOf(
                    listFixture(
                        id = 5L,
                        type = ListType.SMART,
                        smartRuleJson = recentRule,
                        ruleTemplateId = null
                    )
                )
            )
        backgroundScope.launch { s.sync.run() }
        runCurrent()

        val members = s.dao.getMembersOfList(5L)
        assertEquals(listOf(1L), members.map { it.contactId })
        assertEquals(
            t0,
            members.single().nextDueAt,
            "a new match is due now, like a convert snapshot"
        )

        // Calling them is the moment "recently added, not called" stops matching.
        s.callEventRepo.update { it + callEventFixture(id = 101L, contactId = 1L, occurredAt = t0) }
        runCurrent()

        assertTrue(s.dao.getMembersOfList(5L).isEmpty())
    }

    @Test
    fun `a smart list with no cadence gets Keep in touch`() = runTest {
        val s =
            setup(
                listOf(
                    listFixture(
                        id = 5L,
                        type = ListType.SMART,
                        smartRuleJson = recentRule,
                        ruleTemplateId = null
                    )
                )
            )
        backgroundScope.launch { s.sync.run() }
        runCurrent()

        assertEquals(listOf(5L to 1L), s.listRepo.updateRuleTemplateCalls.toList())
    }

    @Test
    fun `static and archived lists are left alone`() = runTest {
        val s = setup(
            listOf(
                listFixture(
                    id = 5L,
                    type = ListType.SMART,
                    smartRuleJson = recentRule,
                    ruleTemplateId = 1L
                ),
                listFixture(id = 6L, type = ListType.STATIC, ruleTemplateId = null),
                listFixture(
                    id = 7L,
                    type = ListType.SMART,
                    smartRuleJson = recentRule,
                    ruleTemplateId = null,
                    isArchived = true
                )
            )
        )
        backgroundScope.launch { s.sync.run() }
        runCurrent()

        // List 5 is the control: it proves the loop ran, so the emptiness
        // below means "left alone", not "never started".
        assertEquals(setOf(5L), s.dao.rows.map { it.listId }.toSet())
        assertTrue(
            s.listRepo.updateRuleTemplateCalls.isEmpty(),
            "no cadence written to static or archived lists"
        )
    }
}
