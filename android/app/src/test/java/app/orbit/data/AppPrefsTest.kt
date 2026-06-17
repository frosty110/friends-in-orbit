package app.orbit.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Behavioral coverage for the three call-log-sync pref
 * pairs on [AppPrefs]:
 *  - `callLogImportDays` (default 90; coerced into [1, 3650])
 *  - `lastCallLogSyncAt` (default 0L; clamped to ≥0)
 *  - `isCallLogSyncEnabled` (default false)
 *
 * Fixture pattern mirrors AppViewModelTest:
 *  - Robolectric's [ApplicationProvider.getApplicationContext] supplies a real
 *    [android.content.Context] so the production [AppPrefs] constructor runs unchanged.
 *  - `@Config(application = Application::class)` bypasses OrbitApp.onCreate (no Hilt
 *    graph, no WorkManager init) — we only need the DataStore to come up.
 *  - `@After` wipes the on-disk DataStore file and the process-wide cache so each
 *    @Test method sees fresh defaults.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class AppPrefsTest {

    private lateinit var prefs: AppPrefs

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        prefs = AppPrefs(ctx)
    }

    @After
    fun clearDataStore() {
        // Explicitly reset every key this test class writes, then wipe the on-disk
        // file. DataStore caches a process-wide singleton per (Context, name), so
        // purging the file alone is not sufficient between @Test methods within
        // the same classloader.
        runBlocking {
            prefs.setCallLogImportDays(90)
            prefs.setLastCallLogSyncAt(0L)
            prefs.setCallLogSyncEnabled(false)
        }
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val prefsDir = java.io.File(ctx.filesDir.parentFile, "datastore")
        if (prefsDir.exists()) prefsDir.deleteRecursively()
    }

    // ------------------------------------------------------------------
    // callLogImportDays
    // ------------------------------------------------------------------

    @Test
    fun callLogImportDays_defaults_to_90() = runTest {
        assertEquals(90, prefs.callLogImportDays.first())
    }

    @Test
    fun callLogImportDays_persists_set_value() = runTest {
        prefs.setCallLogImportDays(30)
        assertEquals(30, prefs.callLogImportDays.first())
    }

    @Test
    fun callLogImportDays_coerces_below_1_to_1() = runTest {
        prefs.setCallLogImportDays(0)
        assertEquals(1, prefs.callLogImportDays.first())
        prefs.setCallLogImportDays(-5)
        assertEquals(1, prefs.callLogImportDays.first())
    }

    @Test
    fun callLogImportDays_coerces_above_3650_to_3650() = runTest {
        prefs.setCallLogImportDays(9999)
        assertEquals(3650, prefs.callLogImportDays.first())
    }

    // ------------------------------------------------------------------
    // lastCallLogSyncAt
    // ------------------------------------------------------------------

    @Test
    fun lastCallLogSyncAt_defaults_to_zero() = runTest {
        assertEquals(0L, prefs.lastCallLogSyncAt.first())
    }

    @Test
    fun lastCallLogSyncAt_persists_long_millis() = runTest {
        val ts = 1_729_776_000_000L
        prefs.setLastCallLogSyncAt(ts)
        assertEquals(ts, prefs.lastCallLogSyncAt.first())
    }

    @Test
    fun lastCallLogSyncAt_clamps_negative_to_zero() = runTest {
        prefs.setLastCallLogSyncAt(-42L)
        assertEquals(0L, prefs.lastCallLogSyncAt.first())
    }

    // ------------------------------------------------------------------
    // isCallLogSyncEnabled
    // ------------------------------------------------------------------

    @Test
    fun isCallLogSyncEnabled_defaults_to_false() = runTest {
        assertFalse(prefs.isCallLogSyncEnabled.first())
    }

    @Test
    fun isCallLogSyncEnabled_persists_true() = runTest {
        prefs.setCallLogSyncEnabled(true)
        assertTrue(prefs.isCallLogSyncEnabled.first())
    }
}
