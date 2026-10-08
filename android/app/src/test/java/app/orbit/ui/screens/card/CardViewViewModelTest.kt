package app.orbit.ui.screens.card

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import app.orbit.R
import app.orbit.data.entity.ListType
import app.orbit.data.feed.CardFeed
import app.orbit.domain.FakeCallEventRepository
import app.orbit.domain.FakeContactRepository
import app.orbit.domain.FakeListRepository
import app.orbit.domain.FakeNoteRepository
import app.orbit.domain.FakeRuleTemplateRepository
import app.orbit.domain.JsonProvider
import app.orbit.domain.callEventFixture
import app.orbit.domain.clock.TestClock
import app.orbit.domain.contactFixture
import app.orbit.domain.listFixture
import app.orbit.domain.membershipFixture
import app.orbit.domain.ruleTemplateFixture
import app.orbit.domain.undo.UndoStack
import app.orbit.domain.usecase.PauseContactUseCase
import app.orbit.domain.usecase.SkipContactUseCase
import app.orbit.domain.usecase.SurfaceNextUseCase
import app.orbit.domain.usecase.SurfaceQueueUseCase
import app.orbit.testutil.MainDispatcherRule
import app.orbit.ui.util.UiText
import app.orbit.ui.util.formatAgo
import app.orbit.ui.util.formatSpan
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.format.TextStyle
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
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
 *     EmptyNothingEligible | Ready | Error) ordering per ARCH-02 contract
 *     (initial state is ALWAYS Loading for a parseable list id).
 *
 * Card-view audit (2026-10-06) added the state-contract fences for CARD-05
 * (the pause hint), CARD-07 (Error with Try again, a malformed id), the smart
 * list's empty deck, the list name on every state, and the F-8 flash
 * (`first non-Loading emission is Ready`, which needs the feed on a paused
 * dispatcher: on an unconfined one the combine answers before anyone
 * subscribes, which is how the flash hid from the earlier tests).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CardViewViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val T0: Instant = Instant.parse("2026-01-01T12:00:00Z")

    /**
     * Builds a [CardViewViewModel] over fakes. Default `savedStateListId = "1"`
     * (parseable Long). Pass a non-numeric string to exercise the malformed-id
     * path. Pre-seeds a rule template with id=1L so the SurfaceNextUseCase
     * pipeline has a resolvable template, so tests that seed
     * `listFixture(id=1L, ruleTemplateId=1L)` get a live path; tests that
     * don't seed anything still get an empty-repo Flow that emits null.
     * [feedDispatcher] runs CardFeed's Eagerly-started flows; unconfined by
     * default, paused for the tests about what the screen sees first.
     */
    private fun fixture(
        savedStateListId: String? = "1",
        feedDispatcher: TestDispatcher = UnconfinedTestDispatcher(),
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
            scope = CoroutineScope(feedDispatcher),
        )
        val vm = CardViewViewModel(
            cardFeed = cardFeed,
            skipContact = skipContact,
            surfaceSooner = surfaceSooner,
            // CARD-10: the shared log path; the state contract never logs.
            logConnection = app.orbit.domain.usecase.LogConnectionUseCase(
                markCalled = app.orbit.domain.usecase.MarkCalledUseCase(
                    contactRepo = contactRepo,
                    listRepo = listRepo,
                    callEventRepo = callEventRepo,
                    ruleTemplateRepo = templateRepo,
                    clock = clock,
                    json = json,
                ),
                addRetroactiveNote = app.orbit.domain.usecase.AddRetroactiveNoteUseCase(noteRepo),
                clock = clock,
                zoneId = java.time.ZoneId.of("UTC"),
            ),
            // 2026-06-09 — swipe-undo surface: the VM captures + restores the
            // membership schedule through ListRepository and stages the inverse
            // on the depth-1 UndoStack.
            listRepo = listRepo,
            // CARD-11 reads the confirmed call.
            callEventRepo = callEventRepo,
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
        return Setup(vm, contactRepo, listRepo, callEventRepo)
    }

    private data class Setup(
        val vm: CardViewViewModel,
        val contactRepo: FakeContactRepository,
        val listRepo: FakeListRepository,
        val callEventRepo: FakeCallEventRepository,
    )

    /** Seeds list 1 (template 1) with one cold-start member, "Sarah" (id 1). */
    private fun Setup.seedSarahReady() {
        contactRepo.seed(listOf(contactFixture(id = 1L, displayName = "Sarah")))
        listRepo.seed(listOf(listFixture(id = 1L, ruleTemplateId = 1L)))
        listRepo.seedMemberships(
            listOf(membershipFixture(contactId = 1L, listId = 1L, nextDueAt = null)),
        )
    }

    /**
     * The first emission that is not [CardViewUiState.Loading]. StateFlow
     * conflation under the unconfined dispatcher may or may not deliver the
     * Loading prefix; the tests assert the terminal contract either way.
     */
    private suspend fun ReceiveTurbine<CardViewUiState>.awaitLoaded(): CardViewUiState {
        while (true) {
            val item = awaitItem()
            if (item !is CardViewUiState.Loading) return item
        }
    }

    // ============================================================================
    // Test 1: empty repo → EmptyNoMembers. With no contacts and no memberships
    // seeded, SurfaceNextUseCase's `visibleMembers` collection is empty → the
    // result is NoMembers, which the VM maps to EmptyNoMembers. (Pre-tide-marker
    // (2026-05-08) this was AllCaughtUp.) No list row either, so its name is blank.
    // ============================================================================

    @Test
    fun `empty repo emits EmptyNoMembers`() = runTest {
        val (vm, _, _, _) = fixture(savedStateListId = "1")
        vm.uiState.test(timeout = 2.seconds) {
            assertEquals(CardViewUiState.EmptyNoMembers(), awaitLoaded())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ============================================================================
    // Test 2: seeded candidate → Ready carries contact name, and the list's
    // name and type (the app bar's title and the menu's "Add people" gate).
    // ============================================================================

    @Test
    fun `seeded candidate produces Ready with contact name and list context`() = runTest {
        val setup = fixture(savedStateListId = "1")
        // Seed: list id=1 with template id=1, one contact named "Sarah" who is
        // a member of list 1. No prior call events → cold-start: engine
        // surfaces the contact immediately.
        setup.seedSarahReady()
        setup.vm.uiState.test(timeout = 2.seconds) {
            val next = awaitLoaded()
            assertTrue(
                next is CardViewUiState.Ready,
                "expected Ready, got $next",
            )
            assertEquals("Sarah", next.contact.name)
            assertEquals("c-1", next.contact.id)
            assertEquals(1, next.queueSize)
            assertEquals("List 1", next.listContext)
            assertEquals(ListType.STATIC, next.listType)
            assertNull(next.lastCallAt, "never called")
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ============================================================================
    // Test 3: CARD-07: a non-numeric listId (a bad deep link) is the Error
    // deck, not a quiet one. It used to route to EmptyNothingEligible and read
    // "All quiet for now." with a Browse button into a list that does not exist
    // (rules.md Code 3: a path that cannot happen gets a loud guard). That deck
    // has no feed behind it, so it says so (`canRetry = false`) and Try again
    // leaves it exactly as it was; until 2026-10-06 the screen offered Try
    // again as its accent and the tap changed nothing.
    // ============================================================================

    @Test
    fun `CARD-07 - non-numeric listId String routes to Error that cannot retry`() = runTest {
        val (vm, _, _, _) = fixture(savedStateListId = "inner")
        vm.uiState.test(timeout = 2.seconds) {
            assertEquals(CardViewUiState.Error(canRetry = false), awaitItem())
            vm.onRetry()
            expectNoEvents()
            assertEquals(CardViewUiState.Error(canRetry = false), vm.uiState.value)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ============================================================================
    // Test 4: F-8: Loading is the structural initial value of the data-bound
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
    // Test 5: F-8 lock: seeded candidate transitions Loading → Ready after
    // scheduler drain (the post-load half of the F-8 contract).
    // ============================================================================

    @Test
    fun `Loading transitions to Ready when seeded candidate emits`() {
        val dispatcher = StandardTestDispatcher()
        mainDispatcherRule.withMainDispatcher(dispatcher) {
            val setup = fixture(savedStateListId = "1")
            setup.seedSarahReady()
            assertEquals(
                CardViewUiState.Loading,
                setup.vm.uiState.value,
                "synchronous initial value before scheduler drains",
            )
            // SharingStarted.WhileSubscribed only collects upstream once there
            // is a downstream subscriber. Park a collector so the stateIn
            // pipeline runs when the scheduler advances.
            val collectScope = CoroutineScope(dispatcher)
            val job = collectScope.launch { setup.vm.uiState.collect {} }
            dispatcher.scheduler.advanceUntilIdle()
            val drained = setup.vm.uiState.value
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
    // Test 6: F-8 lock: empty repo transitions Loading → EmptyNoMembers after
    // scheduler drain (the post-load empty-state half of the F-8 contract).
    // Tide marker (2026-05-08) — the empty state for "no memberships" is now
    // EmptyNoMembers, distinct from EmptyNothingEligible (paused / out of reach).
    // ============================================================================

    @Test
    fun `Loading transitions to EmptyNoMembers when no candidate is seeded`() {
        val dispatcher = StandardTestDispatcher()
        mainDispatcherRule.withMainDispatcher(dispatcher) {
            val (vm, _, _, _) = fixture(savedStateListId = "1")
            assertEquals(
                CardViewUiState.Loading,
                vm.uiState.value,
                "synchronous initial value before scheduler drains",
            )
            val collectScope = CoroutineScope(dispatcher)
            val job = collectScope.launch { vm.uiState.collect {} }
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(
                CardViewUiState.EmptyNoMembers(),
                vm.uiState.value,
                "post-drain state for empty repo",
            )
            job.cancel()
        }
    }

    // ============================================================================
    // Test 7: CARD-07 non-regression: the malformed-id branch has no feed to
    // wait on, so its synchronous initial value is already Error, not Loading.
    // ============================================================================

    @Test
    fun `CARD-07 - non-numeric listId starts at Error synchronously`() {
        mainDispatcherRule.withMainDispatcher(StandardTestDispatcher()) {
            val setup = fixture(savedStateListId = "inner")
            assertEquals(
                CardViewUiState.Error(canRetry = false),
                setup.vm.uiState.value,
                "listId == null branch uses initialValue = Error, not Loading",
            )
        }
    }

    // ============================================================================
    // Test 8: F-8 in full (CARD-05): on a cold open the screen subscribes
    // before CardFeed's combine has answered, and the feed's StateFlow replays
    // its placeholder. The placeholder used to be a real-looking
    // NothingEligible, so a seeded list flashed "All quiet for now." before
    // Ready. The feed and the VM run on one paused dispatcher here (the
    // unconfined fixture lets the combine answer before anyone subscribes,
    // which hid the flash); the collector is unconfined on the same scheduler
    // so it sees every value instead of StateFlow's conflated latest.
    // ============================================================================

    @Test
    fun `CARD-05 - first non-Loading emission for a seeded list is Ready, never an empty deck`() {
        val dispatcher = StandardTestDispatcher()
        mainDispatcherRule.withMainDispatcher(dispatcher) {
            val setup = fixture(savedStateListId = "1", feedDispatcher = dispatcher)
            setup.seedSarahReady()
            val seen = mutableListOf<CardViewUiState>()
            val collectScope = CoroutineScope(UnconfinedTestDispatcher(dispatcher.scheduler))
            val job = collectScope.launch { setup.vm.uiState.collect { seen += it } }
            dispatcher.scheduler.advanceUntilIdle()
            val first = seen.firstOrNull { it !is CardViewUiState.Loading }
            assertTrue(first is CardViewUiState.Ready, "expected Ready first, saw $seen")
            assertTrue(
                seen.none { it is CardViewUiState.EmptyNothingEligible || it is CardViewUiState.EmptyNoMembers },
                "no empty deck may flash before the data arrives, saw $seen",
            )
            job.cancel()
        }
    }

    // ============================================================================
    // Test 9: CARD-07: a failed read is an Error deck with Try again, not a
    // crash. The failure is in the feed's upstream (observeById), so it must
    // be caught before the feed's Eagerly-started stateIn (an exception there
    // has no handler on @ApplicationScope); Try again must rebuild the feed
    // entry, since the memoized flow that failed never emits again.
    // ============================================================================

    @Test
    fun `CARD-07 - a failing list read emits Error and Try again recovers`() = runTest {
        val setup = fixture(savedStateListId = "1")
        setup.seedSarahReady()
        setup.listRepo.failObserveById = true
        setup.vm.uiState.test(timeout = 2.seconds) {
            assertEquals(CardViewUiState.Error(), awaitLoaded())
            setup.listRepo.failObserveById = false
            setup.vm.onRetry()
            val recovered = awaitItemMatching { it !is CardViewUiState.Loading && it !is CardViewUiState.Error }
            assertTrue(recovered is CardViewUiState.Ready, "expected Ready after Try again, got $recovered")
            assertEquals("Sarah", recovered.contact.name)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ============================================================================
    // Test 10: the smart list's empty deck carries its type, so the screen
    // can offer "List settings" instead of "Add people" (its members are its
    // rule's matches; the sync removed anyone added by hand, silently).
    // ============================================================================

    @Test
    fun `a smart list with no members emits EmptyNoMembers with its name and type`() = runTest {
        val setup = fixture(savedStateListId = "1")
        setup.listRepo.seed(listOf(listFixture(id = 1L, ruleTemplateId = 1L, name = "Late night", type = ListType.SMART)))
        setup.vm.uiState.test(timeout = 2.seconds) {
            assertEquals(
                CardViewUiState.EmptyNoMembers(listName = "Late night", listType = ListType.SMART),
                awaitLoaded(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ============================================================================
    // Tests 11 and 12: CARD-05: "All quiet for now" names who comes up next
    // and when. A paused person is announced for when the pause lifts, not for
    // their stale nextDueAt (the hint used to say "Sarah comes up on Tuesday"
    // while her pause ran for weeks); a pause until you unpause has no date, so
    // nobody is named.
    // ============================================================================

    @Test
    fun `CARD-05 - a timed pause is announced for when it lifts, with the list's name`() = runTest {
        val setup = fixture(savedStateListId = "1")
        val pauseEnds = T0.plus(Duration.ofDays(20))
        setup.contactRepo.seed(listOf(contactFixture(id = 1L, displayName = "Sarah Connor", pausedUntil = pauseEnds)))
        setup.listRepo.seed(listOf(listFixture(id = 1L, ruleTemplateId = 1L, name = "Inner orbit")))
        setup.listRepo.seedMemberships(
            listOf(membershipFixture(contactId = 1L, listId = 1L, nextDueAt = T0.plus(Duration.ofDays(3)))),
        )
        setup.vm.uiState.test(timeout = 2.seconds) {
            assertEquals(
                CardViewUiState.EmptyNothingEligible(
                    upNextName = "Sarah Connor",
                    upNextLabel = UiText.res(R.string.card_due_in_span, formatSpan(20)),
                    listName = "Inner orbit",
                    listType = ListType.STATIC,
                ),
                awaitLoaded(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `CARD-05 - a pause until you unpause names nobody`() = runTest {
        val setup = fixture(savedStateListId = "1")
        setup.contactRepo.seed(
            listOf(contactFixture(id = 1L, displayName = "Sarah Connor", pausedUntil = PauseContactUseCase.INDEFINITE_PAUSE_SENTINEL)),
        )
        setup.listRepo.seed(listOf(listFixture(id = 1L, ruleTemplateId = 1L, name = "Inner orbit")))
        setup.listRepo.seedMemberships(
            listOf(membershipFixture(contactId = 1L, listId = 1L, nextDueAt = T0.plus(Duration.ofDays(3)))),
        )
        setup.vm.uiState.test(timeout = 2.seconds) {
            assertEquals(
                CardViewUiState.EmptyNothingEligible(upNextName = null, upNextLabel = null, listName = "Inner orbit"),
                awaitLoaded(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ============================================================================
    // Tests 13 and 14: CARD-04: the pair's rhythm once there are four calls
    // (three gaps, the median), stated beneath the "since you last spoke" line;
    // three calls are a guess, so no rhythm.
    // ============================================================================

    private fun Setup.seedSarahDueNowWithCalls(daysAgo: List<Long>) {
        contactRepo.seed(listOf(contactFixture(id = 1L, displayName = "Sarah Connor")))
        listRepo.seed(listOf(listFixture(id = 1L, ruleTemplateId = 1L)))
        listRepo.seedMemberships(
            listOf(membershipFixture(contactId = 1L, listId = 1L, nextDueAt = T0.minus(Duration.ofHours(1)))),
        )
        callEventRepo.seed(
            daysAgo.mapIndexed { index, days ->
                callEventFixture(id = index + 1L, contactId = 1L, occurredAt = T0.minus(Duration.ofDays(days)))
            },
        )
    }

    @Test
    fun `CARD-04 - four calls two weeks apart read as a two-week rhythm`() = runTest {
        val setup = fixture(savedStateListId = "1")
        setup.seedSarahDueNowWithCalls(daysAgo = listOf(3L, 17L, 31L, 45L))
        setup.vm.uiState.test(timeout = 2.seconds) {
            val state = awaitLoaded()
            assertTrue(state is CardViewUiState.Ready, "expected Ready, got $state")
            // "You spoke 3 days ago." over the rhythm; WhyLineVoiceTest holds
            // the rendered words to voice.md.
            assertEquals(
                UiText.res(
                    R.string.card_why_two_lines,
                    UiText.res(R.string.card_why_ago, formatAgo(3)),
                    UiText.plural(R.plurals.card_rhythm_weeks, 2, 2),
                ),
                state.whyNowLine,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `CARD-04 - three calls give the spoke line and no rhythm`() = runTest {
        val setup = fixture(savedStateListId = "1")
        setup.seedSarahDueNowWithCalls(daysAgo = listOf(3L, 17L, 31L))
        setup.vm.uiState.test(timeout = 2.seconds) {
            val state = awaitLoaded()
            assertTrue(state is CardViewUiState.Ready, "expected Ready, got $state")
            assertEquals(UiText.res(R.string.card_why_ago, formatAgo(3)), state.whyNowLine)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ============================================================================
    // Test 15: the forward-looking phrase behind Log a connection's snackbar
    // and the up-next hint: "later today" / "tomorrow" / "on {weekday}" /
    // "in {span}". Later and Sooner say no "when" since 2026-10-08 (CARD-02).
    // ============================================================================

    @Test
    fun `futureDueLabel buckets by days ahead`() {
        val (vm, _, _, _) = fixture(savedStateListId = "1")
        assertEquals(UiText.res(R.string.card_due_later_today), vm.futureDueLabel(T0.plus(Duration.ofHours(2)), T0))
        assertEquals(UiText.res(R.string.card_due_tomorrow), vm.futureDueLabel(T0.plus(Duration.ofDays(1)), T0))
        // T0 is a Thursday; three days on is Sunday, in the default locale's words.
        assertEquals(
            UiText.res(R.string.card_due_on_day, DayOfWeek.SUNDAY.getDisplayName(TextStyle.FULL, Locale.getDefault())),
            vm.futureDueLabel(T0.plus(Duration.ofDays(3)), T0),
        )
        assertEquals(UiText.res(R.string.card_due_in_span, formatSpan(10)), vm.futureDueLabel(T0.plus(Duration.ofDays(10)), T0))
    }

    private suspend fun <T> ReceiveTurbine<T>.awaitItemMatching(predicate: (T) -> Boolean): T {
        while (true) {
            val item = awaitItem()
            if (predicate(item)) return item
        }
    }
}
