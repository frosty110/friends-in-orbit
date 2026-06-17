package app.orbit.ui.screens.calllog

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import app.orbit.data.dao.RecordingListMembershipDao
import app.orbit.data.entity.CallDirection
import app.orbit.data.entity.CallEventEntity
import app.orbit.data.entity.CallSource
import app.orbit.data.entity.ContactEntity
import app.orbit.domain.FakeCallEventRepository
import app.orbit.domain.FakeContactRepository
import app.orbit.domain.FakeListRepository
import app.orbit.domain.callEventFixture
import app.orbit.domain.clock.TestClock
import app.orbit.domain.contactFixture
import app.orbit.testutil.MainDispatcherRule
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Behavioral tests for the spec-complete [CallLogViewModel]:
 * calendar-day grouping, wall-clock labels, direction filtering (MANUAL
 * events under All + Outgoing), and honest pagination remainders.
 *
 * All instants are built FROM [ZoneId.systemDefault] local date-times so the
 * grouping assertions are deterministic on any machine — the VM groups by
 * the system zone, and so do the fixtures.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallLogViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val zone: ZoneId = ZoneId.systemDefault()

    private fun at(date: String, time: String): Instant =
        LocalDateTime.parse("${date}T$time").atZone(zone).toInstant()

    /** 9am on the reference "today". */
    private val now: Instant = at("2026-06-09", "09:00")

    private var savedLocale: Locale = Locale.getDefault()

    @Before
    fun pinLocale() {
        savedLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(savedLocale)
    }

    private fun vm(
        events: List<CallEventEntity>,
        contacts: List<ContactEntity> = listOf(contactFixture(id = 1L, displayName = "Sarah")),
    ): CallLogViewModel = CallLogViewModel(
        callEventRepo = FakeCallEventRepository(events),
        contactRepo = FakeContactRepository().apply { seed(contacts) },
        listMembershipDao = RecordingListMembershipDao(),
        listRepo = FakeListRepository(),
        clock = TestClock(now),
    )

    private suspend fun ReceiveTurbine<CallLogUiState>.awaitReady(): CallLogUiState.Ready {
        while (true) {
            when (val item = awaitItem()) {
                is CallLogUiState.Ready -> return item
                CallLogUiState.Loading -> continue
                CallLogUiState.Empty -> fail("expected Ready, got Empty")
            }
        }
    }

    private fun CallLogUiState.Ready.rowCount(): Int = sections.sumOf { it.rows.size }

    // ============================================================================
    // Calendar-day grouping — 11pm call vs next-morning 9am read
    // ============================================================================

    @Test
    fun `groups by local calendar day not 24h windows`() = runTest {
        val vm = vm(
            events = listOf(
                callEventFixture(id = 1L, contactId = 1L, occurredAt = at("2026-06-09", "08:00")),
                // 10 hours before "now" — but the previous calendar day.
                callEventFixture(id = 2L, contactId = 1L, occurredAt = at("2026-06-08", "23:00")),
            ),
        )
        vm.uiState.test(timeout = 5.seconds) {
            val ready = awaitReady()
            assertEquals(listOf("Today", "Yesterday"), ready.sections.map { it.label })
            assertEquals(listOf(1L), ready.sections[0].rows.map { it.callEventId })
            assertEquals(listOf(2L), ready.sections[1].rows.map { it.callEventId })
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ============================================================================
    // Wall-clock label
    // ============================================================================

    @Test
    fun `rows carry the wall-clock time of the call`() = runTest {
        val vm = vm(
            events = listOf(
                callEventFixture(id = 1L, contactId = 1L, occurredAt = at("2026-06-09", "04:30")),
            ),
        )
        vm.uiState.test(timeout = 5.seconds) {
            val ready = awaitReady()
            assertEquals("4:30am", ready.sections[0].rows[0].timeLabel)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ============================================================================
    // Direction filter — MANUAL under All + Outgoing, never Incoming
    // ============================================================================

    @Test
    fun `manual events stay visible under All and Outgoing and hide under Incoming`() = runTest {
        val vm = vm(
            events = listOf(
                callEventFixture(
                    id = 1L, contactId = 1L, occurredAt = at("2026-06-09", "08:00"),
                    direction = CallDirection.OUTGOING, durationSeconds = 0,
                    source = CallSource.MANUAL,
                ),
                callEventFixture(
                    id = 2L, contactId = 1L, occurredAt = at("2026-06-09", "07:00"),
                    direction = CallDirection.INCOMING,
                ),
                callEventFixture(
                    id = 3L, contactId = 1L, occurredAt = at("2026-06-09", "06:00"),
                    direction = CallDirection.OUTGOING,
                ),
            ),
        )
        vm.uiState.test(timeout = 5.seconds) {
            val all = awaitReady()
            assertEquals(CallLogDirectionFilter.ALL, all.filter)
            assertEquals(listOf(1L, 2L, 3L), all.sections.flatMap { s -> s.rows.map { it.callEventId } })

            vm.onFilterChange(CallLogDirectionFilter.OUTGOING)
            val outgoing = awaitReady()
            assertEquals(listOf(1L, 3L), outgoing.sections.flatMap { s -> s.rows.map { it.callEventId } })

            vm.onFilterChange(CallLogDirectionFilter.INCOMING)
            val incoming = awaitReady()
            assertEquals(listOf(2L), incoming.sections.flatMap { s -> s.rows.map { it.callEventId } })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `narrowing filter with no matches keeps Ready with empty sections`() = runTest {
        val vm = vm(
            events = listOf(
                callEventFixture(
                    id = 1L, contactId = 1L, occurredAt = at("2026-06-09", "08:00"),
                    direction = CallDirection.OUTGOING,
                ),
            ),
        )
        vm.uiState.test(timeout = 5.seconds) {
            awaitReady()
            vm.onFilterChange(CallLogDirectionFilter.INCOMING)
            val incoming = awaitReady()
            assertTrue(incoming.sections.isEmpty(), "filtered-out set keeps Ready, not Empty")
            assertEquals(0, incoming.remainingCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ============================================================================
    // Honest pagination — true increments, real remainder, footer hides at end
    // ============================================================================

    @Test
    fun `pagination reveals true increments and reports the real remainder`() = runTest {
        // 450 events, all on the reference day (08:00 going back one minute
        // per event keeps every instant inside 2026-06-09).
        val events = (0 until 450).map { i ->
            callEventFixture(
                id = (i + 1).toLong(),
                contactId = 1L,
                occurredAt = at("2026-06-09", "08:00").minusSeconds(i * 60L),
            )
        }
        val vm = vm(events)
        vm.uiState.test(timeout = 5.seconds) {
            val first = awaitReady()
            assertEquals(200, first.rowCount())
            assertEquals(250, first.remainingCount)

            vm.onShowMore()
            val second = awaitReady()
            assertEquals(400, second.rowCount())
            assertEquals(50, second.remainingCount)

            vm.onShowMore()
            val third = awaitReady()
            assertEquals(450, third.rowCount())
            assertEquals(0, third.remainingCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `filter change resets pagination to one page`() = runTest {
        // 250 outgoing + 10 incoming. After expanding to everything, switching
        // the filter re-opens bounded — without the reset the remainder would
        // read 0 instead of 50.
        val events = (0 until 250).map { i ->
            callEventFixture(
                id = (i + 1).toLong(),
                contactId = 1L,
                occurredAt = at("2026-06-09", "08:00").minusSeconds(i * 60L),
                direction = CallDirection.OUTGOING,
            )
        } + (0 until 10).map { i ->
            callEventFixture(
                id = (i + 251).toLong(),
                contactId = 1L,
                occurredAt = at("2026-06-08", "12:00").minusSeconds(i * 60L),
                direction = CallDirection.INCOMING,
            )
        }
        val vm = vm(events)
        vm.uiState.test(timeout = 5.seconds) {
            val first = awaitReady()
            assertEquals(60, first.remainingCount) // 260 total − 200 visible

            vm.onShowMore()
            val expanded = awaitReady()
            assertEquals(260, expanded.rowCount())
            assertEquals(0, expanded.remainingCount)

            vm.onFilterChange(CallLogDirectionFilter.OUTGOING)
            val refiltered = awaitReady()
            assertEquals(200, refiltered.rowCount())
            assertEquals(50, refiltered.remainingCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ============================================================================
    // Empty — no correlated events at all
    // ============================================================================

    @Test
    fun `no events emits Empty`() = runTest {
        val vm = vm(events = emptyList())
        vm.uiState.test(timeout = 5.seconds) {
            while (true) {
                when (val item = awaitItem()) {
                    CallLogUiState.Empty -> break
                    CallLogUiState.Loading -> continue
                    is CallLogUiState.Ready -> fail("expected Empty, got Ready")
                }
            }
            cancelAndIgnoreRemainingEvents()
        }
    }
}
