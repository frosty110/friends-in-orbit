package app.orbit.ui.screens.settings.ignored

import app.cash.turbine.test
import app.orbit.data.dao.RecordingListMembershipDao
import app.orbit.data.dao.TestListDaoStub
import app.orbit.data.db.TransactionRunner
import app.orbit.domain.FakeContactRepository
import app.orbit.domain.FakeListRepository
import app.orbit.domain.clock.TestClock
import app.orbit.domain.contactFixture
import app.orbit.domain.undo.UndoStack
import app.orbit.domain.usecase.IgnoreContactUseCase
import app.orbit.domain.usecase.UnignoreContactUseCase
import app.orbit.testutil.MainDispatcherRule
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * Behavioral tests for [SettingsIgnoredViewModel].
 *
 * Asserts the four behavioral invariants:
 *   1. Empty repo → [SettingsIgnoredUiState.Empty].
 *   2. One ignored contact → [SettingsIgnoredUiState.Ready] with one row, name +
 *      "Ignored today" subtitle, sorted by ignoredAt DESC.
 *   3. **B1 — `isArchived` filter:** an ignored AND archived contact is filtered
 *      out so only the non-archived ignored row appears.
 *   4. `onUnignore(...)` flips `isIgnored = false` via the real
 *      [UnignoreContactUseCase] and emits a `SnackbarEvent("Restored {Name}", "Undo")`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsIgnoredViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val T0: Instant = Instant.parse("2026-04-26T12:00:00Z")

    /** Pass-through TransactionRunner — runs the block directly on the calling coroutine. */
    private val passThruTx = object : TransactionRunner {
        override suspend fun <R> withTransaction(block: suspend () -> R): R = block()
    }

    private fun fixture(initial: List<app.orbit.data.entity.ContactEntity> = emptyList()): Setup {
        val contactRepo = FakeContactRepository(initial)
        val listDao = TestListDaoStub()
        val clock = TestClock(T0)
        val membershipDao = RecordingListMembershipDao()
        val listRepo = FakeListRepository()
        val ignoreContactUseCase = IgnoreContactUseCase(
            txRunner = passThruTx,
            contactRepo = contactRepo,
            listMembershipDao = membershipDao,
            listRepo = listRepo,
            clock = clock,
        )
        val unignoreContactUseCase = UnignoreContactUseCase(
            txRunner = passThruTx,
            contactRepo = contactRepo,
            listDao = listDao,
            listMembershipDao = membershipDao,
            listRepo = listRepo,
            clock = clock,
        )
        val undoStack = UndoStack()
        val vm = SettingsIgnoredViewModel(
            contactRepo = contactRepo,
            ignoreContactUseCase = ignoreContactUseCase,
            unignoreContactUseCase = unignoreContactUseCase,
            undoStack = undoStack,
            clock = clock,
        )
        return Setup(vm, contactRepo, undoStack)
    }

    private data class Setup(
        val vm: SettingsIgnoredViewModel,
        val contactRepo: FakeContactRepository,
        val undoStack: UndoStack,
    )

    // ============================================================================
    // Test 1 — empty repo → Empty
    // ============================================================================

    @Test
    fun `empty repo emits Empty`() = runTest {
        val (vm, _, _) = fixture()
        val ready = vm.uiState
            .filterIsInstance<SettingsIgnoredUiState>()
            .filterIsInstance<SettingsIgnoredUiState.Empty>()
            .first()
        assertEquals(SettingsIgnoredUiState.Empty, ready)
    }

    // ============================================================================
    // Test 2 — one ignored contact → Ready with one row carrying relative label.
    // Subtitle prefix "Ignored " is fixed; the relative formatter is the single
    // source of truth (ui/util/RelativeTime.kt). At T0 == ignoredAt, expected
    // label is "Ignored today".
    // ============================================================================

    @Test
    fun `seeded ignored contact emits Ready with one row`() = runTest {
        val ignored = contactFixture(id = 42L, isIgnored = true)
            .copy(displayName = "Alex Chen", ignoredAt = T0)
        val (vm, _, _) = fixture(initial = listOf(ignored))

        val ready = vm.uiState
            .filterIsInstance<SettingsIgnoredUiState.Ready>()
            .first()
        assertEquals(1, ready.ignored.size)
        val row = ready.ignored[0]
        assertEquals(42L, row.id)
        assertEquals("Alex Chen", row.name)
        assertEquals("Ignored today", row.ignoredRelativeLabel)
    }

    // ============================================================================
    // Test 3 — B1: ignored AND archived row is filtered out.
    // Two contacts seeded: one ignored-only, one ignored-and-archived. The
    // VM's `!isArchived` filter must drop the archived one so only the visible
    // row appears.
    // ============================================================================

    @Test
    fun `B1 archived contact does not appear in Ignored view`() = runTest {
        val visible = contactFixture(id = 1L, isIgnored = true)
            .copy(displayName = "Visible One", ignoredAt = T0)
        val hidden = contactFixture(id = 2L, isIgnored = true, isArchived = true)
            .copy(displayName = "Archived Hidden", ignoredAt = T0)
        val (vm, _, _) = fixture(initial = listOf(visible, hidden))

        val ready = vm.uiState
            .filterIsInstance<SettingsIgnoredUiState.Ready>()
            .first()
        assertEquals(1, ready.ignored.size, "B1 — archived contact must be filtered")
        assertEquals(1L, ready.ignored.single().id)
    }

    // ============================================================================
    // Test 4 — onUnignore flips isIgnored=false on the contact and emits a
    // "Restored {Name} · Undo" snackbar event.
    // ============================================================================

    @Test
    fun `onUnignore flips contact and emits Restored snackbar`() = runTest {
        val ignored = contactFixture(id = 42L, isIgnored = true)
            .copy(displayName = "Alex Chen", ignoredAt = T0)
        val (vm, contactRepo, undoStack) = fixture(initial = listOf(ignored))

        // Drain to Ready so the upstream is hot before we mutate.
        vm.uiState.filterIsInstance<SettingsIgnoredUiState.Ready>().first()

        vm.snackbarEvents.test(timeout = 2.seconds) {
            vm.onUnignore(42L, "Alex Chen")
            val event = awaitItem()
            assertEquals("Restored Alex Chen", event.message)
            assertEquals("Undo", event.actionLabel)
            cancelAndIgnoreRemainingEvents()
        }

        // markIgnored was called with isIgnored=false (the un-ignore write).
        val unsetCall = contactRepo.markIgnoredCalls.last()
        assertEquals(42L, unsetCall.contactId)
        assertEquals(false, unsetCall.isIgnored)

        // UndoStack carries an inverse closure — peek (do not consume).
        val pending = undoStack.peek()
        assertTrue(pending != null, "Undo closure must be queued for snackbar Undo tap")
        assertEquals("Restored Alex Chen", pending.label)
    }
}
