package app.orbit.ui.screens.week

import androidx.lifecycle.SavedStateHandle
import app.orbit.data.entity.CallDirection
import app.orbit.domain.FakeCallEventRepository
import app.orbit.domain.FakeContactRepository
import app.orbit.domain.FakeListRepository
import app.orbit.domain.callEventFixture
import app.orbit.domain.clock.TestClock
import app.orbit.domain.contactFixture
import app.orbit.domain.listFixture
import app.orbit.testutil.MainDispatcherRule
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * HOME-13's ViewModel: its one state contract from Loading to Ready, the
 * two errors (Try again when a read failed, Go back alone when there is no
 * list to read), and today moving on with a resume.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WeekViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    // Midday UTC, so "an hour ago" is today in any zone the suite runs in.
    private val now: Instant = Instant.parse("2026-10-07T12:00:00Z")
    private val clock = TestClock(now)
    private val zone: ZoneId = ZoneId.systemDefault()
    private val lists = FakeListRepository(initialLists = listOf(listFixture(id = 5L, name = "Inner orbit")))
    private val contacts = FakeContactRepository(listOf(contactFixture(id = 7L, displayName = "Kai Mensah")))
    private val calls = FakeCallEventRepository(
        listOf(
            callEventFixture(1L, 7L, now.minus(Duration.ofHours(1)), CallDirection.OUTGOING, 14 * 60),
            // Twenty days back: this week and the two before it.
            callEventFixture(2L, 7L, now.minus(Duration.ofDays(20)), CallDirection.INCOMING, 9 * 60),
        ),
    )

    private fun vm(listId: String = "5") = WeekViewModel(
        SavedStateHandle(mapOf(WeekViewModel.ARG_LIST_ID to listId)),
        lists,
        calls,
        contacts,
        clock,
    )

    private suspend fun WeekViewModel.ready(): WeekUiState.Ready =
        uiState.first { it is WeekUiState.Ready } as WeekUiState.Ready

    @Test
    fun `it is Loading until the first read answers`() {
        assertEquals(WeekUiState.Loading, vm().uiState.value)
    }

    @Test
    fun `the list's weeks arrive newest first, this week ending today`() = runTest {
        val ready = vm().ready()
        val today = now.atZone(zone).toLocalDate()

        assertEquals("Inner orbit", ready.listName)
        assertEquals(today, ready.today)
        assertEquals(3, ready.weeks.size)
        assertEquals(today, ready.weeks[0].lastDay)
        assertEquals(today.minusDays(6), ready.weeks[0].firstDay)
        assertEquals(listOf(1L), ready.weeks[0].days.flatMap { d -> d.calls.map { it.callEventId } })
        assertEquals("Kai Mensah", ready.weeks[0].days.last().calls.single().contactName)
        assertEquals(listOf(2L), ready.weeks[2].days.flatMap { d -> d.calls.map { it.callEventId } })
    }

    @Test
    fun `a list id that does not parse is an error with nothing to retry`() = runTest {
        assertEquals(WeekUiState.Error(canRetry = false), vm(listId = "not-a-list").uiState.first { it !is WeekUiState.Loading })
    }

    @Test
    fun `a list that is gone is an error with nothing to retry`() = runTest {
        assertEquals(WeekUiState.Error(canRetry = false), vm(listId = "99").uiState.first { it !is WeekUiState.Loading })
    }

    @Test
    fun `a failed read offers Try again, and Try again recovers`() = runTest {
        lists.failObserveById = true
        val vm = vm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        assertEquals(WeekUiState.Error(canRetry = true), vm.uiState.value)

        lists.failObserveById = false
        vm.onRetry()

        assertIs<WeekUiState.Ready>(vm.uiState.value)
    }

    @Test
    fun `a resume on a new day moves this week on`() = runTest {
        val vm = vm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        val before = assertIs<WeekUiState.Ready>(vm.uiState.value)

        // The same day again changes nothing.
        vm.onResumed()
        assertEquals(before, vm.uiState.value)

        clock.advance(Duration.ofDays(1))
        vm.onResumed()

        val after = assertIs<WeekUiState.Ready>(vm.uiState.value)
        val tomorrow = now.plus(Duration.ofDays(1)).atZone(zone).toLocalDate()
        assertEquals(tomorrow, after.weeks[0].lastDay)
        assertEquals(tomorrow, after.today)
        // Yesterday's call is now one column further back in this week.
        assertEquals(1, after.weeks[0].days[5].calls.size)
    }
}
