package app.orbit.ui.screens.contact

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import app.orbit.data.dao.RecordingListMembershipDao
import app.orbit.data.db.TransactionRunner
import app.orbit.data.entity.ListEntity
import app.orbit.data.entity.ListMembershipEntity
import app.orbit.data.entity.CallDirection
import app.orbit.data.entity.CallEventEntity
import app.orbit.data.entity.CallSource
import app.orbit.data.entity.NoteEntity
import app.orbit.domain.FakeContactRepository
import app.orbit.domain.FakeListRepository
import app.orbit.domain.FakeNoteRepository
import app.orbit.domain.JsonProvider
import app.orbit.domain.clock.TestClock
import app.orbit.domain.contactFixture
import app.orbit.domain.model.PauseDuration
import app.orbit.domain.rule.RuleParams
import app.orbit.domain.undo.UndoStack
import kotlinx.serialization.encodeToString
import app.orbit.domain.usecase.AddNoteUseCase
import app.orbit.domain.usecase.AddRetroactiveNoteUseCase
import app.orbit.domain.usecase.ArchiveContactUseCase
import app.orbit.domain.usecase.DeleteNoteUseCase
import app.orbit.domain.usecase.EditNoteUseCase
import app.orbit.domain.usecase.IgnoreContactUseCase
import app.orbit.domain.usecase.MarkCalledUseCase
import app.orbit.domain.usecase.PauseContactUseCase
import app.orbit.testutil.MainDispatcherRule
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

    private fun fixture(
        contactIdArg: String? = "c-5",
    ): Setup {
        val contactRepo = FakeContactRepository()
        val noteRepo = FakeNoteRepository()
        val clock = TestClock(T0)
        val pauseContact = PauseContactUseCase(
            contactRepo = contactRepo,
            clock = clock,
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
            clock = clock,
        )
        // ArchiveContactUseCase wired into the VM. Real use case over the same
        // FakeContactRepository so the captured setArchivedCalls list shows the
        // archive write went through.
        val archiveContactUseCase = ArchiveContactUseCase(contactRepo = contactRepo)
        // RuleTemplateRepository injected into the VM so the combine can derive
        // `currentTemplateName` from the primary list's `ruleTemplateId`.
        // FakeRuleTemplateRepository over an empty initial list is fine for the
        // existing tests — the no-override branch falls back to "Keep in touch"
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
            json = JsonProvider.json,
        )
        val undoStack = UndoStack()
        val savedState = SavedStateHandle(mapOf("contactId" to contactIdArg))
        val vm = ContactDetailViewModel(
            contactRepo = contactRepo,
            listRepo = listRepo,
            callEventRepo = callEventRepo,
            noteRepo = noteRepo,
            pauseContact = pauseContact,
            addNoteUseCase = addNoteUseCase,
            editNoteUseCase = editNoteUseCase,
            deleteNoteUseCase = deleteNoteUseCase,
            ignoreContactUseCase = ignoreContactUseCase,
            archiveContactUseCase = archiveContactUseCase,
            ruleTemplateRepo = ruleTemplateRepo,
            addRetroactiveNoteUseCase = addRetroactiveNoteUseCase,
            markCalledUseCase = markCalledUseCase,
            undoStack = undoStack,
            clock = clock,
            savedStateHandle = savedState,
        )
        return Setup(vm, contactRepo, noteRepo, listRepo, callEventRepo)
    }

    private data class Setup(
        val vm: ContactDetailViewModel,
        val contactRepo: FakeContactRepository,
        val noteRepo: FakeNoteRepository,
        val listRepo: FakeListRepository,
        val callEventRepo: app.orbit.domain.FakeCallEventRepository,
    )

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
    // Test 2 — seed contact + note → Ready carries both
    // ============================================================================

    @Test
    fun `seeded contact and note emits Ready`() = runTest {
        val (vm, contactRepo, noteRepo) = fixture(contactIdArg = "c-5")
        contactRepo.seed(listOf(contactFixture(id = 5L, displayName = "Sarah")))
        noteRepo.seed(
            listOf(
                NoteEntity(id = 1L, contactId = 5L, createdAt = T0, body = "Met at the park"),
            ),
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
    fun `customScheduleVisible flips true when contact appears on two lists`() = runTest {
        val setup = fixture(contactIdArg = "c-5")
        setup.contactRepo.seed(listOf(contactFixture(id = 5L, displayName = "Sarah")))
        setup.listRepo.seed(
            listOf(
                ListEntity(id = 1L, name = "Inner orbit", sortOrder = 0),
                ListEntity(id = 2L, name = "Late night", sortOrder = 1),
            ),
        )
        setup.listRepo.seedMemberships(
            listOf(
                ListMembershipEntity(contactId = 5L, listId = 1L, addedAt = T0),
                ListMembershipEntity(contactId = 5L, listId = 2L, addedAt = T0),
            ),
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
            "opening the override editor must not write ruleOverrideJson",
        )
    }

    @Test
    fun `onOpenOverride flips the editor branch in uiState without a persisted override`() = runTest {
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
                "peek-open editor renders defaults; nothing decoded from storage",
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
    // currentParams flips to null + currentTemplateName flips to "Custom
    // schedule (recovering)" without crashing the VM.
    // ============================================================================

    // ============================================================================
    // Test 9 — LOG-03: onAddRetroactiveNote uses CallEventRepository.byId (O(1))
    //          and back-dates createdAt to the call's occurredAt
    // ============================================================================

    @Test
    fun `onAddRetroactiveNote back-dates createdAt to the call's occurredAt via byId lookup`() = runTest {
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
                    source = CallSource.CALL_LOG,
                ),
            ),
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
    fun `corrupted ruleOverrideJson recovers via try-catch and flips to recovering copy`() = runTest {
        val setup = fixture(contactIdArg = "c-5")
        setup.contactRepo.seed(
            listOf(
                contactFixture(
                    id = 5L,
                    displayName = "Sarah",
                    ruleOverrideJson = "this-is-not-valid-json",
                ),
            ),
        )
        setup.vm.uiState.test(timeout = 2.seconds) {
            var ready: ContactDetailUiState.Ready? = null
            while (ready == null) {
                val next = awaitItem()
                if (next is ContactDetailUiState.Ready) ready = next
            }
            assertTrue(ready.hasOverride, "hasOverride should be true when ruleOverrideJson != null")
            assertEquals(null, ready.currentParams)
            assertEquals("Custom schedule (recovering)", ready.currentTemplateName)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
