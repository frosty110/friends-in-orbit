package app.orbit.ui.screens.card

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import app.orbit.data.feed.CardFeed
import app.orbit.domain.FakeCallEventRepository
import app.orbit.domain.FakeContactRepository
import app.orbit.domain.FakeListRepository
import app.orbit.domain.FakeNoteRepository
import app.orbit.domain.FakeRuleTemplateRepository
import app.orbit.domain.JsonProvider
import app.orbit.domain.clock.TestClock
import app.orbit.domain.contactFixture
import app.orbit.domain.listFixture
import app.orbit.domain.membershipFixture
import app.orbit.domain.ruleTemplateFixture
import app.orbit.domain.undo.UndoStack
import app.orbit.domain.usecase.MarkCalledUseCase
import app.orbit.domain.usecase.SkipContactUseCase
import app.orbit.domain.usecase.SurfaceNextUseCase
import app.orbit.domain.usecase.SurfaceQueueUseCase
import app.orbit.testutil.MainDispatcherRule
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * Behavioral tests for [CardViewViewModel] — the first `@HiltViewModel` in
 * the codebase and the regression fence for the VM pattern that the rest of
 * the screens replicate.
 *
 * Pattern (mirrors `SurfaceNextUseCaseTest`):
 *   - Real use cases constructed over fake repositories (from
 *     [app.orbit.domain.FakeRepositories]).
 *   - [TestClock] pinned at `2026-01-01T12:00:00Z`.
 *   - [ZoneOffset.UTC] so active-hours math is deterministic.
 *   - [MainDispatcherRule] so `viewModelScope` + `stateIn` run on the test
 *     dispatcher (no `Dispatchers.Main` availability error).
 *   - [app.cash.turbine.test] asserts Loading → (EmptyNoMembers |
 *     EmptyNothingEligible | Ready) ordering per ARCH-02 contract (initial
 *     state is ALWAYS Loading).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CardViewViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val T0: Instant = Instant.parse("2026-01-01T12:00:00Z")

    /**
     * Builds a [CardViewViewModel] over fakes. Default `savedStateListId = "1"`
     * (parseable Long). Pass a non-numeric sentinel to exercise the
     * parse-fallback path. Pre-seeds a rule template with id=1L so the
     * SurfaceNextUseCase pipeline has a resolvable template — tests that seed
     * `listFixture(id=1L, ruleTemplateId=1L)` get a live path; tests that
     * don't seed anything still get an empty-repo Flow that emits null.
     */
    private fun fixture(
        savedStateListId: String? = "1",
    ): Setup {
        val contactRepo = FakeContactRepository()
        val listRepo = FakeListRepository()
        val callEventRepo = FakeCallEventRepository()
        val templateRepo = FakeRuleTemplateRepository(
            initial = listOf(ruleTemplateFixture(id = 1L)),
        )
        val clock = TestClock(T0)
        val json = JsonProvider.json
        val surfaceNext = SurfaceNextUseCase(
            contactRepo = contactRepo,
            listRepo = listRepo,
            callEventRepo = callEventRepo,
            ruleTemplateRepo = templateRepo,
            clock = clock,
            json = json,
        )
        val markCalled = MarkCalledUseCase(
            contactRepo = contactRepo,
            listRepo = listRepo,
            callEventRepo = callEventRepo,
            ruleTemplateRepo = templateRepo,
            clock = clock,
            json = json,
        )
        val skipContact = SkipContactUseCase(
            contactRepo = contactRepo,
            listRepo = listRepo,
            ruleTemplateRepo = templateRepo,
            clock = clock,
            json = json,
        )
        // CardViewViewModel ctor takes surfaceSooner + listRepository; the
        // args are wired here so the unit-test target compiles.
        // SurfaceSoonerUseCase requires a TransactionRunner. Pass-thru
        // runner runs the block directly (no dispatcher switch).
        val passThruTx = object : app.orbit.data.db.TransactionRunner {
            override suspend fun <T> withTransaction(block: suspend () -> T): T = block()
        }
        val surfaceSooner = app.orbit.domain.usecase.SurfaceSoonerUseCase(
            txRunner = passThruTx,
            contactRepo = contactRepo,
            listRepo = listRepo,
            ruleTemplateRepo = templateRepo,
            clock = clock,
            json = json,
        )
        // CardViewViewModel ctor takes NoteRepository
        // + Clock for the RecentNotesSummary (NOTE-03). Empty FakeNoteRepository
        // exercises the empty-section path; tests that need to assert recent
        // notes can seed via the existing FakeNoteRepository.seed() helper.
        val noteRepo = FakeNoteRepository()
        val savedState = SavedStateHandle(mapOf("listId" to savedStateListId))
        // Real [CardFeed] over the existing fakes; the
        // singleton's `forList(listId)` projection re-derives from the same
        // `surfaceNext × observeById × recentForContact` flows the legacy VM
        // combine consumed, so the existing seeded-state tests assert on the
        // same observable contract through the new singleton.
        // Card hydration (2026-06-09) — CardFeed now also folds the surfaced
        // contact's recent call events, the real due-now queue size, and the
        // up-next hint; the ctor takes the queue use case + the two extra repos.
        val surfaceQueue = SurfaceQueueUseCase(
            contactRepo = contactRepo,
            listRepo = listRepo,
            callEventRepo = callEventRepo,
            ruleTemplateRepo = templateRepo,
            clock = clock,
            json = json,
        )
        val cardFeed = CardFeed(
            surfaceNext = surfaceNext,
            surfaceQueue = surfaceQueue,
            listRepo = listRepo,
            noteRepo = noteRepo,
            contactRepo = contactRepo,
            callEventRepo = callEventRepo,
            clock = clock,
            scope = CoroutineScope(UnconfinedTestDispatcher()),
        )
        val vm = CardViewViewModel(
            cardFeed = cardFeed,
            markCalled = markCalled,
            skipContact = skipContact,
            surfaceSooner = surfaceSooner,
            // 2026-06-09 — swipe-undo surface: the VM captures + restores the
            // membership schedule through ListRepository and stages the inverse
            // on the depth-1 UndoStack.
            listRepo = listRepo,
            undoStack = UndoStack(),
            // CORE-04 — return-from-dial resync seam; state-contract tests don't
            // exercise the dial path, so a no-op SAM suffices.
            callLogResync = { },
            clock = clock,
            // WR-02 — CardViewViewModel now takes ZoneId so the per-emission
            // nowHour snapshot reads from the injected binding instead of
            // ZoneId.systemDefault(). Tests pin to UTC for determinism.
            zoneId = java.time.ZoneId.of("UTC"),
            savedStateHandle = savedState,
        )
        return Setup(vm, contactRepo, listRepo)
    }

    private data class Setup(
        val vm: CardViewViewModel,
        val contactRepo: FakeContactRepository,
        val listRepo: FakeListRepository,
    )

    // ============================================================================
    // Test 1 — empty repo → EmptyNoMembers. With no contacts and no memberships
    // seeded, SurfaceNextUseCase's `visibleMembers` collection is empty → the
    // result is NoMembers, which the VM maps to EmptyNoMembers. (Pre-tide-marker
    // (2026-05-08) this was AllCaughtUp.)
    // ============================================================================

    @Test
    fun `empty repo emits EmptyNoMembers`() = runTest {
        val (vm, _, _) = fixture(savedStateListId = "1")
        vm.uiState.test(timeout = 2.seconds) {
            assertEquals(CardViewUiState.EmptyNoMembers, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ============================================================================
    // Test 2 — seeded candidate → Ready carries contact name
    // ============================================================================

    @Test
    fun `seeded candidate produces Ready with contact name`() = runTest {
        val (vm, contactRepo, listRepo) = fixture(savedStateListId = "1")
        // Seed: list id=1 with template id=1, one contact named "Sarah" who is
        // a member of list 1. No prior call events → cold-start: engine
        // surfaces the contact immediately.
        contactRepo.seed(listOf(contactFixture(id = 1L, displayName = "Sarah")))
        listRepo.seed(listOf(listFixture(id = 1L, ruleTemplateId = 1L)))
        listRepo.seedMemberships(
            listOf(membershipFixture(contactId = 1L, listId = 1L, nextDueAt = null)),
        )
        vm.uiState.test(timeout = 2.seconds) {
            val next = awaitItem()
            assertTrue(
                next is CardViewUiState.Ready,
                "expected Ready, got $next",
            )
            assertEquals("Sarah", next.contact.name)
            assertEquals("c-1", next.contact.id)
            assertEquals(1, next.queueSize)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ============================================================================
    // Test 3 — non-numeric listId in SavedStateHandle → EmptyNothingEligible
    // (the unparseable demo-sentinel branch of the VM constructor).
    // ============================================================================

    @Test
    fun `non-numeric listId String routes to EmptyNothingEligible`() = runTest {
        val (vm, _, _) = fixture(savedStateListId = "inner")
        vm.uiState.test(timeout = 2.seconds) {
            // listId.toLongOrNull() returns null → flowOf(EmptyNothingEligible()).
            assertEquals(CardViewUiState.EmptyNothingEligible(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ============================================================================
    // Test 4 — F-8: Loading is the structural initial value of the data-bound
    // branch. StandardTestDispatcher pauses the coroutine machinery so the
    // synchronous StateFlow.value immediately after VM construction equals the
    // `stateIn(initialValue)`.
    // ============================================================================

    @Test
    fun `initial StateFlow value is Loading before scheduler drains`() {
        // StandardTestDispatcher pauses the coroutine machinery; stateIn won't
        // launch its upstream pipeline until the test scheduler explicitly
        // advances. Capture the synchronous StateFlow.value immediately after
        // VM construction — it must equal the stateIn initialValue.
        //
        // F-8 (REVIEW-WHOLE-APP-2026-05-04, commit 92b4fac) flips the initial
        // value back to Loading: surfacing an empty-state shell pre-emission
        // caused a visible empty-copy flash on first list open. The screen
        // now renders a transparent placeholder for Loading and only shows
        // the empty-state shell when an empty SurfaceResult arrives post-load.
        mainDispatcherRule.withMainDispatcher(StandardTestDispatcher()) {
            val setup = fixture(savedStateListId = "1")
            assertEquals(
                CardViewUiState.Loading,
                setup.vm.uiState.value,
                "stateIn(initialValue = Loading) contract — first observable value (F-8 / 92b4fac)",
            )
        }
    }

    // ============================================================================
    // Test 5 — F-8 lock: seeded candidate transitions Loading → Ready after
    // scheduler drain (the post-load half of the F-8 contract).
    // ============================================================================

    @Test
    fun `Loading transitions to Ready when seeded candidate emits`() {
        val dispatcher = StandardTestDispatcher()
        mainDispatcherRule.withMainDispatcher(dispatcher) {
            val (vm, contactRepo, listRepo) = fixture(savedStateListId = "1")
            contactRepo.seed(listOf(contactFixture(id = 1L, displayName = "Sarah")))
            listRepo.seed(listOf(listFixture(id = 1L, ruleTemplateId = 1L)))
            listRepo.seedMemberships(
                listOf(membershipFixture(contactId = 1L, listId = 1L, nextDueAt = null)),
            )
            assertEquals(
                CardViewUiState.Loading,
                vm.uiState.value,
                "synchronous initial value before scheduler drains",
            )
            // SharingStarted.WhileSubscribed only collects upstream once there
            // is a downstream subscriber. Park a collector so the stateIn
            // pipeline runs when the scheduler advances.
            val collectScope = CoroutineScope(dispatcher)
            val job = collectScope.launch { vm.uiState.collect {} }
            dispatcher.scheduler.advanceUntilIdle()
            val drained = vm.uiState.value
            assertTrue(
                drained is CardViewUiState.Ready,
                "expected Ready after drain, got $drained",
            )
            assertEquals("Sarah", drained.contact.name)
            assertEquals("c-1", drained.contact.id)
            job.cancel()
        }
    }

    // ============================================================================
    // Test 6 — F-8 lock: empty repo transitions Loading → EmptyNoMembers after
    // scheduler drain (the post-load empty-state half of the F-8 contract).
    // Tide marker (2026-05-08) — the empty state for "no memberships" is now
    // EmptyNoMembers, distinct from EmptyNothingEligible (paused / out of reach).
    // ============================================================================

    @Test
    fun `Loading transitions to EmptyNoMembers when no candidate is seeded`() {
        val dispatcher = StandardTestDispatcher()
        mainDispatcherRule.withMainDispatcher(dispatcher) {
            val (vm, _, _) = fixture(savedStateListId = "1")
            assertEquals(
                CardViewUiState.Loading,
                vm.uiState.value,
                "synchronous initial value before scheduler drains",
            )
            val collectScope = CoroutineScope(dispatcher)
            val job = collectScope.launch { vm.uiState.collect {} }
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(
                CardViewUiState.EmptyNoMembers,
                vm.uiState.value,
                "post-drain state for empty repo",
            )
            job.cancel()
        }
    }

    // ============================================================================
    // Test 7 — F-8 non-regression: non-numeric listId routes through the
    // `flowOf(EmptyNothingEligible)` branch whose synchronous initial differs
    // from the numeric branch. This guards the unparseable demo-sentinel path
    // against future Loading-everywhere refactors.
    // ============================================================================

    @Test
    fun `non-numeric listId starts at EmptyNothingEligible synchronously`() {
        mainDispatcherRule.withMainDispatcher(StandardTestDispatcher()) {
            val setup = fixture(savedStateListId = "inner")
            assertEquals(
                CardViewUiState.EmptyNothingEligible(),
                setup.vm.uiState.value,
                "listId == null branch uses initialValue = EmptyNothingEligible, not Loading",
            )
        }
    }
}
