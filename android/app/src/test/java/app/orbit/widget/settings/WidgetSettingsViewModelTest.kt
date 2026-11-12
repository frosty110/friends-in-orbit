package app.orbit.widget.settings

import android.app.Application
import android.appwidget.AppWidgetManager
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import app.orbit.R
import app.orbit.data.AppPrefs
import app.orbit.data.WidgetSettings
import app.orbit.data.WidgetSource
import app.orbit.data.entity.ListEntity
import app.orbit.domain.FakeCallEventRepository
import app.orbit.domain.FakeContactRepository
import app.orbit.domain.FakeListRepository
import app.orbit.domain.FakeRuleTemplateRepository
import app.orbit.domain.JsonProvider
import app.orbit.domain.clock.TestClock
import app.orbit.domain.contactFixture
import app.orbit.domain.listFixture
import app.orbit.domain.membershipFixture
import app.orbit.domain.ruleTemplateFixture
import app.orbit.domain.usecase.SurfaceNextUseCase
import app.orbit.domain.usecase.SurfaceQueueUseCase
import app.orbit.domain.usecase.WidgetSurfaceUseCase
import app.orbit.testutil.MainDispatcherRule
import app.orbit.testutil.awaitValue
import app.orbit.ui.screens.picker.SnackbarEvent
import app.orbit.ui.util.UiText
import app.orbit.widget.OneWidgetRefresher
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Widget settings (WIDGET-12, WIDGET-15): what the screen offers, what a
 * choice saves and redraws, and what a failed save tells the user.
 *
 * Real use case over the fake repositories, real DataStore per test
 * (`testutil/TestDataStore.kt`), and a counting [OneWidgetRefresher]. The
 * state is kept hot by a collector started before any write, so a write is
 * observed through the combine rather than by a late subscriber (the
 * DataStore race described in TestDataStore.kt).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class WidgetSettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val t0: Instant = Instant.parse("2026-01-01T12:00:00Z")
    private val widgetId = 5
    private val redraws = mutableListOf<Int>()

    @After
    fun tearDown() {
        storeScope.cancel()
        appScope.cancel()
    }

    private val family = listFixture(id = 1L, name = "Family", sortOrder = 2)
    private val friends = listFixture(id = 2L, name = "Old friends", sortOrder = 1)
    private val archived = listFixture(id = 3L, name = "Archived", isArchived = true)

    private fun prefs(failingWrites: Boolean = false): AppPrefs {
        val store = PreferenceDataStoreFactory.create(scope = storeScope) {
            File(tmp.root, "orbit_prefs.preferences_pb")
        }
        if (!failingWrites) return AppPrefs(store)
        return object : AppPrefs(store) {
            override suspend fun setWidgetSource(appWidgetId: Int, source: WidgetSource) {
                throw IOException("disk full")
            }
        }
    }

    private fun buildVm(
        prefs: AppPrefs,
        lists: List<ListEntity> = listOf(family, friends, archived),
    ): WidgetSettingsViewModel {
        // Ana on Family (most overdue anywhere), Ben on Old friends.
        val contactRepo = FakeContactRepository(
            listOf(contactFixture(id = 10L, displayName = "Ana"), contactFixture(id = 11L, displayName = "Ben")),
        )
        val listRepo = FakeListRepository(
            lists,
            listOf(
                membershipFixture(contactId = 10L, listId = 1L, nextDueAt = t0.minusSeconds(900)),
                membershipFixture(contactId = 11L, listId = 2L, nextDueAt = t0.minusSeconds(100)),
            ),
        )
        val templates = FakeRuleTemplateRepository(listOf(ruleTemplateFixture(id = 1L)))
        val clock = TestClock(t0)
        val calls = FakeCallEventRepository()
        val surface = WidgetSurfaceUseCase(
            surfaceNext = SurfaceNextUseCase(contactRepo, listRepo, calls, templates, clock, JsonProvider.json),
            surfaceQueue = SurfaceQueueUseCase(contactRepo, listRepo, calls, templates, clock, JsonProvider.json),
            listRepo = listRepo,
            clock = clock,
            zoneId = ZoneOffset.UTC,
        )
        val vm = WidgetSettingsViewModel(
            savedStateHandle = SavedStateHandle(mapOf(AppWidgetManager.EXTRA_APPWIDGET_ID to widgetId)),
            listRepo = listRepo,
            appPrefs = prefs,
            widgetSurface = surface,
            refresher = OneWidgetRefresher { synchronized(redraws) { redraws += it } },
            appScope = appScope,
        )
        appScope.launch { vm.uiState.collect {} }
        return vm
    }

    private suspend fun WidgetSettingsViewModel.awaitReady(
        matching: (WidgetSettingsUiState.Ready) -> Boolean = { true },
    ): WidgetSettingsUiState.Ready = withTimeout(30_000L) {
        (uiState as Flow<WidgetSettingsUiState>)
            .filterIsInstance<WidgetSettingsUiState.Ready>()
            .filter(matching)
            .first()
    }

    /** Glance sends no first update to a widget with a configure screen; opening the screen asks for it. */
    @Test
    fun opening_the_screen_draws_this_widget() = runBlocking {
        buildVm(prefs())

        awaitValue(listOf(widgetId)) { synchronized(redraws) { redraws.toList() } }
    }

    @Test
    fun offers_every_list_then_active_lists_in_lists_order_and_previews_the_cross_list_lead() = runBlocking {
        val vm = buildVm(prefs())

        val ready = vm.awaitReady()

        assertEquals(listOf(ListOption(2L, "Old friends"), ListOption(1L, "Family")), ready.lists)
        assertEquals(WidgetSource.EveryList, ready.source)
        assertEquals(false, ready.namesHidden)
        assertEquals(WidgetSettingsPreview.Person("Ana", photoUri = null, masked = false), ready.preview)
    }

    @Test
    fun choosing_a_list_saves_it_redraws_this_widget_and_previews_that_lists_lead() = runBlocking {
        val prefs = prefs()
        val vm = buildVm(prefs)
        vm.awaitReady()

        vm.onSelectSource(WidgetSource.OneList(2L))

        val ready = vm.awaitReady { it.source == WidgetSource.OneList(2L) }
        assertEquals(WidgetSettingsPreview.Person("Ben", photoUri = null, masked = false), ready.preview)
        awaitValue(WidgetSource.OneList(2L)) { prefs.widgetSettings(widgetId).first().source }
        // The opening draw, then the one for the change.
        awaitValue(listOf(widgetId, widgetId)) { synchronized(redraws) { redraws.toList() } }
    }

    @Test
    fun hiding_names_saves_it_and_masks_the_preview() = runBlocking {
        val prefs = prefs()
        val vm = buildVm(prefs)
        vm.awaitReady()

        vm.onShowNamesChange(false)

        val ready = vm.awaitReady { it.namesHidden }
        assertEquals(true, (ready.preview as WidgetSettingsPreview.Person).masked)
        awaitValue(WidgetSettings(WidgetSource.EveryList, namesHidden = true)) { prefs.widgetSettings(widgetId).first() }
    }

    @Test
    fun an_archived_choice_previews_as_missing() = runBlocking {
        val prefs = prefs()
        prefs.setWidgetSource(widgetId, WidgetSource.OneList(3L))
        val vm = buildVm(prefs)

        val ready = vm.awaitReady()

        assertEquals(WidgetSettingsPreview.SourceMissing, ready.preview)
        assertEquals(listOf(2L, 1L), ready.lists.map { it.id }, "archived lists are not offered")
    }

    @Test
    fun no_lists_offers_only_every_list_and_is_quiet() = runBlocking {
        val vm = buildVm(prefs(), lists = emptyList())

        val ready = vm.awaitReady()

        assertEquals(emptyList(), ready.lists)
        assertEquals(WidgetSettingsPreview.Quiet, ready.preview)
    }

    /** rules.md Code 3: a failed save is said, keeps the old choice, and redraws nothing. */
    @Test
    fun a_failed_save_tells_the_user_and_keeps_the_previous_choice() = runBlocking {
        val vm = buildVm(prefs(failingWrites = true))
        vm.awaitReady()
        val events = mutableListOf<SnackbarEvent>()
        val collecting = appScope.launch { vm.snackbarEvents.collect { synchronized(events) { events += it } } }

        vm.onSelectSource(WidgetSource.OneList(1L))

        awaitValue(listOf(SnackbarEvent(UiText.res(R.string.widget_settings_save_failed)))) {
            synchronized(events) { events.toList() }
        }
        assertEquals(WidgetSource.EveryList, vm.awaitReady().source)
        assertEquals(listOf(widgetId), synchronized(redraws) { redraws.toList() }, "only the opening draw")
        collecting.cancel()
    }
}
