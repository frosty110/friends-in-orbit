package app.orbit

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import app.orbit.calllog.ContentObserverController
import app.orbit.data.AppPrefs
import app.orbit.data.db.OrbitDatabase
import app.orbit.data.repository.ResetOutcome
import app.orbit.data.repository.ResetService
import app.orbit.domain.FakeCallEventRepository
import app.orbit.domain.FakeContactRepository
import app.orbit.domain.callEventFixture
import app.orbit.domain.clock.TestClock
import app.orbit.domain.contactFixture
import app.orbit.nav.Routes
import app.orbit.testutil.MainDispatcherRule
import app.orbit.testutil.awaitValue
import app.orbit.testutil.newPrefs
import app.orbit.ui.screens.onboarding.OnboardingStep
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
 * the NOTE-02 post-call prompt, the auto privacy curtain, and the reset outcome
 * it hands MainActivity (SET-06).
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
        contactRepo: FakeContactRepository = FakeContactRepository(),
    ) = AppViewModel(prefs, callEventRepo, contactRepo, TestClock(now), resetService)

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

    // ── post-call prompt (NOTE-02) ───────────────────────────────────────────

    @Test
    fun `checkPostCallPrompt surfaces a recent unnoted outgoing call`() = runBlocking {
        val contactRepo = FakeContactRepository(listOf(contactFixture(id = 1L, displayName = "Sam")))
        val callEventRepo = FakeCallEventRepository(
            listOf(callEventFixture(id = 7L, contactId = 1L, occurredAt = now.minus(Duration.ofMinutes(1)))),
        )
        val vm = buildVm(buildAppPrefs(), callEventRepo, contactRepo)

        vm.checkPostCallPrompt()

        val prompt = assertNotNull(
            withTimeout(30_000L) { vm.postCallPrompt.filter { it != null }.first() },
        )
        assertEquals(7L, prompt.callEventId)
        assertEquals(1L, prompt.contactId)
        assertEquals("Sam", prompt.contactName)
    }

    @Test
    fun `checkPostCallPrompt stays clear when there is no recent call`() = runBlocking {
        val vm = buildVm(buildAppPrefs()) // empty repositories

        vm.checkPostCallPrompt()

        assertNull(vm.postCallPrompt.first())
    }

    @Test
    fun `dismissPostCallPrompt clears the banner and suppresses the re-prompt`() = runBlocking {
        val contactRepo = FakeContactRepository(listOf(contactFixture(id = 1L, displayName = "Sam")))
        val callEventRepo = FakeCallEventRepository(
            listOf(callEventFixture(id = 7L, contactId = 1L, occurredAt = now.minus(Duration.ofMinutes(1)))),
        )
        val vm = buildVm(buildAppPrefs(), callEventRepo, contactRepo)

        vm.checkPostCallPrompt()
        withTimeout(30_000L) { vm.postCallPrompt.filter { it != null }.first() }

        vm.dismissPostCallPrompt(7L)
        assertNull(vm.postCallPrompt.first(), "banner clears immediately on dismiss")

        // The same call must not re-surface on a later resume.
        vm.checkPostCallPrompt()
        assertNull(vm.postCallPrompt.first(), "a dismissed call is suppressed on re-check")
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
