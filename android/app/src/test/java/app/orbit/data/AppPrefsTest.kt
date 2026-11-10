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
 * Behavioral coverage for the two call-log-sync pref pairs on [AppPrefs],
 * and for each widget's settings (WIDGET-12, WIDGET-15, WIDGET-16):
 *  - `callLogImportDays` (default 90; coerced into [1, 3650])
 *  - `lastCallLogSyncAt` (default 0L; clamped to >= 0)
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
    // Per-widget settings (WIDGET-12, WIDGET-15, WIDGET-16)
    // ------------------------------------------------------------------

    /** A widget placed before per-widget settings has no keys: every list, names shown. */
    @Test
    fun widgetSettings_default_to_every_list_with_names_shown() = runTest {
        assertEquals(WidgetSettings.DEFAULT, prefs.widgetSettings(appWidgetId = 3).first())
        assertEquals(WidgetSource.EveryList, WidgetSettings.DEFAULT.source)
        assertEquals(false, WidgetSettings.DEFAULT.namesHidden)
    }

    @Test
    fun widgetSettings_are_kept_per_widget() = runTest {
        prefs.setWidgetSource(appWidgetId = 3, source = WidgetSource.OneList(42L))
        prefs.setWidgetNamesHidden(appWidgetId = 3, hidden = true)

        assertEquals(
            WidgetSettings(WidgetSource.OneList(42L), namesHidden = true),
            prefs.widgetSettings(3).first(),
        )
        assertEquals(WidgetSettings.DEFAULT, prefs.widgetSettings(4).first(), "another widget is untouched")
    }

    @Test
    fun widgetSource_back_to_every_list() = runTest {
        prefs.setWidgetSource(appWidgetId = 3, source = WidgetSource.OneList(42L))
        prefs.setWidgetSource(appWidgetId = 3, source = WidgetSource.EveryList)

        assertEquals(WidgetSource.EveryList, prefs.widgetSettings(3).first().source)
    }

    @Test
    fun clearWidgetSettings_forgets_only_the_removed_widgets() = runTest {
        prefs.setWidgetSource(appWidgetId = 3, source = WidgetSource.OneList(1L))
        prefs.setWidgetNamesHidden(appWidgetId = 3, hidden = true)
        prefs.setWidgetSource(appWidgetId = 4, source = WidgetSource.OneList(2L))

        prefs.clearWidgetSettings(listOf(3))

        assertEquals(WidgetSettings.DEFAULT, prefs.widgetSettings(3).first())
        assertEquals(WidgetSource.OneList(2L), prefs.widgetSettings(4).first().source)
    }

    @Test
    fun clearAllWidgetSettings_resets_every_widget_and_nothing_else() = runTest {
        prefs.setCallLogImportDays(30)
        prefs.setWidgetSource(appWidgetId = 3, source = WidgetSource.OneList(1L))
        prefs.setWidgetNamesHidden(appWidgetId = 4, hidden = true)

        prefs.clearAllWidgetSettings()

        assertEquals(WidgetSettings.DEFAULT, prefs.widgetSettings(3).first())
        assertEquals(WidgetSettings.DEFAULT, prefs.widgetSettings(4).first())
        assertEquals(30, prefs.callLogImportDays.first(), "other settings stay")
    }

    /** SET-06: reset wipes the store, widget settings with it. */
    @Test
    fun resetAll_clears_widget_settings() = runTest {
        prefs.setWidgetSource(appWidgetId = 3, source = WidgetSource.OneList(1L))

        prefs.resetAll()

        assertEquals(WidgetSettings.DEFAULT, prefs.widgetSettings(3).first())
    }
}
