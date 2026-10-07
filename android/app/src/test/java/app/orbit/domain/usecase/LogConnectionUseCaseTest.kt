package app.orbit.domain.usecase

import app.orbit.data.entity.CallDirection
import app.orbit.data.entity.CallSource
import app.orbit.domain.FakeCallEventRepository
import app.orbit.domain.FakeContactRepository
import app.orbit.domain.FakeListRepository
import app.orbit.domain.FakeNoteRepository
import app.orbit.domain.FakeRuleTemplateRepository
import app.orbit.domain.JsonProvider
import app.orbit.domain.clock.TestClock
import app.orbit.domain.contactFixture
import app.orbit.domain.listFixture
import app.orbit.domain.membershipFixture
import app.orbit.domain.ruleTemplateFixture
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * [LogConnectionUseCase]: the one way to log a connection or an attempt,
 * called by Contact detail (CONTACT-09) and the card (CARD-10). The body
 * moved here from `ContactDetailViewModel.onLogConnection` unchanged, so these
 * pin what it always did: one event through MarkCalledUseCase (the atomic
 * reschedule), MANUAL or ATTEMPT, `durationSeconds = 0`, the note back-dated
 * to the same moment, and how Today, Yesterday and a picked date become an
 * instant. Contact detail's own tests still drive it through that ViewModel.
 */
class LogConnectionUseCaseTest {

    private val t0: Instant = Instant.parse("2026-01-01T12:00:00Z")

    private class Setup(
        val useCase: LogConnectionUseCase,
        val callEvents: FakeCallEventRepository,
        val notes: FakeNoteRepository,
    )

    private fun setup(zone: ZoneId = ZoneOffset.UTC, onList: Boolean = true): Setup {
        val contacts = FakeContactRepository(listOf(contactFixture(id = 1L)))
        val lists = FakeListRepository(listOf(listFixture(id = 1L, ruleTemplateId = 1L)))
        if (onList) lists.seedMemberships(listOf(membershipFixture(contactId = 1L, listId = 1L)))
        val callEvents = FakeCallEventRepository()
        val notes = FakeNoteRepository()
        val clock = TestClock(t0)
        val markCalled = MarkCalledUseCase(
            contactRepo = contacts,
            listRepo = lists,
            callEventRepo = callEvents,
            ruleTemplateRepo = FakeRuleTemplateRepository(listOf(ruleTemplateFixture(id = 1L))),
            clock = clock,
            json = JsonProvider.json,
        )
        return Setup(
            LogConnectionUseCase(markCalled, AddRetroactiveNoteUseCase(notes), clock, zone),
            callEvents,
            notes,
        )
    }

    @Test
    fun `a connection today is one MANUAL event now, rescheduled on every list`() = runTest {
        val s = setup()

        val result = s.useCase(1L, LogConnectionWhen.Today, note = "", isAttempt = false)

        assertEquals(MutationResult.Success, result)
        val write = s.callEvents.markCalledAtomicCalls.single()
        assertEquals(1L, write.contactId)
        assertEquals(t0, write.event.occurredAt)
        assertEquals(CallSource.MANUAL, write.event.source)
        assertEquals(CallDirection.OUTGOING, write.event.direction)
        assertEquals(0, write.event.durationSeconds)
        assertEquals(setOf(1L), write.nextDueByListId.keys, "the person's list is rescheduled")
        assertTrue(s.notes.insertCalls.isEmpty(), "no note was asked for")
    }

    @Test
    fun `an attempt yesterday is an ATTEMPT a day back, with the note back-dated to it`() = runTest {
        val s = setup()

        s.useCase(1L, LogConnectionWhen.Yesterday, note = "  Left a voicemail  ", isAttempt = true)

        val event = s.callEvents.markCalledAtomicCalls.single().event
        assertEquals(CallSource.ATTEMPT, event.source)
        assertEquals(t0.minus(Duration.ofDays(1)), event.occurredAt)
        val note = s.notes.insertCalls.single()
        assertEquals("Left a voicemail", note.body, "trimmed")
        assertEquals(event.occurredAt, note.createdAt, "the note sits at the moment logged")
    }

    @Test
    fun `a picked date lands at noon of that day in the phone's zone`() = runTest {
        val zone = ZoneId.of("America/Los_Angeles")
        val s = setup(zone = zone)
        val pickedUtcMidnight = LocalDate.of(2025, 12, 20).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        s.useCase(1L, LogConnectionWhen.OnDate(pickedUtcMidnight), note = "", isAttempt = false)

        assertEquals(
            LocalDate.of(2025, 12, 20).atTime(12, 0).atZone(zone).toInstant(),
            s.callEvents.markCalledAtomicCalls.single().event.occurredAt,
        )
    }

    @Test
    fun `a picked date never lands in the future`() = runTest {
        // Today, picked: local noon is after t0 in a zone ahead of UTC's noon.
        val s = setup(zone = ZoneId.of("America/Los_Angeles"))
        val today = LocalDate.of(2026, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        s.useCase(1L, LogConnectionWhen.OnDate(today), note = "", isAttempt = false)

        assertEquals(t0, s.callEvents.markCalledAtomicCalls.single().event.occurredAt)
    }

    @Test
    fun `someone on no list is still logged, and the result says nothing was rescheduled`() = runTest {
        val s = setup(onList = false)

        val result = s.useCase(1L, LogConnectionWhen.Today, note = "Coffee", isAttempt = false)

        assertEquals(MutationResult.MembershipMissing, result)
        assertEquals(1, s.callEvents.markCalledAtomicCalls.size)
        assertEquals(1, s.notes.insertCalls.size)
    }
}
