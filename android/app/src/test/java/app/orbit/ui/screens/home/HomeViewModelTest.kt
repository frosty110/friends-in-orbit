package app.orbit.ui.screens.home

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import app.orbit.R
import app.orbit.data.AppPrefs
import app.orbit.data.entity.ListEntity
import app.orbit.data.entity.ListType
import app.orbit.data.feed.HomeFeed
import app.orbit.data.feed.ListEnrichment
import app.orbit.data.feed.NextUpRaw
import app.orbit.data.repository.ListRepository
import app.orbit.domain.FakeCallEventRepository
import app.orbit.domain.FakeContactRepository
import app.orbit.domain.FakeListRepository
import app.orbit.domain.FakeRuleTemplateRepository
import app.orbit.domain.JsonProvider
import app.orbit.domain.WidgetRefreshTrigger
import app.orbit.domain.clock.TestClock
import app.orbit.domain.listFixture
import app.orbit.domain.usecase.SurfaceNextUseCase
import app.orbit.notify.NudgeScheduler
import app.orbit.testutil.MainDispatcherRule
import app.orbit.testutil.newPrefs
import app.orbit.ui.util.UiText
import app.orbit.ui.util.formatAgo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Behavioral tests for [HomeViewModel]: the VM is a thin subscriber to
 * [HomeFeed]. Tests inject [FakeHomeFeed] (an `open class` subclass, the same
 * precedent [app.orbit.domain.usecase.MarkCalledUseCase] established for
 * `Test*` subclassing) so the steady-state `tiles` and `enrichment` flows can
 * be driven deterministically without spinning up the full feed projection.
 * The HOME-10 feed-failure test uses the real [HomeFeed] over a repository
 * whose list read throws, so the catch under test is the production one.
 *
 * Pattern (mirrors [app.orbit.ui.screens.card.CardViewViewModelTest]):
 *   - Real VM over the [FakeHomeFeed] subclass.
 *   - [MainDispatcherRule] so `viewModelScope.stateIn` runs on the test
 *     dispatcher.
 *   - [app.cash.turbine.test] asserts terminal state only: the
 *     UnconfinedTestDispatcher + stateIn initial-value collapse is a known
 *     pattern friction. Tests assert the observable terminal contract per
 *     ARCH-02.
 *   - Snackbar copy is `UiText`; the tests compare the value the VM meant to
 *     say and resolve it against Robolectric's resources for the words.
 *   - Each test owns its DataStore (`tmp.newPrefs`), see testutil/TestDataStore.kt.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class HomeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @After
    fun tearDown() {
        storeScope.cancel()
    }

    /**
     * Test seam: overrides [HomeFeed.tiles], [HomeFeed.enrichment] and
     * [HomeFeed.failed] with flows the test owns. The parent constructor's
     * projections over an empty [FakeListRepository] still run (Kotlin
     * evaluates the parent initializer before the subclass override binds),
     * but their results are discarded; the subclass's flows are what
     * HomeViewModel sees. [failTiles] stands in for a feed whose source threw
     * (HOME-10); the fake's [retry] clears it the way the real feed's next
     * successful emission does.
     */
    private class FakeHomeFeed(
        appPrefs: AppPrefs,
        initialTiles: List<ListTileState> = emptyList(),
        initialEnrichment: Map<Long, ListEnrichment> = emptyMap(),
    ) : HomeFeed(
        listRepo = FakeListRepository(),
        clock = TestClock(),
        appPrefs = appPrefs,
        surfaceNext = SurfaceNextUseCase(
            contactRepo = FakeContactRepository(),
            listRepo = FakeListRepository(),
            callEventRepo = FakeCallEventRepository(),
            ruleTemplateRepo = FakeRuleTemplateRepository(),
            clock = TestClock(),
            json = JsonProvider.json,
        ),
        callEventRepo = FakeCallEventRepository(),
        contactRepo = FakeContactRepository(),
        scope = CoroutineScope(UnconfinedTestDispatcher()),
    ) {
        private val _tiles = MutableStateFlow(initialTiles)
        override val tiles: StateFlow<List<ListTileState>> = _tiles.asStateFlow()
        override val enrichment: StateFlow<Map<Long, ListEnrichment>> = MutableStateFlow(initialEnrichment)

        val failTiles = MutableStateFlow(false)
        override val failed: StateFlow<Boolean> = failTiles.asStateFlow()
        var retryCalls = 0

        override fun retry() {
            retryCalls++
            failTiles.value = false
        }

        @Suppress("unused")
        fun emit(value: List<ListTileState>) {
            _tiles.value = value
        }
    }

    /**
     * Records the nudge-chain calls Home's menu must make (NOTIF-11) and never
     * touches WorkManager. The same shape as the recording schedulers in the
     * Lists tests, which are private to their files.
     */
    private class HomeFakeNudgeScheduler : NudgeScheduler(
        context = ApplicationProvider.getApplicationContext<Context>(),
        listRepo = FakeListRepository(),
    ) {
        val cancelCalls: MutableList<Long> = mutableListOf()
        val scheduleFromEntityCalls: MutableList<ListEntity> = mutableListOf()

        override fun cancel(listId: Long) {
            cancelCalls += listId
        }

        override suspend fun scheduleFromEntity(list: ListEntity) {
            scheduleFromEntityCalls += list
        }
    }

    /** The writes Home's menu makes fail, the way a full disk would; reads go to the fake. */
    private class FailingWritesListRepository(
        delegate: FakeListRepository = FakeListRepository(),
    ) : ListRepository by delegate {
        override suspend fun setArchived(listId: Long, archived: Boolean) {
            throw IllegalStateException("disk full")
        }

        override suspend fun updateNotificationsEnabled(listId: Long, enabled: Boolean) {
            throw IllegalStateException("disk full")
        }
    }

    /** The active-lists read fails on demand, the way a database read error would. */
    private class FailingReadsListRepository(
        private val delegate: FakeListRepository,
    ) : ListRepository by delegate {
        var failActive = false

        override fun observeActive(): Flow<List<ListEntity>> =
            if (failActive) flow { throw IllegalStateException("database read failed") } else delegate.observeActive()
    }

    /** Counts widget refresh requests (WIDGET-06) without WorkManager. */
    private class HomeRecordingWidgetTrigger : WidgetRefreshTrigger {
        var refreshes = 0
        override fun scheduleRefresh() {
            refreshes += 1
        }
    }

    private class Setup(
        val vm: HomeViewModel,
        val homeFeed: FakeHomeFeed,
        val scheduler: HomeFakeNudgeScheduler,
        val clock: TestClock,
        val widget: HomeRecordingWidgetTrigger,
    )

    private fun fixture(
        initialTiles: List<ListTileState> = emptyList(),
        initialEnrichment: Map<Long, ListEnrichment> = emptyMap(),
        listRepo: ListRepository = FakeListRepository(),
        // The feed has already failed when the VM is built (HOME-10).
        feedFailed: Boolean = false,
    ): Setup {
        val homeFeed = FakeHomeFeed(
            appPrefs = tmp.newPrefs(storeScope),
            initialTiles = initialTiles,
            initialEnrichment = initialEnrichment,
        )
        homeFeed.failTiles.value = feedFailed
        val clock = TestClock()
        val scheduler = HomeFakeNudgeScheduler()
        val widget = HomeRecordingWidgetTrigger()
        val vm = HomeViewModel(
            homeFeed = homeFeed,
            listRepo = listRepo,
            nudgeScheduler = scheduler,
            clock = clock,
            widgetRefreshTrigger = widget,
        )
        return Setup(vm, homeFeed, scheduler, clock, widget)
    }

    /** The production feed over [listRepo], on a scope that runs eagerly. */
    private fun realFeed(listRepo: ListRepository, clock: TestClock): HomeFeed = HomeFeed(
        listRepo = listRepo,
        clock = clock,
        appPrefs = tmp.newPrefs(storeScope),
        surfaceNext = SurfaceNextUseCase(
            contactRepo = FakeContactRepository(),
            listRepo = listRepo,
            callEventRepo = FakeCallEventRepository(),
            ruleTemplateRepo = FakeRuleTemplateRepository(),
            clock = clock,
            json = JsonProvider.json,
        ),
        callEventRepo = FakeCallEventRepository(),
        contactRepo = FakeContactRepository(),
        scope = CoroutineScope(UnconfinedTestDispatcher()),
    )

    private fun tile(id: Long, name: String, type: ListType = ListType.STATIC, dueCount: Int = 0) =
        ListTileState(id = id, name = name, dueCount = dueCount, type = type)

    /**
     * Drains intermediate frames and returns the first item matching
     * [predicate]. The VM's uiState can emit a transient prefix that StateFlow
     * conflation may or may not collapse under UnconfinedTestDispatcher:
     * `Loading` (pre-first-DB-answer guard) when the feed is seeded empty, or
     * a cache-first `Ready` with `memberCount = null` before the
     * `observeMemberCountsByListId` hydration lands. Asserting through this
     * helper keeps the tests on the terminal contract (file KDoc) regardless
     * of how many prefix frames are delivered.
     */
    private suspend fun <T> app.cash.turbine.ReceiveTurbine<T>.awaitItemMatching(
        predicate: (T) -> Boolean,
    ): T {
        while (true) {
            val item = awaitItem()
            if (predicate(item)) return item
        }
    }

    private fun HomeUiState.isHydratedReady(): Boolean =
        this is HomeUiState.Ready && lists.all { it.memberCount != null }

    // ============================================================================
    // HOME-10: a failing source shows Error (Home used to sit on stale chrome,
    // or crash when the failure was inside the feed) and Try again recovers.
    // ============================================================================

    @Test
    fun `failing member counts emit Error and retry recovers`() = runTest {
        val listRepo = FakeListRepository()
        val setup = fixture(listRepo = listRepo)
        listRepo.failMemberCounts = true
        setup.vm.uiState.test(timeout = 2.seconds) {
            assertEquals(HomeUiState.Error, awaitItemMatching { it !is HomeUiState.Loading })
            listRepo.failMemberCounts = false
            setup.vm.onRetry()
            assertEquals(HomeUiState.Empty, awaitItemMatching { it !is HomeUiState.Loading && it !is HomeUiState.Error })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a failed feed maps to Error and Try again asks the feed to retry`() = runTest {
        val setup = fixture(initialTiles = listOf(tile(1L, "Inner orbit")), feedFailed = true)
        setup.vm.uiState.test(timeout = 2.seconds) {
            // The flag is checked before the cached tiles, so Error wins even
            // though the feed still holds a list (and is the initial value, so
            // the screen never renders the stale card first).
            assertEquals(HomeUiState.Error, awaitItemMatching { it !is HomeUiState.Loading })
            setup.vm.onRetry()
            val ready = awaitItemMatching { it.isHydratedReady() } as HomeUiState.Ready
            assertEquals(listOf("Inner orbit"), ready.lists.map { it.name })
            assertEquals(1, setup.homeFeed.retryCalls, "Try again must re-subscribe the feed, not only the VM's own sources")
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * The production catch: `observeActive()` throws inside [HomeFeed], on the
     * application scope. Before the feed caught it, the exception escaped the
     * handler-less scope and the app crashed; now the feed reports it as data
     * and Try again re-subscribes and recovers.
     */
    @Test
    fun `a throwing list read inside the real feed shows Error and recovers on retry`() = runTest {
        val repo = FailingReadsListRepository(
            FakeListRepository(initialLists = listOf(listFixture(id = 1L, name = "Inner orbit"))),
        )
        repo.failActive = true
        val clock = TestClock()
        val feed = realFeed(repo, clock)
        // The feed reports the failed read instead of throwing.
        assertTrue(withTimeout(2_000L) { feed.failed.first { it } })
        val vm = HomeViewModel(homeFeed = feed, listRepo = repo, nudgeScheduler = HomeFakeNudgeScheduler(), clock = clock)

        vm.uiState.test(timeout = 2.seconds) {
            assertEquals(HomeUiState.Error, awaitItemMatching { it !is HomeUiState.Loading })
            repo.failActive = false
            vm.onRetry()
            val ready = awaitItemMatching { it.isHydratedReady() } as HomeUiState.Ready
            assertEquals(listOf("Inner orbit"), ready.lists.map { it.name })
            assertFalse(feed.failed.value)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ============================================================================
    // States: Empty, Ready, ordering, type and dueCount pass-through
    // ============================================================================

    @Test
    fun `empty tiles emits Empty`() = runTest {
        val setup = fixture()
        setup.vm.uiState.test(timeout = 2.seconds) {
            // FakeHomeFeed seeded empty → initial value is Loading (the
            // pre-first-DB-answer guard); the synchronously-answering fakes
            // resolve it to Empty. awaitItemMatching tolerates conflation.
            assertEquals(
                HomeUiState.Empty,
                awaitItemMatching { it !is HomeUiState.Loading },
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `one tile emits Ready with one tile`() = runTest {
        val setup = fixture(initialTiles = listOf(tile(1L, "Inner orbit")))
        setup.vm.uiState.test(timeout = 2.seconds) {
            val next = awaitItemMatching { it.isHydratedReady() }
            assertTrue(next is HomeUiState.Ready, "expected Ready, got $next")
            assertEquals(1, next.lists.size)
            assertEquals(1L, next.lists[0].id)
            assertEquals("Inner orbit", next.lists[0].name)
            // dueCount is read directly from ListEntity.dueCount.
            // Fixture sets 0 here; assertion mirrors.
            assertEquals(0, next.lists[0].dueCount)
            // Member counts hydrate from ListRepository
            // .observeMemberCountsByListId(); the empty fake has no
            // memberships → 0, never null, in the terminal state.
            assertEquals(0, next.lists[0].memberCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `multiple tiles project to Ready preserving order`() = runTest {
        val setup = fixture(
            initialTiles = listOf(
                tile(1L, "Inner orbit"),
                tile(2L, "Late night"),
                tile(3L, "People who ground me"),
            ),
        )
        setup.vm.uiState.test(timeout = 2.seconds) {
            val next = awaitItem()
            assertTrue(next is HomeUiState.Ready, "expected Ready, got $next")
            assertEquals(3, next.lists.size)
            assertEquals(
                listOf("Inner orbit", "Late night", "People who ground me"),
                next.lists.map { it.name },
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    // `ListTileState` carries a `type: ListType` field so the Home card can
    // show the smart-list glyph and leave "Add people" out of the menu for
    // SMART lists only (LIST-07). The projection lives in HomeFeed; the type
    // must round-trip through the VM unchanged.
    @Test
    fun `list type propagates from feed to VM uiState`() = runTest {
        val setup = fixture(
            initialTiles = listOf(
                tile(1L, "Inner orbit"),
                tile(2L, "Recently added", type = ListType.SMART),
            ),
        )
        setup.vm.uiState.test(timeout = 2.seconds) {
            val next = awaitItem()
            assertTrue(next is HomeUiState.Ready, "expected Ready, got $next")
            assertEquals(2, next.lists.size)
            assertEquals(ListType.STATIC, next.lists[0].type)
            assertEquals(ListType.SMART, next.lists[1].type)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // The VM reads ListEntity.dueCount directly (a column kept fresh by the
    // mutator use cases). The value must round-trip from feed to UI
    // unchanged: no recompute, no clock projection, no combine.
    @Test
    fun `dueCount propagates verbatim from feed`() = runTest {
        val setup = fixture(
            initialTiles = listOf(
                tile(1L, "Inner orbit", dueCount = 4),
                tile(2L, "Late night", dueCount = 0),
            ),
        )
        setup.vm.uiState.test(timeout = 2.seconds) {
            val next = awaitItem()
            assertTrue(next is HomeUiState.Ready, "expected Ready, got $next")
            assertEquals(4, next.lists[0].dueCount)
            assertEquals(0, next.lists[1].dueCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ============================================================================
    // HOME-3, "Next up" hydration: the VM turns the feed's raw head of queue
    // into the card's person, with the warm why line. No number since
    // 2026-10-08: the row's call button went (HOME-9).
    // ============================================================================

    private fun enrichmentFor(lastCalledAt: Instant?) = mapOf(
        1L to ListEnrichment(
            nextUp = NextUpRaw(
                contactId = 7L,
                name = "Kai Mensah",
                photoUri = "content://photo/7",
                lastCalledAt = lastCalledAt,
            ),
            rhythm = emptyList(),
        ),
    )

    private suspend fun nextUpFor(lastCalledAt: Instant?): NextUp {
        val setup = fixture(
            initialTiles = listOf(tile(1L, "Inner orbit")),
            initialEnrichment = enrichmentFor(lastCalledAt),
        )
        var nextUp: NextUp? = null
        setup.vm.uiState.test(timeout = 2.seconds) {
            val ready = awaitItemMatching { it.isHydratedReady() } as HomeUiState.Ready
            nextUp = ready.lists.single().nextUp
            cancelAndIgnoreRemainingEvents()
        }
        return assertNotNull(nextUp, "the enriched tile carries a Next up")
    }

    @Test
    fun `next up carries the person`() = runTest {
        val nextUp = nextUpFor(lastCalledAt = null)
        assertEquals(7L, nextUp.contactId)
        assertEquals("Kai Mensah", nextUp.name)
        assertEquals("content://photo/7", nextUp.photoUri)
    }

    // HOME-3: the why line is recency as context, never shame (voice.md), in
    // the app's one "ago" wording (WhyLineVoiceTest holds every bucket of it
    // to the never-say list), with no "You" since 2026-10-08. TestClock's now
    // is 2026-01-01T12:00Z.
    @Test
    fun `the why line reads never, today, yesterday or how long ago you spoke`() = runTest {
        val now = TestClock().now()
        assertEquals(UiText.res(R.string.home_why_never), nextUpFor(lastCalledAt = null).why)
        assertEquals(UiText.res(R.string.home_why_today), nextUpFor(lastCalledAt = now.minus(Duration.ofHours(3))).why)
        assertEquals(UiText.res(R.string.home_why_yesterday), nextUpFor(lastCalledAt = now.minus(Duration.ofDays(1))).why)
        val threeWeeks = nextUpFor(lastCalledAt = now.minus(Duration.ofDays(21))).why
        assertEquals(UiText.res(R.string.home_why_ago, formatAgo(21)), threeWeeks)
        assertEquals("Spoke 3 weeks ago", threeWeeks.asString(context))
        assertEquals("No calls yet", UiText.res(R.string.home_why_never).asString(context))
    }

    // ============================================================================
    // Long-press quick-actions: deferred delete + mutation dispatch, each
    // with its snackbar contract (features/home/README.md "List tile
    // long-press") and its nudge-chain side effect (NOTIF-11).
    // ============================================================================

    @Test
    fun `requestDelete hides the tile optimistically and undoDelete restores it`() = runTest {
        val setup = fixture(initialTiles = listOf(tile(1L, "Inner orbit"), tile(2L, "Late night")))
        setup.vm.uiState.test(timeout = 2.seconds) {
            // Drain to the member-count-hydrated steady state first: the
            // cache-first initial Ready (memberCount = null) may precede it,
            // and a buffered hydration frame would otherwise interleave with
            // the requestDelete emission below.
            assertEquals(2, (awaitItemMatching { it.isHydratedReady() } as HomeUiState.Ready).lists.size)
            // Deferred delete hides the row without purging it from the repo yet.
            setup.vm.requestDelete(1L)
            assertEquals(listOf(2L), (awaitItem() as HomeUiState.Ready).lists.map { it.id })
            // Undo re-surfaces it; nothing was ever deleted.
            setup.vm.undoDelete(1L)
            assertEquals(listOf(1L, 2L), (awaitItem() as HomeUiState.Ready).lists.map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `requestDelete offers Undo and commitDelete purges the list and ends its nudge chain`() = runTest {
        val listRepo = FakeListRepository()
        val setup = fixture(listRepo = listRepo)
        setup.vm.snackbarEvents.test(timeout = 2.seconds) {
            // Not pending → idempotent no-op (e.g. Undo already removed it).
            setup.vm.commitDelete(9L)
            assertTrue(listRepo.deleteCalls.isEmpty(), "commitDelete on a non-pending id must not delete")
            assertTrue(setup.scheduler.cancelCalls.isEmpty())
            assertEquals(0, setup.widget.refreshes, "nothing changed, so the widget is not asked to refresh")

            setup.vm.requestDelete(9L)
            val event = awaitItem()
            assertEquals(
                HomeSnackbarEvent(
                    message = UiText.res(R.string.lists_snackbar_deleted),
                    actionLabel = UiText.res(R.string.components_action_undo),
                    payloadListId = 9L,
                    kind = HomeSnackbarEvent.Kind.DELETE_UNDO,
                ),
                event,
            )
            assertEquals("List deleted.", event.message.asString(context))
            assertEquals("Undo", event.actionLabel?.asString(context))

            // Stage then finalize → exactly one repo delete, and the chain
            // goes with the row (NOTIF-11), so no orphan slot fires later.
            setup.vm.commitDelete(9L)
            assertEquals(listOf(9L), listRepo.deleteCalls)
            assertEquals(listOf(9L), setup.scheduler.cancelCalls)
            // WIDGET-06: the deleted list's lead leaves the widget with the
            // row. Staging asked for nothing (the database was untouched).
            assertEquals(1, setup.widget.refreshes)
            expectNoEvents()
        }
    }

    @Test
    fun `toggleNotifications flips the flag in place and confirms with the matching copy`() = runTest {
        val listRepo = FakeListRepository()
        val setup = fixture(listRepo = listRepo)
        setup.vm.snackbarEvents.test(timeout = 2.seconds) {
            setup.vm.toggleNotifications(listId = 7L, currentlyEnabled = true)
            assertEquals(listOf(7L to false), listRepo.updateNotificationsEnabledCalls)
            val paused = awaitItem()
            assertEquals(HomeSnackbarEvent(message = UiText.res(R.string.home_snackbar_nudges_paused)), paused)
            assertEquals("Nudges paused.", paused.message.asString(context))
            assertEquals(HomeSnackbarEvent.Kind.PLAIN, paused.kind)
            assertNull(paused.actionLabel, "pausing nudges has no Undo; tapping again reverses it")

            setup.vm.toggleNotifications(listId = 7L, currentlyEnabled = false)
            assertEquals(listOf(7L to false, 7L to true), listRepo.updateNotificationsEnabledCalls)
            val resumed = awaitItem()
            assertEquals(HomeSnackbarEvent(message = UiText.res(R.string.home_snackbar_nudges_on)), resumed)
            assertEquals("Nudges on.", resumed.message.asString(context))
            expectNoEvents()
        }
    }

    @Test
    fun `archiveList cancels the nudge chain and offers Undo, and undoArchive schedules it again`() = runTest {
        val list = listFixture(id = 5L, name = "Inner orbit")
        val listRepo = FakeListRepository(initialLists = listOf(list))
        val setup = fixture(listRepo = listRepo)
        setup.vm.snackbarEvents.test(timeout = 2.seconds) {
            setup.vm.archiveList(5L)
            assertEquals(listOf(5L to true), listRepo.setArchivedCalls)
            // NOTIF-11: the list's chain is cancelled with the archive. Home
            // used to flip the flag only, and the chain kept nudging for a
            // list the user had put away.
            assertEquals(listOf(5L), setup.scheduler.cancelCalls)
            // WIDGET-06: the widget reads the active lists, so it is asked to
            // refresh with the archive. Home did not ask until 2026-10-06
            // (only Lists did), and a widget kept offering the archived
            // list's lead, with a live Call button, until the hourly sweep.
            assertEquals(1, setup.widget.refreshes)
            val event = awaitItem()
            assertEquals(
                HomeSnackbarEvent(
                    message = UiText.res(R.string.lists_snackbar_archived),
                    actionLabel = UiText.res(R.string.components_action_undo),
                    payloadListId = 5L,
                    kind = HomeSnackbarEvent.Kind.ARCHIVE_UNDO,
                ),
                event,
            )
            assertEquals("List archived.", event.message.asString(context))

            setup.vm.undoArchive(5L)
            assertEquals(listOf(5L to true, 5L to false), listRepo.setArchivedCalls)
            // The chain comes back with the list, from the entity's own schedule.
            assertEquals(listOf(5L), setup.scheduler.scheduleFromEntityCalls.map { it.id })
            // And so does the list's lead on the widget.
            assertEquals(2, setup.widget.refreshes)
            expectNoEvents()
        }
    }

    // ============================================================================
    // Failure path (rules.md Code 3): a write that fails says so, once, and
    // never follows it with a success message or an Undo that undoes nothing.
    // ============================================================================

    @Test
    fun `a failed archive emits only the failure and leaves the nudge chain alone`() = runTest {
        val setup = fixture(listRepo = FailingWritesListRepository())
        setup.vm.snackbarEvents.test(timeout = 2.seconds) {
            setup.vm.archiveList(5L)
            val event = awaitItem()
            assertEquals(HomeSnackbarEvent(message = UiText.res(R.string.components_snackbar_save_failed)), event)
            assertEquals("Couldn't save your change", event.message.asString(context))
            assertNull(event.actionLabel, "no Undo for a write that never landed")
            // The cancel sits after the write inside runMutation, so a failed
            // write leaves the chain exactly as it was, and nothing changed
            // for the widget to show.
            assertTrue(setup.scheduler.cancelCalls.isEmpty())
            assertEquals(0, setup.widget.refreshes)
            expectNoEvents()
        }
    }

    @Test
    fun `a failed nudge toggle emits only the failure`() = runTest {
        val setup = fixture(listRepo = FailingWritesListRepository())
        setup.vm.snackbarEvents.test(timeout = 2.seconds) {
            setup.vm.toggleNotifications(listId = 7L, currentlyEnabled = true)
            assertEquals(
                HomeSnackbarEvent(message = UiText.res(R.string.components_snackbar_save_failed)),
                awaitItem(),
            )
            expectNoEvents()
        }
    }
}
