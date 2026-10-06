package app.orbit.ui.screens.calllog

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import app.orbit.R
import app.orbit.data.dao.RecordingListMembershipDao
import app.orbit.data.entity.CallDirection
import app.orbit.data.entity.CallEventEntity
import app.orbit.data.entity.CallSource
import app.orbit.data.entity.ContactEntity
import app.orbit.data.entity.ListEntity
import app.orbit.data.entity.ListMembershipEntity
import app.orbit.data.repository.CallEventRepository
import app.orbit.domain.FakeCallEventRepository
import app.orbit.domain.FakeContactRepository
import app.orbit.domain.FakeListRepository
import app.orbit.domain.callEventFixture
import app.orbit.domain.clock.TestClock
import app.orbit.domain.contactFixture
import app.orbit.domain.listFixture
import app.orbit.testutil.MainDispatcherRule
import app.orbit.ui.util.UiText
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Behavioral tests for the spec-complete [CallLogViewModel]:
 * calendar-day grouping, wall-clock labels, direction filtering (MANUAL and
 * ATTEMPT events under All + Outgoing), the row join (list context, the
 * orphan guard, ignored greying, the kind and duration mapping), honest
 * pagination remainders, and the LOG-04 and LOG-05 states.
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

    // Every VM a test builds, so [runVmTest] can end their coroutines.
    private val created = mutableListOf<CallLogViewModel>()

    /**
     * `runTest` that cancels and joins each VM's scope before returning, while
     * the test Main dispatcher is still installed. The VM joins on
     * Dispatchers.Default; left running, its upstream is stopped later by
     * WhileSubscribed's timeout, and that background completion resumes on a
     * Main that no longer exists on the plain JVM, failing whichever test
     * runs next ("uncaught exceptions before the test started").
     */
    private fun runVmTest(block: suspend TestScope.() -> Unit) = runTest {
        try {
            block()
        } finally {
            created.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() }
            created.clear()
        }
    }

    /**
     * A membership DAO whose `observeAll` emits the seeded rows. The base fake
     * emits none, which is what every test here wanted until the list-context
     * join got its own tests.
     */
    private class SeededMembershipDao(
        private val rows: List<ListMembershipEntity>,
    ) : RecordingListMembershipDao() {
        override fun observeAll(): Flow<List<ListMembershipEntity>> = flowOf(rows)
    }

    /**
     * [callLogDenied] = null leaves the permission unreported (the state the VM
     * is in before the screen's first resume); every other value is pushed the
     * way the screen pushes it.
     */
    private fun vm(
        events: List<CallEventEntity>,
        contacts: List<ContactEntity> = listOf(contactFixture(id = 1L, displayName = "Sarah")),
        contactIdArg: String? = null,
        callLogDenied: Boolean? = false,
        callEventRepo: CallEventRepository = FakeCallEventRepository(events),
        memberships: List<ListMembershipEntity> = emptyList(),
        lists: List<ListEntity> = emptyList(),
    ): CallLogViewModel = CallLogViewModel(
        callEventRepo = callEventRepo,
        contactRepo = FakeContactRepository().apply { seed(contacts) },
        listMembershipDao = SeededMembershipDao(memberships),
        listRepo = FakeListRepository().apply { seed(lists) },
        clock = TestClock(now),
        savedStateHandle = SavedStateHandle(
            if (contactIdArg == null) emptyMap() else mapOf(CallLogViewModel.ARG_CONTACT_ID to contactIdArg),
        ),
    ).also { vm ->
        created += vm
        callLogDenied?.let(vm::onCallLogPermissionChanged)
    }

    private suspend fun ReceiveTurbine<CallLogUiState>.awaitReady(): CallLogUiState.Ready {
        while (true) {
            when (val item = awaitItem()) {
                is CallLogUiState.Ready -> return item
                is CallLogUiState.Loading -> continue
                else -> fail("expected Ready, got $item")
            }
        }
    }

    /** The first state that is not Loading. */
    private suspend fun ReceiveTurbine<CallLogUiState>.awaitSettled(): CallLogUiState {
        while (true) {
            val item = awaitItem()
            if (item !is CallLogUiState.Loading) return item
        }
    }

    private fun CallLogUiState.Ready.rowCount(): Int = sections.sumOf { it.rows.size }

    private fun CallLogUiState.Ready.rows(): List<CallLogRow> = sections.flatMap { it.rows }

    private fun CallLogUiState.Ready.rowIds(): List<Long> = rows().map { it.callEventId }

    // ============================================================================
    // The row join: list context, the orphan guard, ignored greying, kinds
    // ============================================================================

    @Test
    fun `list context is the newest membership, and none means no list`() = runVmTest {
        val vm = vm(
            events = listOf(
                callEventFixture(id = 1L, contactId = 1L, occurredAt = at("2026-06-09", "08:00")),
                callEventFixture(id = 2L, contactId = 2L, occurredAt = at("2026-06-09", "07:00")),
            ),
            contacts = listOf(contactFixture(id = 1L), contactFixture(id = 2L)),
            lists = listOf(listFixture(id = 10L, name = "Inner orbit"), listFixture(id = 20L, name = "Old friends")),
            // Two memberships for one person: the later `addedAt` wins, whatever
            // order the rows arrive in. The second person is on no list.
            memberships = listOf(
                ListMembershipEntity(contactId = 1L, listId = 20L, addedAt = at("2026-06-01", "12:00")),
                ListMembershipEntity(contactId = 1L, listId = 10L, addedAt = at("2026-05-01", "12:00")),
            ),
        )
        vm.uiState.test(timeout = 5.seconds) {
            val rows = awaitReady().rows()
            assertEquals("Old friends", rows.first { it.callEventId == 1L }.listName)
            assertEquals("", rows.first { it.callEventId == 2L }.listName)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an event whose contact is gone is dropped, not rendered as a ghost row`() = runVmTest {
        val vm = vm(
            events = listOf(
                callEventFixture(id = 1L, contactId = 1L, occurredAt = at("2026-06-09", "08:00")),
                // No contact row 99 exists (FK-cascade window, fake repo race).
                callEventFixture(id = 2L, contactId = 99L, occurredAt = at("2026-06-09", "07:00")),
            ),
        )
        vm.uiState.test(timeout = 5.seconds) {
            assertEquals(listOf(1L), awaitReady().rowIds())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an ignored person's row carries isIgnored so the screen can grey it`() = runVmTest {
        val vm = vm(
            events = listOf(
                callEventFixture(id = 1L, contactId = 1L, occurredAt = at("2026-06-09", "08:00")),
                callEventFixture(id = 2L, contactId = 2L, occurredAt = at("2026-06-09", "07:00")),
            ),
            contacts = listOf(contactFixture(id = 1L, isIgnored = true), contactFixture(id = 2L)),
        )
        vm.uiState.test(timeout = 5.seconds) {
            val rows = awaitReady().rows()
            assertTrue(rows.first { it.callEventId == 1L }.isIgnored)
            assertFalse(rows.first { it.callEventId == 2L }.isIgnored)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `kinds map to their icon, and only real calls carry a duration`() = runVmTest {
        val vm = vm(
            events = listOf(
                callEventFixture(
                    id = 1L, contactId = 1L, occurredAt = at("2026-06-09", "08:00"),
                    direction = CallDirection.OUTGOING, durationSeconds = 300,
                ),
                callEventFixture(
                    id = 2L, contactId = 1L, occurredAt = at("2026-06-09", "07:00"),
                    direction = CallDirection.INCOMING, durationSeconds = 90,
                ),
                callEventFixture(
                    id = 3L, contactId = 1L, occurredAt = at("2026-06-09", "06:00"),
                    direction = CallDirection.OUTGOING, durationSeconds = 0, source = CallSource.MANUAL,
                ),
                callEventFixture(
                    id = 4L, contactId = 1L, occurredAt = at("2026-06-09", "05:00"),
                    direction = CallDirection.OUTGOING, durationSeconds = 0, source = CallSource.ATTEMPT,
                ),
            ),
        )
        vm.uiState.test(timeout = 5.seconds) {
            val rows = awaitReady().rows().associateBy { it.callEventId }

            assertEquals(CallLogKind.Outgoing, rows.getValue(1L).kind)
            assertEquals("phone-outgoing", rows.getValue(1L).directionIconName)
            assertEquals(UiText.plural(R.plurals.time_duration_minutes, 5, 5), rows.getValue(1L).durationLabel)

            assertEquals(CallLogKind.Incoming, rows.getValue(2L).kind)
            assertEquals("phone-incoming", rows.getValue(2L).directionIconName)
            assertEquals(UiText.plural(R.plurals.time_duration_minutes, 1, 1), rows.getValue(2L).durationLabel)

            assertEquals(CallLogKind.Logged, rows.getValue(3L).kind)
            assertEquals("check-circle", rows.getValue(3L).directionIconName)
            assertNull(rows.getValue(3L).durationLabel, "a logged connection has no length to show")

            assertEquals(CallLogKind.Attempted, rows.getValue(4L).kind)
            assertEquals("phone-slash", rows.getValue(4L).directionIconName)
            assertNull(rows.getValue(4L).durationLabel, "an attempt has no length to show")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `attempts count as reaching out, so they show under All and Outgoing only`() = runVmTest {
        val vm = vm(
            events = listOf(
                callEventFixture(
                    id = 1L, contactId = 1L, occurredAt = at("2026-06-09", "08:00"),
                    direction = CallDirection.OUTGOING, durationSeconds = 0, source = CallSource.ATTEMPT,
                ),
                callEventFixture(
                    id = 2L, contactId = 1L, occurredAt = at("2026-06-09", "07:00"),
                    direction = CallDirection.INCOMING,
                ),
            ),
        )
        vm.uiState.test(timeout = 5.seconds) {
            assertEquals(listOf(1L, 2L), awaitReady().rowIds())

            vm.onFilterChange(CallLogDirectionFilter.OUTGOING)
            assertEquals(listOf(1L), awaitReady().rowIds())

            vm.onFilterChange(CallLogDirectionFilter.INCOMING)
            assertEquals(listOf(2L), awaitReady().rowIds())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ============================================================================
    // Calendar-day grouping — 11pm call vs next-morning 9am read
    // ============================================================================

    @Test
    fun `groups by local calendar day not 24h windows`() = runVmTest {
        val vm = vm(
            events = listOf(
                callEventFixture(id = 1L, contactId = 1L, occurredAt = at("2026-06-09", "08:00")),
                // 10 hours before "now" — but the previous calendar day.
                callEventFixture(id = 2L, contactId = 1L, occurredAt = at("2026-06-08", "23:00")),
            ),
        )
        vm.uiState.test(timeout = 5.seconds) {
            val ready = awaitReady()
            // "Today", "Yesterday" (strings_time.xml, via formatDayHeader).
            assertEquals(
                listOf(UiText.res(R.string.time_day_today), UiText.res(R.string.time_day_yesterday)),
                ready.sections.map { it.label },
            )
            assertEquals(listOf(1L), ready.sections[0].rows.map { it.callEventId })
            assertEquals(listOf(2L), ready.sections[1].rows.map { it.callEventId })
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ============================================================================
    // Wall-clock label
    // ============================================================================

    @Test
    fun `rows carry the wall-clock time of the call`() = runVmTest {
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
    fun `manual events stay visible under All and Outgoing and hide under Incoming`() = runVmTest {
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
    fun `narrowing filter with no matches keeps Ready with empty sections`() = runVmTest {
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
    fun `pagination reveals true increments and reports the real remainder`() = runVmTest {
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
    fun `filter change resets pagination to one page`() = runVmTest {
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

    @Test
    fun `re-choosing the active filter keeps the pages already shown`() = runVmTest {
        // Tapping the chip that is already selected is a no-op in the VM: it
        // must not collapse 400 revealed rows back to one page.
        val events = (0 until 450).map { i ->
            callEventFixture(
                id = (i + 1).toLong(),
                contactId = 1L,
                occurredAt = at("2026-06-09", "08:00").minusSeconds(i * 60L),
            )
        }
        val vm = vm(events)
        vm.uiState.test(timeout = 5.seconds) {
            awaitReady()
            vm.onShowMore()
            assertEquals(400, awaitReady().rowCount())

            vm.onFilterChange(CallLogDirectionFilter.ALL)
            expectNoEvents()

            // The next page continues from 400, which it could not if the
            // re-selection had reset the count to 200.
            vm.onShowMore()
            val third = awaitReady()
            assertEquals(450, third.rowCount())
            assertEquals(0, third.remainingCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ============================================================================
    // Empty — no correlated events at all
    // ============================================================================

    @Test
    fun `no events emits Empty`() = runVmTest {
        val vm = vm(events = emptyList())
        vm.uiState.test(timeout = 5.seconds) {
            assertEquals(CallLogUiState.Empty(), awaitSettled())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ============================================================================
    // LOG-04: "View all calls" narrows the log to one person
    // ============================================================================

    @Test
    fun `contactId route arg shows only that person's calls and names them`() = runVmTest {
        val vm = vm(
            events = listOf(
                callEventFixture(id = 1L, contactId = 1L, occurredAt = at("2026-06-09", "08:00")),
                callEventFixture(id = 2L, contactId = 2L, occurredAt = at("2026-06-09", "07:00")),
                callEventFixture(id = 3L, contactId = 1L, occurredAt = at("2026-06-08", "12:00")),
            ),
            contacts = listOf(
                contactFixture(id = 1L, displayName = "Sarah Levin"),
                contactFixture(id = 2L, displayName = "Marcus Reid"),
            ),
            contactIdArg = "1",
        )
        vm.uiState.test(timeout = 5.seconds) {
            val ready = awaitReady()
            assertEquals(listOf(1L, 3L), ready.sections.flatMap { s -> s.rows.map { it.callEventId } })
            assertEquals(CallLogScope.Person(contactId = 1L, name = "Sarah Levin"), ready.scope)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `contactId route arg accepts the ui id form`() = runVmTest {
        val vm = vm(
            events = listOf(
                callEventFixture(id = 1L, contactId = 1L, occurredAt = at("2026-06-09", "08:00")),
                callEventFixture(id = 2L, contactId = 2L, occurredAt = at("2026-06-09", "07:00")),
            ),
            contacts = listOf(contactFixture(id = 1L), contactFixture(id = 2L)),
            contactIdArg = "c-2",
        )
        vm.uiState.test(timeout = 5.seconds) {
            assertEquals(listOf(2L), awaitReady().sections.flatMap { s -> s.rows.map { it.callEventId } })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `without a contactId the log is everyone's`() = runVmTest {
        val vm = vm(
            events = listOf(
                callEventFixture(id = 1L, contactId = 1L, occurredAt = at("2026-06-09", "08:00")),
                callEventFixture(id = 2L, contactId = 2L, occurredAt = at("2026-06-09", "07:00")),
            ),
            contacts = listOf(contactFixture(id = 1L), contactFixture(id = 2L)),
        )
        vm.uiState.test(timeout = 5.seconds) {
            val ready = awaitReady()
            assertEquals(2, ready.rowCount())
            assertEquals(CallLogScope.Everyone, ready.scope)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a person with no calls is Empty and still named`() = runVmTest {
        val vm = vm(
            events = listOf(callEventFixture(id = 2L, contactId = 2L, occurredAt = at("2026-06-09", "07:00"))),
            contacts = listOf(contactFixture(id = 1L, displayName = "Sarah"), contactFixture(id = 2L)),
            contactIdArg = "1",
        )
        vm.uiState.test(timeout = 5.seconds) {
            assertEquals(CallLogUiState.Empty(CallLogScope.Person(1L, "Sarah")), awaitSettled())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ============================================================================
    // LOG-05: honest states: unknown permission, denied, data failure
    // ============================================================================

    @Test
    fun `stays Loading until the permission is known, never a false Empty`() = runVmTest {
        val vm = vm(events = emptyList(), callLogDenied = null)
        vm.uiState.test(timeout = 5.seconds) {
            assertTrue(awaitItem() is CallLogUiState.Loading)
            expectNoEvents()
            vm.onCallLogPermissionChanged(denied = true)
            assertEquals(CallLogUiState.PermissionDenied(), awaitSettled())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `denied with no history is PermissionDenied, not Empty`() = runVmTest {
        val vm = vm(events = emptyList(), callLogDenied = true)
        vm.uiState.test(timeout = 5.seconds) {
            assertEquals(CallLogUiState.PermissionDenied(), awaitSettled())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `denied with history keeps the rows and flags the notice`() = runVmTest {
        val vm = vm(
            events = listOf(callEventFixture(id = 1L, contactId = 1L, occurredAt = at("2026-06-09", "08:00"))),
            callLogDenied = true,
        )
        vm.uiState.test(timeout = 5.seconds) {
            val ready = awaitReady()
            assertEquals(1, ready.rowCount())
            assertTrue(ready.callLogDenied)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `granting access on resume replaces the denied state`() = runVmTest {
        val vm = vm(events = emptyList(), callLogDenied = true)
        vm.uiState.test(timeout = 5.seconds) {
            assertEquals(CallLogUiState.PermissionDenied(), awaitSettled())
            vm.onCallLogPermissionChanged(denied = false)
            assertEquals(CallLogUiState.Empty(), awaitSettled())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a failing stream shows Error, and Retry recovers`() = runVmTest {
        var attempts = 0
        val healthy = FakeCallEventRepository(
            listOf(callEventFixture(id = 1L, contactId = 1L, occurredAt = at("2026-06-09", "08:00"))),
        )
        // Fails on the first subscription only, like a transient read error.
        val flaky = object : CallEventRepository by healthy {
            override fun observeForLog(limit: Int): Flow<List<CallEventEntity>> {
                attempts += 1
                return if (attempts == 1) {
                    flow { throw IOException("disk") }
                } else {
                    healthy.observeForLog(limit)
                }
            }
        }
        val vm = vm(events = emptyList(), callEventRepo = flaky)
        vm.uiState.test(timeout = 5.seconds) {
            assertEquals(CallLogUiState.Error(), awaitSettled())
            vm.onRetry()
            assertEquals(1, awaitReady().rowCount())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `one person's failing stream is an Error that still knows who, and Retry names them`() = runVmTest {
        var attempts = 0
        val healthy = FakeCallEventRepository(
            listOf(callEventFixture(id = 1L, contactId = 1L, occurredAt = at("2026-06-09", "08:00"))),
        )
        // With a contactId on the route the VM reads observeForContact, so
        // that is the stream that has to fail (observeForLog is never called).
        val flaky = object : CallEventRepository by healthy {
            override fun observeForContact(contactId: Long, limit: Int): Flow<List<CallEventEntity>> {
                attempts += 1
                return if (attempts == 1) {
                    flow { throw IOException("disk") }
                } else {
                    healthy.observeForContact(contactId, limit)
                }
            }
        }
        val vm = vm(
            events = emptyList(),
            contacts = listOf(contactFixture(id = 1L, displayName = "Sarah Levin")),
            contactIdArg = "1",
            callEventRepo = flaky,
        )
        vm.uiState.test(timeout = 5.seconds) {
            // The read failed before its first emission, so the name has not
            // loaded: the Error carries the person with a blank name, and the
            // screen titles it "Call history" so TalkBack has a pane title.
            assertEquals(CallLogUiState.Error(CallLogScope.Person(contactId = 1L, name = "")), awaitSettled())
            vm.onRetry()
            val ready = awaitReady()
            assertEquals(listOf(1L), ready.rowIds())
            assertEquals(CallLogScope.Person(contactId = 1L, name = "Sarah Levin"), ready.scope)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
