package app.orbit.testutil

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Shared JUnit 4 rule — swaps `Dispatchers.Main` for a test dispatcher so coroutine-driven
 * ViewModels under test don't throw `IllegalStateException: Module with the Main dispatcher
 * had failed to initialize`. Every `*ViewModelTest` file mounts this via
 * `@get:Rule val mainDispatcherRule = MainDispatcherRule()`.
 *
 * ## Picking the right fixture
 *
 * - **Pure JVM + fake repositories** (e.g. `CardViewViewModelTest`, `HomeViewModelTest`):
 *   no real `Context`, no DataStore, no Room. Runs on the standard JUnit runner. Fastest.
 * - **Robolectric + real DataStore** (e.g. `AppViewModelTest`, `SettingsViewModelTest`,
 *   `OnboardingBulkAddViewModelTest`): needs `ApplicationProvider.getApplicationContext()`
 *   to construct `AppPrefs`. Pair this rule with an explicit `@After` that resets every
 *   flag the test writes (DataStore caches a process-wide singleton per Context+name; a
 *   file-only wipe is insufficient). See `AppViewModelTest.clearDataStore` for the pattern.
 *
 * Source recipe: developer.android.com/kotlin/coroutines/test#main-dispatcher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val testDispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(testDispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }

    /**
     * Temporarily swap `Dispatchers.Main` for [override] (typically a paused
     * `StandardTestDispatcher`) while [block] runs, then restore the rule's own
     * [testDispatcher]. Use this instead of nesting `setMain/resetMain` calls by
     * hand — bare `resetMain()` un-sets the dispatcher entirely, it does NOT
     * restore the Unconfined one this rule installed.
     */
    fun withMainDispatcher(override: TestDispatcher, block: () -> Unit) {
        Dispatchers.setMain(override)
        try {
            block()
        } finally {
            Dispatchers.setMain(testDispatcher)
        }
    }
}
