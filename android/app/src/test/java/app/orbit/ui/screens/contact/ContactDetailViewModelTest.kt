package app.orbit.ui.screens.contact

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import app.orbit.R
import app.orbit.data.NoteRow
import app.orbit.data.dao.RecordingListMembershipDao
import app.orbit.data.dao.TestListDaoStub
import app.orbit.data.db.TransactionRunner
import app.orbit.data.entity.CallDirection
import app.orbit.data.entity.CallEventEntity
import app.orbit.data.entity.CallSource
import app.orbit.data.entity.ContactEntity
import app.orbit.data.entity.ListEntity
import app.orbit.data.entity.ListMembershipEntity
import app.orbit.data.entity.NoteEntity
import app.orbit.data.repository.ContactRepository
import app.orbit.domain.FakeContactRepository
import app.orbit.domain.FakeListRepository
import app.orbit.domain.FakeNoteRepository
import app.orbit.domain.JsonProvider
import app.orbit.domain.WidgetRefreshTrigger
import app.orbit.domain.clock.TestClock
import app.orbit.domain.contactFixture
import app.orbit.domain.model.PauseDuration
import app.orbit.domain.rule.RuleParams
import app.orbit.domain.undo.UndoStack
import app.orbit.domain.usecase.AddNoteUseCase
import app.orbit.domain.usecase.AddRetroactiveNoteUseCase
import app.orbit.domain.usecase.ArchiveContactUseCase
import app.orbit.domain.usecase.DeleteNoteUseCase
import app.orbit.domain.usecase.EditNoteUseCase
import app.orbit.domain.usecase.IgnoreContactUseCase
import app.orbit.domain.usecase.LogConnectionUseCase
import app.orbit.domain.usecase.LogConnectionWhen
import app.orbit.domain.usecase.MarkCalledUseCase
import app.orbit.domain.usecase.PauseContactUseCase
import app.orbit.domain.usecase.UnignoreContactUseCase
import app.orbit.testutil.MainDispatcherRule
import app.orbit.ui.util.UiText
import app.orbit.ui.util.formatDuration
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * Behavioral tests for [ContactDetailViewModel].
 *
 * Pattern mirrors [app.orbit.ui.screens.card.CardViewViewModelTest] and
 * consumes [FakeNoteRepository]. Real [PauseContactUseCase] wired over
 * FakeContactRepository + TestClock.
 *
 * Tests assert the terminal observable state per ARCH-02; the
 * UnconfinedTestDispatcher + stateIn initialValue collapse is a known
 * side-effect of this pattern.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactDetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val T0: Instant = Instant.parse("2026-01-01T12:00:00Z")

    /**
     * [wrapContactRepo] lets a test make the VM's contact stream fail;
     * [savedArgs] adds route args beside `contactId` (`focusNote`,
     * `scrollToCallEventId`).
     */
    private fun fixture(
        contactIdArg: String? = "c-5",
        savedArgs: Map<String, String?> = emptyMap(),
        wrapContactRepo: (FakeContactRepository) -> ContactRepository = { it },
        // The zone the VM is built with; a test about day boundaries passes
        // one the host cannot share, so a ZoneId.systemDefault() in the VM
        // would show up on any machine.
        zoneId: ZoneId = ZoneOffset.UTC,
    ): Setup {
        val contactRepo = FakeContactRepository()
        val noteRepo = FakeNoteRepository()
        val clock = TestClock(T0)
        val pauseContact = PauseContactUseCase(
            contactRepo = contactRepo,
            clock = clock
        )
        // ContactDetailViewModel's ctor takes listRepo + callEventRepo.
        val listRepo = app.orbit.domain.FakeListRepository()
        val callEventRepo = app.orbit.domain.FakeCallEventRepository()
        // VM also takes the 3 note use cases + UndoStack. Real use cases over
        // the FakeNoteRepository so addNote / onDeleteNote / onEditNote tests
        // assert against captured arguments without mocks.
        val addNoteUseCase = AddNoteUseCase(noteRepo = noteRepo, clock = clock)
        val editNoteUseCase = EditNoteUseCase(noteRepo = noteRepo)
        val deleteNoteUseCase = DeleteNoteUseCase(noteRepo = noteRepo)
        // IgnoreContactUseCase wired into the VM. Real use case over passThruTx
        // + an empty RecordingListMembershipDao so the existing tests keep
        // passing without touching IgnoreContactUseCase behaviour.
        // IgnoreContactUseCase is covered by its own dedicated test class.
        val passThruTx = object : TransactionRunner {
            override suspend fun <R> withTransaction(block: suspend () -> R): R = block()
        }
        val membershipDao = RecordingListMembershipDao()
        val ignoreContactUseCase = IgnoreContactUseCase(
            txRunner = passThruTx,
            contactRepo = contactRepo,
            listMembershipDao = membershipDao,
            listRepo = listRepo,
            clock = clock
        )
        // The inverse, for CONTACT-10's Unignore: the same real use case the
        // Ignored screen uses, over an empty list DAO (nothing to restore).
        val unignoreContactUseCase = UnignoreContactUseCase(
            txRunner = passThruTx,
            contactRepo = contactRepo,
            listDao = TestListDaoStub(),
            listMembershipDao = membershipDao,
            listRepo = listRepo,
            clock = clock
        )
        // ArchiveContactUseCase wired into the VM. Real use case over the same
        // FakeContactRepository so the captured setArchivedCalls list shows the
        // archive write went through.
        val archiveContactUseCase = ArchiveContactUseCase(contactRepo = contactRepo)
        // RuleTemplateRepository injected into the VM so the combine can derive
        // `inheritedRhythm` from the primary list's `ruleTemplateId`.
        // FakeRuleTemplateRepository over an empty initial list is fine for the
        // existing tests: the no-override branch has no rhythm to describe
        // and the override-decode path doesn't read the repo at all.
        val ruleTemplateRepo = app.orbit.domain.FakeRuleTemplateRepository()
        // AddRetroactiveNoteUseCase wired into the VM so onAddRetroactiveNote()
        // back-dates createdAt to the call event's occurredAt via the byId O(1)
        // lookup. Real use case over the same FakeNoteRepository — the captured
        // insertCalls list shows the retro write went through with the
        // back-dated timestamp.
        val addRetroactiveNoteUseCase = AddRetroactiveNoteUseCase(noteRepo = noteRepo)
        // 2026-06-09 "Log a connection" — real MarkCalledUseCase over the same
        // fakes so onLogConnection writes through the engines' recompute path.
        val markCalledUseCase = MarkCalledUseCase(
            contactRepo = contactRepo,
            listRepo = listRepo,
            callEventRepo = callEventRepo,
            ruleTemplateRepo = ruleTemplateRepo,
            clock = clock,
            json = JsonProvider.json
        )
        val undoStack = UndoStack()
        val savedState = SavedStateHandle(mapOf("contactId" to contactIdArg) + savedArgs)
        // WIDGET-06: counts the refreshes the VM asks for beside its direct
        // pausedUntil writes (the use case's own trigger is not under test).
        var widgetRefreshes = 0
        val vm = ContactDetailViewModel(
            contactRepo = wrapContactRepo(contactRepo),
            listRepo = listRepo,
            callEventRepo = callEventRepo,
            noteRepo = noteRepo,
            pauseContact = pauseContact,
            addNoteUseCase = addNoteUseCase,
            editNoteUseCase = editNoteUseCase,
            deleteNoteUseCase = deleteNoteUseCase,
            ignoreContactUseCase = ignoreContactUseCase,
            unignoreContactUseCase = unignoreContactUseCase,
            archiveContactUseCase = archiveContactUseCase,
            ruleTemplateRepo = ruleTemplateRepo,
            addRetroactiveNoteUseCase = addRetroactiveNoteUseCase,
            logConnection = LogConnectionUseCase(markCalledUseCase, addRetroactiveNoteUseCase, clock, zoneId),
            undoStack = undoStack,
            clock = clock,
            zoneId = zoneId,
            savedStateHandle = savedState,
            widgetRefreshTrigger = WidgetRefreshTrigger { widgetRefreshes++ }
        )
        return Setup(vm, contactRepo, noteRepo, listRepo, callEventRepo) { widgetRefreshes }
    }

    private data class Setup(
        val vm: ContactDetailViewModel,
        val contactRepo: FakeContactRepository,
        val noteRepo: FakeNoteRepository,
        val listRepo: FakeListRepository,
        val callEventRepo: app.orbit.domain.FakeCallEventRepository,
        val widgetRefreshes: () -> Int
    )

    private fun callEvent(
        id: Long,
        occurredAt: Instant,
        durationSeconds: Int = 600,
        source: CallSource = CallSource.CALL_LOG
    ) = CallEventEntity(
        id = id,
        contactId = 5L,
        occurredAt = occurredAt,
        direction = CallDirection.OUTGOING,
        durationSeconds = durationSeconds,
        source = source
    )

    /** Skips Loading and intermediate emissions until a Ready that [accept]s. */
    private suspend fun ReceiveTurbine<ContactDetailUiState>.awaitReady(
        accept: (ContactDetailUiState.Ready) -> Boolean = { true }
    ): ContactDetailUiState.Ready {
        while (true) {
            val next = awaitItem()
            if (next is ContactDetailUiState.Ready && accept(next)) return next
        }
    }

    // ============================================================================
    // Test 1 — unparseable contactId → NotFound
    // ============================================================================

    @Test
    fun `invalid contactId emits NotFound`() = runTest {
        val (vm, _, _) = fixture(contactIdArg = "missing")
        vm.uiState.test(timeout = 2.seconds) {
            // "missing".removePrefix("c-").toLongOrNull() = null → flowOf(null) → NotFound.
            assertEquals(ContactDetailUiState.NotFound, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ============================================================================
    // CONTACT-08: a failing source is Error, not a crash; Retry recovers
    // ============================================================================

    @Test
    fun `a failing source shows Error, and Retry recovers`() = runTest {
        var failing = true
        val s = fixture(
            wrapContactRepo = { fake ->
                object : ContactRepository by fake {
                    override fun observeById(id: Long): Flow<ContactEntity?> = flow {
                        if (failing) throw IOException("simulated read failure")
                        emitAll(fake.observeById(id))
                    }
                }
            }
        )
        s.contactRepo.seed(listOf(contactFixture(id = 5L, displayName = "Sam")))
        s.vm.uiState.test(timeout = 2.seconds) {
            var item = awaitItem()
            while (item == ContactDetailUiState.Loading) item = awaitItem()
            assertEquals(ContactDetailUiState.Error, item)
            failing = false
            s.vm.onRetry()
            var next = awaitItem()
            while (next !is ContactDetailUiState.Ready) next = awaitItem()
            assertEquals("Sam", next.contact.name)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ============================================================================
    // Test 2 — seed contact + note → Ready carries both
    // ============================================================================

    @Test
    fun `seeded contact and note emits Ready`() = runTest {
        val (vm, contactRepo, noteRepo) = fixture(contactIdArg = "c-5")
        contactRepo.seed(listOf(contactFixture(id = 5L, displayName = "Sarah")))
        noteRepo.seed(
            listOf(
                NoteEntity(id = 1L, contactId = 5L, createdAt = T0, body = "Met at the park")
            )
        )
        vm.uiState.test(timeout = 2.seconds) {
            val next = awaitItem()
            assertTrue(next is ContactDetailUiState.Ready, "expected Ready, got $next")
            assertEquals("Sarah", next.contact.name)
            assertEquals("c-5", next.contact.id)
            assertEquals(1, next.notes.size)
            assertEquals("Met at the park", next.notes[0].body)
            assertEquals(5L, next.notes[0].contactId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ============================================================================
    // Test 3 — addNote() captures body in FakeNoteRepository.insertCalls (B3)
    // ============================================================================

    @Test
    fun `addNote appends to FakeNoteRepository insertCalls`() = runTest {
        val (vm, contactRepo, noteRepo) = fixture(contactIdArg = "c-5")
        contactRepo.seed(listOf(contactFixture(id = 5L, displayName = "Sarah")))

        vm.addNote("hello")
        advanceUntilIdle()

        assertEquals(1, noteRepo.insertCalls.size)
        val captured = noteRepo.insertCalls[0]
        assertEquals(5L, captured.contactId)
        assertEquals("hello", captured.body)
        assertEquals(T0, captured.createdAt)
    }

    // ============================================================================
    // Test 4 — onPauseContact(OneWeek) pushes a pausedUntil via FakeContactRepository.setPausedCalls
    // ============================================================================

    @Test
    fun `an active pause is reported, indefinite or timed`() = runTest {
        // Regression: nothing on this screen showed that a person was paused.
        val sentinel = app.orbit.domain.usecase.PauseContactUseCase.INDEFINITE_PAUSE_SENTINEL
        val setup = fixture(contactIdArg = "c-5")
        setup.contactRepo.seed(
            listOf(contactFixture(id = 5L, displayName = "Sarah", pausedUntil = sentinel))
        )
        setup.vm.uiState.test(timeout = 2.seconds) {
            var state = awaitItem()
            while (state !is ContactDetailUiState.Ready) state = awaitItem()
            // "Paused until you unpause" (strings_contact.xml).
            assertEquals(UiText.res(R.string.contact_paused_indefinitely), state.pausedLabel)
            cancelAndIgnoreRemainingEvents()
        }

        val timed = fixture(contactIdArg = "c-5")
        // T0 is 2026-01-01T12:00Z; ten days later is 11 Jan.
        timed.contactRepo.seed(
            listOf(
                contactFixture(
                    id = 5L,
                    displayName = "Sarah",
                    pausedUntil = T0.plusSeconds(10 * 24 * 3600L)
                )
            )
        )
        timed.vm.uiState.test(timeout = 2.seconds) {
            var state = awaitItem()
            while (state !is ContactDetailUiState.Ready) state = awaitItem()
            // "Paused until 11 Jan": the date is formatted in the VM, the words are a resource.
            assertEquals(UiText.res(R.string.contact_paused_until, "11 Jan"), state.pausedLabel)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `no pause, or an expired one, reports nothing`() = runTest {
        val setup = fixture(contactIdArg = "c-5")
        setup.contactRepo.seed(
            listOf(
                contactFixture(id = 5L, displayName = "Sarah", pausedUntil = T0.minusSeconds(60))
            )
        )
        setup.vm.uiState.test(timeout = 2.seconds) {
            var state = awaitItem()
            while (state !is ContactDetailUiState.Ready) state = awaitItem()
            assertNull(state.pausedLabel)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onUnpauseNow ends an indefinite pause with Undo`() = runTest {
        // Regression: there was no Unpause anywhere, so an indefinite pause
        // outlived its snackbar permanently.
        val sentinel = app.orbit.domain.usecase.PauseContactUseCase.INDEFINITE_PAUSE_SENTINEL
        val setup = fixture(contactIdArg = "c-5")
        setup.contactRepo.seed(
            listOf(contactFixture(id = 5L, displayName = "Sarah", pausedUntil = sentinel))
        )
        setup.vm.uiState.test(timeout = 2.seconds) {
            var state = awaitItem()
            while (state !is ContactDetailUiState.Ready) state = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        setup.vm.snackbarEvents.test(timeout = 2.seconds) {
            setup.vm.onUnpauseNow()
            val event = awaitItem()
            assertEquals(UiText.res(R.string.components_snackbar_unpaused, "Sarah"), event.message)
            assertEquals(UiText.res(R.string.components_action_undo), event.actionLabel)
            cancelAndIgnoreRemainingEvents()
        }
        assertNull(setup.contactRepo.getById(5L)?.pausedUntil)
    }

    @Test
    fun `onPauseContact sets pausedUntil via use case`() = runTest {
        val (vm, contactRepo, _) = fixture(contactIdArg = "c-5")
        contactRepo.seed(listOf(contactFixture(id = 5L, displayName = "Sarah")))

        vm.onPauseContact(PauseDuration.OneWeek)
        advanceUntilIdle()

        assertEquals(1, contactRepo.setPausedCalls.size)
        val captured = contactRepo.setPausedCalls[0]
        assertEquals(5L, captured.contactId)
        // OneWeek = Duration.ofDays(7); T0 + 7d
        assertEquals(T0.plusSeconds(7 * 24 * 60 * 60L), captured.pausedUntil)
    }

    // ============================================================================
    // Test 5 — CONTACT-03: customScheduleVisible derived from listsOn.size >= 2
    // ============================================================================

    @Test
    fun `an archived list is not named, counted or followed (LIST-24)`() = runTest {
        // The archived list is the first membership row, so before LIST-24 it
        // was named, made two lists (showing the custom schedule), and was the
        // list whose rhythm this person "follows".
        val setup = fixture(contactIdArg = "c-5")
        setup.contactRepo.seed(listOf(contactFixture(id = 5L, displayName = "Sarah")))
        setup.listRepo.seed(
            listOf(
                ListEntity(id = 1L, name = "Old friends", sortOrder = 0, isArchived = true),
                ListEntity(id = 2L, name = "Inner orbit", sortOrder = 1)
            )
        )
        setup.listRepo.seedMemberships(
            listOf(
                ListMembershipEntity(contactId = 5L, listId = 1L, addedAt = T0),
                ListMembershipEntity(contactId = 5L, listId = 2L, addedAt = T0)
            )
        )
        setup.vm.uiState.test(timeout = 2.seconds) {
            var ready: ContactDetailUiState.Ready? = null
            while (ready?.listsOn.isNullOrEmpty()) {
                val next = awaitItem()
                if (next is ContactDetailUiState.Ready) ready = next
            }
            assertEquals(listOf("Inner orbit"), ready!!.listsOn)
            assertTrue(!ready.customScheduleVisible)
            assertEquals("Inner orbit", ready.primaryListName)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a saved schedule shows on one list so it can be seen and reset (CONTACT-03)`() = runTest {
        // Left on one list (removed from the other, or it was archived), the
        // saved rhythm still runs; it was hidden with no way to reset it.
        val saved = app.orbit.domain.JsonProvider.json.encodeToString(
            app.orbit.domain.rule.RuleParams.serializer(),
            app.orbit.domain.rule.RuleParams.KeepInTouch().withIntervalHours(10 * 24),
        )
        val setup = fixture(contactIdArg = "c-5")
        setup.contactRepo.seed(listOf(contactFixture(id = 5L, displayName = "Sarah", ruleOverrideJson = saved)))
        setup.listRepo.seed(listOf(ListEntity(id = 1L, name = "Inner orbit", sortOrder = 0)))
        setup.listRepo.seedMemberships(listOf(ListMembershipEntity(contactId = 5L, listId = 1L, addedAt = T0)))
        setup.vm.uiState.test(timeout = 2.seconds) {
            var ready: ContactDetailUiState.Ready? = null
            while (ready?.listsOn.isNullOrEmpty()) {
                val next = awaitItem()
                if (next is ContactDetailUiState.Ready) ready = next
            }
            assertEquals(1, ready!!.listsOn.size)
            assertTrue(ready.customScheduleVisible)
            assertTrue(ready.hasOverride)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `one list and no saved schedule shows none`() {
        assertTrue(!app.orbit.ui.screens.contact.sections.showsCustomSchedule(1, hasSavedSchedule = false))
        assertTrue(app.orbit.ui.screens.contact.sections.showsCustomSchedule(1, hasSavedSchedule = true))
        assertTrue(app.orbit.ui.screens.contact.sections.showsCustomSchedule(2, hasSavedSchedule = false))
    }

    @Test
    fun `customScheduleVisible flips true when contact appears on two lists`() = runTest {
        val setup = fixture(contactIdArg = "c-5")
        setup.contactRepo.seed(listOf(contactFixture(id = 5L, displayName = "Sarah")))
        setup.listRepo.seed(
            listOf(
                ListEntity(id = 1L, name = "Inner orbit", sortOrder = 0),
                ListEntity(id = 2L, name = "Late night", sortOrder = 1)
            )
        )
        setup.listRepo.seedMemberships(
            listOf(
                ListMembershipEntity(contactId = 5L, listId = 1L, addedAt = T0),
                ListMembershipEntity(contactId = 5L, listId = 2L, addedAt = T0)
            )
        )
        setup.vm.uiState.test(timeout = 2.seconds) {
            // Skip Loading + intermediate Ready emissions until the one with
            // memberships seeded (size = 2) lands.
            var ready: ContactDetailUiState.Ready? = null
            while (ready?.customScheduleVisible != true) {
                val next = awaitItem()
                if (next is ContactDetailUiState.Ready) ready = next
            }
            assertEquals(2, ready.listsOn.size)
            assertTrue(ready.customScheduleVisible)
            assertTrue(!ready.hasOverride)
            // currentParams is null when no override exists; UI hands a
            // default RuleParams.KeepInTouch() down to the section.
            assertEquals(null, ready.currentParams)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ============================================================================
    // CONTACT-03 + LIST-30: the inherited rhythm is described by its interval,
    // never named. Until 2026-10-07 the sentence read "Follows the late night
    // rhythm from Late night." and the editor offered the three rhythm names.
    // ============================================================================

    private suspend fun inheritedRhythmFor(listOverride: RuleParams?): UiText? {
        val setup = fixture(contactIdArg = "c-5")
        setup.contactRepo.seed(listOf(contactFixture(id = 5L, displayName = "Sarah")))
        setup.listRepo.seed(
            listOf(
                ListEntity(
                    id = 1L,
                    name = "Late night",
                    sortOrder = 0,
                    ruleParamsOverrideJson = listOverride?.let {
                        JsonProvider.json.encodeToString(RuleParams.serializer(), it)
                    },
                ),
                ListEntity(id = 2L, name = "Inner orbit", sortOrder = 1),
            )
        )
        setup.listRepo.seedMemberships(
            listOf(
                ListMembershipEntity(contactId = 5L, listId = 1L, addedAt = T0),
                ListMembershipEntity(contactId = 5L, listId = 2L, addedAt = T0),
            )
        )
        var rhythm: UiText? = null
        setup.vm.uiState.test(timeout = 2.seconds) {
            var ready: ContactDetailUiState.Ready? = null
            while (ready?.customScheduleVisible != true) {
                val next = awaitItem()
                if (next is ContactDetailUiState.Ready) ready = next
            }
            rhythm = ready.inheritedRhythm
            cancelAndIgnoreRemainingEvents()
        }
        return rhythm
    }

    @Test
    fun `a late night list is described by its interval, not its name`() = runTest {
        assertEquals(
            UiText.plural(R.plurals.contact_rhythm_every_days, 3, 3),
            inheritedRhythmFor(RuleParams.LateNight()),
        )
    }

    @Test
    fun `a one day rhythm reads every day, and a keep in touch list its own interval`() = runTest {
        assertEquals(UiText.res(R.string.contact_rhythm_every_day), inheritedRhythmFor(RuleParams.Energize()))
        assertEquals(
            UiText.plural(R.plurals.contact_rhythm_every_days, 14, 14),
            inheritedRhythmFor(RuleParams.KeepInTouch().withIntervalHours(14 * 24)),
        )
    }

    @Test
    fun `a list with no readable rhythm has nothing to describe`() = runTest {
        assertNull(inheritedRhythmFor(null))
    }

    // ============================================================================
    // Opening the override editor is READ-ONLY (peek != mutation).
    // Previously onOpenOverride persisted a default RuleParams the moment the
    // sheet opened — no user change, no undo.
    // ============================================================================

    @Test
    fun `onOpenOverride persists nothing`() = runTest {
        val setup = fixture(contactIdArg = "c-5")
        setup.contactRepo.seed(listOf(contactFixture(id = 5L, displayName = "Sarah")))

        setup.vm.onOpenOverride()
        advanceUntilIdle()

        assertEquals(
            0,
            setup.contactRepo.setRuleOverrideCalls.size,
            "opening the override editor must not write ruleOverrideJson"
        )
    }

    @Test
    fun `onOpenOverride flips the editor branch in uiState without a persisted override`() =
        runTest {
            val setup = fixture(contactIdArg = "c-5")
            setup.contactRepo.seed(listOf(contactFixture(id = 5L, displayName = "Sarah")))

            setup.vm.onOpenOverride()
            setup.vm.uiState.test(timeout = 2.seconds) {
                var ready: ContactDetailUiState.Ready? = null
                while (ready?.hasOverride != true) {
                    val next = awaitItem()
                    if (next is ContactDetailUiState.Ready) ready = next
                }
                assertEquals(
                    null,
                    ready.currentParams,
                    "peek-open editor renders defaults; nothing decoded from storage"
                )
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(0, setup.contactRepo.setRuleOverrideCalls.size)
        }

    @Test
    fun `onClearOverride closes a peeked-open editor`() = runTest {
        val setup = fixture(contactIdArg = "c-5")
        setup.contactRepo.seed(listOf(contactFixture(id = 5L, displayName = "Sarah")))

        setup.vm.onOpenOverride()
        setup.vm.onClearOverride()
        advanceUntilIdle()

        setup.vm.uiState.test(timeout = 2.seconds) {
            var ready: ContactDetailUiState.Ready? = null
            while (ready == null) {
                val next = awaitItem()
                if (next is ContactDetailUiState.Ready) ready = next
            }
            assertEquals(false, ready.hasOverride, "reset returns to the inherit branch")
            cancelAndIgnoreRemainingEvents()
        }
        // The persisted column was cleared (null write) — the only mutation.
        assertEquals(1, setup.contactRepo.setRuleOverrideCalls.size)
        assertEquals(null, setup.contactRepo.setRuleOverrideCalls[0].json)
    }

    // ============================================================================
    // Test 6 — CONTACT-03: onSaveOverride writes via setRuleOverrideJson (B2)
    // ============================================================================

    @Test
    fun `onSaveOverride writes encoded RuleParams via setRuleOverrideJson`() = runTest {
        val setup = fixture(contactIdArg = "c-5")
        setup.contactRepo.seed(listOf(contactFixture(id = 5L, displayName = "Sarah")))

        val newParams: RuleParams = RuleParams.KeepInTouch(cooldownMinHours = 36)
        setup.vm.onSaveOverride(newParams)
        advanceUntilIdle()

        assertEquals(1, setup.contactRepo.setRuleOverrideCalls.size)
        val captured = setup.contactRepo.setRuleOverrideCalls[0]
        assertEquals(5L, captured.contactId)
        assertTrue(captured.json != null, "json should not be null on save path")
        // Round-trip encode/decode confirms the JSON shape OverrideResolver expects.
        val roundTripped = JsonProvider.json.decodeFromString<RuleParams>(captured.json!!)
        assertEquals(newParams, roundTripped)
    }

    // ============================================================================
    // Test 7 — CONTACT-03: onClearOverride wipes the column (passes null)
    // ============================================================================

    @Test
    fun `onClearOverride writes null via setRuleOverrideJson`() = runTest {
        val setup = fixture(contactIdArg = "c-5")
        setup.contactRepo.seed(listOf(contactFixture(id = 5L, displayName = "Sarah")))

        setup.vm.onClearOverride()
        advanceUntilIdle()

        assertEquals(1, setup.contactRepo.setRuleOverrideCalls.size)
        val captured = setup.contactRepo.setRuleOverrideCalls[0]
        assertEquals(5L, captured.contactId)
        assertEquals(null, captured.json)
    }

    // ============================================================================
    // Test 8 — CONTACT-03: corrupted JSON recovers gracefully —
    // currentParams flips to null + inheritedRhythm flips to "Custom
    // schedule (recovering)" without crashing the VM.
    // ============================================================================

    // ============================================================================
    // Test 9 — LOG-03: onAddRetroactiveNote uses CallEventRepository.byId (O(1))
    //          and back-dates createdAt to the call's occurredAt
    // ============================================================================

    @Test
    fun `Usually reads the call pattern, the same as the card`() = runTest {
        // Regression: Contact detail never applied the call-pattern overlay, so
        // "Usually" was always blank while Card View showed "Mornings".
        val setup = fixture(contactIdArg = "c-5")
        setup.contactRepo.seed(listOf(contactFixture(id = 5L, displayName = "Sarah")))
        setup.callEventRepo.seed(
            (1L..3L).map { day ->
                CallEventEntity(
                    id = day,
                    contactId = 5L,
                    // 08:15 UTC on three different days: a morning pattern.
                    occurredAt = Instant.parse("2025-12-2${day}T08:15:00Z"),
                    direction = CallDirection.OUTGOING,
                    durationSeconds = 600,
                    source = CallSource.CALL_LOG
                )
            }
        )

        setup.vm.uiState.test(timeout = 2.seconds) {
            var state = awaitItem()
            while (state !is ContactDetailUiState.Ready) state = awaitItem()
            assertEquals(UiText.res(R.string.time_daypart_mornings), state.contact.bestWindowLabel)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onAddRetroactiveNote back-dates createdAt to the call's occurredAt via byId lookup`() =
        runTest {
            val setup = fixture(contactIdArg = "c-5")
            setup.contactRepo.seed(listOf(contactFixture(id = 5L, displayName = "Sarah")))
            // Seed a call event 14 minutes ago — the use case must use this
            // event's occurredAt as the note's createdAt, NOT the clock's now.
            val occurred = T0.minusSeconds(14 * 60L)
            setup.callEventRepo.seed(
                listOf(
                    CallEventEntity(
                        id = 42L,
                        contactId = 5L,
                        occurredAt = occurred,
                        direction = CallDirection.OUTGOING,
                        durationSeconds = 180,
                        source = CallSource.CALL_LOG
                    )
                )
            )

            setup.vm.onAddRetroactiveNote(callEventId = 42L, body = "Was a great chat")
            advanceUntilIdle()

            assertEquals(1, setup.noteRepo.insertCalls.size)
            val captured = setup.noteRepo.insertCalls[0]
            assertEquals(5L, captured.contactId)
            assertEquals("Was a great chat", captured.body)
            // The load-bearing assertion: the back-dated timestamp matches the
            // call's occurredAt — proves byId returned the right row AND the
            // use case wired createdAt = occurredAt (not clock.now()).
            assertEquals(occurred, captured.createdAt)
        }

    @Test
    fun `corrupted ruleOverrideJson recovers via try-catch and flips to recovering copy`() =
        runTest {
            val setup = fixture(contactIdArg = "c-5")
            setup.contactRepo.seed(
                listOf(
                    contactFixture(
                        id = 5L,
                        displayName = "Sarah",
                        ruleOverrideJson = "this-is-not-valid-json"
                    )
                )
            )
            setup.vm.uiState.test(timeout = 2.seconds) {
                var ready: ContactDetailUiState.Ready? = null
                while (ready == null) {
                    val next = awaitItem()
                    if (next is ContactDetailUiState.Ready) ready = next
                }
                assertTrue(
                    ready.hasOverride,
                    "hasOverride should be true when ruleOverrideJson != null"
                )
                assertEquals(null, ready.currentParams)
                // Nothing to name: with an override stored, the section shows
                // the editor (on defaults), never the "Follows the ... rhythm"
                // sentence. It used to hold "Custom schedule (recovering)",
                // which no branch displayed.
                assertNull(ready.inheritedRhythm)
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ============================================================================
    // 2026-10-06: the README behaviours that had no VM test (contact-detail-13):
    // a regression in any of them passed CI.
    // ============================================================================

    @Test
    fun `onIgnore ignores with an Undo snackbar, and Undo restores`() = runTest {
        val setup = fixture()
        setup.contactRepo.seed(listOf(contactFixture(id = 5L, displayName = "Sarah")))

        setup.vm.snackbarEvents.test(timeout = 2.seconds) {
            setup.vm.onIgnore("Sarah")
            val event = awaitItem()
            assertEquals(UiText.res(R.string.components_snackbar_ignored, "Sarah"), event.message)
            assertEquals(UiText.res(R.string.components_action_undo), event.actionLabel)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(true, setup.contactRepo.getById(5L)?.isIgnored)

        setup.vm.onUndo()
        advanceUntilIdle()
        assertEquals(false, setup.contactRepo.getById(5L)?.isIgnored)
    }

    @Test
    fun `onUnignore unignores with an Undo snackbar, and Undo re-ignores`() = runTest {
        // CONTACT-10: the inverse offered on the page itself; before, the way
        // back from an ignored person's page was Settings > Ignored.
        val setup = fixture()
        setup.contactRepo.seed(
            listOf(contactFixture(id = 5L, displayName = "Sarah", isIgnored = true))
        )

        setup.vm.snackbarEvents.test(timeout = 2.seconds) {
            setup.vm.onUnignore("Sarah")
            val event = awaitItem()
            assertEquals(UiText.res(R.string.components_snackbar_unignored, "Sarah"), event.message)
            assertEquals(UiText.res(R.string.components_action_undo), event.actionLabel)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(false, setup.contactRepo.getById(5L)?.isIgnored)

        setup.vm.onUndo()
        advanceUntilIdle()
        assertEquals(true, setup.contactRepo.getById(5L)?.isIgnored)
    }

    @Test
    fun `onArchive archives with an Undo snackbar, and Undo restores`() = runTest {
        val setup = fixture()
        setup.contactRepo.seed(
            listOf(contactFixture(id = 5L, displayName = "Sarah", isOrphaned = true))
        )

        setup.vm.snackbarEvents.test(timeout = 2.seconds) {
            setup.vm.onArchive("Sarah")
            val event = awaitItem()
            assertEquals(UiText.res(R.string.contact_snackbar_archived, "Sarah"), event.message)
            assertEquals(UiText.res(R.string.components_action_undo), event.actionLabel)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(true, setup.contactRepo.getById(5L)?.isArchived)

        setup.vm.onUndo()
        advanceUntilIdle()
        assertEquals(false, setup.contactRepo.getById(5L)?.isArchived)
    }

    @Test
    fun `onLogConnection today records a MANUAL connection now and says Logged`() = runTest {
        val setup = fixture()
        setup.contactRepo.seed(listOf(contactFixture(id = 5L, displayName = "Sarah")))

        setup.vm.snackbarEvents.test(timeout = 2.seconds) {
            setup.vm.onLogConnection(LogConnectionWhen.Today, note = "", isAttempt = false)
            assertEquals(UiText.res(R.string.contact_snackbar_logged), awaitItem().message)
            cancelAndIgnoreRemainingEvents()
        }
        val written = setup.callEventRepo.observeForContact(5L, limit = 50).first().single()
        assertEquals(CallSource.MANUAL, written.source)
        assertEquals(0, written.durationSeconds)
        assertEquals(T0, written.occurredAt)
        assertTrue(setup.noteRepo.insertCalls.isEmpty(), "a blank note is not written")
    }

    @Test
    fun `onLogConnection as an attempt records ATTEMPT yesterday, back-dates the note and says Attempt logged`() =
        runTest {
            val setup = fixture()
            setup.contactRepo.seed(listOf(contactFixture(id = 5L, displayName = "Sarah")))

            setup.vm.snackbarEvents.test(timeout = 2.seconds) {
                setup.vm.onLogConnection(
                    LogConnectionWhen.Yesterday,
                    note = "  voicemail  ",
                    isAttempt = true
                )
                assertEquals(
                    UiText.res(R.string.contact_snackbar_attempt_logged),
                    awaitItem().message
                )
                cancelAndIgnoreRemainingEvents()
            }
            val yesterday = T0.minus(Duration.ofDays(1))
            val written = setup.callEventRepo.observeForContact(5L, limit = 50).first().single()
            assertEquals(CallSource.ATTEMPT, written.source)
            assertEquals(yesterday, written.occurredAt)
            val note = setup.noteRepo.insertCalls.single()
            assertEquals("voicemail", note.body)
            assertEquals(yesterday, note.createdAt, "the note is dated to the attempt, not to now")
        }

    @Test
    fun `onLogConnection on a picked date lands at local noon of that day, and never in the future`() =
        runTest {
            // A fixed offset no host runs on, so the expectation below can only
            // match if the VM reads the zone it was given.
            val zone = ZoneOffset.ofHoursMinutes(5, 30)
            val setup = fixture(zoneId = zone)
            setup.contactRepo.seed(listOf(contactFixture(id = 5L, displayName = "Sarah")))

            // The date picker hands back UTC midnight of the chosen day.
            val day = LocalDate.of(2025, 12, 20)
            setup.vm.onLogConnection(
                LogConnectionWhen.OnDate(
                    day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                ),
                note = "",
                isAttempt = false
            )
            // A day after today is clamped to now: no event may sit in the future.
            val future = T0.plus(Duration.ofDays(10)).atZone(ZoneOffset.UTC).toLocalDate()
            setup.vm.onLogConnection(
                LogConnectionWhen.OnDate(
                    future.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                ),
                note = "",
                isAttempt = false
            )
            advanceUntilIdle()

            val written = setup.callEventRepo.observeForContact(5L, limit = 50).first()
            assertEquals(2, written.size)
            val localNoon = day.atTime(12, 0).atZone(zone).toInstant()
            assertTrue(
                written.any { it.occurredAt == localNoon },
                "the picked day lands at noon in the VM's zone"
            )
            assertTrue(written.any { it.occurredAt == T0 }, "a future day is clamped to now")
            assertTrue(written.none { it.occurredAt.isAfter(T0) })
        }

    @Test
    fun `onDeleteNote deletes with Undo, and Undo re-inserts the same note`() = runTest {
        val setup = fixture()
        setup.contactRepo.seed(listOf(contactFixture(id = 5L, displayName = "Sarah")))
        setup.noteRepo.seed(
            listOf(NoteEntity(id = 1L, contactId = 5L, createdAt = T0, body = "Met at the park"))
        )
        val row =
            NoteRow(
                id = 1L,
                contactId = 5L,
                body = "Met at the park",
                createdAtMs = T0.toEpochMilli()
            )

        setup.vm.snackbarEvents.test(timeout = 2.seconds) {
            setup.vm.onDeleteNote(row)
            val event = awaitItem()
            assertEquals(UiText.res(R.string.contact_snackbar_note_deleted), event.message)
            assertEquals(UiText.res(R.string.components_action_undo), event.actionLabel)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1L, setup.noteRepo.deleteCalls.single().id)

        setup.vm.onUndo()
        advanceUntilIdle()
        val restored = setup.noteRepo.insertCalls.single()
        assertEquals(1L, restored.id)
        assertEquals(T0, restored.createdAt, "the note comes back in its place, not as a new one")
    }

    @Test
    fun `onEditNote writes the trimmed body`() = runTest {
        val setup = fixture()
        setup.contactRepo.seed(listOf(contactFixture(id = 5L, displayName = "Sarah")))
        setup.noteRepo.seed(
            listOf(NoteEntity(id = 1L, contactId = 5L, createdAt = T0, body = "Met at the park"))
        )
        val row =
            NoteRow(
                id = 1L,
                contactId = 5L,
                body = "Met at the park",
                createdAtMs = T0.toEpochMilli()
            )

        setup.vm.onEditNote(row, "  Met at the lake  ")
        advanceUntilIdle()

        val updated = setup.noteRepo.updateCalls.single()
        assertEquals(1L, updated.id)
        assertEquals("Met at the lake", updated.body)
        assertEquals(T0, updated.createdAt)
    }

    @Test
    fun `longest gap needs two events, then spans the widest gap between neighbours`() = runTest {
        val one = fixture()
        one.contactRepo.seed(listOf(contactFixture(id = 5L, displayName = "Sarah")))
        one.callEventRepo.seed(listOf(callEvent(1L, T0.minus(Duration.ofDays(3)))))
        one.vm.uiState.test(timeout = 2.seconds) {
            assertNull(awaitReady().longestGapLabel)
            cancelAndIgnoreRemainingEvents()
        }

        val many = fixture()
        many.contactRepo.seed(listOf(contactFixture(id = 5L, displayName = "Sarah")))
        many.callEventRepo.seed(
            listOf(
                callEvent(1L, T0.minus(Duration.ofDays(30))),
                callEvent(2L, T0.minus(Duration.ofDays(9))), // 21 days after the first
                callEvent(3L, T0.minus(Duration.ofDays(6))) // 3 days after the second
            )
        )
        many.vm.uiState.test(timeout = 2.seconds) {
            // "21 days" (strings_time.xml), the widest gap, not the total span.
            assertEquals(
                UiText.plural(R.plurals.time_span_days, 21, 21),
                awaitReady().longestGapLabel
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `one measured call and two logged connections count three calls but one measured`() =
        runTest {
            // CONTACT-02: the screen gates "Average length" on measuredCalls, so
            // this person reads "Not enough calls yet" instead of the one call's length.
            val setup = fixture()
            setup.contactRepo.seed(listOf(contactFixture(id = 5L, displayName = "Sarah")))
            setup.callEventRepo.seed(
                listOf(
                    callEvent(1L, T0.minus(Duration.ofDays(3)), durationSeconds = 600),
                    callEvent(
                        2L,
                        T0.minus(Duration.ofDays(2)),
                        durationSeconds = 0,
                        source = CallSource.MANUAL
                    ),
                    callEvent(
                        3L,
                        T0.minus(Duration.ofDays(1)),
                        durationSeconds = 0,
                        source = CallSource.MANUAL
                    )
                )
            )
            setup.vm.uiState.test(timeout = 2.seconds) {
                val contact = awaitReady { it.contact.totalCalls == 3 }.contact
                assertEquals(3, contact.totalCalls)
                assertEquals(1, contact.measuredCalls)
                assertEquals(formatDuration(600), contact.avgLengthLabel)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `focusNote=1 reaches a screen that starts collecting after the VM exists, once`() =
        runTest {
            // NOTE-02. The screen's LaunchedEffect collects after hiltViewModel()
            // has built the VM, so the event must survive until then.
            val setup = fixture(savedArgs = mapOf("focusNote" to "1"))
            setup.vm.focusNoteEvent.test(timeout = 2.seconds) {
                awaitItem()
                awaitComplete()
            }
            // Collecting again (a rotation re-runs the LaunchedEffect on the same
            // VM) must not re-focus the field.
            setup.vm.focusNoteEvent.test(timeout = 2.seconds) { awaitComplete() }
            // And without the arg there is nothing to deliver.
            val plain = fixture()
            plain.vm.focusNoteEvent.test(timeout = 2.seconds) { awaitComplete() }
        }

    @Test
    fun `scrollToCallEventId reaches Ready with the row's id and the parallel call ids`() =
        runTest {
            val setup = fixture(savedArgs = mapOf("scrollToCallEventId" to "42"))
            setup.contactRepo.seed(listOf(contactFixture(id = 5L, displayName = "Sarah")))
            setup.callEventRepo.seed(
                listOf(
                    callEvent(43L, T0.minus(Duration.ofDays(2))),
                    callEvent(42L, T0.minus(Duration.ofDays(1)))
                )
            )
            setup.vm.uiState.test(timeout = 2.seconds) {
                val ready = awaitReady { it.recentCalls.size == 2 }
                assertEquals(42L, ready.scrollToCallEventId)
                assertEquals(42L, ready.retroNoteAffordanceFor)
                // Newest first, the same order as recentCalls.
                assertEquals(listOf(42L, 43L), ready.recentCallEventIds)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `an orphaned person emits Orphaned with their notes and call ids`() = runTest {
        // CONTACT-06: notes are Orbit's data and outlive the phone contact.
        val setup = fixture()
        setup.contactRepo.seed(
            listOf(contactFixture(id = 5L, displayName = "Sarah", isOrphaned = true))
        )
        setup.noteRepo.seed(
            listOf(NoteEntity(id = 1L, contactId = 5L, createdAt = T0, body = "Met at the park"))
        )
        setup.callEventRepo.seed(listOf(callEvent(42L, T0.minus(Duration.ofDays(1)))))
        setup.vm.uiState.test(timeout = 2.seconds) {
            var state = awaitItem()
            while (state !is ContactDetailUiState.Orphaned || state.notes.isEmpty() || state.recentCalls.isEmpty()) {
                state = awaitItem()
            }
            assertEquals("Met at the park", state.notes.single().body)
            assertEquals(listOf(42L), state.recentCallEventIds)
            assertEquals(1, state.recentCalls.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a lapsed timed pause shows the unpause banner, an indefinite pause never does`() =
        runTest {
            // CONTACT-05.
            val lapsed = fixture()
            lapsed.contactRepo.seed(
                listOf(
                    contactFixture(
                        id = 5L,
                        displayName = "Sarah",
                        pausedUntil = T0.minusSeconds(60)
                    )
                )
            )
            lapsed.vm.uiState.test(timeout = 2.seconds) {
                val ready = awaitReady()
                assertTrue(ready.unpausePromptVisible)
                assertNull(ready.pausedLabel, "a lapsed pause is no longer in force")
                cancelAndIgnoreRemainingEvents()
            }

            val indefinite = fixture()
            indefinite.contactRepo.seed(
                listOf(
                    contactFixture(
                        id = 5L,
                        displayName = "Sarah",
                        pausedUntil = PauseContactUseCase.INDEFINITE_PAUSE_SENTINEL
                    )
                )
            )
            indefinite.vm.uiState.test(timeout = 2.seconds) {
                assertEquals(false, awaitReady().unpausePromptVisible)
                cancelAndIgnoreRemainingEvents()
            }

            val inForce = fixture()
            inForce.contactRepo.seed(
                listOf(
                    contactFixture(
                        id = 5L,
                        displayName = "Sarah",
                        pausedUntil = T0.plus(Duration.ofDays(10))
                    )
                )
            )
            inForce.vm.uiState.test(timeout = 2.seconds) {
                assertEquals(false, awaitReady().unpausePromptVisible)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `onRelink emits the Re-link nav event for this person`() = runTest {
        val setup = fixture()
        setup.contactRepo.seed(
            listOf(contactFixture(id = 5L, displayName = "Sarah", isOrphaned = true))
        )
        setup.vm.navEvents.test(timeout = 2.seconds) {
            setup.vm.onRelink()
            assertEquals(ContactDetailViewModel.NavEvent.RelinkPicker(5L), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a failed write says so instead of failing silently`() = runTest {
        // rules.md Code 3: runMutation turns the exception into a snackbar.
        val setup = fixture(
            wrapContactRepo = { fake ->
                object : ContactRepository by fake {
                    override suspend fun setPausedUntil(id: Long, until: Instant?) {
                        throw IOException("simulated write failure")
                    }
                }
            }
        )
        setup.contactRepo.seed(listOf(contactFixture(id = 5L, displayName = "Sarah")))
        setup.vm.snackbarEvents.test(timeout = 2.seconds) {
            setup.vm.onUnpauseContact()
            assertEquals(UiText.res(R.string.contact_snackbar_unpause_failed), awaitItem().message)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a new call event re-emits Ready with the stats updated`() = runTest {
        // README acceptance: stats update live when call detection records a call.
        val setup = fixture()
        setup.contactRepo.seed(listOf(contactFixture(id = 5L, displayName = "Sarah")))
        setup.vm.uiState.test(timeout = 2.seconds) {
            val before = awaitReady()
            assertEquals(0, before.contact.totalCalls)
            assertNull(before.contact.lastCalledLabel)

            setup.callEventRepo.seed(listOf(callEvent(42L, T0.minus(Duration.ofDays(1)))))

            val after = awaitReady { it.contact.totalCalls == 1 }
            assertEquals(1, after.recentCalls.size)
            assertEquals(listOf(42L), after.recentCallEventIds)
            assertNotNull(after.contact.lastCalledLabel)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `ignored, archived and the phone contact id reach Ready`() = runTest {
        // CONTACT-10 and "Open in Contacts" read these.
        val setup = fixture()
        setup.contactRepo.seed(
            listOf(
                contactFixture(
                    id = 5L,
                    displayName = "Sarah",
                    isIgnored = true,
                    isArchived = true,
                    phoneContactId = 77L
                )
            )
        )
        setup.vm.uiState.test(timeout = 2.seconds) {
            val ready = awaitReady()
            assertTrue(ready.isIgnored)
            assertTrue(ready.isArchived)
            assertEquals(77L, ready.phoneContactId)
            cancelAndIgnoreRemainingEvents()
        }

        val callLogOnly = fixture()
        callLogOnly.contactRepo.seed(listOf(contactFixture(id = 5L, displayName = "Sarah")))
        callLogOnly.vm.uiState.test(timeout = 2.seconds) {
            val ready = awaitReady()
            assertEquals(false, ready.isIgnored)
            assertEquals(false, ready.isArchived)
            assertNull(ready.phoneContactId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the call log permission reaches Ready and clears again`() = runTest {
        // ARCH-04: the screen reports on every resume, the VM carries the flag.
        val setup = fixture()
        setup.contactRepo.seed(listOf(contactFixture(id = 5L, displayName = "Sarah")))
        setup.vm.onCallLogPermissionChanged(denied = true)
        setup.vm.uiState.test(timeout = 2.seconds) {
            assertTrue(awaitReady().callLogDenied)
            setup.vm.onCallLogPermissionChanged(denied = false)
            awaitReady { !it.callLogDenied }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `unpausing refreshes the widget, and so does undoing a pause`() = runTest {
        // WIDGET-06: these writes bypass PauseContactUseCase, where the trigger
        // otherwise fires, so the widget kept offering a paused person (or
        // hiding an unpaused one) until the hourly sweep.
        val setup = fixture()
        setup.contactRepo.seed(
            listOf(
                contactFixture(
                    id = 5L,
                    displayName = "Sarah",
                    pausedUntil = PauseContactUseCase.INDEFINITE_PAUSE_SENTINEL
                )
            )
        )
        setup.vm.onUnpauseNow()
        advanceUntilIdle()
        assertEquals(1, setup.widgetRefreshes())

        setup.vm.onUndo()
        advanceUntilIdle()
        assertEquals(2, setup.widgetRefreshes(), "undoing the unpause pauses again")

        setup.vm.onUnpauseContact()
        advanceUntilIdle()
        assertEquals(3, setup.widgetRefreshes(), "the banner's dismiss unpauses")

        setup.vm.onPauseContact(PauseDuration.OneWeek)
        advanceUntilIdle()
        setup.vm.onUndo()
        advanceUntilIdle()
        assertEquals(4, setup.widgetRefreshes(), "undoing a pause unpauses")
    }
}
