package app.orbit.data

import android.app.Application
import app.orbit.testutil.newPrefs
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behavioral coverage for the two call-log-sync pref pairs on [AppPrefs], and
 * the card's move count (CARD-09):
 *  - `callLogImportDays` (default 90; coerced into [1, 3650])
 *  - `lastCallLogSyncAt` (default 0L; clamped to >= 0)
 *  - `cardMovesMade` (default 0; counted up to the caller's cap)
 *
 * Fixture: each method gets its own DataStore file and scope through
 * `testutil/TestDataStore.kt`, cancelled in `@After`, so no method can see
 * another's writes or wait on another's stranded actor. Robolectric is still
 * needed because [AppPrefs] maps `android.Manifest` permission names.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class AppPrefsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefs: AppPrefs by lazy { tmp.newPrefs(storeScope) }

    @After
    fun tearDown() {
        storeScope.cancel()
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
    // CARD-09: the card's move count, for the idle hints
    // ------------------------------------------------------------------

    @Test
    fun cardMovesMade_starts_at_zero_and_counts_up_to_the_cap_only() = runTest {
        assertEquals(0, prefs.cardMovesMade.first())
        repeat(3) { prefs.recordCardMove(cap = 5) }
        assertEquals(3, prefs.cardMovesMade.first())
        repeat(4) { prefs.recordCardMove(cap = 5) }
        assertEquals(5, prefs.cardMovesMade.first(), "nothing past the point where the hints stop")
    }
}
