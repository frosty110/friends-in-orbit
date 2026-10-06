package app.orbit.testutil

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.orbit.data.AppPrefs
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.rules.TemporaryFolder

/**
 * One DataStore per test method, on a scope the test owns.
 *
 * `preferencesDataStore` (the production delegate in `AppPrefs.kt`) creates
 * a single store per process and never closes it. The unit tests fork one JVM
 * per test CLASS (`forkEvery = 1` in app/build.gradle.kts), so every method
 * of a Robolectric class shared one store: its cache, its actor and its
 * coordinator lock. Whatever one method left behind, the next inherited, and
 * the suite's 30 second timeouts kept surfacing in whichever method ran next.
 *
 * Use it as:
 *
 * ```
 * @get:Rule val tmp = TemporaryFolder()
 * private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
 * private val prefs by lazy { tmp.newPrefs(storeScope) }
 * @After fun tearDown() { storeScope.cancel() }
 * ```
 *
 * Cancelling the scope kills the actor and any message still in it with the
 * test, so nothing can block the next method. The file lives under the rule's
 * folder, so the store starts from a MISSING file (defaults), not an empty
 * one, and the next method's store does not trip DataStore's one-active-store-
 * per-file check. A test that needs several [AppPrefs] instances over the same
 * data must share one store between them: two stores on one file throw.
 *
 * ## Waiting for a write: poll, never subscribe mid-write
 *
 * A collector that subscribes to `DataStore.data` while a write is in flight
 * can miss that write for good, in DataStore 1.1.1 (read from its bytecode,
 * 2026-10-06): the writer (`DataStoreImpl$writeData$2`) increments the
 * coordinator version BEFORE it writes the file; a new collector
 * (`internalDataFlow`) calls `readDataAndUpdateCache(requireLock = false)`,
 * whose `tryLock` fails while the writer holds the lock, so it reads the file
 * without the lock and stamps what it read with `getVersion()` taken AFTER
 * the read (`readDataOrHandleCorruption`). Old contents, new version. The
 * flow emits that stale value, then `dropWhile`s every cache state whose
 * version is not newer, which drops the writer's own update. So
 * `prefs.x.filter { it == expected }.first()`, started after `edit` was
 * launched, times out now and then (SettingsViewModelTest, first attempt of
 * `onImportDaysChanged writes through to prefs`). [awaitValue] polls a fresh
 * `first()` instead: each one reads the cache, which the writer updates before
 * it releases the lock, so the poll converges.
 */
fun TemporaryFolder.newPrefs(scope: CoroutineScope): AppPrefs =
    AppPrefs(PreferenceDataStoreFactory.create(scope = scope) { File(root, "orbit_prefs.preferences_pb") })

/**
 * Polls [read] until it returns [expected], or fails after [timeoutMs]. The
 * way to wait for a DataStore write from a test; see the file comment for why
 * a long-lived collector cannot be trusted here.
 */
suspend fun <T> awaitValue(expected: T, timeoutMs: Long = 30_000L, read: suspend () -> T) {
    withTimeout(timeoutMs) {
        while (read() != expected) delay(25)
    }
}
