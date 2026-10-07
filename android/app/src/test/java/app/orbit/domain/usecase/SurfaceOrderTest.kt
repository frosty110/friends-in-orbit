package app.orbit.domain.usecase

import app.orbit.data.entity.CallEventEntity
import app.orbit.data.entity.ContactEntity
import app.orbit.data.entity.ListMembershipEntity
import app.orbit.domain.FakeCallEventRepository
import app.orbit.domain.FakeContactRepository
import app.orbit.domain.FakeListRepository
import app.orbit.domain.FakeRuleTemplateRepository
import app.orbit.domain.JsonProvider
import app.orbit.domain.callEventFixture
import app.orbit.domain.clock.Clock
import app.orbit.domain.clock.TestClock
import app.orbit.domain.contactFixture
import app.orbit.domain.listFixture
import app.orbit.domain.membershipFixture
import app.orbit.domain.ruleTemplateFixture
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * BROWSE-07: Browse's sequence ([SurfaceQueueUseCase]) and the card's person
 * ([SurfaceNextUseCase]) come from one ordering, [SurfaceOrder]. Until
 * 2026-10-07 the two use cases carried their own copies, and the queue
 * recomputed every member from the rule engine while the card read the
 * persisted `nextDueAt`, so after a Later or Sooner Browse's first row was not
 * the card's person.
 */
class SurfaceOrderTest {

    private val t0: Instant = Instant.parse("2026-01-01T12:00:00Z")

    private class Fixture(
        val queue: SurfaceQueueUseCase,
        val next: SurfaceNextUseCase
    )

    private fun fixture(
        contacts: List<ContactEntity>,
        memberships: List<ListMembershipEntity>,
        calls: List<CallEventEntity> = emptyList(),
        clock: Clock = TestClock(t0)
    ): Fixture {
        val contactRepo = FakeContactRepository(contacts)
        val listRepo = FakeListRepository(listOf(listFixture(id = 1L, ruleTemplateId = 1L)))
        listRepo.seedMemberships(memberships)
        val callRepo = FakeCallEventRepository(calls)
        val templates = FakeRuleTemplateRepository(listOf(ruleTemplateFixture(id = 1L)))
        return Fixture(
            queue = SurfaceQueueUseCase(
                contactRepo,
                listRepo,
                callRepo,
                templates,
                clock,
                JsonProvider.json
            ),
            next = SurfaceNextUseCase(
                contactRepo,
                listRepo,
                callRepo,
                templates,
                clock,
                JsonProvider.json
            )
        )
    }

    @Test
    fun `the sequence is the card's order for a mixed list, and its head is the card's person`() =
        runTest {
            val twoDaysAgo = t0.minus(Duration.ofDays(2))
            val inThreeDays = t0.plus(Duration.ofDays(3))
            val f = fixture(
                contacts = listOf(
                    contactFixture(id = 1L, displayName = "Dana"),
                    contactFixture(id = 2L, displayName = "Eli"),
                    contactFixture(id = 3L, displayName = "Fay"),
                    contactFixture(
                        id = 4L,
                        displayName = "Gus",
                        pausedUntil = t0.plus(Duration.ofDays(5))
                    ),
                    contactFixture(id = 5L, displayName = "Hal", isIgnored = true),
                    contactFixture(id = 6L, displayName = "Ivy"),
                    contactFixture(id = 7L, displayName = "Jo"),
                    contactFixture(id = 8L, displayName = "Kim")
                ),
                memberships = listOf(
                    // Dana up now, Eli in the future, Fay never called (so now),
                    // Gus paused and Hal ignored (both out), Ivy on Dana's time,
                    // Jo on Eli's, Kim called but never scheduled.
                    membershipFixture(contactId = 1L, listId = 1L, nextDueAt = twoDaysAgo),
                    membershipFixture(contactId = 2L, listId = 1L, nextDueAt = inThreeDays),
                    membershipFixture(contactId = 3L, listId = 1L, nextDueAt = null),
                    membershipFixture(contactId = 4L, listId = 1L, nextDueAt = twoDaysAgo),
                    membershipFixture(contactId = 5L, listId = 1L, nextDueAt = twoDaysAgo),
                    membershipFixture(contactId = 6L, listId = 1L, nextDueAt = twoDaysAgo),
                    membershipFixture(contactId = 7L, listId = 1L, nextDueAt = inThreeDays),
                    membershipFixture(contactId = 8L, listId = 1L, nextDueAt = null)
                ),
                calls = listOf(
                    callEventFixture(
                        id = 1L,
                        contactId = 6L,
                        occurredAt = t0.minus(Duration.ofDays(30))
                    ),
                    // The default rule's 48-hour cooldown puts Kim at t0 + 1 day.
                    callEventFixture(
                        id = 2L,
                        contactId = 8L,
                        occurredAt = t0.minus(Duration.ofDays(1))
                    )
                )
            )

            val sequence = f.queue(1L).first()

            // Equal times break by last call (never called first), then by id.
            assertEquals(listOf(1L, 6L, 3L, 8L, 2L, 7L), sequence.map { it.contact.id })
            assertEquals(
                listOf(
                    twoDaysAgo,
                    twoDaysAgo,
                    t0,
                    t0.plus(Duration.ofDays(1)),
                    inThreeDays,
                    inThreeDays
                ),
                sequence.map { it.nextDueAt }
            )
            val head = f.next(1L).first()
            assertTrue(head is SurfaceResult.Found)
            assertEquals(
                sequence.first().contact.id,
                head.contact.id,
                "Browse's first row is the card's person"
            )
            assertEquals(sequence.first().nextDueAt, head.nextDueAt)
        }

    @Test
    fun `the sequence follows a persisted time over the rule's cold-start answer`() = runTest {
        // Avery was moved out with Later (persisted, in 10 days); Blake was
        // added and never scheduled, so the rule says "now". The card shows
        // Blake. Browse's queue recomputed both from the rule and put Avery
        // (lower id) first: two different people at the top of one list.
        val f = fixture(
            contacts = listOf(
                contactFixture(id = 1L, displayName = "Avery"),
                contactFixture(id = 2L, displayName = "Blake")
            ),
            memberships = listOf(
                membershipFixture(
                    contactId = 1L,
                    listId = 1L,
                    nextDueAt = t0.plus(Duration.ofDays(10)),
                    skipCount = 1
                ),
                membershipFixture(contactId = 2L, listId = 1L, nextDueAt = null)
            )
        )

        assertEquals(listOf(2L, 1L), f.queue(1L).first().map { it.contact.id })
        assertEquals(2L, (f.next(1L).first() as SurfaceResult.Found).contact.id)
    }

    @Test
    fun `people who have never been scheduled tie on one reading of the clock`() = runTest {
        // A clock that moves on every read, as the real one can across a
        // millisecond. The engines read it per contact, so three cold-start
        // people sorted by the order the database returned them (3, 1, 2)
        // instead of by id, and the card and Browse could disagree.
        val ticking = object : Clock {
            var at = t0
            override fun now(): Instant = at.also { at = at.plusMillis(1) }
        }
        val f = fixture(
            contacts = listOf(
                contactFixture(id = 1L),
                contactFixture(id = 2L),
                contactFixture(id = 3L)
            ),
            memberships = listOf(
                membershipFixture(contactId = 3L, listId = 1L),
                membershipFixture(contactId = 1L, listId = 1L),
                membershipFixture(contactId = 2L, listId = 1L)
            ),
            clock = ticking
        )

        val sequence = f.queue(1L).first()

        assertEquals(listOf(1L, 2L, 3L), sequence.map { it.contact.id })
        assertTrue(sequence.all { it.movesWithClock }, "never scheduled, never called")
        assertEquals(
            1,
            sequence.map { it.nextDueAt }.distinct().size,
            "one reading of now for everyone"
        )
    }
}
