package app.orbit.ui.screens.onboarding

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.orbit.data.AppPrefs
import app.orbit.testutil.MainDispatcherRule
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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
 * Behavioral tests for [OnboardingDoneViewModel] — the single canonical writer
 * of `AppPrefs.setOnboardingComplete(true)` (the looped-onboarding pain point
 * requires this be exactly-once and transactional).
 *
 * The VM fires its write from `init`, so simply constructing it must:
 *   1. flip `AppPrefs.isOnboardingComplete` → true,
 *   2. clear `AppPrefs.lastOnboardingStep` (F-3 — a future re-onboarding starts
 *      cleanly at Welcome rather than the last persisted step),
 *   3. flip the VM's own [OnboardingDoneViewModel.completed] StateFlow → true
 *      once the write lands (gates the "Take me home" CTA).
 *
 * Fixture pattern mirrors SettingsViewModelTest:
 *   - Robolectric + real DataStore via `ApplicationProvider.getApplicationContext()`.
 *   - `@Config(application = Application::class)` bypasses `OrbitApp.onCreate`.
 *   - `runBlocking` (real time) — DataStore writes hop to a real IO dispatcher
 *     that does not cooperate with `runTest`'s virtual clock.
 *   - `@After` wipes the persisted DataStore so neighbouring test classes see
 *     fresh defaults regardless of runner ordering.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class OnboardingDoneViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val context: android.content.Context get() =
        ApplicationProvider.getApplicationContext()

    private fun buildAppPrefs(): AppPrefs = AppPrefs(context)

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

    // ============================================================================
    // Test 1 — reaching the Done route persists onboarding-complete = true.
    // ============================================================================

    @Test
    fun `init writes onboarding complete`() = runBlocking {
        val prefs = buildAppPrefs()
        OnboardingDoneViewModel(prefs)

        val complete = withTimeout(30_000L) {
            prefs.isOnboardingComplete.filter { it }.first()
        }
        assertTrue(complete, "init must persist onboarding-complete = true")
    }

    // ============================================================================
    // Test 2 — the persisted resume key is cleared on completion (F-3) so a
    // future re-onboarding starts at Welcome, not the last step.
    // ============================================================================

    @Test
    fun `init clears the last onboarding step`() = runBlocking {
        val prefs = buildAppPrefs()
        // Seed a stale resume key and let it land before constructing the VM.
        prefs.setLastOnboardingStep(OnboardingStep.Sync.name)
        withTimeout(30_000L) {
            prefs.lastOnboardingStep.filter { it != null }.first()
        }

        OnboardingDoneViewModel(prefs)

        val cleared = withTimeout(30_000L) {
            prefs.lastOnboardingStep.filter { it == null }.first()
        }
        assertEquals(null, cleared, "the resume key must be cleared on completion")
    }

    // ============================================================================
    // Test 3 — the `completed` StateFlow flips true only after the write lands;
    // the screen gates the "Take me home" CTA on this.
    // ============================================================================

    @Test
    fun `completed flips true after the write lands`() = runBlocking {
        val prefs = buildAppPrefs()
        val vm = OnboardingDoneViewModel(prefs)

        val completed = withTimeout(30_000L) {
            vm.completed.filter { it }.first()
        }
        assertTrue(completed, "completed must flip true once setOnboardingComplete returns")
    }
}
