package app.orbit.ui.screens.onboarding

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import app.orbit.data.AppPrefs
import app.orbit.testutil.MainDispatcherRule
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behavioral tests for [OnboardingPermissionsViewModel].
 *
 * **Shape-only tests.** The VM calls [androidx.core.content.ContextCompat.checkSelfPermission]
 * directly (no `PermissionSource` interface — authoring a testability seam is
 * out of scope here). JVM tests only verify that the StateFlow emits an
 * instance of [OnboardingPermissionsUiState.Ready] on initial subscription and
 * after `onRefresh()`. The boolean values depend on the Robolectric test
 * host's permission grants and are NOT asserted — on-device validation covers
 * boolean correctness.
 *
 * Robolectric fixture (same pattern as SettingsViewModelTest):
 *   - `@Config(application = Application::class)` bypasses `OrbitApp.onCreate`
 *     which schedules WorkManager work (WorkManager isn't initialized in
 *     JVM test context; would throw).
 *   - `ApplicationProvider.getApplicationContext()` supplies the
 *     `@ApplicationContext`-scoped [android.content.Context] directly (no Hilt
 *     in unit tests).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class OnboardingPermissionsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun buildVm(): OnboardingPermissionsViewModel {
        val context: Application = ApplicationProvider.getApplicationContext()
        return OnboardingPermissionsViewModel(
            context = context,
            // F-2 fix (2026-04-30 hot-fix-260430-hs4) — VM now combines
            // permission state with AppPrefs.hasAsked* flows so
            // isPermanentlyDenied can disambiguate first-launch from
            // don't-ask-again. Tests use a real AppPrefs over the
            // Robolectric DataStore (same pattern as SettingsViewModelTest).
            appPrefs = AppPrefs(context),
        )
    }

    // ============================================================================
    // Test 1 — initial emission is a Ready shape (booleans are host-dependent,
    // not asserted).
    // ============================================================================

    @Test
    fun `initial emission is Ready shape`() = runTest {
        val vm = buildVm()
        vm.uiState.test(timeout = 2.seconds) {
            val first = awaitItem()
            assertTrue(
                first is OnboardingPermissionsUiState.Ready,
                "expected Ready shape, got $first",
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ============================================================================
    // Test 2 — onRefresh() emits a subsequent Ready shape (not null, not Error).
    // ============================================================================

    @Test
    fun `onRefresh emits new Ready shape`() = runTest {
        val vm = buildVm()
        vm.uiState.test(timeout = 2.seconds) {
            val first = awaitItem()
            assertTrue(first is OnboardingPermissionsUiState.Ready)

            vm.onRefresh()
            // The MutableStateFlow deduplicates when the new value equals the
            // previous one; under Robolectric's default permission grant state
            // the recomputed Ready is value-equal, so we assert that the
            // current StateFlow.value is still a Ready (shape, not value).
            assertTrue(
                vm.uiState.value is OnboardingPermissionsUiState.Ready,
                "expected Ready shape after onRefresh, got ${vm.uiState.value}",
            )
            cancelAndIgnoreRemainingEvents()
        }
    }
}
