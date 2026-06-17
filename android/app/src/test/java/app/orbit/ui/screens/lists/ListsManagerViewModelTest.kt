package app.orbit.ui.screens.lists

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import app.orbit.data.entity.ListEntity
import app.orbit.data.entity.ListType
import app.orbit.domain.FakeListRepository
import app.orbit.domain.FakeRuleTemplateRepository
import app.orbit.domain.JsonProvider
import app.orbit.domain.ReorderArgs
import app.orbit.domain.listFixture
import app.orbit.domain.smart.SmartListRule
import app.orbit.notify.NudgeSchedule
import app.orbit.notify.NudgeScheduler
import app.orbit.testutil.MainDispatcherRule
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Recording subclass of [NudgeScheduler] for [ListsManagerViewModelTest].
 * Overrides [cancel] and [scheduleFromEntity] so WorkManager is never called.
 * Requires Robolectric to satisfy the @ApplicationContext constructor
 * (same pattern as ListConfigViewModelTest / ListPromptWorkerTest).
 *
 * Named [ListsManagerFakeNudgeScheduler] (not RecordingNudgeScheduler) to avoid a
 * redeclaration clash with ListConfigViewModelTest's private RecordingNudgeScheduler
 * in the same package compilation unit.
 */
private class ListsManagerFakeNudgeScheduler : NudgeScheduler(
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

/**
 * Behavioral tests for [ListsManagerViewModel].
 *
 * The `Ready` state is `Ready(active, archived, archivedExpanded)`; this file
 * is kept in lockstep with the VM so `compileDebugUnitTestKotlin` stays green
 * between commits.
 *
 * Pattern: `MainDispatcherRule` (UnconfinedTestDispatcher default) + Turbine
 * `vm.uiState.test {}`. The first `awaitItem()` may be `Loading` or already
 * `Ready` depending on dispatcher draining — tests guard with `as? Ready` and
 * one extra `awaitItem()` if needed (see CardViewViewModelTest precedent).
 *
 * Robolectric is required so [RecordingNudgeScheduler] can satisfy the
 * @ApplicationContext constructor without touching WorkManager. The Application
 * class is overridden to `Application::class` so OrbitApp.onCreate is bypassed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class ListsManagerViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun firstReady(items: suspend () -> ListsManagerUiState): suspend () -> ListsManagerUiState =
        items

    // ============================================================================
    // Test 1 — archive flips a row from active → archived in the next emission.
    // LIST-02 (archive).
    // ============================================================================

    @Test
    fun archive_excludes_from_active() = runTest {
        val l1 = listFixture(id = 1L, sortOrder = 0)
        val l2 = listFixture(id = 2L, sortOrder = 1)
        val l3 = listFixture(id = 3L, sortOrder = 2)
        val repo = FakeListRepository(initialLists = listOf(l1, l2, l3))
        val vm = ListsManagerViewModel(listRepo = repo, ruleTemplateRepo = FakeRuleTemplateRepository(), nudgeScheduler = ListsManagerFakeNudgeScheduler())

        vm.uiState.test(timeout = 2.seconds) {
            // First emission may be Loading or already Ready depending on dispatcher draining.
            var first = awaitItem()
            if (first is ListsManagerUiState.Loading) first = awaitItem()
            val ready = first as ListsManagerUiState.Ready
            assertEquals(3, ready.active.size)
            assertEquals(0, ready.archived.size)

            vm.archiveList(2L)

            val afterArchive = awaitItem() as ListsManagerUiState.Ready
            assertEquals(2, afterArchive.active.size)
            assertEquals(1, afterArchive.archived.size)
            assertEquals(2L, afterArchive.archived.first().id)
            assertEquals(listOf(1L, 3L), afterArchive.active.map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
        // Argument-capture sanity — VM dispatched exactly one setArchived(true).
        assertEquals(listOf(2L to true), repo.setArchivedCalls.toList())
    }

    // ============================================================================
    // Test 2 — unarchive restores a row from archived → active.
    // LIST-02 (archive — restore path).
    // ============================================================================

    @Test
    fun unarchive_restores_to_active() = runTest {
        val archived = listFixture(id = 1L, sortOrder = 0, isArchived = true)
        val repo = FakeListRepository(initialLists = listOf(archived))
        val vm = ListsManagerViewModel(listRepo = repo, ruleTemplateRepo = FakeRuleTemplateRepository(), nudgeScheduler = ListsManagerFakeNudgeScheduler())

        vm.uiState.test(timeout = 2.seconds) {
            var first = awaitItem()
            if (first is ListsManagerUiState.Loading) first = awaitItem()
            val ready = first as ListsManagerUiState.Ready
            assertEquals(0, ready.active.size)
            assertEquals(1, ready.archived.size)

            vm.unarchiveList(1L)

            val afterRestore = awaitItem() as ListsManagerUiState.Ready
            assertEquals(1, afterRestore.active.size)
            assertEquals(0, afterRestore.archived.size)
            assertEquals(1L, afterRestore.active.first().id)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf(1L to false), repo.setArchivedCalls.toList())
    }

    // ============================================================================
    // Test 3 — moveList dispatches to repo with the supplied indices.
    // LIST-02 (reorder) — the mutex contract is exercised by the
    // sequential VM dispatch; the FakeListRepository's reorderCalls capture
    // confirms exactly-one-dispatch-per-call.
    // ============================================================================

    @Test
    fun reorder_dispatches_to_repo_with_indices() = runTest {
        val l1 = listFixture(id = 1L, sortOrder = 0)
        val l2 = listFixture(id = 2L, sortOrder = 1)
        val l3 = listFixture(id = 3L, sortOrder = 2)
        val repo = FakeListRepository(initialLists = listOf(l1, l2, l3))
        val vm = ListsManagerViewModel(listRepo = repo, ruleTemplateRepo = FakeRuleTemplateRepository(), nudgeScheduler = ListsManagerFakeNudgeScheduler())

        vm.uiState.test(timeout = 2.seconds) {
            // Drain to first Ready so the upstream is collecting.
            var first = awaitItem()
            if (first is ListsManagerUiState.Loading) first = awaitItem()
            assertTrue(first is ListsManagerUiState.Ready)

            vm.moveList(0, 2)

            // The fake also re-emits new sortOrder; drain that emission.
            val afterReorder = awaitItem() as ListsManagerUiState.Ready
            assertEquals(3, afterReorder.active.size)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf(ReorderArgs(0, 2)), repo.reorderCalls.toList())
    }

    // ============================================================================
    // Test 4 — smart list carries a sentence-case rule summary on its tile.
    // LIST-07 + the Lists Manager copywriting contract.
    // ============================================================================

    @Test
    fun smart_list_carries_rule_summary() = runTest {
        val rule = SmartListRule.RecentlyAddedNotCalled(30)
        val ruleJson = JsonProvider.json.encodeToString(SmartListRule.serializer(), rule)
        val smart = listFixture(
            id = 7L,
            type = ListType.SMART,
            smartRuleJson = ruleJson,
        )
        val repo = FakeListRepository(initialLists = listOf(smart))
        val vm = ListsManagerViewModel(listRepo = repo, ruleTemplateRepo = FakeRuleTemplateRepository(), nudgeScheduler = ListsManagerFakeNudgeScheduler())

        vm.uiState.test(timeout = 2.seconds) {
            var first = awaitItem()
            if (first is ListsManagerUiState.Loading) first = awaitItem()
            val ready = first as ListsManagerUiState.Ready
            val tile = ready.active.single()
            assertEquals("Recently added · 30 days", tile.ruleSummary)
            assertEquals(ListType.SMART, tile.type)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ============================================================================
    // Test 5 — static list (smartRuleJson = null) has no rule summary.
    // ============================================================================

    @Test
    fun static_list_has_null_ruleSummary() = runTest {
        val staticList = listFixture(
            id = 11L,
            type = ListType.STATIC,
            smartRuleJson = null,
        )
        val repo = FakeListRepository(initialLists = listOf(staticList))
        val vm = ListsManagerViewModel(listRepo = repo, ruleTemplateRepo = FakeRuleTemplateRepository(), nudgeScheduler = ListsManagerFakeNudgeScheduler())

        vm.uiState.test(timeout = 2.seconds) {
            var first = awaitItem()
            if (first is ListsManagerUiState.Loading) first = awaitItem()
            val ready = first as ListsManagerUiState.Ready
            val tile = ready.active.single()
            assertNull(tile.ruleSummary)
            assertEquals(ListType.STATIC, tile.type)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ============================================================================
    // Test 6 — empty repo terminal state is Empty, not Ready with empty lists.
    // ============================================================================

    @Test
    fun empty_state_when_no_lists() = runTest {
        val repo = FakeListRepository()
        val vm = ListsManagerViewModel(listRepo = repo, ruleTemplateRepo = FakeRuleTemplateRepository(), nudgeScheduler = ListsManagerFakeNudgeScheduler())

        vm.uiState.test(timeout = 2.seconds) {
            var first = awaitItem()
            if (first is ListsManagerUiState.Loading) first = awaitItem()
            assertEquals(ListsManagerUiState.Empty, first)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun renameList_trims_and_dispatches_updateName() = runTest {
        val list = listFixture(id = 7L, sortOrder = 0)
        val repo = FakeListRepository(initialLists = listOf(list))
        val vm = ListsManagerViewModel(listRepo = repo, ruleTemplateRepo = FakeRuleTemplateRepository(), nudgeScheduler = ListsManagerFakeNudgeScheduler())

        val before = repo.updateNameCalls.size
        vm.renameList(listId = 7L, name = "  Late night  ")

        assertEquals(before + 1, repo.updateNameCalls.size)
        val (writtenId, writtenName) = repo.updateNameCalls.last()
        assertEquals(7L, writtenId)
        assertEquals("Late night", writtenName)
    }

    @Test
    fun renameList_drops_blank_input_as_noop() = runTest {
        val list = listFixture(id = 7L, sortOrder = 0)
        val repo = FakeListRepository(initialLists = listOf(list))
        val vm = ListsManagerViewModel(listRepo = repo, ruleTemplateRepo = FakeRuleTemplateRepository(), nudgeScheduler = ListsManagerFakeNudgeScheduler())

        val before = repo.updateNameCalls.size
        vm.renameList(listId = 7L, name = "   ")
        assertEquals(before, repo.updateNameCalls.size, "blank input must not dispatch updateName")
    }

    @Test
    fun renameList_drops_empty_string_as_noop() = runTest {
        val list = listFixture(id = 7L, sortOrder = 0)
        val repo = FakeListRepository(initialLists = listOf(list))
        val vm = ListsManagerViewModel(listRepo = repo, ruleTemplateRepo = FakeRuleTemplateRepository(), nudgeScheduler = ListsManagerFakeNudgeScheduler())

        val before = repo.updateNameCalls.size
        vm.renameList(listId = 7L, name = "")
        assertEquals(before, repo.updateNameCalls.size, "empty input must not dispatch updateName")
    }

    // ============================================================================
    // 2026-06-09 #26 — create must hand the new id to the screen so it can
    // navigate to the new list's configuration instead of stranding the user.
    // ============================================================================

    @Test
    fun createList_emits_new_id_for_navigation() = runTest {
        val repo = FakeListRepository(initialLists = listOf(listFixture(id = 3L, sortOrder = 0)))
        val vm = ListsManagerViewModel(listRepo = repo, ruleTemplateRepo = FakeRuleTemplateRepository(), nudgeScheduler = ListsManagerFakeNudgeScheduler())
        val blank = TemplateChoice.Catalog.first { it.id == "blank" }

        vm.createdListEvents.test(timeout = 2.seconds) {
            vm.createList(blank, "Night owls")
            // FakeListRepository.create assigns max(id) + 1.
            assertEquals(4L, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals("Night owls", repo.createCalls.single().name)
    }

    @Test
    fun createList_blank_name_emits_no_navigation_event() = runTest {
        val repo = FakeListRepository()
        val vm = ListsManagerViewModel(listRepo = repo, ruleTemplateRepo = FakeRuleTemplateRepository(), nudgeScheduler = ListsManagerFakeNudgeScheduler())
        val blank = TemplateChoice.Catalog.first { it.id == "blank" }

        vm.createdListEvents.test(timeout = 2.seconds) {
            vm.createList(blank, "   ")
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(repo.createCalls.isEmpty(), "blank name must not create a list")
    }

    // ============================================================================
    // NOTIF-11 — archive cancels nudge, delete cancels nudge,
    // unarchive re-enqueues nudge. Each asserts both the repo mutation and the
    // NudgeScheduler call in the same runMutation block.
    // ============================================================================

    @Test
    fun archiveList_cancels_nudge_chain() = runTest {
        val list = listFixture(id = 5L, sortOrder = 0)
        val repo = FakeListRepository(initialLists = listOf(list))
        val nudge = ListsManagerFakeNudgeScheduler()
        val vm = ListsManagerViewModel(listRepo = repo, ruleTemplateRepo = FakeRuleTemplateRepository(), nudgeScheduler = nudge)

        vm.uiState.test(timeout = 2.seconds) {
            var first = awaitItem()
            if (first is ListsManagerUiState.Loading) first = awaitItem()
            assertTrue(first is ListsManagerUiState.Ready)

            vm.archiveList(5L)

            // Consume the archive emission.
            cancelAndIgnoreRemainingEvents()
        }

        // Repo mutation happened.
        assertEquals(listOf(5L to true), repo.setArchivedCalls.toList())
        // NOTIF-11: nudge chain was cancelled.
        assertEquals(listOf(5L), nudge.cancelCalls.toList())
    }

    @Test
    fun deleteList_cancels_nudge_chain() = runTest {
        val list = listFixture(id = 9L, sortOrder = 0)
        val repo = FakeListRepository(initialLists = listOf(list))
        val nudge = ListsManagerFakeNudgeScheduler()
        val vm = ListsManagerViewModel(listRepo = repo, ruleTemplateRepo = FakeRuleTemplateRepository(), nudgeScheduler = nudge)

        vm.uiState.test(timeout = 2.seconds) {
            var first = awaitItem()
            if (first is ListsManagerUiState.Loading) first = awaitItem()
            assertTrue(first is ListsManagerUiState.Ready)

            vm.deleteList(9L)

            // Consume deletion emission (Empty state after the only list is gone).
            cancelAndIgnoreRemainingEvents()
        }

        // Repo mutation happened.
        assertEquals(listOf(9L), repo.deleteCalls.toList())
        // NOTIF-11 / D-25 folded todo: nudge chain was cancelled.
        assertEquals(listOf(9L), nudge.cancelCalls.toList())
    }

    @Test
    fun unarchiveList_reenqueues_nudge_chain() = runTest {
        val archived = listFixture(id = 3L, sortOrder = 0, isArchived = true)
        val repo = FakeListRepository(initialLists = listOf(archived))
        val nudge = ListsManagerFakeNudgeScheduler()
        val vm = ListsManagerViewModel(listRepo = repo, ruleTemplateRepo = FakeRuleTemplateRepository(), nudgeScheduler = nudge)

        vm.uiState.test(timeout = 2.seconds) {
            var first = awaitItem()
            if (first is ListsManagerUiState.Loading) first = awaitItem()
            assertTrue(first is ListsManagerUiState.Ready)

            vm.unarchiveList(3L)

            // Consume unarchive emission.
            cancelAndIgnoreRemainingEvents()
        }

        // Repo mutation happened.
        assertEquals(listOf(3L to false), repo.setArchivedCalls.toList())
        // NOTIF-11: nudge chain was re-enqueued via scheduleFromEntity.
        assertEquals(1, nudge.scheduleFromEntityCalls.size)
        assertEquals(3L, nudge.scheduleFromEntityCalls.single().id)
    }
}
