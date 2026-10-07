package app.orbit

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import app.orbit.calllog.ContentObserverController
import app.orbit.data.AppPrefs
import app.orbit.data.dao.WaitingCallRow
import app.orbit.data.db.OrbitDatabase
import app.orbit.data.entity.CallDirection
import app.orbit.data.repository.ResetOutcome
import app.orbit.data.repository.ResetService
import app.orbit.data.repository.WaitingCalls
import app.orbit.domain.FakeCallEventRepository
import app.orbit.domain.clock.TestClock
import app.orbit.nav.Routes
import app.orbit.testutil.MainDispatcherRule
import app.orbit.testutil.awaitValue
import app.orbit.testutil.newFailingStore
import app.orbit.testutil.newPrefs
import app.orbit.ui.screens.onboarding.OnboardingStep
import app.orbit.ui.util.UiText
import app.orbit.ui.util.formatDuration
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behavioral tests for [AppViewModel] — boot-time start-destination resolution,
 * Home's calls waiting for a note (HOME-14), the auto privacy curtain, and the
 * reset outcome it hands MainActivity (SET-06).
 *
 * Fixture pattern (mirrors OnboardingDoneViewModelTest / SettingsViewModelTest):
 *   - Robolectric for the Context; a real DataStore per test method, built by
 *     `tmp.newPrefs(storeScope)` (testutil/TestDataStore.kt) on a scope the
 *     `@After` cancels, so no state and no stranded write reaches the next method.
 *   - `@Config(application = Application::class)` bypasses `OrbitApp.onCreate`.
 *   - `MainDispatcherRule` (UnconfinedTestDispatcher) so the VM's init/launch
 *     bodies run eagerly under `runBlocking`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class AppViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val now: Instant = Instant.parse("2026-01-01T12:00:00Z")

    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefs: AppPrefs by lazy { tmp.newPrefs(storeScope) }

    private fun buildAppPrefs(): AppPrefs = prefs

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: OrbitDatabase

    /**
     * The wipe itself is pinned by `ResetServiceTest`; here the service only
     * needs to record an outcome, so the no-op `performReset` keeps WorkManager
     * and Room out of the picture.
     */
    private class NoWipeResetService(
        ctx: Context,
        db: OrbitDatabase,
        prefs: AppPrefs,
    ) : ResetService(ctx, db, prefs, ContentObserverController(ctx)) {
        override suspend fun performReset() = Unit
    }

    private val resetService: NoWipeResetService by lazy { NoWipeResetService(context, db, prefs) }

    private fun buildVm(
        prefs: AppPrefs,
        callEventRepo: FakeCallEventRepository = FakeCallEventRepository(),
    ): AppViewModel {
        val clock = TestClock(now)
        return AppViewModel(prefs, WaitingCalls(callEventRepo, prefs, clock), clock, resetService)
    }

    @Before
    fun openDb() {
        db = Room.inMemoryDatabaseBuilder(context, OrbitDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
        storeScope.cancel()
    }

    private suspend fun startDestinationOf(prefs: AppPrefs): String? =
        withTimeout(30_000L) { buildVm(prefs).startDestination.filter { it != null }.first() }

    // ── start destination ────────────────────────────────────────────────────

    @Test
    fun `start destination is Home when onboarding is complete`() = runBlocking {
        val prefs = buildAppPrefs()
        prefs.setOnboardingComplete(true)
        awaitValue(true) { prefs.isOnboardingComplete.first() }

        assertEquals(Routes.Home, startDestinationOf(prefs))
    }

    @Test
    fun `start destination resumes at the persisted onboarding step`() = runBlocking {
        val prefs = buildAppPrefs()
        prefs.setOnboardingComplete(false)
        prefs.setLastOnboardingStep(OnboardingStep.PermCallLog.name)
        awaitValue(OnboardingStep.PermCallLog.name) { prefs.lastOnboardingStep.first() }

        assertEquals(Routes.OnboardPermCallLog, startDestinationOf(prefs))
    }

    @Test
    fun `FirstList resume falls back to Sync because listId is not recoverable`() = runBlocking {
        val prefs = buildAppPrefs()
        prefs.setOnboardingComplete(false)
        prefs.setLastOnboardingStep(OnboardingStep.FirstList.name)
        awaitValue(OnboardingStep.FirstList.name) { prefs.lastOnboardingStep.first() }

        assertEquals(Routes.OnboardSync, startDestinationOf(prefs))
    }

    @Test
    fun `start destination is Welcome when not onboarded and no step is persisted`() = runBlocking {
        val prefs = buildAppPrefs()
        prefs.setOnboardingComplete(false)
        prefs.setLastOnboardingStep(null)

        assertEquals(Routes.OnboardWelcome, startDestinationOf(prefs))
    }

    // ── calls waiting for a note (HOME-14 over NOTE-05) ──────────────────────

    private fun waitingRow(id: Long, contactId: Long, name: String, ago: Duration, seconds: Int = 14 * 60) =
        WaitingCallRow(
            callEventId = id,
            contactId = contactId,
            occurredAt = now.minus(ago),
            direction = CallDirection.OUTGOING,
            durationSeconds = seconds,
            displayName = name,
            photoUri = null,
        )

    @Test
    fun `nothing is read until Home resumes, then the waiting calls are worded`() = runBlocking {
        val calls = FakeCallEventRepository().apply {
            waitingRows.value = listOf(waitingRow(7L, 1L, "Kai Mensah", ago = Duration.ofHours(2)))
        }
        val vm = buildVm(buildAppPrefs(), calls)

        vm.notesWaiting.test {
            assertEquals(emptyList(), awaitItem(), "no resume yet: no read")
            vm.onHomeResumed()
            val shown = awaitItem().single()
            assertEquals(7L, shown.callEventId)
            assertEquals(1L, shown.contactId)
            assertEquals("Kai Mensah", shown.name)
            // "14 min · 2 hours ago", from the app's two formatters.
            assertEquals(
                UiText.res(
                    R.string.components_notes_waiting_meta,
                    formatDuration(14 * 60),
                    UiText.plural(R.plurals.time_ago_hours, 2, 2),
                ),
                shown.meta,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the window starts 24 hours before the resume, and calls under a minute are left out`() = runBlocking {
        val calls = FakeCallEventRepository().apply {
            waitingRows.value = listOf(
                waitingRow(1L, 1L, "Kai", ago = Duration.ofHours(23)),
                waitingRow(2L, 2L, "Mara", ago = Duration.ofHours(25)),
                waitingRow(3L, 3L, "Sam", ago = Duration.ofHours(1), seconds = 59),
            )
        }
        val vm = buildVm(buildAppPrefs(), calls)

        vm.notesWaiting.test {
            awaitItem()
            vm.onHomeResumed()
            assertEquals(listOf(1L), awaitItem().map { it.callEventId })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `dismiss takes the call off, says so with Undo, and Undo brings it back`() = runBlocking {
        val calls = FakeCallEventRepository().apply {
            waitingRows.value = listOf(
                waitingRow(7L, 1L, "Kai", ago = Duration.ofHours(2)),
                waitingRow(8L, 2L, "Mara", ago = Duration.ofHours(3)),
            )
        }
        val prefs = buildAppPrefs()
        val vm = buildVm(prefs, calls)
        vm.onHomeResumed()

        vm.notesWaitingEvents.test {
            vm.dismissNotesWaiting(listOf(7L, 8L))
            val event = awaitItem()
            assertEquals(UiText.plural(R.plurals.home_snackbar_calls_dismissed, 2, 2), event.message)
            assertEquals(listOf(7L, 8L), event.undoCallEventIds)
            cancelAndIgnoreRemainingEvents()
        }
        awaitValue(setOf(7L, 8L)) { prefs.dismissedPostCallIds.first() }

        vm.undoDismissNotesWaiting(listOf(7L, 8L))
        awaitValue(emptySet()) { prefs.dismissedPostCallIds.first() }
    }

    @Test
    fun `a dismissal that cannot be saved says so and offers no Undo`() = runBlocking {
        val store = tmp.newFailingStore(storeScope)
        val vm = buildVm(AppPrefs(store))

        vm.notesWaitingEvents.test {
            vm.dismissNotesWaiting(listOf(7L))
            val event = awaitItem()
            assertEquals(UiText.res(R.string.components_snackbar_save_failed), event.message)
            assertTrue(event.undoCallEventIds.isEmpty(), "nothing was dismissed, so nothing to undo")
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── reset outcome (SET-06) ──────────────────────────────────────────────

    // The outcome is the service's sticky state, mirrored for MainActivity,
    // which reads it after Settings is gone and clears it before it restarts
    // the task, so the same process never acts on it twice.
    @Test
    fun `resetOutcome mirrors the service and is cleared once handled`() = runBlocking {
        val vm = buildVm(buildAppPrefs())
        assertNull(vm.resetOutcome.value)

        resetService.resetAll()

        assertEquals(ResetOutcome.Completed, vm.resetOutcome.value, "readable after the fact")
        vm.onResetOutcomeHandled()
        assertNull(vm.resetOutcome.value)
        assertNull(resetService.outcome.value, "cleared on the service itself")
    }

    // ── privacy curtain ──────────────────────────────────────────────────────

    @Test
    fun `privacy curtain follows foreground state`() = runBlocking {
        val vm = buildVm(buildAppPrefs())

        vm.privacyCurtainActive.test {
            assertEquals(false, awaitItem(), "foreground by default → no curtain")
            vm.onForegroundChanged(false)
            assertEquals(true, awaitItem(), "backgrounded → curtain on")
            vm.onForegroundChanged(true)
            assertEquals(false, awaitItem(), "foreground again → curtain off")
            cancelAndIgnoreRemainingEvents()
        }
    }
}
