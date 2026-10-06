package app.orbit.ui.screens.lists

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import app.orbit.data.entity.ListEntity
import app.orbit.data.entity.ListType
import app.orbit.data.entity.RuleKind
import app.orbit.data.repository.ListRepository
import app.orbit.domain.FakeListRepository
import app.orbit.domain.FakeRuleTemplateRepository
import app.orbit.domain.JsonProvider
import app.orbit.domain.ReorderArgs
import app.orbit.domain.WidgetRefreshTrigger
import app.orbit.domain.listFixture
import app.orbit.domain.rule.RuleParams
import app.orbit.domain.ruleTemplateFixture
import app.orbit.domain.smart.SmartListRule
import app.orbit.notify.NudgeScheduler
import app.orbit.testutil.MainDispatcherRule
import app.orbit.ui.screens.home.HomeSnackbarEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

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
    listRepo = FakeListRepository()
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
 * A [ListRepository] whose writes can be made to throw, the way a full disk
 * or a closed database would, so the failure paths are reachable: the VM
 * must say "Couldn't save your change" and nothing else. Delegates every
 * other call to the real fake, whose capture lists the assertions still read.
 * Local to this file (ListConfigViewModelTest has its own, differently named,
 * since private top-level classes in one package may not share a name).
 */
private class ListsManagerThrowingListRepository(
    val delegate: FakeListRepository
) : ListRepository by delegate {
    var failWrites: Boolean = false

    private fun failIfAsked() {
        if (failWrites) throw IllegalStateException("database write failed")
    }

    override suspend fun setArchived(listId: Long, archived: Boolean) {
        failIfAsked()
        delegate.setArchived(listId, archived)
    }

    override suspend fun updateNotificationsEnabled(listId: Long, enabled: Boolean) {
        failIfAsked()
        delegate.updateNotificationsEnabled(listId, enabled)
    }

    override suspend fun delete(listId: Long) {
        failIfAsked()
        delegate.delete(listId)
    }
}

/** Counts widget refresh requests (WIDGET-06) without WorkManager. */
private class ListsManagerRecordingWidgetTrigger : WidgetRefreshTrigger {
    var refreshes = 0
    override fun scheduleRefresh() {
        refreshes += 1
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

    // Rule summaries and snackbar copy are UiText; resolve them against real resources.
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun firstReady(
        items: suspend () -> ListsManagerUiState
    ): suspend () -> ListsManagerUiState = items

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
        val vm =
            ListsManagerViewModel(
                listRepo = repo,
                ruleTemplateRepo = FakeRuleTemplateRepository(),
                nudgeScheduler = ListsManagerFakeNudgeScheduler()
            )

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
        val vm =
            ListsManagerViewModel(
                listRepo = repo,
                ruleTemplateRepo = FakeRuleTemplateRepository(),
                nudgeScheduler = ListsManagerFakeNudgeScheduler()
            )

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
        val vm =
            ListsManagerViewModel(
                listRepo = repo,
                ruleTemplateRepo = FakeRuleTemplateRepository(),
                nudgeScheduler = ListsManagerFakeNudgeScheduler()
            )

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
            smartRuleJson = ruleJson
        )
        val repo = FakeListRepository(initialLists = listOf(smart))
        val vm =
            ListsManagerViewModel(
                listRepo = repo,
                ruleTemplateRepo = FakeRuleTemplateRepository(),
                nudgeScheduler = ListsManagerFakeNudgeScheduler()
            )

        vm.uiState.test(timeout = 2.seconds) {
            var first = awaitItem()
            if (first is ListsManagerUiState.Loading) first = awaitItem()
            val ready = first as ListsManagerUiState.Ready
            val tile = ready.active.single()
            assertEquals("Recently added · 30 days", tile.ruleSummary?.asString(context))
            assertEquals(ListType.SMART, tile.type)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ============================================================================
    // Test 5: a regular list's row carries its rhythm (the README promises
    // every row a rhythm summary; regular lists had none until 2026-10-06).
    // The per-list override wins; otherwise the template's defaults; a list
    // with neither has no subtitle.
    // ============================================================================

    private val seededTemplates = FakeRuleTemplateRepository().apply {
        seed(
            listOf(
                ruleTemplateFixture(id = 1L, kind = RuleKind.KEEP_IN_TOUCH, params = RuleParams.KeepInTouch()),
                ruleTemplateFixture(id = 2L, kind = RuleKind.LATE_NIGHT, params = RuleParams.LateNight()),
                ruleTemplateFixture(id = 3L, kind = RuleKind.ENERGIZE, params = RuleParams.Energize())
            )
        )
    }

    private suspend fun singleTileSubtitle(list: ListEntity, templates: FakeRuleTemplateRepository): String? {
        val vm =
            ListsManagerViewModel(
                listRepo = FakeListRepository(initialLists = listOf(list)),
                ruleTemplateRepo = templates,
                nudgeScheduler = ListsManagerFakeNudgeScheduler()
            )
        var subtitle: String? = null
        vm.uiState.test(timeout = 2.seconds) {
            var first = awaitItem()
            if (first is ListsManagerUiState.Loading) first = awaitItem()
            val tile = (first as ListsManagerUiState.Ready).active.single()
            assertEquals(ListType.STATIC, tile.type)
            subtitle = tile.ruleSummary?.asString(context)
            cancelAndIgnoreRemainingEvents()
        }
        return subtitle
    }

    @Test
    fun regular_list_carries_its_override_rhythm() = runTest {
        val fortnightly = JsonProvider.json.encodeToString(
            RuleParams.serializer(),
            RuleParams.KeepInTouch().withIntervalHours(14 * 24)
        )
        val list = listFixture(id = 11L, ruleTemplateId = 1L, ruleParamsOverrideJson = fortnightly)
        assertEquals("Every 14 days", singleTileSubtitle(list, seededTemplates))
    }

    @Test
    fun regular_list_without_override_reads_its_templates_rhythm() = runTest {
        // "Start from blank" writes no override, so its rhythm lives only in
        // the seeded template (the Keep in touch default is 48 hours).
        val blank = listFixture(id = 12L, ruleTemplateId = 1L, ruleParamsOverrideJson = null)
        assertEquals("Every 2 days", singleTileSubtitle(blank, seededTemplates))

        val lateNight = listFixture(id = 13L, ruleTemplateId = 2L, ruleParamsOverrideJson = null)
        assertEquals("Late night rhythm", singleTileSubtitle(lateNight, seededTemplates))

        val energize = listFixture(id = 14L, ruleTemplateId = 3L, ruleParamsOverrideJson = null)
        assertEquals("Energize rhythm", singleTileSubtitle(energize, seededTemplates))
    }

    @Test
    fun regular_list_with_no_template_and_no_override_has_no_subtitle() = runTest {
        val partial = listFixture(id = 15L, ruleTemplateId = null, ruleParamsOverrideJson = null)
        assertNull(singleTileSubtitle(partial, FakeRuleTemplateRepository()))
    }

    // ============================================================================
    // Test 6 — empty repo terminal state is Empty, not Ready with empty lists.
    // ============================================================================

    @Test
    fun empty_state_when_no_lists() = runTest {
        val repo = FakeListRepository()
        val vm =
            ListsManagerViewModel(
                listRepo = repo,
                ruleTemplateRepo = FakeRuleTemplateRepository(),
                nudgeScheduler = ListsManagerFakeNudgeScheduler()
            )

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
        val vm =
            ListsManagerViewModel(
                listRepo = repo,
                ruleTemplateRepo = FakeRuleTemplateRepository(),
                nudgeScheduler = ListsManagerFakeNudgeScheduler()
            )

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
        val vm =
            ListsManagerViewModel(
                listRepo = repo,
                ruleTemplateRepo = FakeRuleTemplateRepository(),
                nudgeScheduler = ListsManagerFakeNudgeScheduler()
            )

        val before = repo.updateNameCalls.size
        vm.renameList(listId = 7L, name = "   ")
        assertEquals(before, repo.updateNameCalls.size, "blank input must not dispatch updateName")
    }

    @Test
    fun renameList_drops_empty_string_as_noop() = runTest {
        val list = listFixture(id = 7L, sortOrder = 0)
        val repo = FakeListRepository(initialLists = listOf(list))
        val vm =
            ListsManagerViewModel(
                listRepo = repo,
                ruleTemplateRepo = FakeRuleTemplateRepository(),
                nudgeScheduler = ListsManagerFakeNudgeScheduler()
            )

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
        val vm =
            ListsManagerViewModel(
                listRepo = repo,
                ruleTemplateRepo = FakeRuleTemplateRepository(),
                nudgeScheduler = ListsManagerFakeNudgeScheduler()
            )
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
    fun createList_writes_the_templates_own_interval() = runTest {
        // Regression: every template made the same 2-day list (the Keep in
        // touch default), whatever its subtitle promised.
        val repo = FakeListRepository()
        val vm =
            ListsManagerViewModel(
                listRepo = repo,
                ruleTemplateRepo = FakeRuleTemplateRepository(),
                nudgeScheduler = ListsManagerFakeNudgeScheduler()
            )
        val family = TemplateChoice.Catalog.first { it.id == "family" }
        val blank = TemplateChoice.Catalog.first { it.id == "blank" }

        vm.createdListEvents.test(timeout = 2.seconds) {
            vm.createList(family, "Family")
            awaitItem()
            vm.createList(blank, "Night owls")
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        val familyJson =
            assertNotNull(repo.createCalls.first { it.name == "Family" }.ruleParamsOverrideJson)
        val params = JsonProvider.json.decodeFromString(RuleParams.serializer(), familyJson)
        assertEquals(RuleParams.KeepInTouch().withIntervalHours(14 * 24), params)
        assertNull(
            repo.createCalls.first { it.name == "Night owls" }.ruleParamsOverrideJson,
            "Start from blank keeps the template default"
        )
    }

    @Test
    fun createList_blank_name_emits_no_navigation_event() = runTest {
        val repo = FakeListRepository()
        val vm =
            ListsManagerViewModel(
                listRepo = repo,
                ruleTemplateRepo = FakeRuleTemplateRepository(),
                nudgeScheduler = ListsManagerFakeNudgeScheduler()
            )
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
        val vm =
            ListsManagerViewModel(
                listRepo = repo,
                ruleTemplateRepo = FakeRuleTemplateRepository(),
                nudgeScheduler = nudge
            )

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
    fun deleteList_defers_the_purge_until_commit_then_cancels_nudge_chain() = runTest {
        // Regression: Lists deleted immediately with no Undo, while the same
        // action on Home offered one. Delete is now deferred like Home's.
        val list = listFixture(id = 9L, sortOrder = 0, isArchived = true)
        val repo = FakeListRepository(initialLists = listOf(list))
        val nudge = ListsManagerFakeNudgeScheduler()
        val vm =
            ListsManagerViewModel(
                listRepo = repo,
                ruleTemplateRepo = FakeRuleTemplateRepository(),
                nudgeScheduler = nudge
            )

        vm.snackbarEvents.test(timeout = 2.seconds) {
            vm.deleteList(9L)
            val event = awaitItem()
            assertEquals("List deleted.", event.message.asString(context))
            assertEquals("Undo", event.actionLabel?.asString(context))
            assertEquals(HomeSnackbarEvent.Kind.DELETE_UNDO, event.kind)
            assertEquals(9L, event.payloadListId)
            cancelAndIgnoreRemainingEvents()
        }
        // Hidden, but not purged while the Undo window is open.
        vm.uiState.test(timeout = 2.seconds) {
            var state = awaitItem()
            if (state is ListsManagerUiState.Loading) state = awaitItem()
            assertEquals(ListsManagerUiState.Empty, state, "the staged list must be hidden")
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(
            repo.deleteCalls.isEmpty(),
            "nothing may be purged before the Undo window closes"
        )

        vm.commitDelete(9L)

        assertEquals(listOf(9L), repo.deleteCalls.toList())
        // NOTIF-11 / D-25 folded todo: nudge chain was cancelled.
        assertEquals(listOf(9L), nudge.cancelCalls.toList())
    }

    @Test
    fun undoDelete_restores_the_row_and_never_purges() = runTest {
        val list = listFixture(id = 9L, sortOrder = 0, isArchived = true)
        val repo = FakeListRepository(initialLists = listOf(list))
        val vm =
            ListsManagerViewModel(
                listRepo = repo,
                ruleTemplateRepo = FakeRuleTemplateRepository(),
                nudgeScheduler = ListsManagerFakeNudgeScheduler()
            )

        vm.deleteList(9L)
        vm.undoDelete(9L)
        vm.commitDelete(9L) // the snackbar's finally still runs; it must be a no-op now

        assertTrue(repo.deleteCalls.isEmpty())
        vm.uiState.test(timeout = 2.seconds) {
            var state = awaitItem()
            if (state is ListsManagerUiState.Loading) state = awaitItem()
            val ready = state as ListsManagerUiState.Ready
            assertEquals(listOf(9L), ready.archived.map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun unarchiveList_reenqueues_nudge_chain() = runTest {
        val archived = listFixture(id = 3L, sortOrder = 0, isArchived = true)
        val repo = FakeListRepository(initialLists = listOf(archived))
        val nudge = ListsManagerFakeNudgeScheduler()
        val vm =
            ListsManagerViewModel(
                listRepo = repo,
                ruleTemplateRepo = FakeRuleTemplateRepository(),
                nudgeScheduler = nudge
            )

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

    // ============================================================================
    // Success snackbars are gated on the write (rules.md Code 3). Before
    // 2026-10-06 archive emitted "List archived." with Undo whether or not
    // setArchived threw, and the screen showed "List restored." itself before
    // the write resolved.
    //
    // The VM's event flow has more than one slot, so a wrongly emitted second
    // event reaches the Turbine collector and expectNoEvents() catches it.
    // With the old one-slot buffer the collector never ran between two
    // synchronous tryEmits (the launch runs inside an unconfined event loop)
    // and the duplicate was dropped: the ungated code passed these tests by
    // the same accident that hid the bug in production. Seen failing with
    // the gate removed once the buffer grew.
    // ============================================================================

    private fun throwingFixture(vararg lists: ListEntity): Triple<ListsManagerViewModel, ListsManagerThrowingListRepository, ListsManagerRecordingWidgetTrigger> {
        val repo = ListsManagerThrowingListRepository(FakeListRepository(initialLists = lists.toList()))
        val widget = ListsManagerRecordingWidgetTrigger()
        val vm = ListsManagerViewModel(
            listRepo = repo,
            ruleTemplateRepo = FakeRuleTemplateRepository(),
            nudgeScheduler = ListsManagerFakeNudgeScheduler(),
            widgetRefreshTrigger = widget
        )
        return Triple(vm, repo, widget)
    }

    @Test
    fun archiveList_failure_emits_only_the_failure_snackbar() = runTest {
        val (vm, repo, widget) = throwingFixture(listFixture(id = 5L, sortOrder = 0))
        repo.failWrites = true

        vm.snackbarEvents.test(timeout = 2.seconds) {
            vm.archiveList(5L)
            val event = awaitItem()
            assertEquals("Couldn't save your change", event.message.asString(context))
            assertNull(event.actionLabel, "a failure offers no Undo")
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(0, widget.refreshes, "nothing changed, so the widget is not asked to refresh")
    }

    @Test
    fun archiveList_success_announces_with_undo_and_refreshes_the_widget() = runTest {
        val (vm, _, widget) = throwingFixture(listFixture(id = 5L, sortOrder = 0))

        vm.snackbarEvents.test(timeout = 2.seconds) {
            vm.archiveList(5L)
            val event = awaitItem()
            assertEquals("List archived.", event.message.asString(context))
            assertEquals(HomeSnackbarEvent.Kind.ARCHIVE_UNDO, event.kind)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, widget.refreshes)
    }

    @Test
    fun unarchiveList_announces_restored_only_once_the_write_is_in() = runTest {
        val (vm, repo, widget) = throwingFixture(listFixture(id = 3L, sortOrder = 0, isArchived = true))

        vm.snackbarEvents.test(timeout = 2.seconds) {
            vm.unarchiveList(3L)
            val event = awaitItem()
            assertEquals("List restored.", event.message.asString(context))
            assertEquals(HomeSnackbarEvent.Kind.PLAIN, event.kind)
            expectNoEvents()

            repo.failWrites = true
            vm.unarchiveList(3L)
            assertEquals("Couldn't save your change", awaitItem().message.asString(context))
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, widget.refreshes, "only the successful restore refreshes the widget")
    }

    @Test
    fun onUndoArchive_restores_quietly() = runTest {
        // Undo of "List archived." puts the row back without a second
        // snackbar, as Home's undo does.
        val (vm, repo, widget) = throwingFixture(listFixture(id = 3L, sortOrder = 0, isArchived = true))

        vm.snackbarEvents.test(timeout = 2.seconds) {
            vm.onUndoArchive(3L)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf(3L to false), repo.delegate.setArchivedCalls.toList(), "the row is back")
        assertEquals(1, widget.refreshes)
    }

    @Test
    fun commitDelete_refreshes_the_widget_only_when_the_purge_succeeds() = runTest {
        val (vm, repo, widget) = throwingFixture(listFixture(id = 9L, sortOrder = 0, isArchived = true))

        vm.deleteList(9L)
        repo.failWrites = true
        vm.commitDelete(9L)
        assertEquals(0, widget.refreshes)

        repo.failWrites = false
        vm.deleteList(9L)
        vm.commitDelete(9L)
        assertEquals(1, widget.refreshes)
    }

    // ============================================================================
    // LIST-23: the row menu offers Pause nudges / Resume nudges, as Home's
    // long-press menu does, with Home's confirming words.
    // ============================================================================

    @Test
    fun toggleNudges_flips_the_flag_and_confirms_with_homes_words() = runTest {
        val repo = FakeListRepository(initialLists = listOf(listFixture(id = 4L, sortOrder = 0, notificationsEnabled = true)))
        val vm =
            ListsManagerViewModel(
                listRepo = repo,
                ruleTemplateRepo = FakeRuleTemplateRepository(),
                nudgeScheduler = ListsManagerFakeNudgeScheduler()
            )

        vm.snackbarEvents.test(timeout = 2.seconds) {
            vm.toggleNudges(4L)
            assertEquals("Nudges paused.", awaitItem().message.asString(context))
            vm.toggleNudges(4L)
            assertEquals("Nudges on.", awaitItem().message.asString(context))
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf(4L to false, 4L to true), repo.updateNotificationsEnabledCalls.toList())
    }

    @Test
    fun toggleNudges_failure_emits_only_the_failure_snackbar() = runTest {
        val (vm, repo, _) = throwingFixture(listFixture(id = 4L, sortOrder = 0))
        repo.failWrites = true

        vm.snackbarEvents.test(timeout = 2.seconds) {
            vm.toggleNudges(4L)
            assertEquals("Couldn't save your change", awaitItem().message.asString(context))
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun tile_carries_the_notifications_flag_for_the_menu_label() = runTest {
        val repo = FakeListRepository(initialLists = listOf(listFixture(id = 4L, sortOrder = 0, notificationsEnabled = false)))
        val vm =
            ListsManagerViewModel(
                listRepo = repo,
                ruleTemplateRepo = FakeRuleTemplateRepository(),
                nudgeScheduler = ListsManagerFakeNudgeScheduler()
            )
        vm.uiState.test(timeout = 2.seconds) {
            var first = awaitItem()
            if (first is ListsManagerUiState.Loading) first = awaitItem()
            assertEquals(false, (first as ListsManagerUiState.Ready).active.single().notificationsEnabled)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // LIST-22: a failing source shows Error instead of killing the stream,
    // and Try again re-subscribes and recovers.
    @Test
    fun failing_source_shows_error_and_retry_recovers() = runTest {
        val repo = FakeListRepository(initialLists = listOf(listFixture(id = 1L, sortOrder = 0)))
        repo.failMemberCounts = true
        val vm =
            ListsManagerViewModel(
                listRepo = repo,
                ruleTemplateRepo = FakeRuleTemplateRepository(),
                nudgeScheduler = ListsManagerFakeNudgeScheduler()
            )
        vm.uiState.test(timeout = 2.seconds) {
            var state = awaitItem()
            while (state is ListsManagerUiState.Loading) state = awaitItem()
            assertEquals(ListsManagerUiState.Error, state)

            repo.failMemberCounts = false
            vm.onRetry()
            state = awaitItem()
            while (state is ListsManagerUiState.Loading || state is ListsManagerUiState.Error) state = awaitItem()
            assertTrue(state is ListsManagerUiState.Ready, "Try again recovers to Ready, got $state")
            cancelAndIgnoreRemainingEvents()
        }
    }
}
