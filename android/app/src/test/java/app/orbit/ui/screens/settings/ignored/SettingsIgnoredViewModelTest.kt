package app.orbit.ui.screens.settings.ignored

import app.cash.turbine.test
import app.orbit.R
import app.orbit.data.dao.RecordingListMembershipDao
import app.orbit.data.dao.TestListDaoStub
import app.orbit.data.db.TransactionRunner
import app.orbit.data.entity.ContactEntity
import app.orbit.data.repository.ContactRepository
import app.orbit.domain.FakeContactRepository
import app.orbit.domain.FakeListRepository
import app.orbit.domain.clock.TestClock
import app.orbit.domain.contactFixture
import app.orbit.domain.undo.UndoStack
import app.orbit.domain.usecase.IgnoreContactUseCase
import app.orbit.domain.usecase.UnignoreContactUseCase
import app.orbit.testutil.MainDispatcherRule
import app.orbit.ui.util.UiText
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * Behavioral tests for [SettingsIgnoredViewModel].
 *
 * Asserts the behavioral invariants:
 *   1. Empty repo → [SettingsIgnoredUiState.Empty].
 *   2. One ignored contact → [SettingsIgnoredUiState.Ready] with one row, name +
 *      "Ignored today" subtitle, sorted by ignoredAt DESC.
 *   3. **B1: `isArchived` filter:** an ignored AND archived contact is filtered
 *      out so only the non-archived ignored row appears.
 *   4. `onUnignore(...)` flips `isIgnored = false` via the real
 *      [UnignoreContactUseCase] and emits an "Unignored {Name}" snackbar with Undo;
 *      `onUndo()` re-ignores through the recorded inverse.
 *   5. A failing read shows [SettingsIgnoredUiState.Error], and Try again recovers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsIgnoredViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val T0: Instant = Instant.parse("2026-04-26T12:00:00Z")

    /** Pass-through TransactionRunner: runs the block directly on the calling coroutine. */
    private val passThruTx = object : TransactionRunner {
        override suspend fun <R> withTransaction(block: suspend () -> R): R = block()
    }

    /**
     * Wraps the fake so the FIRST subscription to `observeIgnored()` fails and
     * every later one passes through. The use cases keep writing to the inner
     * fake, so the rest of the fixture is unchanged.
     */
    private class FlakyIgnoredRepository(private val inner: FakeContactRepository) : ContactRepository by inner {
        var failuresLeft: Int = 1
        override fun observeIgnored(): Flow<List<ContactEntity>> = flow {
            if (failuresLeft > 0) {
                failuresLeft--
                throw IllegalStateException("disk")
            }
            emitAll(inner.observeIgnored())
        }
    }

    private fun fixture(
        initial: List<ContactEntity> = emptyList(),
        vmRepo: (FakeContactRepository) -> ContactRepository = { it },
    ): Setup {
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
            clock = clock
        )
        val unignoreContactUseCase = UnignoreContactUseCase(
            txRunner = passThruTx,
            contactRepo = contactRepo,
            listDao = listDao,
            listMembershipDao = membershipDao,
            listRepo = listRepo,
            clock = clock
        )
        val undoStack = UndoStack()
        val vm = SettingsIgnoredViewModel(
            contactRepo = vmRepo(contactRepo),
            ignoreContactUseCase = ignoreContactUseCase,
            unignoreContactUseCase = unignoreContactUseCase,
            undoStack = undoStack,
            clock = clock
        )
        return Setup(vm, contactRepo, undoStack)
    }

    private data class Setup(
        val vm: SettingsIgnoredViewModel,
        val contactRepo: FakeContactRepository,
        val undoStack: UndoStack
    )

    // ============================================================================
    // Test 1: empty repo → Empty
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
    // Test 2: one ignored contact → Ready with one row carrying relative label.
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
        // "Ignored {today}" (strings_settings.xml); "today" is formatRelative's
        // UiText (strings_time.xml), nested as the argument.
        assertEquals(
            UiText.res(R.string.settings_ignored_relative, UiText.res(R.string.time_ago_today)),
            row.ignoredRelativeLabel,
        )
    }

    // ============================================================================
    // Test 3: B1: ignored AND archived row is filtered out.
    // Two contacts seeded: one ignored-only, one ignored-and-archived. The
    // ignored query (mirrored by the fake) drops the archived one so only the visible
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
        assertEquals(1, ready.ignored.size, "B1: archived contact must be filtered")
        assertEquals(1L, ready.ignored.single().id)
    }

    // ============================================================================
    // Test 4: onUnignore flips isIgnored=false on the contact and emits an
    // "Unignored {Name} · Undo" snackbar event.
    // ============================================================================

    @Test
    fun `onUnignore flips contact and emits Unignored snackbar`() = runTest {
        val ignored = contactFixture(id = 42L, isIgnored = true)
            .copy(displayName = "Alex Chen", ignoredAt = T0)
        val (vm, contactRepo, undoStack) = fixture(initial = listOf(ignored))

        // Drain to Ready so the upstream is hot before we mutate.
        vm.uiState.filterIsInstance<SettingsIgnoredUiState.Ready>().first()

        vm.snackbarEvents.test(timeout = 2.seconds) {
            vm.onUnignore(42L, "Alex Chen")
            val event = awaitItem()
            // "Unignored Alex Chen" / "Undo": one word for the inverse of Ignore
            // (voice.md glossary; the picker's row says the same).
            assertEquals(UiText.res(R.string.components_snackbar_unignored, "Alex Chen"), event.message)
            assertEquals(UiText.res(R.string.components_action_undo), event.actionLabel)
            cancelAndIgnoreRemainingEvents()
        }

        // markIgnored was called with isIgnored=false (the un-ignore write).
        val unsetCall = contactRepo.markIgnoredCalls.last()
        assertEquals(42L, unsetCall.contactId)
        assertEquals(false, unsetCall.isIgnored)

        // UndoStack carries an inverse closure: peek (do not consume).
        val pending = undoStack.peek()
        assertTrue(pending != null, "Undo closure must be queued for snackbar Undo tap")
        // Its inverse re-ignores (the words are on the event above).
        pending.inverse()
        assertEquals(true, contactRepo.markIgnoredCalls.last().isIgnored)
    }

    // ============================================================================
    // Test 5: the snackbar's Undo tap (onUndo) replays the inverse: the person
    // is ignored again and the stack is empty.
    // ============================================================================

    @Test
    fun `onUndo re-ignores the person and consumes the undo`() = runTest {
        val ignored = contactFixture(id = 42L, isIgnored = true)
            .copy(displayName = "Alex Chen", ignoredAt = T0)
        val (vm, contactRepo, undoStack) = fixture(initial = listOf(ignored))
        vm.uiState.filterIsInstance<SettingsIgnoredUiState.Ready>().first()

        vm.onUnignore(42L, "Alex Chen")
        assertEquals(false, contactRepo.markIgnoredCalls.last().isIgnored, "un-ignore lands first")

        vm.onUndo()

        val reIgnore = contactRepo.markIgnoredCalls.last()
        assertEquals(42L, reIgnore.contactId)
        assertEquals(true, reIgnore.isIgnored, "Undo must re-ignore the same person")
        assertNull(undoStack.peek(), "the undo is consumed by the tap")
    }

    // ============================================================================
    // Test 6: a failing read shows Error (not a skeleton for ever, not a
    // crash), and Try again re-subscribes and recovers (rubric D6).
    // ============================================================================

    @Test
    fun `a failing source shows Error, and Retry recovers`() = runTest {
        val ignored = contactFixture(id = 42L, isIgnored = true)
            .copy(displayName = "Alex Chen", ignoredAt = T0)
        val (vm, _, _) = fixture(initial = listOf(ignored), vmRepo = { FlakyIgnoredRepository(it) })

        assertEquals(
            SettingsIgnoredUiState.Error,
            vm.uiState.filterIsInstance<SettingsIgnoredUiState.Error>().first(),
        )

        vm.onRetry()

        val ready = vm.uiState.filterIsInstance<SettingsIgnoredUiState.Ready>().first()
        assertEquals(listOf(42L), ready.ignored.map { it.id })
    }
}
