package app.orbit.testutil

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.orbit.data.AppPrefs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A preferences DataStore held in memory, for plain JUnit tests of a
 * ViewModel that reads or writes one [AppPrefs] value (CARD-09's move count).
 *
 * No file and no IO dispatcher, so it runs on the test's own dispatcher and
 * virtual time, and none of the file store's races ([newPrefs]'s KDoc)
 * apply. Reads see a write as soon as `edit` returns. Use [newPrefs] when
 * the test is about the store itself.
 *
 * Several [AppPrefs] built over one instance share its data, which is how a
 * test stands for the same preferences after the process came back.
 */
class InMemoryPrefsStore : DataStore<Preferences> {

    private val state = MutableStateFlow(emptyPreferences())
    private val lock = Mutex()

    override val data: Flow<Preferences> get() = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        lock.withLock {
            transform(state.value).toPreferences().also { state.value = it }
        }
}

/** An [AppPrefs] over [store] (a fresh empty one unless a test shares it). */
fun inMemoryPrefs(store: InMemoryPrefsStore = InMemoryPrefsStore()): AppPrefs = AppPrefs(store)
