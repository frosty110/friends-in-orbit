package app.orbit.ui.screens.onboarding

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import app.orbit.data.AppPrefs
import app.orbit.testutil.MainDispatcherRule
import app.orbit.testutil.newPrefs
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
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
 *   - A real DataStore per test method (`tmp.newPrefs(storeScope)`, testutil/
 *     TestDataStore.kt), cancelled in `@After`, so the hasAsked flag test 3
 *     writes never reaches another method.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class OnboardingPermissionsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefs: AppPrefs by lazy { tmp.newPrefs(storeScope) }

    private fun buildVm(): OnboardingPermissionsViewModel {
        val context: Application = ApplicationProvider.getApplicationContext()
        return OnboardingPermissionsViewModel(
            context = context,
            // F-2 fix (2026-04-30 hot-fix-260430-hs4): the VM combines
            // permission state with AppPrefs.hasAsked* flows so
            // isPermanentlyDenied can disambiguate first-launch from
            // don't-ask-again. Tests use a real AppPrefs over this method's
            // own DataStore (same pattern as SettingsViewModelTest).
            appPrefs = prefs,
        )
    }

    @After
    fun tearDown() {
        storeScope.cancel()
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

    // ============================================================================
    // Test 3 — onLauncherFired(READ_CONTACTS) flips the per-permission
    // `hasAsked` flag through AppPrefs and the next Ready emission reflects it
    // (F-2 — disambiguates first-launch from "don't ask again"). This arm is
    // host-independent: the asked flag is driven by our own write, not by the
    // Robolectric host's permission grants.
    //
    // `runBlocking` (real time) — DataStore writes hop to a real IO dispatcher
    // that does not cooperate with `runTest`'s virtual clock (same rationale as
    // SettingsViewModelTest's DataStore tests).
    // ============================================================================

    @Test
    fun `onLauncherFired flips hasAsked flag in Ready state`() = runBlocking {
        val vm = buildVm()

        // Fresh prefs: the contacts asked flag starts false.
        val before = withTimeout(30_000L) {
            vm.uiState.filterIsInstance<OnboardingPermissionsUiState.Ready>().first()
        }
        assertFalse(before.hasAskedContacts, "fresh prefs must start hasAskedContacts = false")

        vm.onLauncherFired(Manifest.permission.READ_CONTACTS)

        val after = withTimeout(30_000L) {
            vm.uiState
                .filterIsInstance<OnboardingPermissionsUiState.Ready>()
                .filter { it.hasAskedContacts }
                .first()
        }
        assertTrue(after.hasAskedContacts, "onLauncherFired must flip hasAskedContacts = true")
        // The other permission flags are untouched by a contacts-only launch.
        assertFalse(after.hasAskedCallLog, "call-log asked flag must remain false")
        assertFalse(after.hasAskedNotifications, "notifications asked flag must remain false")
    }
}
