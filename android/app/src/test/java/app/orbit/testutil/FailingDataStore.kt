package app.orbit.testutil

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import org.junit.rules.TemporaryFolder

/**
 * A DataStore whose writes fail on demand, for the rules.md Code 3 tests: a
 * preference setter that throws must become "Couldn't save your change", not
 * a silent no-op and not a crash.
 *
 * Reads pass through to the real store underneath, so a ViewModel built over
 * `AppPrefs(store)` still loads and renders; only `updateData` (every
 * `dataStore.edit { }` in `AppPrefs`) throws while [failWrites] is true.
 * Start with it false when the ViewModel under test writes in `init`
 * (`OnboardingDoneViewModel`), then flip it before the write under test.
 *
 * Same ownership rules as [newPrefs]: one store per test method, on a scope
 * the test cancels. The file name differs from [newPrefs]'s so a test may
 * hold both without tripping DataStore's one-active-store-per-file check.
 */
class FailingWritesDataStore(
    private val inner: DataStore<Preferences>,
) : DataStore<Preferences> {

    @Volatile var failWrites: Boolean = true

    override val data: Flow<Preferences> get() = inner.data

    override suspend fun updateData(
        transform: suspend (t: Preferences) -> Preferences,
    ): Preferences {
        if (failWrites) throw IOException("disk full")
        return inner.updateData(transform)
    }
}

/** A [FailingWritesDataStore] on a fresh file under this rule's folder; wrap it in `AppPrefs`. */
fun TemporaryFolder.newFailingStore(scope: CoroutineScope): FailingWritesDataStore =
    FailingWritesDataStore(
        PreferenceDataStoreFactory.create(scope = scope) {
            File(root, "orbit_prefs_failing.preferences_pb")
        },
    )
