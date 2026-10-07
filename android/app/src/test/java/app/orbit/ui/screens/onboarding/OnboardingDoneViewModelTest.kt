package app.orbit.ui.screens.onboarding

import android.app.Application
import app.orbit.R
import app.orbit.data.AppPrefs
import app.orbit.testutil.MainDispatcherRule
import app.orbit.testutil.awaitValue
import app.orbit.testutil.newFailingStore
import app.orbit.testutil.newPrefs
import app.orbit.ui.util.UiText
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behavioral tests for [OnboardingDoneViewModel], the single canonical writer
 * of `AppPrefs.setOnboardingComplete(true)` (the looped-onboarding pain point
 * requires this be exactly-once and transactional).
 *
 * The VM fires its write from `init`, so simply constructing it must:
 *   1. flip `AppPrefs.isOnboardingComplete` → true,
 *   2. clear `AppPrefs.lastOnboardingStep` (F-3: a future re-onboarding starts
 *      cleanly at Welcome rather than the last persisted step),
 *   3. flip the VM's own [OnboardingDoneViewModel.completed] StateFlow → true
 *      once the write lands (gates the "Open Orbit" CTA).
 *
 * And `onNudgeLauncherFired` records that notifications were asked for
 * (ONB-30), the flag Settings reads to tell never-asked from turned-off; when
 * that write fails the user is told (rules.md Code 3). When the completion
 * write itself fails the user is told too, with a Try again that finishes it
 * (test 6); before 2026-10-06 "Open Orbit" stayed disabled with nothing said.
 *
 * Fixture:
 *   - Robolectric; `@Config(application = Application::class)` bypasses
 *     `OrbitApp.onCreate`.
 *   - Each method gets its own DataStore file and scope
 *     (`testutil/TestDataStore.kt`), cancelled in `@After`, so no method sees
 *     another's writes or waits on a write another method stranded. The class
 *     used to share the process-wide singleton and reset it by hand, which is
 *     where its 30 second timeouts came from.
 *   - `runBlocking` (real time): DataStore writes hop to a real IO dispatcher
 *     that does not cooperate with `runTest`'s virtual clock.
 *   - Every method waits for `vm.completed` before asserting, so no ViewModel
 *     coroutine is alive when the rule resets `Dispatchers.Main`; prefs reads
 *     poll (`awaitValue`), never subscribe mid-write (see TestDataStore.kt).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class OnboardingDoneViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefs: AppPrefs by lazy { tmp.newPrefs(storeScope) }

    @After
    fun tearDown() {
        storeScope.cancel()
    }

    private suspend fun OnboardingDoneViewModel.awaitCompleted() {
        withTimeout(30_000L) { completed.first { it } }
    }

    // ============================================================================
    // Test 1: reaching the Done route persists onboarding-complete = true.
    // ============================================================================

    @Test
    fun `init writes onboarding complete`() = runBlocking {
        val vm = OnboardingDoneViewModel(prefs)
        vm.awaitCompleted()

        awaitValue(true) { prefs.isOnboardingComplete.first() }
        assertTrue(prefs.isOnboardingComplete.first(), "init must persist onboarding-complete = true")
    }

    // ============================================================================
    // Test 2: the persisted resume key is cleared on completion (F-3) so a
    // future re-onboarding starts at Welcome, not the last step.
    // ============================================================================

    @Test
    fun `init clears the last onboarding step`() = runBlocking {
        // Seed a stale resume key and let it land before constructing the VM.
        prefs.setLastOnboardingStep(OnboardingStep.Sync.name)
        awaitValue(OnboardingStep.Sync.name) { prefs.lastOnboardingStep.first() }

        val vm = OnboardingDoneViewModel(prefs)
        vm.awaitCompleted()

        awaitValue(null) { prefs.lastOnboardingStep.first() }
        assertEquals(null, prefs.lastOnboardingStep.first(), "the resume key must be cleared on completion")
    }

    @Test
    fun `init forgets the onboarding list so a later re-onboarding starts a new one`() =
        runBlocking {
            prefs.setOnboardingListId(42L)
            awaitValue(42L) { prefs.onboardingListId.first() }

            val vm = OnboardingDoneViewModel(prefs)
            vm.awaitCompleted()

            awaitValue(null) { prefs.onboardingListId.first() }
            assertEquals(null, prefs.onboardingListId.first())
        }

    // ============================================================================
    // Test 3: the `completed` StateFlow flips true only after the write lands;
    // the screen gates the "Open Orbit" CTA on this.
    // ============================================================================

    @Test
    fun `completed flips true after the write lands`() = runBlocking {
        val vm = OnboardingDoneViewModel(prefs)

        vm.awaitCompleted()
        assertTrue(vm.completed.value, "completed must flip true once setOnboardingComplete returns")
        assertTrue(prefs.isOnboardingComplete.first(), "and the write has landed by then")
    }

    // ============================================================================
    // Test 4 (ONB-30): the nudge ask's launcher resolving marks notifications
    // as asked once, whatever the answer.
    // ============================================================================

    @Test
    fun `onNudgeLauncherFired records that notifications were asked for`() = runBlocking {
        val vm = OnboardingDoneViewModel(prefs)
        vm.awaitCompleted()
        assertEquals(false, prefs.hasAskedNotifications.first(), "nothing asked yet")

        vm.onNudgeLauncherFired()

        awaitValue(true) { prefs.hasAskedNotifications.first() }
        assertTrue(prefs.hasAskedNotifications.first())
    }

    // ============================================================================
    // Test 5 (rules.md Code 3): a failed "asked once" write says "Couldn't save
    // your change" instead of leaving Settings quietly reading "Not allowed"
    // for a permission the phone will no longer ask about.
    // ============================================================================

    @Test
    fun `a failed asked-once write tells the user`() = runBlocking {
        // The completion write in init must land; only the flag's write fails.
        val store = tmp.newFailingStore(storeScope).apply { failWrites = false }
        val failingPrefs = AppPrefs(store)
        val vm = OnboardingDoneViewModel(failingPrefs)
        vm.awaitCompleted()
        store.failWrites = true
        val snackbar = async { withTimeout(30_000L) { vm.snackbarEvents.first() } }
        delay(50)

        vm.onNudgeLauncherFired()

        assertEquals(UiText.res(R.string.components_snackbar_save_failed), snackbar.await().message)
        assertEquals(false, failingPrefs.hasAskedNotifications.first(), "the flag was not written")
    }

    // ============================================================================
    // Test 6 (rules.md Code 3): a failed completion write says "Couldn't save
    // your change" with Try again, keeps "Open Orbit" disabled, and the retry
    // finishes the write once the store recovers. Fails without the fix: the
    // exception reached the thread's uncaught handler and nothing was emitted.
    // ============================================================================

    @Test
    fun `a failed completion write tells the user and Try again finishes it`() = runBlocking {
        val store = tmp.newFailingStore(storeScope) // failWrites = true from the start
        val failingPrefs = AppPrefs(store)
        val vm = OnboardingDoneViewModel(failingPrefs)
        delay(50)
        // The init write failed and was caught: no exception reached the test,
        // nothing was written, and "Open Orbit" stays disabled. (The event flow
        // has no replay, so the init failure's snackbar is observed through the
        // same function on the retry below, with a collector in place.)
        assertEquals(false, vm.completed.value, "Open Orbit stays disabled")
        assertEquals(false, failingPrefs.isOnboardingComplete.first(), "nothing was written")

        val snackbar = async { withTimeout(30_000L) { vm.snackbarEvents.first() } }
        delay(50)
        vm.onRetryComplete()
        val event = snackbar.await()
        assertEquals(UiText.res(R.string.components_snackbar_save_failed), event.message)
        assertEquals(UiText.res(R.string.components_error_retry), event.actionLabel)
        assertEquals(false, vm.completed.value, "a failed retry keeps Open Orbit disabled")

        store.failWrites = false
        vm.onRetryComplete()
        vm.awaitCompleted()
        awaitValue(true) { failingPrefs.isOnboardingComplete.first() }
    }
}
