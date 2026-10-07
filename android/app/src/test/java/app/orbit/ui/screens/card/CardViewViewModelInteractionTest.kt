package app.orbit.ui.screens.card

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import app.orbit.R
import app.orbit.data.entity.CallEventEntity
import app.orbit.data.entity.CallSource
import app.orbit.data.feed.CardFeed
import app.orbit.data.repository.CallEventRepository
import app.orbit.domain.CallLogResyncTrigger
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
import app.orbit.domain.usecase.LogConnectionWhen
import app.orbit.domain.usecase.AddRetroactiveNoteUseCase
import app.orbit.domain.usecase.LogConnectionUseCase
import app.orbit.domain.usecase.MarkCalledUseCase
import app.orbit.domain.usecase.SkipContactUseCase
import app.orbit.domain.usecase.SurfaceNextUseCase
import app.orbit.domain.usecase.SurfaceQueueUseCase
import app.orbit.domain.usecase.SurfaceSoonerUseCase
import app.orbit.testutil.InMemoryPrefsStore
import app.orbit.testutil.MainDispatcherRule
import app.orbit.testutil.inMemoryPrefs
import app.orbit.ui.util.UiText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Interaction-loop tests for [CardViewViewModel]: the swipe / undo / dial /
 * acknowledgement surface that [CardViewViewModelTest] (state contract) does
 * not cover: Later and Sooner with their undo (CARD-02), the failure snackbar
 * (rules.md Code 3), and "Called {name}" with what confirms and what cancels
 * it (CARD-03).
 *
 * Same construction pattern as [CardViewViewModelTest]: real use cases over the
 * [app.orbit.domain.FakeRepositories] fakes, [TestClock] pinned at
 * `2026-01-01T12:00:00Z`, UTC zone, and [MainDispatcherRule]'s
 * `UnconfinedTestDispatcher` so `viewModelScope.launch` mutations run eagerly.
 * `runTest` picks up that dispatcher's scheduler, so `advanceTimeBy` moves the
 * VM's 15-second acknowledgement window.
 *
 * Plain JUnit, no Robolectric: copy is asserted as the [UiText] the VM meant
 * to say (`UiText.res(R.string.card_called_named, "Sarah")`), not resolved.
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
        val resync: RecordingResync,
        val noteRepo: FakeNoteRepository,
        val prefsStore: InMemoryPrefsStore,
        val savedState: SavedStateHandle,
        /** A second ViewModel over the same data and preferences, from [handle]: a restored process. */
        val rebuild: (handle: SavedStateHandle) -> CardViewViewModel,
        /** The writes "Log a connection" goes through; can be made to fail. */
        val logWrites: ReschedulingCallEvents,
    )

    /** Records [CallLogResyncTrigger] calls so return-from-dial sync is assertable. */
    private class RecordingResync : CallLogResyncTrigger {
        val calls = mutableListOf<Boolean>()
        override fun enqueueImmediateSync(fullResync: Boolean) {
            calls += fullResync
        }
    }

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
        val resync = RecordingResync()
        val prefsStore = InMemoryPrefsStore()
        val logWrites = ReschedulingCallEvents(callEventRepo, listRepo)
        val logConnection = LogConnectionUseCase(
            markCalled = MarkCalledUseCase(
                contactRepo = contactRepo,
                listRepo = listRepo,
                callEventRepo = logWrites,
                ruleTemplateRepo = templateRepo,
                clock = clock,
                json = json,
            ),
            addRetroactiveNote = AddRetroactiveNoteUseCase(noteRepo),
            clock = clock,
            zoneId = ZoneId.of("UTC"),
        )
        val build: (SavedStateHandle) -> CardViewViewModel = { handle ->
            CardViewViewModel(
                cardFeed = cardFeed,
                skipContact = skipContact,
                surfaceSooner = surfaceSooner,
                logConnection = logConnection,
                listRepo = listRepo,
                callEventRepo = callEventRepo,
                appPrefs = inMemoryPrefs(prefsStore),
                undoStack = undoStack,
                callLogResync = resync,
                clock = clock,
                zoneId = ZoneId.of("UTC"),
                savedStateHandle = handle,
            )
        }
        val vm = build(savedState)
        return Setup(vm, contactRepo, listRepo, callEventRepo, undoStack, resync, noteRepo, prefsStore, savedState, build, logWrites)
    }

    /**
     * The fake call-event repository, plus what the real atomic write does
     * beside inserting the event: reschedule the person's memberships to the
     * engines' answer (CallEventRepositoryImpl's transaction). The plain fake
     * records the arguments only, so without this a logged connection would
     * never move the deck. [failWrites] makes the write throw (rules.md Code 3).
     */
    private class ReschedulingCallEvents(
        private val inner: FakeCallEventRepository,
        private val listRepo: FakeListRepository,
    ) : CallEventRepository by inner {
        var failWrites = false

        override suspend fun markCalledAtomic(
            contactId: Long,
            event: CallEventEntity,
            nextDueByListId: Map<Long, Instant?>,
        ) {
            if (failWrites) throw IllegalStateException("database write failed")
            inner.markCalledAtomic(contactId, event, nextDueByListId)
            listRepo.updateMemberships { rows ->
                rows.map { row ->
                    if (row.contactId == contactId && row.listId in nextDueByListId) {
                        row.copy(nextDueAt = nextDueByListId[row.listId], skipCount = 0)
                    } else {
                        row
                    }
                }
            }
        }
    }

    /** A copy of [handle]'s saved values: what a restored process hands a new ViewModel. */
    private fun restored(handle: SavedStateHandle): SavedStateHandle =
        SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })

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
    fun `onSwipeLeft emits an undoable message naming the person and stages an undo`() = runTest {
        val setup = fixture()
        setup.seedSarahReady()
        // Park a uiState collector so the WhileSubscribed feed runs; the
        // mutation reads listRepo directly so it does not depend on a fresh
        // snapshot, but the prior schedule capture does.
        setup.vm.uiState.test(timeout = 2.seconds) {
            awaitItem() // drain to Ready
            setup.vm.messages.test(timeout = 2.seconds) {
                setup.vm.onSwipeLeft(contactId = 1L)
                val event = awaitItem()
                assertTrue(event is CardMessage.Undoable, "expected an undoable message, got $event")
                // The copy is a string resource; assert which sentence was chosen and that
                // it names Sarah and carries a "when" ("Sarah will come up again {when}.").
                val text = event.text
                assertTrue(
                    text is app.orbit.ui.util.UiText.Res &&
                        text.id == app.orbit.R.string.card_later_named_when &&
                        text.args.first() == "Sarah" &&
                        text.args.size == 2,
                    "expected the snackbar to name Sarah and say when, got $text",
                )
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
    fun `onSwipeRight emits an undoable message naming the person`() = runTest {
        val setup = fixture()
        setup.contactRepo.seed(listOf(contactFixture(id = 1L, displayName = "Sarah Connor")))
        setup.listRepo.seed(listOf(listFixture(id = 1L, ruleTemplateId = 1L)))
        setup.listRepo.seedMemberships(
            listOf(membershipFixture(contactId = 1L, listId = 1L, nextDueAt = T0.plus(Duration.ofDays(10)))),
        )
        setup.vm.uiState.test(timeout = 2.seconds) {
            awaitItem()
            setup.vm.messages.test(timeout = 2.seconds) {
                setup.vm.onSwipeRight(contactId = 1L)
                val event = awaitItem()
                assertTrue(event is CardMessage.Undoable, "expected an undoable message, got $event")
                // "Sarah comes up {when}." from strings_card.xml (CARD-02; not
                // "is now due", the deadline framing voice.md retired).
                val text = event.text
                assertTrue(
                    text is app.orbit.ui.util.UiText.Res &&
                        text.id == app.orbit.R.string.card_sooner_named_when &&
                        text.args.first() == "Sarah" &&
                        text.args.size == 2,
                    "expected the snackbar to name Sarah and say when, got $text",
                )
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
            setup.vm.onSwipeLeft(contactId = 1L) // stages undo (token 1), bumps to +24h
            awaitItem() // re-emission after skip
            assertTrue(setup.undoStack.peek() != null, "undo staged after skip")

            setup.vm.onUndo(token = 1L)
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
            // Nothing staged: no token was ever issued, so onUndo is ignored
            // and no restore call is recorded.
            setup.vm.onUndo(token = 1L)
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(
            setup.listRepo.recomputeDueCountCalls.isEmpty(),
            "no inverse means no recompute side effects",
        )
        assertNull(setup.undoStack.peek())
    }

    // CARD-02, gate G1 (UX rubric): three quick swipes, then Undo on the first
    // snackbar, changes nothing. Undo on an older snackbar must never revert the
    // newest action, which belongs to someone else; only the newest token works.
    @Test
    fun `CARD-02 - three quick Laters then Undo on the first snackbar changes nothing`() = runTest {
        val setup = fixture()
        setup.seedSarahReady()
        setup.vm.uiState.test(timeout = 2.seconds) {
            awaitItem()
            setup.vm.messages.test(timeout = 2.seconds) {
                setup.vm.onSwipeLeft(contactId = 1L)
                assertEquals(1L, (awaitItem() as CardMessage.Undoable).token)
                setup.vm.onSwipeLeft(contactId = 1L)
                assertEquals(2L, (awaitItem() as CardMessage.Undoable).token)
                setup.vm.onSwipeLeft(contactId = 1L)
                assertEquals(3L, (awaitItem() as CardMessage.Undoable).token)
                cancelAndIgnoreRemainingEvents()
            }
            setup.vm.onUndo(token = 1L) // the first snackbar's Undo, tapped late
            cancelAndIgnoreRemainingEvents()
        }
        val row = setup.listRepo.observeMembershipsForContact(1L).first().single()
        assertEquals(3, row.skipCount, "all three Laters stand; the stale Undo replayed nothing")
        assertTrue(setup.undoStack.peek() != null, "the newest action stays undoable")
    }

    // ========================================================================
    // Failure path (rules.md Code 3): a write that fails says so on the
    // snackbar, names the person, and stages nothing to undo.
    // ========================================================================

    @Test
    fun `a Later whose write fails emits Failed naming the person and stages no undo`() = runTest {
        val setup = fixture()
        setup.seedSarahReady()
        setup.listRepo.failWrites = true
        setup.vm.uiState.test(timeout = 2.seconds) {
            awaitItem() // Ready
            setup.vm.messages.test(timeout = 2.seconds) {
                setup.vm.onSwipeLeft(contactId = 1L)
                assertEquals(
                    CardMessage.Failed(UiText.res(R.string.card_later_failed_named, "Sarah")),
                    awaitItem(),
                )
                cancelAndIgnoreRemainingEvents()
            }
            cancelAndIgnoreRemainingEvents()
        }
        val row = setup.listRepo.observeMembershipsForContact(1L).first().single()
        assertEquals(0, row.skipCount, "the failed write changed nothing")
        assertNull(setup.undoStack.peek(), "nothing to undo after a failure")
    }

    @Test
    fun `a Sooner whose write fails emits Failed naming the person`() = runTest {
        val setup = fixture()
        setup.contactRepo.seed(listOf(contactFixture(id = 1L, displayName = "Sarah Connor")))
        setup.listRepo.seed(listOf(listFixture(id = 1L, ruleTemplateId = 1L)))
        setup.listRepo.seedMemberships(
            listOf(membershipFixture(contactId = 1L, listId = 1L, nextDueAt = T0.plus(Duration.ofDays(10)))),
        )
        setup.listRepo.failWrites = true
        setup.vm.uiState.test(timeout = 2.seconds) {
            awaitItem()
            setup.vm.messages.test(timeout = 2.seconds) {
                setup.vm.onSwipeRight(contactId = 1L)
                assertEquals(
                    CardMessage.Failed(UiText.res(R.string.card_sooner_failed_named, "Sarah")),
                    awaitItem(),
                )
                cancelAndIgnoreRemainingEvents()
            }
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ========================================================================
    // Silent call advance (CORE-04) — a dial placed from the card triggers an
    // immediate incremental call-log resync on return, so a detected call
    // advances the deck on its own. There is no "did you talk?" confirmation:
    // the call log is the source of truth (off-log calls use "Log a connection"
    // on the contact screen), and tapping a confirmation would double-count.
    // ========================================================================

    @Test
    fun `onCall then onReturnedFromDial triggers one incremental resync`() = runTest {
        val setup = fixture()
        setup.seedSarahReady()
        setup.vm.uiState.test(timeout = 2.seconds) {
            awaitItem() // Ready

            setup.vm.onCall(contactId = 1L)
            // Dial recorded but nothing fires until the screen resumes.
            assertTrue(setup.resync.calls.isEmpty(), "no resync until return")

            setup.vm.onReturnedFromDial()
            // Exactly one incremental (non-full) resync so the detected call
            // advances the deck without waiting on the debounced observer or the
            // TTL-gated resume sync.
            assertEquals(
                listOf(false),
                setup.resync.calls,
                "one incremental resync on return-from-dial",
            )
            // No call is recorded by the VM itself — detection owns that.
            assertTrue(
                setup.callEventRepo.markCalledAtomicCalls.isEmpty(),
                "the VM never records the call — no double-count",
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onReturnedFromDial without a prior dial does not resync`() = runTest {
        val setup = fixture()
        setup.seedSarahReady()
        setup.vm.uiState.test(timeout = 2.seconds) {
            awaitItem()
            // Plain resume (config change, returning from Settings) — no dial
            // was pending, so nothing syncs.
            setup.vm.onReturnedFromDial()
            assertTrue(setup.resync.calls.isEmpty(), "no pending dial → no resync")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the pending dial is consumed once, a second resume does not resync`() = runTest {
        val setup = fixture()
        setup.seedSarahReady()
        setup.vm.uiState.test(timeout = 2.seconds) {
            awaitItem()
            setup.vm.onCall(contactId = 1L)
            setup.vm.onReturnedFromDial() // consumes the pending dial -> 1 resync
            setup.vm.onReturnedFromDial() // nothing pending -> no further resync
            assertEquals(
                listOf(false),
                setup.resync.calls,
                "the dial marker is consumed exactly once",
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ========================================================================
    // CARD-03: "Called {name}" once the call is real. Confirmation is evidence
    // of the call: a connected call at or after the dial for the person, or the
    // deck moving past them. Nothing after 15 seconds without either; a swipe
    // cancels the wait.
    //
    // The first test is the case the old motion-only check missed: on a
    // one-member list the called person stays at the head (re-surfaced as
    // "Coming up"), so a real, logged call was never acknowledged.
    // ========================================================================

    private val calledSarah = CardMessage.Called(UiText.res(R.string.card_called_named, "Sarah"), contactId = 1L)

    @Test
    fun `CARD-03 - a call that lands while the person stays at the head is acknowledged`() = runTest {
        val setup = fixture()
        setup.seedSarahReady()
        setup.vm.uiState.test(timeout = 2.seconds) {
            awaitItem() // Ready(Sarah)
            setup.vm.messages.test(timeout = 2.seconds) {
                setup.vm.onCall(contactId = 1L)
                setup.vm.onReturnedFromDial()
                expectNoEvents() // nothing until the call log has the call
                // The call log sync lands the call; Sarah is still the head.
                // Under a minute: a longer call opens the note page instead
                // (CARD-11, below), so this pins the snackbar's own path.
                setup.callEventRepo.seed(
                    listOf(callEventFixture(id = 1L, contactId = 1L, occurredAt = T0, durationSeconds = 45)),
                )
                assertEquals(calledSarah, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            val head = expectMostRecentItem()
            assertTrue(head is CardViewUiState.Ready && head.contactId == 1L, "Sarah stays at the head, got $head")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `CARD-03 - a dial that only logs an attempt is not acknowledged`() = runTest {
        val setup = fixture()
        setup.seedSarahReady()
        setup.vm.uiState.test(timeout = 2.seconds) {
            awaitItem()
            setup.vm.messages.test(timeout = 2.seconds) {
                setup.vm.onCall(contactId = 1L)
                setup.vm.onReturnedFromDial()
                // Voicemail: an ATTEMPT is a reach-out that did not connect.
                setup.callEventRepo.seed(
                    listOf(callEventFixture(id = 1L, contactId = 1L, occurredAt = T0, durationSeconds = 0, source = CallSource.ATTEMPT)),
                )
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `CARD-03 - the deck moving past the person is acknowledged`() = runTest {
        val setup = fixture()
        // Two cold-start members; Sarah (id 1) is the head by the id tiebreak.
        setup.contactRepo.seed(
            listOf(contactFixture(id = 1L, displayName = "Sarah Connor"), contactFixture(id = 2L, displayName = "Kai Reyes")),
        )
        setup.listRepo.seed(listOf(listFixture(id = 1L, ruleTemplateId = 1L)))
        setup.listRepo.seedMemberships(
            listOf(
                membershipFixture(contactId = 1L, listId = 1L, nextDueAt = null),
                membershipFixture(contactId = 2L, listId = 1L, nextDueAt = null),
            ),
        )
        setup.vm.uiState.test(timeout = 2.seconds) {
            val head = awaitItem()
            assertTrue(head is CardViewUiState.Ready && head.contactId == 1L, "Sarah first, got $head")
            setup.vm.messages.test(timeout = 2.seconds) {
                setup.vm.onCall(contactId = 1L)
                setup.vm.onReturnedFromDial()
                // The call log sync re-schedules Sarah (markCalledAtomic writes
                // nextDueAt), so Kai becomes the head.
                setup.listRepo.updateMemberships { rows ->
                    rows.map { if (it.contactId == 1L) it.copy(nextDueAt = T0.plus(Duration.ofDays(14))) else it }
                }
                assertEquals(calledSarah, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `CARD-03 - nothing is said when the call is not confirmed within 15 seconds`() = runTest {
        val setup = fixture()
        setup.seedSarahReady()
        setup.vm.uiState.test(timeout = 2.seconds) {
            awaitItem()
            setup.vm.messages.test(timeout = 2.seconds) {
                setup.vm.onCall(contactId = 1L)
                setup.vm.onReturnedFromDial()
                advanceTimeBy(16_000L)
                // A call logged after the window is not credited either: the
                // wait ended and nothing is pending.
                setup.callEventRepo.seed(listOf(callEventFixture(id = 1L, contactId = 1L, occurredAt = T0)))
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `CARD-03 - a swipe after the dial cancels the acknowledgement`() = runTest {
        val setup = fixture()
        setup.seedSarahReady()
        setup.vm.uiState.test(timeout = 2.seconds) {
            awaitItem()
            setup.vm.messages.test(timeout = 2.seconds) {
                setup.vm.onCall(contactId = 1L)
                setup.vm.onReturnedFromDial()
                setup.vm.onSwipeLeft(contactId = 1L)
                assertTrue(awaitItem() is CardMessage.Undoable, "the Later's own snackbar")
                // A call that lands now would have confirmed, had the wait
                // still been running.
                setup.callEventRepo.seed(listOf(callEventFixture(id = 1L, contactId = 1L, occurredAt = T0)))
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
            cancelAndIgnoreRemainingEvents()
        }
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
        // with the "You spoke 3 days ago." framing line derived from the
        // latest connected call event.
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
            // "You spoke {3 days ago}." from strings_card.xml; the argument is
            // formatAgo's UiText ("3 days ago", strings_time.xml), nested.
            assertEquals(
                app.orbit.ui.util.UiText.res(
                    app.orbit.R.string.card_why_ago,
                    app.orbit.ui.util.UiText.plural(app.orbit.R.plurals.time_ago_days, 3, 3),
                ),
                state.whyNowLine,
            )
            assertTrue(!state.isAheadOfToday, "past nextDueAt → due today, not ahead")
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ========================================================================
    // CARD-09: what the idle hints say, and when they stop. The "when" in a
    // hint must be the day the move's own snackbar then says: both come from
    // the move's use case (its preview, then its write) and one comesUp
    // bucket, worded once to stand alone ("Thursday") and once mid-sentence
    // ("on Thursday").
    // ========================================================================

    /** Hint ids and snackbar ids for the same bucket. */
    private val sameBucket = mapOf(
        R.string.card_hint_today to R.string.card_due_later_today,
        R.string.card_hint_tomorrow to R.string.card_due_tomorrow,
        R.string.card_hint_on_day to R.string.card_due_on_day,
        R.string.card_hint_in_span to R.string.card_due_in_span,
    )

    /** The hint ("Later · {when}") and the snackbar ("... {when}.") name the same day. */
    private fun assertSameWhen(hint: UiText, snackbar: UiText, case: String) {
        val hintWhen = ((hint as UiText.Res).args.single() as UiText.Res)
        val snackbarWhen = (snackbar as UiText.Res).args.last() as UiText.Res
        assertEquals(sameBucket[hintWhen.id], snackbarWhen.id, "$case: hint $hintWhen, snackbar $snackbarWhen")
        assertEquals(hintWhen.args, snackbarWhen.args, "$case: the same day or span")
    }

    /** Sarah on list 1 with [nextDueAt]; returns the hints, then the snackbar of [move]. */
    private suspend fun hintThenMove(
        nextDueAt: Instant?,
        move: CardViewViewModel.() -> Unit,
    ): Pair<CardMoveHints, UiText> {
        val setup = fixture()
        setup.contactRepo.seed(listOf(contactFixture(id = 1L, displayName = "Sarah Connor")))
        setup.listRepo.seed(listOf(listFixture(id = 1L, ruleTemplateId = 1L)))
        setup.listRepo.seedMemberships(listOf(membershipFixture(contactId = 1L, listId = 1L, nextDueAt = nextDueAt)))
        lateinit var result: Pair<CardMoveHints, UiText>
        setup.vm.uiState.test(timeout = 2.seconds) {
            awaitItem()
            val hints = assertNotNull(setup.vm.moveHints(contactId = 1L))
            setup.vm.messages.test(timeout = 2.seconds) {
                setup.vm.move()
                result = hints to (awaitItem() as CardMessage.Undoable).text
                cancelAndIgnoreRemainingEvents()
            }
            cancelAndIgnoreRemainingEvents()
        }
        return result
    }

    @Test
    fun `CARD-09 - a Later hint says the day the Later snackbar then says`() = runTest {
        // Up now, cold start, ahead by days, ahead by weeks: tomorrow, a
        // weekday and a span, the buckets a Later can land in.
        val cases = listOf(
            "up now" to T0.minus(Duration.ofHours(1)),
            "never scheduled" to null,
            "in 3 days" to T0.plus(Duration.ofDays(3)),
            "in 20 days" to T0.plus(Duration.ofDays(20)),
        )
        cases.forEach { (case, due) ->
            val (hints, snackbar) = hintThenMove(due) { onSwipeLeft(contactId = 1L) }
            assertEquals(R.string.card_hint_later_when, (hints.later as UiText.Res).id, case)
            assertSameWhen(hints.later, snackbar, case)
        }
    }

    @Test
    fun `CARD-09 - a Sooner hint says the day the Sooner snackbar then says`() = runTest {
        val cases = listOf(
            "up now" to T0.minus(Duration.ofHours(1)),
            "in 3 days" to T0.plus(Duration.ofDays(3)),
            "in 20 days" to T0.plus(Duration.ofDays(20)),
        )
        cases.forEach { (case, due) ->
            val (hints, snackbar) = hintThenMove(due) { onSwipeRight(contactId = 1L) }
            assertEquals(R.string.card_hint_sooner_when, (hints.sooner as UiText.Res).id, case)
            assertSameWhen(hints.sooner, snackbar, case)
        }
    }

    @Test
    fun `CARD-09 - the hints stop for good after five moves, in a new process too`() = runTest {
        val setup = fixture()
        setup.seedSarahReady()
        setup.vm.uiState.test(timeout = 2.seconds) {
            awaitItem()
            repeat(SWIPE_HINT_MOVES_TO_LEARN - 1) { setup.vm.onSwipeLeft(contactId = 1L) }
            assertNotNull(setup.vm.moveHints(contactId = 1L), "four moves: still learning")
            setup.vm.onSwipeRight(contactId = 1L)
            assertNull(setup.vm.moveHints(contactId = 1L), "five moves, Later or Sooner: no more hints")
            cancelAndIgnoreRemainingEvents()
        }
        // The count is in the preferences, not the ViewModel: a new process
        // over the same preferences shows none either.
        val restoredVm = setup.rebuild(SavedStateHandle(mapOf("listId" to "1")))
        assertNull(restoredVm.moveHints(contactId = 1L), "the count survives the process")
        assertEquals(SWIPE_HINT_MOVES_TO_LEARN, inMemoryPrefs(setup.prefsStore).cardMovesMade.first())
    }

    @Test
    fun `CARD-09 - a move the hints cannot see ahead says Later and Sooner alone`() = runTest {
        val setup = fixture()
        setup.seedSarahReady()
        // Someone not on this list: neither use case can say what a move
        // would do, so the hint is the bare word, never a guess.
        setup.contactRepo.seed(
            listOf(contactFixture(id = 1L, displayName = "Sarah Connor"), contactFixture(id = 9L, displayName = "Kai")),
        )
        assertEquals(
            CardMoveHints(later = UiText.res(R.string.card_later), sooner = UiText.res(R.string.card_sooner)),
            setup.vm.moveHints(contactId = 9L),
        )
    }

    @Test
    fun `CARD-09 - no hints on a deck whose list id never parsed`() = runTest {
        val setup = fixture(savedStateListId = "not-a-number")
        assertNull(setup.vm.moveHints(contactId = 1L))
    }

    // ========================================================================
    // CARD-10: Log a connection from the card. One write through the shared
    // use case (what Contact detail does), the deck moves on through the feed,
    // then "Logged. Sarah comes up again {when}." with a nameless twin.
    // ========================================================================

    /** Sarah (1) and Kai (2), both never scheduled; Sarah heads the deck by the id tiebreak. */
    private fun Setup.seedSarahAndKai() {
        contactRepo.seed(
            listOf(contactFixture(id = 1L, displayName = "Sarah Connor"), contactFixture(id = 2L, displayName = "Kai Reyes")),
        )
        listRepo.seed(listOf(listFixture(id = 1L, ruleTemplateId = 1L)))
        listRepo.seedMemberships(
            listOf(
                membershipFixture(contactId = 1L, listId = 1L, nextDueAt = null),
                membershipFixture(contactId = 2L, listId = 1L, nextDueAt = null),
            ),
        )
    }

    @Test
    fun `CARD-10 - a connection logged from the card writes once, moves the deck on and says when`() = runTest {
        val setup = fixture()
        setup.seedSarahAndKai()
        setup.vm.uiState.test(timeout = 2.seconds) {
            val head = awaitItem()
            assertTrue(head is CardViewUiState.Ready && head.contactId == 1L, "Sarah first, got $head")
            setup.vm.messages.test(timeout = 2.seconds) {
                setup.vm.onLogConnection(1L, LogConnectionWhen.Today, note = "Had dinner yesterday", isAttempt = false)
                val due = assertNotNull(
                    setup.listRepo.observeMembershipsForContact(1L).first().single().nextDueAt,
                    "the log put Sarah back into the rhythm",
                )
                val `when` = setup.vm.futureDueLabel(due, T0)
                assertEquals(
                    CardMessage.Logged(
                        text = UiText.res(R.string.card_logged_named_when, "Sarah", `when`),
                        curtainText = UiText.res(R.string.card_logged_unnamed_when, `when`),
                    ),
                    awaitItem(),
                )
                cancelAndIgnoreRemainingEvents()
            }
            // The deck moved on through the feed, as after a call.
            val next = expectMostRecentItem()
            assertTrue(next is CardViewUiState.Ready && next.contactId == 2L, "Kai is next, got $next")
            cancelAndIgnoreRemainingEvents()
        }
        // One event, through MarkCalledUseCase (the shared path): a MANUAL
        // connection now, with the note back-dated to it.
        val logged = setup.callEventRepo.markCalledAtomicCalls.single()
        assertEquals(1L, logged.contactId)
        assertEquals(CallSource.MANUAL, logged.event.source)
        assertEquals(T0, logged.event.occurredAt)
        assertEquals(0, logged.event.durationSeconds)
        val note = setup.noteRepo.insertCalls.single()
        assertEquals("Had dinner yesterday", note.body)
        assertEquals(T0, note.createdAt)
    }

    @Test
    fun `CARD-10 - an attempt logged from the card says Attempt logged and when`() = runTest {
        val setup = fixture()
        setup.seedSarahAndKai()
        setup.vm.uiState.test(timeout = 2.seconds) {
            awaitItem()
            setup.vm.messages.test(timeout = 2.seconds) {
                setup.vm.onLogConnection(1L, LogConnectionWhen.Today, note = "", isAttempt = true)
                // An attempt waits the flat AttemptCooldown (3 days) to come back.
                val `when` = setup.vm.futureDueLabel(T0.plus(Duration.ofDays(3)), T0)
                assertEquals(
                    CardMessage.Logged(
                        text = UiText.res(R.string.card_attempt_logged_named_when, "Sarah", `when`),
                        curtainText = UiText.res(R.string.card_attempt_logged_unnamed_when, `when`),
                    ),
                    awaitItem(),
                )
                cancelAndIgnoreRemainingEvents()
            }
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(CallSource.ATTEMPT, setup.callEventRepo.markCalledAtomicCalls.single().event.source)
        assertTrue(setup.noteRepo.insertCalls.isEmpty(), "no note was written")
    }

    @Test
    fun `CARD-10 - a log that could not be saved says so and moves nothing`() = runTest {
        val setup = fixture()
        setup.seedSarahAndKai()
        setup.logWrites.failWrites = true
        setup.vm.uiState.test(timeout = 2.seconds) {
            awaitItem()
            setup.vm.messages.test(timeout = 2.seconds) {
                setup.vm.onLogConnection(1L, LogConnectionWhen.Today, note = "", isAttempt = false)
                assertEquals(CardMessage.Failed(UiText.res(R.string.components_snackbar_save_failed)), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            expectNoEvents()
            val head = setup.vm.uiState.value
            assertTrue(head is CardViewUiState.Ready && head.contactId == 1L, "Sarah stays, got $head")
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(setup.callEventRepo.markCalledAtomicCalls.isEmpty())
    }

    @Test
    fun `CARD-10 - a log is not mistaken for a call placed from the card`() = runTest {
        val setup = fixture()
        setup.seedSarahAndKai()
        setup.vm.uiState.test(timeout = 2.seconds) {
            awaitItem()
            setup.vm.messages.test(timeout = 2.seconds) {
                // A dial that did not connect, then a log: the deck moves past
                // Sarah, which CARD-03 would have read as the call.
                setup.vm.onCall(contactId = 1L)
                setup.vm.onReturnedFromDial()
                setup.vm.onLogConnection(1L, LogConnectionWhen.Today, note = "", isAttempt = false)
                assertTrue(awaitItem() is CardMessage.Logged, "the log's own snackbar")
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ========================================================================
    // CARD-11: a call placed from the card that connected and lasted a minute
    // or more (WaitingCalls.MIN_SECONDS, NOTE-05's floor) opens the note page
    // for that call, once, in place of "Called {name}".
    // ========================================================================

    @Test
    fun `CARD-11 - a connected call of a minute or more opens the note page for it, once`() = runTest {
        val setup = fixture()
        setup.seedSarahReady()
        setup.vm.uiState.test(timeout = 2.seconds) {
            awaitItem()
            setup.vm.messages.test(timeout = 2.seconds) {
                setup.vm.onCall(contactId = 1L)
                setup.vm.onReturnedFromDial()
                setup.callEventRepo.seed(
                    listOf(callEventFixture(id = 7L, contactId = 1L, occurredAt = T0, durationSeconds = 60)),
                )
                assertEquals(CardMessage.OpenNote(contactId = 1L, callEventId = 7L), awaitItem())
                // Coming back from the page resumes the card: nothing is pending.
                setup.vm.onReturnedFromDial()
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
            // A rotation re-collects the messages: nothing is replayed.
            setup.vm.messages.test(timeout = 2.seconds) {
                setup.vm.onReturnedFromDial()
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf(false), setup.resync.calls, "one sync for one dial")
    }

    @Test
    fun `CARD-11 - a call under a minute keeps the Called snackbar`() = runTest {
        val setup = fixture()
        setup.seedSarahReady()
        setup.vm.uiState.test(timeout = 2.seconds) {
            awaitItem()
            setup.vm.messages.test(timeout = 2.seconds) {
                setup.vm.onCall(contactId = 1L)
                setup.vm.onReturnedFromDial()
                setup.callEventRepo.seed(
                    listOf(callEventFixture(id = 7L, contactId = 1L, occurredAt = T0, durationSeconds = 30)),
                )
                assertEquals(calledSarah, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `CARD-11 - an attempt never opens the note page`() = runTest {
        // On a list of two the attempt's short reschedule moves the deck past
        // Sarah, which CARD-03 has always counted as the call: today's
        // snackbar, kept. The page is for a call that connected.
        val setup = fixture()
        setup.seedSarahAndKai()
        setup.vm.uiState.test(timeout = 2.seconds) {
            awaitItem()
            setup.vm.messages.test(timeout = 2.seconds) {
                setup.vm.onCall(contactId = 1L)
                setup.vm.onReturnedFromDial()
                setup.callEventRepo.seed(
                    listOf(
                        callEventFixture(
                            id = 7L,
                            contactId = 1L,
                            occurredAt = T0,
                            durationSeconds = 0,
                            source = CallSource.ATTEMPT,
                        ),
                    ),
                )
                setup.listRepo.updateMemberships { rows ->
                    rows.map { if (it.contactId == 1L) it.copy(nextDueAt = T0.plus(Duration.ofDays(3))) else it }
                }
                assertEquals(calledSarah, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `CARD-11 - a call before the dial is not the one placed from the card`() = runTest {
        // An hour-long call this morning, then a dial now whose call is short:
        // the page is for the call from the card, so the snackbar it is.
        val setup = fixture()
        setup.seedSarahReady()
        setup.vm.uiState.test(timeout = 2.seconds) {
            awaitItem()
            setup.vm.messages.test(timeout = 2.seconds) {
                setup.vm.onCall(contactId = 1L)
                setup.vm.onReturnedFromDial()
                setup.callEventRepo.seed(
                    listOf(
                        callEventFixture(id = 6L, contactId = 1L, occurredAt = T0.minus(Duration.ofHours(4)), durationSeconds = 3_600),
                        callEventFixture(id = 7L, contactId = 1L, occurredAt = T0, durationSeconds = 20),
                    ),
                )
                assertEquals(calledSarah, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `CARD-11 - a process killed during the call still opens the page on return, and only once`() = runTest {
        val setup = fixture()
        setup.seedSarahReady()
        setup.vm.onCall(contactId = 1L)
        // The process dies while the dialer is in front; Android hands the new
        // process the saved state.
        val afterDeath = restored(setup.savedState)
        val revived = setup.rebuild(afterDeath)
        revived.uiState.test(timeout = 2.seconds) {
            awaitItem()
            revived.messages.test(timeout = 2.seconds) {
                revived.onReturnedFromDial()
                setup.callEventRepo.seed(
                    listOf(callEventFixture(id = 7L, contactId = 1L, occurredAt = T0, durationSeconds = 600)),
                )
                assertEquals(CardMessage.OpenNote(contactId = 1L, callEventId = 7L), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            cancelAndIgnoreRemainingEvents()
        }
        // And dies again on the note page: the dial was consumed, so the card
        // under the page does not open it a second time.
        val again = setup.rebuild(restored(afterDeath))
        again.uiState.test(timeout = 2.seconds) {
            awaitItem()
            again.messages.test(timeout = 2.seconds) {
                again.onReturnedFromDial()
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf(false), setup.resync.calls, "one sync for one dial, across both deaths")
    }
}
