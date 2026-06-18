package app.orbit.ui.screens.card

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import app.orbit.data.entity.CallDirection
import app.orbit.data.entity.CallSource
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
import app.orbit.domain.usecase.MarkCalledUseCase
import app.orbit.domain.usecase.SkipContactUseCase
import app.orbit.domain.usecase.SurfaceNextUseCase
import app.orbit.domain.usecase.SurfaceQueueUseCase
import app.orbit.domain.usecase.SurfaceSoonerUseCase
import app.orbit.testutil.MainDispatcherRule
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * Interaction-loop tests for [CardViewViewModel] — the swipe / mark-called /
 * undo / call-prompt surface that [CardViewViewModelTest] (state contract)
 * does not cover.
 *
 * Same construction pattern as [CardViewViewModelTest]: real use cases over the
 * [app.orbit.domain.FakeRepositories] fakes, [TestClock] pinned at
 * `2026-01-01T12:00:00Z`, UTC zone, and [MainDispatcherRule]'s
 * `UnconfinedTestDispatcher` so `viewModelScope.launch` mutations run eagerly.
 *
 * The fixture here returns the extra fake handles (`callEventRepo`, `undoStack`)
 * the interaction assertions read; the state-contract file only needs the
 * contact + list fakes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CardViewViewModelInteractionTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val T0: Instant = Instant.parse("2026-01-01T12:00:00Z")

    private data class Setup(
        val vm: CardViewViewModel,
        val contactRepo: FakeContactRepository,
        val listRepo: FakeListRepository,
        val callEventRepo: FakeCallEventRepository,
        val undoStack: UndoStack,
    )

    private fun fixture(savedStateListId: String? = "1"): Setup {
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
        val passThruTx = object : app.orbit.data.db.TransactionRunner {
            override suspend fun <T> withTransaction(block: suspend () -> T): T = block()
        }
        val surfaceSooner = SurfaceSoonerUseCase(
            txRunner = passThruTx,
            contactRepo = contactRepo,
            listRepo = listRepo,
            ruleTemplateRepo = templateRepo,
            clock = clock,
            json = json,
        )
        val noteRepo = FakeNoteRepository()
        val savedState = SavedStateHandle(mapOf("listId" to savedStateListId))
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
        val undoStack = UndoStack()
        val vm = CardViewViewModel(
            cardFeed = cardFeed,
            markCalled = markCalled,
            skipContact = skipContact,
            surfaceSooner = surfaceSooner,
            listRepo = listRepo,
            undoStack = undoStack,
            clock = clock,
            zoneId = ZoneId.of("UTC"),
            savedStateHandle = savedState,
        )
        return Setup(vm, contactRepo, listRepo, callEventRepo, undoStack)
    }

    /** Seeds one cold-start member ("Sarah", id=1) on list 1 so the card surfaces. */
    private fun Setup.seedSarahReady() {
        contactRepo.seed(listOf(contactFixture(id = 1L, displayName = "Sarah Connor")))
        listRepo.seed(listOf(listFixture(id = 1L, ruleTemplateId = 1L)))
        listRepo.seedMemberships(
            listOf(membershipFixture(contactId = 1L, listId = 1L, nextDueAt = null)),
        )
    }

    // ========================================================================
    // No / skip path — onSwipeLeft defers the contact.
    //
    // Cold-start membership (nextDueAt == null) → SkipContactUseCase clamps the
    // basis to `now` and pushes nextDueAt by the template's skipPenaltyHours
    // (KeepInTouch default = 24h). The fake's incrementSkipCount records the
    // write and bumps skipCount; the snapshot re-emits with the now-future
    // nextDueAt (isAheadOfToday = true).
    // ========================================================================

    @Test
    fun `onSwipeLeft defers contact and bumps skipCount with future due`() = runTest {
        val setup = fixture()
        setup.seedSarahReady()
        setup.vm.uiState.test(timeout = 2.seconds) {
            val ready = awaitItem()
            assertTrue(ready is CardViewUiState.Ready, "expected Ready, got $ready")

            setup.vm.onSwipeLeft(contactId = 1L)

            // Re-emission after the skip — same contact, now ahead of today.
            val afterSkip = awaitItem()
            assertTrue(afterSkip is CardViewUiState.Ready, "expected Ready, got $afterSkip")
            assertTrue(afterSkip.isAheadOfToday, "skip pushes nextDueAt into the future")
            cancelAndIgnoreRemainingEvents()
        }
        // The skip wrote exactly one membership row: +24h from now (cold-start
        // basis clamps to `now`).
        val skip = setup.listRepo.incrementSkipCalls.single()
        assertEquals(1L, skip.contactId)
        assertEquals(1L, skip.listId)
        assertEquals(T0.plus(Duration.ofHours(24)), skip.newNextDueAt)
    }

    @Test
    fun `onSwipeLeft emits a Deferred snackbar and stages an undo`() = runTest {
        val setup = fixture()
        setup.seedSarahReady()
        // Park a uiState collector so the WhileSubscribed feed runs; the
        // mutation reads listRepo directly so it does not depend on a fresh
        // snapshot, but the prior schedule capture does.
        setup.vm.uiState.test(timeout = 2.seconds) {
            awaitItem() // drain to Ready
            setup.vm.snackbarEvents.test(timeout = 2.seconds) {
                setup.vm.onSwipeLeft(contactId = 1L)
                val event = awaitItem()
                assertTrue(
                    event.message.startsWith("Deferred"),
                    "expected a Deferred snackbar, got ${event.message}",
                )
                assertEquals("Undo", event.actionLabel)
                cancelAndIgnoreRemainingEvents()
            }
            cancelAndIgnoreRemainingEvents()
        }
        // The inverse closure is staged on the depth-1 UndoStack.
        assertTrue(setup.undoStack.peek() != null, "swipe-left stages an undo")
    }

    // ========================================================================
    // Surface-sooner path — onSwipeRight brings the contact forward.
    //
    // A future-due membership (nextDueAt = now + 10 days) is pulled earlier by
    // SurfaceSoonerUseCase via updateNextDueAt (NOT incrementSkipCount — H6).
    // ========================================================================

    @Test
    fun `onSwipeRight moves contact up via updateNextDueAt without touching skipCount`() = runTest {
        val setup = fixture()
        setup.contactRepo.seed(listOf(contactFixture(id = 1L, displayName = "Sarah Connor")))
        setup.listRepo.seed(listOf(listFixture(id = 1L, ruleTemplateId = 1L)))
        val futureDue = T0.plus(Duration.ofDays(10))
        setup.listRepo.seedMemberships(
            listOf(membershipFixture(contactId = 1L, listId = 1L, nextDueAt = futureDue)),
        )
        setup.vm.uiState.test(timeout = 2.seconds) {
            awaitItem() // drain to Ready
            setup.vm.onSwipeRight(contactId = 1L)
            cancelAndIgnoreRemainingEvents()
        }
        // Sooner writes via updateNextDueAt, never incrementSkipCount.
        val moved = setup.listRepo.updateNextDueAtCalls.single()
        assertEquals(1L, moved.contactId)
        assertEquals(1L, moved.listId)
        // soonerDelta = max(1, 24/2) = 12h pulled back from the 10-day future.
        assertEquals(futureDue.minus(Duration.ofHours(12)), moved.newNextDueAt)
        assertTrue(
            setup.listRepo.incrementSkipCalls.isEmpty(),
            "sooner must not bump skipCount",
        )
    }

    @Test
    fun `onSwipeRight emits a Moved up snackbar`() = runTest {
        val setup = fixture()
        setup.contactRepo.seed(listOf(contactFixture(id = 1L, displayName = "Sarah Connor")))
        setup.listRepo.seed(listOf(listFixture(id = 1L, ruleTemplateId = 1L)))
        setup.listRepo.seedMemberships(
            listOf(membershipFixture(contactId = 1L, listId = 1L, nextDueAt = T0.plus(Duration.ofDays(10)))),
        )
        setup.vm.uiState.test(timeout = 2.seconds) {
            awaitItem()
            setup.vm.snackbarEvents.test(timeout = 2.seconds) {
                setup.vm.onSwipeRight(contactId = 1L)
                val event = awaitItem()
                assertTrue(
                    event.message.startsWith("Moved up"),
                    "expected a Moved up snackbar, got ${event.message}",
                )
                assertEquals("Undo", event.actionLabel)
                cancelAndIgnoreRemainingEvents()
            }
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ========================================================================
    // Undo path — onUndo pops the UndoStack and replays the inverse, restoring
    // the prior membership schedule via restoreMembershipSchedule.
    // ========================================================================

    @Test
    fun `onUndo restores prior schedule and drains the UndoStack`() = runTest {
        val setup = fixture()
        setup.seedSarahReady()
        setup.vm.uiState.test(timeout = 2.seconds) {
            awaitItem() // Ready
            setup.vm.onSwipeLeft(contactId = 1L) // stages undo, bumps to +24h
            awaitItem() // re-emission after skip
            assertTrue(setup.undoStack.peek() != null, "undo staged after skip")

            setup.vm.onUndo()
            // The card re-surfaces the contact at its restored (null/cold-start)
            // schedule.
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        // Inverse restored the captured (null nextDueAt, skipCount 0) schedule.
        val restored = setup.listRepo.observeMembershipsForContact(1L).first().single()
        assertNull(restored.nextDueAt, "undo restores the original null nextDueAt")
        assertEquals(0, restored.skipCount, "undo restores the original skipCount")
        assertNull(setup.undoStack.peek(), "take() drains the depth-1 stack")
    }

    @Test
    fun `onUndo with empty stack is a no-op`() = runTest {
        val setup = fixture()
        setup.seedSarahReady()
        setup.vm.uiState.test(timeout = 2.seconds) {
            awaitItem()
            // Nothing staged — onUndo's runMutation invokes take()?.inverse,
            // which is null, so no restore call is recorded and no crash.
            setup.vm.onUndo()
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(
            setup.listRepo.recomputeDueCountCalls.isEmpty(),
            "no inverse means no recompute side effects",
        )
        assertNull(setup.undoStack.peek())
    }

    // ========================================================================
    // Yes / mark-called path — the post-dial prompt lifecycle.
    //
    // onCall remembers the dialed contact (pendingDial); the prompt only
    // appears after onReturnedFromDial. onMarkTalked records a MANUAL,
    // zero-duration OUTGOING event and clears the prompt.
    // ========================================================================

    @Test
    fun `onCall then onReturnedFromDial surfaces the call prompt`() = runTest {
        val setup = fixture()
        setup.seedSarahReady()
        setup.vm.uiState.test(timeout = 2.seconds) {
            awaitItem() // Ready — onCall reads name off the Ready state
            assertNull(setup.vm.callPrompt.value, "no prompt before dial")

            setup.vm.onCall(contactId = 1L)
            // Dial recorded but not yet promoted to a visible prompt.
            assertNull(setup.vm.callPrompt.value, "prompt is deferred until resume")

            setup.vm.onReturnedFromDial()
            val prompt = setup.vm.callPrompt.value
            assertTrue(prompt != null, "resume promotes the pending dial")
            assertEquals(1L, prompt.contactId)
            assertEquals("Sarah", prompt.firstName) // substringBefore(' ')
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onCall is a no-op when the contactId is not the surfaced Ready contact`() = runTest {
        val setup = fixture()
        setup.seedSarahReady()
        setup.vm.uiState.test(timeout = 2.seconds) {
            awaitItem() // Ready for contact 1
            setup.vm.onCall(contactId = 999L) // not the head
            setup.vm.onReturnedFromDial()
            assertNull(setup.vm.callPrompt.value, "mismatched dial never promotes")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onMarkTalked records a manual zero-duration outgoing call and clears the prompt`() = runTest {
        val setup = fixture()
        setup.seedSarahReady()
        setup.vm.uiState.test(timeout = 2.seconds) {
            awaitItem() // Ready
            setup.vm.onCall(contactId = 1L)
            setup.vm.onReturnedFromDial()
            assertTrue(setup.vm.callPrompt.value != null, "prompt visible before mark")

            setup.vm.snackbarEvents.test(timeout = 2.seconds) {
                setup.vm.onMarkTalked()
                val event = awaitItem()
                assertEquals("Marked — talked to Sarah", event.message)
                cancelAndIgnoreRemainingEvents()
            }
            cancelAndIgnoreRemainingEvents()
        }
        assertNull(setup.vm.callPrompt.value, "mark clears the prompt")
        val mark = setup.callEventRepo.markCalledAtomicCalls.single()
        assertEquals(1L, mark.contactId)
        assertEquals(CallSource.MANUAL, mark.event.source)
        assertEquals(CallDirection.OUTGOING, mark.event.direction)
        assertEquals(0, mark.event.durationSeconds)
        assertEquals(T0, mark.event.occurredAt)
    }

    @Test
    fun `onDismissCallPrompt clears the prompt without recording a call`() = runTest {
        val setup = fixture()
        setup.seedSarahReady()
        setup.vm.uiState.test(timeout = 2.seconds) {
            awaitItem()
            setup.vm.onCall(contactId = 1L)
            setup.vm.onReturnedFromDial()
            assertTrue(setup.vm.callPrompt.value != null)

            setup.vm.onDismissCallPrompt()
            assertNull(setup.vm.callPrompt.value, "dismiss clears the prompt")
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(
            setup.callEventRepo.markCalledAtomicCalls.isEmpty(),
            "dismiss must not record a call",
        )
    }

    @Test
    fun `onMarkTalked with no active prompt is a no-op`() = runTest {
        val setup = fixture()
        setup.seedSarahReady()
        setup.vm.uiState.test(timeout = 2.seconds) {
            awaitItem()
            // No onCall/onReturnedFromDial → callPrompt is null → early return.
            setup.vm.onMarkTalked()
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(
            setup.callEventRepo.markCalledAtomicCalls.isEmpty(),
            "no prompt means no call recorded",
        )
    }

    // ========================================================================
    // Empty / nothing-due states (post-load) — exercises the EmptyNothingEligible
    // upNext hint and the EmptyNoMembers branch with a parked collector.
    // ========================================================================

    @Test
    fun `members all future-due still surface as Ready ahead of today`() = runTest {
        // Tide marker (2026-05-08): future-due candidates are NOT dropped; they
        // surface as the "ahead of today" tail. A single future-due member
        // therefore yields Ready with isAheadOfToday = true (not
        // EmptyNothingEligible).
        val setup = fixture()
        setup.contactRepo.seed(listOf(contactFixture(id = 1L, displayName = "Sarah Connor")))
        setup.listRepo.seed(listOf(listFixture(id = 1L, ruleTemplateId = 1L)))
        setup.listRepo.seedMemberships(
            listOf(membershipFixture(contactId = 1L, listId = 1L, nextDueAt = T0.plus(Duration.ofDays(5)))),
        )
        setup.vm.uiState.test(timeout = 2.seconds) {
            val state = awaitItem()
            assertTrue(state is CardViewUiState.Ready, "future-due surfaces as Ready, got $state")
            assertTrue(state.isAheadOfToday, "future nextDueAt → ahead of today")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `whyNowLine reflects last call recency on a Ready card`() = runTest {
        // A contact called 3 days ago, due now (nextDueAt in the past) → Ready
        // with the "It's been 3 days." framing line derived from the latest
        // call event.
        val setup = fixture()
        setup.contactRepo.seed(listOf(contactFixture(id = 1L, displayName = "Sarah Connor")))
        setup.listRepo.seed(listOf(listFixture(id = 1L, ruleTemplateId = 1L)))
        setup.listRepo.seedMemberships(
            listOf(membershipFixture(contactId = 1L, listId = 1L, nextDueAt = T0.minus(Duration.ofHours(1)))),
        )
        setup.callEventRepo.seed(
            listOf(callEventFixture(id = 1L, contactId = 1L, occurredAt = T0.minus(Duration.ofDays(3)))),
        )
        setup.vm.uiState.test(timeout = 2.seconds) {
            val state = awaitItem()
            assertTrue(state is CardViewUiState.Ready, "expected Ready, got $state")
            assertEquals("It's been 3 days.", state.whyNowLine)
            assertTrue(!state.isAheadOfToday, "past nextDueAt → due today, not ahead")
            cancelAndIgnoreRemainingEvents()
        }
    }
}
