package app.orbit

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import app.orbit.data.AppPrefs
import app.orbit.domain.FakeCallEventRepository
import app.orbit.domain.FakeContactRepository
import app.orbit.domain.callEventFixture
import app.orbit.domain.clock.TestClock
import app.orbit.domain.contactFixture
import app.orbit.nav.Routes
import app.orbit.testutil.MainDispatcherRule
import app.orbit.ui.screens.onboarding.OnboardingStep
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behavioral tests for [AppViewModel] — boot-time start-destination resolution,
 * the NOTE-02 post-call prompt, and the auto privacy curtain.
 *
 * Fixture pattern (mirrors OnboardingDoneViewModelTest / SettingsViewModelTest):
 *   - Robolectric + real DataStore via `ApplicationProvider.getApplicationContext()`.
 *   - `@Config(application = Application::class)` bypasses `OrbitApp.onCreate`.
 *   - `MainDispatcherRule` (UnconfinedTestDispatcher) so the VM's init/launch
 *     bodies run eagerly under `runBlocking`.
 *   - `@After` wipes the persisted DataStore so neighbouring classes see defaults.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class AppViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val now: Instant = Instant.parse("2026-01-01T12:00:00Z")

    private val context: android.content.Context get() =
        ApplicationProvider.getApplicationContext()

    private fun buildAppPrefs(): AppPrefs = AppPrefs(context)

    private fun buildVm(
        prefs: AppPrefs,
        callEventRepo: FakeCallEventRepository = FakeCallEventRepository(),
        contactRepo: FakeContactRepository = FakeContactRepository(),
    ) = AppViewModel(prefs, callEventRepo, contactRepo, TestClock(now))

    @After
    fun clearDataStore() {
        runBlocking {
            val prefs = buildAppPrefs()
            prefs.setOnboardingComplete(false)
            prefs.setLastOnboardingStep(null)
        }
        val prefsDir = java.io.File(context.filesDir.parentFile, "datastore")
        if (prefsDir.exists()) prefsDir.deleteRecursively()
    }

    private suspend fun startDestinationOf(prefs: AppPrefs): String? =
        withTimeout(30_000L) { buildVm(prefs).startDestination.filter { it != null }.first() }

    // ── start destination ────────────────────────────────────────────────────

    @Test
    fun `start destination is Home when onboarding is complete`() = runBlocking {
        val prefs = buildAppPrefs()
        prefs.setOnboardingComplete(true)
        withTimeout(30_000L) { prefs.isOnboardingComplete.filter { it }.first() }

        assertEquals(Routes.Home, startDestinationOf(prefs))
    }

    @Test
    fun `start destination resumes at the persisted onboarding step`() = runBlocking {
        val prefs = buildAppPrefs()
        prefs.setOnboardingComplete(false)
        prefs.setLastOnboardingStep(OnboardingStep.PermCallLog.name)
        withTimeout(30_000L) { prefs.lastOnboardingStep.filter { it != null }.first() }

        assertEquals(Routes.OnboardPermCallLog, startDestinationOf(prefs))
    }

    @Test
    fun `FirstList resume falls back to Sync because listId is not recoverable`() = runBlocking {
        val prefs = buildAppPrefs()
        prefs.setOnboardingComplete(false)
        prefs.setLastOnboardingStep(OnboardingStep.FirstList.name)
        withTimeout(30_000L) { prefs.lastOnboardingStep.filter { it != null }.first() }

        assertEquals(Routes.OnboardSync, startDestinationOf(prefs))
    }

    @Test
    fun `resume maps PermContacts to its route`() = runBlocking {
        val prefs = buildAppPrefs()
        prefs.setOnboardingComplete(false)
        prefs.setLastOnboardingStep(OnboardingStep.PermContacts.name)
        withTimeout(30_000L) { prefs.lastOnboardingStep.filter { it != null }.first() }

        assertEquals(Routes.OnboardPermContacts, startDestinationOf(prefs))
    }

    @Test
    fun `resume maps PermNotifications to its route`() = runBlocking {
        val prefs = buildAppPrefs()
        prefs.setOnboardingComplete(false)
        prefs.setLastOnboardingStep(OnboardingStep.PermNotifications.name)
        withTimeout(30_000L) { prefs.lastOnboardingStep.filter { it != null }.first() }

        assertEquals(Routes.OnboardPermNotifs, startDestinationOf(prefs))
    }

    @Test
    fun `resume maps Sync to its route`() = runBlocking {
        val prefs = buildAppPrefs()
        prefs.setOnboardingComplete(false)
        prefs.setLastOnboardingStep(OnboardingStep.Sync.name)
        withTimeout(30_000L) { prefs.lastOnboardingStep.filter { it != null }.first() }

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
