package app.orbit.ui.screens.home

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import app.orbit.data.AppPrefs
import app.orbit.data.entity.ListMembershipEntity
import app.orbit.data.entity.ListType
import app.orbit.data.feed.HomeFeed
import app.orbit.domain.FakeListRepository
import app.orbit.domain.clock.TestClock
import app.orbit.testutil.MainDispatcherRule
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behavioral tests for [HomeViewModel] — the VM is a thin subscriber to
 * [HomeFeed]. Tests inject [FakeHomeFeed] (an `open class` subclass — the same
 * precedent [app.orbit.domain.usecase.MarkCalledUseCase] established for
 * `Test*` subclassing) so the steady-state `tiles` flow can be driven
 * deterministically without spinning up the full feed projection.
 *
 * Pattern (mirrors [app.orbit.ui.screens.card.CardViewViewModelTest]):
 *   - Real VM over the [FakeHomeFeed] subclass.
 *   - [MainDispatcherRule] so `viewModelScope.stateIn` runs on the test
 *     dispatcher.
 *   - [app.cash.turbine.test] asserts terminal state only — the
 *     UnconfinedTestDispatcher + stateIn initial-value collapse is a known
 *     pattern friction. Tests assert the observable terminal contract per
 *     ARCH-02.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class HomeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /**
     * Test seam — overrides [HomeFeed.tiles] with a `MutableStateFlow` the
     * test owns. The parent constructor's `stateIn` over `listRepo
     * .observeActive()` still runs (Kotlin evaluates the parent initializer
     * before the subclass override binds), but its result is discarded — the
     * subclass's `tiles` is what HomeViewModel sees. Construction passes a
     * real but empty [FakeListRepository] + [TestClock] + an
     * UnconfinedTestDispatcher-backed [CoroutineScope] so the parent's
     * eagerly-started flow has a benign upstream to settle against.
     *
     * [HomeFeed] takes an `appPrefs: AppPrefs` constructor parameter for the
     * staleness gate. AppPrefs's eagerly-evaluated Flow
     * fields require a real [android.content.Context] (DataStore extension
     * property), so this fixture pulls one from Robolectric's
     * [ApplicationProvider]. Tests below never invoke `refreshDueCountsIfStale`
     * — they exercise the `tiles` projection only — so the prefs instance is
     * benign.
     */
    private class FakeHomeFeed(
        initialTiles: List<ListTileState> = emptyList(),
    ) : HomeFeed(
        listRepo = FakeListRepository(),
        clock = TestClock(),
        appPrefs = AppPrefs(ApplicationProvider.getApplicationContext()),
        scope = CoroutineScope(UnconfinedTestDispatcher()),
    ) {
        private val _tiles = MutableStateFlow(initialTiles)
        override val tiles: StateFlow<List<ListTileState>> = _tiles.asStateFlow()

        @Suppress("unused")
        fun emit(value: List<ListTileState>) {
            _tiles.value = value
        }
    }

    private fun fixture(
        initialTiles: List<ListTileState> = emptyList(),
        memberships: List<ListMembershipEntity> = emptyList(),
    ): Setup {
        val homeFeed = FakeHomeFeed(initialTiles = initialTiles)
        val clock = TestClock()
        val listRepo = FakeListRepository(initialMemberships = memberships)
        val vm = HomeViewModel(
            homeFeed = homeFeed,
            listRepo = listRepo,
            clock = clock,
        )
        return Setup(vm, homeFeed, listRepo)
    }

    private data class Setup(
        val vm: HomeViewModel,
        val homeFeed: FakeHomeFeed,
        val listRepo: FakeListRepository,
    )

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
    // Test 1 — empty tiles → Empty
    // ============================================================================

    @Test
    fun `empty tiles emits Empty`() = runTest {
        val (vm, _) = fixture()
        vm.uiState.test(timeout = 2.seconds) {
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

    // ============================================================================
    // Test 2 — one tile → Ready with one ListTileState carrying id + name
    // ============================================================================

    @Test
    fun `one tile emits Ready with one tile`() = runTest {
        val (vm, _) = fixture(
            initialTiles = listOf(
                ListTileState(id = 1L, name = "Inner orbit", dueCount = 0, type = ListType.STATIC),
            ),
        )
        vm.uiState.test(timeout = 2.seconds) {
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
            assertTrue(next.hasPermissions, "hasPermissions is hard-coded true")
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ============================================================================
    // Test 3 — three tiles → Ready with 3 tiles (ordering preserved)
    // ============================================================================

    @Test
    fun `multiple tiles project to Ready preserving order`() = runTest {
        val (vm, _) = fixture(
            initialTiles = listOf(
                ListTileState(id = 1L, name = "Inner orbit", dueCount = 0, type = ListType.STATIC),
                ListTileState(id = 2L, name = "Late night", dueCount = 0, type = ListType.STATIC),
                ListTileState(id = 3L, name = "People who ground me", dueCount = 0, type = ListType.STATIC),
            ),
        )
        vm.uiState.test(timeout = 2.seconds) {
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

    // ============================================================================
    // Test 4 — ListType propagates from feed to VM uiState (LIST-07 invariant)
    // ============================================================================
    //
    // `ListTileState` carries a `type: ListType` field so the Home tile
    // renderer can show a "shuffle-angular" auto glyph next to SMART tiles
    // only. The projection lives in HomeFeed; the type must round-trip through
    // the VM unchanged — STATIC stays STATIC, SMART stays SMART.

    @Test
    fun `list type propagates from feed to VM uiState`() = runTest {
        val (vm, _) = fixture(
            initialTiles = listOf(
                ListTileState(id = 1L, name = "Inner orbit", dueCount = 0, type = ListType.STATIC),
                ListTileState(id = 2L, name = "Recently added", dueCount = 0, type = ListType.SMART),
            ),
        )
        vm.uiState.test(timeout = 2.seconds) {
            val next = awaitItem()
            assertTrue(next is HomeUiState.Ready, "expected Ready, got $next")
            assertEquals(2, next.lists.size)
            assertEquals(ListType.STATIC, next.lists[0].type)
            assertEquals(ListType.SMART, next.lists[1].type)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ============================================================================
    // Test 5 — dueCount propagates from feed
    // ============================================================================
    //
    // The VM reads ListEntity.dueCount directly (a column kept fresh by the
    // mutator use cases). The value must round-trip from feed to UI
    // unchanged — no recompute, no clock projection, no combine.

    @Test
    fun `dueCount propagates verbatim from feed`() = runTest {
        val (vm, _) = fixture(
            initialTiles = listOf(
                ListTileState(id = 1L, name = "Inner orbit", dueCount = 4, type = ListType.STATIC),
                ListTileState(id = 2L, name = "Late night", dueCount = 0, type = ListType.STATIC),
            ),
        )
        vm.uiState.test(timeout = 2.seconds) {
            val next = awaitItem()
            assertTrue(next is HomeUiState.Ready, "expected Ready, got $next")
            assertEquals(4, next.lists[0].dueCount)
            assertEquals(0, next.lists[1].dueCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ============================================================================
    // Test 6/7 — the "N people ready" header counts distinct contacts. Per-tile
    // dueCount is a per-list column, so a contact due on two lists used to count
    // twice in the header sum. `Ready.dueContactCount` is the union, derived
    // from `observeMembersOfList` with the same due predicate as
    // ListDao.recomputeDueCount (nextDueAt IS NULL OR <= now).
    // ============================================================================

    @Test
    fun `header due count counts a contact due on two lists once`() = runTest {
        val (vm, _, _) = fixture(
            initialTiles = listOf(
                ListTileState(id = 1L, name = "Inner orbit", dueCount = 1, type = ListType.STATIC),
                ListTileState(id = 2L, name = "Late night", dueCount = 1, type = ListType.STATIC),
            ),
            memberships = listOf(
                // nextDueAt = null → due (mirrors the DAO predicate's IS NULL arm).
                ListMembershipEntity(contactId = 42L, listId = 1L, addedAt = Instant.EPOCH),
                ListMembershipEntity(contactId = 42L, listId = 2L, addedAt = Instant.EPOCH),
            ),
        )
        vm.uiState.test(timeout = 2.seconds) {
            val ready = awaitItemMatching {
                it.isHydratedReady() && (it as HomeUiState.Ready).dueContactCount > 0
            } as HomeUiState.Ready
            // Per-tile badges keep their per-list counts...
            assertEquals(2, ready.lists.sumOf { it.dueCount })
            // ...but the header counts the person once.
            assertEquals(1, ready.dueContactCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `header due count excludes memberships not yet due`() = runTest {
        val (vm, _, _) = fixture(
            initialTiles = listOf(
                ListTileState(id = 1L, name = "Inner orbit", dueCount = 1, type = ListType.STATIC),
                ListTileState(id = 2L, name = "Late night", dueCount = 0, type = ListType.STATIC),
            ),
            memberships = listOf(
                ListMembershipEntity(contactId = 42L, listId = 1L, addedAt = Instant.EPOCH),
                // Due in the future relative to TestClock's fixed now → not counted.
                ListMembershipEntity(
                    contactId = 43L,
                    listId = 2L,
                    addedAt = Instant.EPOCH,
                    nextDueAt = Instant.parse("2026-01-02T12:00:00Z"),
                ),
            ),
        )
        vm.uiState.test(timeout = 2.seconds) {
            val ready = awaitItemMatching {
                it.isHydratedReady() && (it as HomeUiState.Ready).dueContactCount > 0
            } as HomeUiState.Ready
            // The inner combine computes the union over a full membership
            // snapshot, so the first positive value is the settled one: only
            // contact 42 is due — contact 43's nextDueAt is in the future.
            assertEquals(1, ready.dueContactCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ============================================================================
    // Long-press quick-actions — deferred delete + mutation dispatch
    // (features/home/README.md "List tile long-press").
    // ============================================================================

    @Test
    fun `requestDelete hides the tile optimistically and undoDelete restores it`() = runTest {
        val (vm, _, _) = fixture(
            initialTiles = listOf(
                ListTileState(id = 1L, name = "Inner orbit", dueCount = 0, type = ListType.STATIC),
                ListTileState(id = 2L, name = "Late night", dueCount = 0, type = ListType.STATIC),
            ),
        )
        vm.uiState.test(timeout = 2.seconds) {
            // Drain to the member-count-hydrated steady state first — the
            // cache-first initial Ready (memberCount = null) may precede it,
            // and a buffered hydration frame would otherwise interleave with
            // the requestDelete emission below.
            assertEquals(2, (awaitItemMatching { it.isHydratedReady() } as HomeUiState.Ready).lists.size)
            // Deferred delete hides the row without purging it from the repo yet.
            vm.requestDelete(1L)
            assertEquals(listOf(2L), (awaitItem() as HomeUiState.Ready).lists.map { it.id })
            // Undo re-surfaces it — nothing was ever deleted.
            vm.undoDelete(1L)
            assertEquals(listOf(1L, 2L), (awaitItem() as HomeUiState.Ready).lists.map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `commitDelete purges a pending list and bare commitDelete is a no-op`() = runTest {
        val (vm, _, listRepo) = fixture()
        // Not pending → idempotent no-op (e.g. Undo already removed it).
        vm.commitDelete(9L)
        assertTrue(listRepo.deleteCalls.isEmpty(), "commitDelete on a non-pending id must not delete")
        // Stage then finalize → exactly one repo delete.
        vm.requestDelete(9L)
        vm.commitDelete(9L)
        assertEquals(listOf(9L), listRepo.deleteCalls)
    }

    @Test
    fun `toggleNotifications flips the per-list flag to the opposite of current`() = runTest {
        val (vm, _, listRepo) = fixture()
        vm.toggleNotifications(listId = 7L, currentlyEnabled = true)
        assertEquals(listOf(7L to false), listRepo.updateNotificationsEnabledCalls)
    }

    @Test
    fun `archiveList archives and undoArchive restores`() = runTest {
        val (vm, _, listRepo) = fixture()
        vm.archiveList(5L)
        vm.undoArchive(5L)
        assertEquals(listOf(5L to true, 5L to false), listRepo.setArchivedCalls)
    }
}
