package app.orbit.domain.usecase

import app.orbit.data.db.TransactionRunner
import app.orbit.data.entity.ListMembershipEntity
import app.orbit.data.repository.ListRepository
import app.orbit.domain.FakeCallEventRepository
import app.orbit.domain.FakeContactRepository
import app.orbit.domain.FakeListRepository
import app.orbit.domain.FakeRuleTemplateRepository
import app.orbit.domain.JsonProvider
import app.orbit.domain.WidgetRefreshTrigger
import app.orbit.domain.clock.TestClock
import app.orbit.domain.contactFixture
import app.orbit.domain.listFixture
import app.orbit.domain.membershipFixture
import app.orbit.domain.ruleTemplateFixture
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * BROWSE-08: a drag in Browse writes the times that put a person between their
 * new neighbours in the card's own order ([SurfaceOrder]), and Undo puts every
 * written time back exactly. Each case reads the order back through
 * [SurfaceQueueUseCase] (Browse) and [SurfaceNextUseCase] (the card), so "the
 * card shows the new order" is checked, not assumed.
 */
class ReorderSequenceUseCaseTest {

    private val t0: Instant = Instant.parse("2026-01-01T12:00:00Z")
    private val passThruTx = object : TransactionRunner {
        override suspend fun <T> withTransaction(block: suspend () -> T): T = block()
    }

    private class Fixture(
        val useCase: ReorderSequenceUseCase,
        val queue: SurfaceQueueUseCase,
        val next: SurfaceNextUseCase,
        val listRepo: FakeListRepository,
        val clock: TestClock,
        val refreshes: IntArray
    ) {
        suspend fun order(): List<Long> = queue(1L).first().map { it.contact.id }
        suspend fun cardPerson(): Long = (next(1L).first() as SurfaceResult.Found).contact.id
        suspend fun membership(id: Long): ListMembershipEntity =
            listRepo.observeMembersOfList(1L).first().single { it.contactId == id }
    }

    /** Contacts 1..[count], with these memberships; no calls unless a test adds them. */
    private fun fixture(
        memberships: List<ListMembershipEntity>,
        tx: TransactionRunner = passThruTx
    ): Fixture {
        val ids = memberships.map { it.contactId }
        val contactRepo =
            FakeContactRepository(ids.map { contactFixture(id = it, displayName = "Person $it") })
        val listRepo = FakeListRepository(listOf(listFixture(id = 1L, ruleTemplateId = 1L)))
        listRepo.seedMemberships(memberships)
        val callRepo = FakeCallEventRepository()
        val templates = FakeRuleTemplateRepository(listOf(ruleTemplateFixture(id = 1L)))
        val clock = TestClock(t0)
        val queue =
            SurfaceQueueUseCase(
                contactRepo,
                listRepo,
                callRepo,
                templates,
                clock,
                JsonProvider.json
            )
        val refreshes = intArrayOf(0)
        return Fixture(
            useCase = ReorderSequenceUseCase(
                tx,
                listRepo,
                queue,
                clock,
                WidgetRefreshTrigger {
                    refreshes[0] += 1
                }
            ),
            queue = queue,
            next = SurfaceNextUseCase(
                contactRepo,
                listRepo,
                callRepo,
                templates,
                clock,
                JsonProvider.json
            ),
            listRepo = listRepo,
            clock = clock,
            refreshes = refreshes
        )
    }

    private fun at(id: Long, time: Instant?, skipCount: Int = 0) =
        membershipFixture(contactId = id, listId = 1L, nextDueAt = time, skipCount = skipCount)

    private fun hours(n: Long): Instant = t0.plus(Duration.ofHours(n))

    // ── Top, middle, end ─────────────────────────────────────────────────────

    @Test
    fun `to the top they come up now, first, and the card shows them`() = runTest {
        val f =
            fixture(
                listOf(at(1, hours(-48)), at(2, hours(-24)), at(3, hours(24)), at(4, hours(72)))
            )

        val result = f.useCase(listId = 1L, contactId = 4L, placeAfter = null)

        assertIs<ReorderSequenceUseCase.Result.Moved>(result)
        assertTrue(result.earlier)
        assertEquals(listOf(4L, 1L, 2L, 3L), f.order())
        assertEquals(4L, f.cardPerson(), "the card reads the new order")
        val written = f.membership(4L).nextDueAt!!
        assertTrue(written.isBefore(hours(-48)), "earlier than the old first person")
        assertTrue(!written.isAfter(t0), "no later than now: up now")
        assertEquals(1, f.listRepo.updateNextDueAtCalls.size, "one write")
        assertEquals(1, f.refreshes[0], "the widgets follow the new head (WIDGET-06)")
    }

    @Test
    fun `to the top of a list where no one is up now still makes them up now`() = runTest {
        val f = fixture(listOf(at(1, hours(24)), at(2, hours(72))))

        f.useCase(1L, contactId = 2L, placeAfter = null)

        assertEquals(listOf(2L, 1L), f.order())
        assertEquals(t0.minusMillis(1), f.membership(2L).nextDueAt)
    }

    @Test
    fun `between two future people lands strictly between their times with one write`() = runTest {
        val f =
            fixture(
                listOf(at(1, hours(-48)), at(2, hours(-24)), at(3, hours(24)), at(4, hours(72)))
            )

        val result = f.useCase(1L, contactId = 1L, placeAfter = 3L)

        assertIs<ReorderSequenceUseCase.Result.Moved>(result)
        assertTrue(!result.earlier)
        assertEquals(listOf(2L, 3L, 1L, 4L), f.order())
        val written = f.membership(1L).nextDueAt!!
        assertTrue(written.isAfter(hours(24)) && written.isBefore(hours(72)))
        assertEquals(listOf(1L), f.listRepo.updateNextDueAtCalls.map { it.contactId })
    }

    @Test
    fun `after the last person who is up now they stay up now, last of them`() = runTest {
        val f =
            fixture(
                listOf(at(1, hours(-48)), at(2, hours(-24)), at(3, hours(24)), at(4, hours(72)))
            )

        f.useCase(1L, contactId = 4L, placeAfter = 2L)

        assertEquals(listOf(1L, 2L, 4L, 3L), f.order())
        val written = f.membership(4L).nextDueAt!!
        assertTrue(
            written.isAfter(hours(-24)) && !written.isAfter(t0),
            "between the neighbour and now"
        )
    }

    @Test
    fun `to the end lands just after the last`() = runTest {
        val f =
            fixture(
                listOf(at(1, hours(-48)), at(2, hours(-24)), at(3, hours(24)), at(4, hours(72)))
            )

        f.useCase(1L, contactId = 1L, placeAfter = 4L)

        assertEquals(listOf(2L, 3L, 4L, 1L), f.order())
        assertEquals(hours(72).plusMillis(1), f.membership(1L).nextDueAt)
    }

    // ── Equal times and people who move with the clock ───────────────────────

    @Test
    fun `between two up-now people with the same time, everyone stays up now in the exact order`() =
        runTest {
            // A smart list stamps everyone it adds with one instant.
            val stamp = hours(-1)
            val f = fixture(listOf(at(1, stamp), at(2, stamp), at(3, stamp), at(4, hours(72))))

            f.useCase(1L, contactId = 4L, placeAfter = 1L)

            assertEquals(listOf(1L, 4L, 2L, 3L), f.order())
            assertEquals(stamp.minusMillis(1), f.membership(4L).nextDueAt)
            assertEquals(
                stamp.minusMillis(2),
                f.membership(1L).nextDueAt,
                "the one above moves just earlier"
            )
            assertEquals(stamp, f.membership(2L).nextDueAt, "everyone below keeps their time")
            assertEquals(
                setOf(1L, 4L),
                f.listRepo.updateNextDueAtCalls.map { it.contactId }.toSet()
            )
            listOf(1L, 2L, 3L, 4L).forEach { assertTrue(!f.membership(it).nextDueAt!!.isAfter(t0)) }
        }

    @Test
    fun `between two future people with the same time, the one below moves a millisecond later`() =
        runTest {
            val tie = hours(72)
            val f = fixture(listOf(at(1, hours(-48)), at(2, tie), at(3, tie), at(4, hours(96))))

            f.useCase(1L, contactId = 1L, placeAfter = 2L)

            assertEquals(listOf(2L, 1L, 3L, 4L), f.order())
            assertEquals(tie.plusMillis(1), f.membership(1L).nextDueAt)
            assertEquals(tie.plusMillis(2), f.membership(3L).nextDueAt)
            assertEquals(
                hours(96),
                f.membership(4L).nextDueAt,
                "the push stops once the order holds"
            )
        }

    @Test
    fun `between two people never scheduled, the order holds as time passes`() = runTest {
        // Everyone new on a list: no time stored, no calls, so each one's
        // time is "now" at every read. Nothing fixed can follow such a person
        // for long, so the people above the moved one get fixed times.
        val f = fixture(listOf(at(1, null), at(2, null), at(3, null), at(4, null)))

        f.useCase(1L, contactId = 4L, placeAfter = 1L)

        assertEquals(listOf(1L, 4L, 2L, 3L), f.order())
        assertNull(f.membership(2L).nextDueAt, "below the moved one, people stay unscheduled")
        assertNull(f.membership(3L).nextDueAt)
        f.clock.advance(Duration.ofHours(6))
        assertEquals(listOf(1L, 4L, 2L, 3L), f.order(), "still exact six hours later")
        assertEquals(1L, f.cardPerson())
    }

    @Test
    fun `to the end behind people never scheduled fixes them all and holds`() = runTest {
        val f = fixture(listOf(at(1, null), at(2, null), at(3, null)))

        f.useCase(1L, contactId = 1L, placeAfter = 3L)

        assertEquals(listOf(2L, 3L, 1L), f.order())
        f.clock.advance(Duration.ofDays(2))
        assertEquals(listOf(2L, 3L, 1L), f.order())
        listOf(1L, 2L, 3L).forEach { assertTrue(!f.membership(it).nextDueAt!!.isAfter(t0)) }
    }

    @Test
    fun `before someone never scheduled, one fixed time is enough`() = runTest {
        val f = fixture(listOf(at(1, hours(-24)), at(2, null), at(3, hours(48))))

        f.useCase(1L, contactId = 3L, placeAfter = 1L)

        assertEquals(listOf(1L, 3L, 2L), f.order())
        assertEquals(listOf(3L), f.listRepo.updateNextDueAtCalls.map { it.contactId })
        f.clock.advance(Duration.ofHours(6))
        assertEquals(listOf(1L, 3L, 2L), f.order())
    }

    // ── Undo, and the moves that write nothing ───────────────────────────────

    @Test
    fun `Undo restores every written time exactly, unscheduled included`() = runTest {
        val f = fixture(listOf(at(1, null, skipCount = 2), at(2, null), at(3, hours(-5))))

        val result = f.useCase(1L, contactId = 3L, placeAfter = 1L)
        assertIs<ReorderSequenceUseCase.Result.Moved>(result)
        result.inverse()

        assertNull(f.membership(1L).nextDueAt)
        assertEquals(2, f.membership(1L).skipCount)
        assertNull(f.membership(2L).nextDueAt)
        assertEquals(hours(-5), f.membership(3L).nextDueAt)
        assertEquals(listOf(3L, 1L, 2L), f.order())
        assertEquals(2, f.refreshes[0], "the move and its Undo each refresh the widgets")
        assertTrue(f.listRepo.recomputeDueCountCalls.size >= 2, "dueCount follows both writes")
    }

    @Test
    fun `dropping someone where they already are writes nothing`() = runTest {
        val f = fixture(listOf(at(1, hours(-2)), at(2, hours(-1))))

        assertEquals(
            ReorderSequenceUseCase.Result.Unchanged,
            f.useCase(1L, contactId = 2L, placeAfter = 1L)
        )
        assertEquals(0, f.listRepo.updateNextDueAtCalls.size)
    }

    @Test
    fun `someone who left the sequence meanwhile is reported, with nothing written`() = runTest {
        val f = fixture(listOf(at(1, hours(-2)), at(2, hours(-1))))

        assertEquals(
            ReorderSequenceUseCase.Result.Missing,
            f.useCase(1L, contactId = 9L, placeAfter = null)
        )
        assertEquals(
            ReorderSequenceUseCase.Result.Missing,
            f.useCase(1L, contactId = 1L, placeAfter = 9L)
        )
        assertEquals(0, f.listRepo.updateNextDueAtCalls.size)
    }

    @Test
    fun `a write that fails throws and reports nothing written`() = runTest {
        val failing = object : TransactionRunner {
            override suspend fun <T> withTransaction(block: suspend () -> T): T =
                throw IllegalStateException("disk full")
        }
        val f = fixture(listOf(at(1, hours(-2)), at(2, hours(-1))), tx = failing)

        assertFailsWith<IllegalStateException> { f.useCase(1L, contactId = 2L, placeAfter = null) }
        assertEquals(listOf(1L, 2L), f.order())
        assertEquals(0, f.refreshes[0])
    }

    @Test
    fun `a row that vanished between the read and the write is reported, not half-moved`() = runTest {
        val f = fixture(listOf(at(1, hours(-2)), at(2, hours(-1))))
        // The membership was removed after the sequence was read: the write
        // finds no row (Room rolls the transaction back on the throw).
        val gone = object : ListRepository by f.listRepo {
            override suspend fun updateNextDueAt(contactId: Long, listId: Long, nextDueAt: Instant) =
                MutationResult.MembershipMissing
        }
        val useCase = ReorderSequenceUseCase(passThruTx, gone, f.queue, f.clock)

        assertEquals(ReorderSequenceUseCase.Result.Missing, useCase(1L, contactId = 2L, placeAfter = null))
    }
}
