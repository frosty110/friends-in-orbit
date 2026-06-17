package app.orbit.ui.screens.lists

import android.app.Application
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import app.orbit.data.dao.RecordingListMembershipDao
import app.orbit.data.db.TransactionRunner
import app.orbit.data.entity.ListType
import app.orbit.data.entity.RuleKind
import app.orbit.domain.FakeCallEventRepository
import app.orbit.domain.FakeContactRepository
import app.orbit.domain.FakeListRepository
import app.orbit.domain.FakeRuleTemplateRepository
import app.orbit.domain.JsonProvider
import app.orbit.domain.clock.TestClock
import app.orbit.domain.contactFixture
import app.orbit.domain.listFixture
import app.orbit.domain.rule.RuleParams
import app.orbit.domain.ruleTemplateFixture
import app.orbit.domain.smart.SmartListEngine
import app.orbit.domain.smart.SmartListRule
import app.orbit.domain.membershipFixture
import app.orbit.domain.undo.UndoStack
import app.orbit.domain.usecase.BulkRemoveFromListUseCase
import app.orbit.notify.NudgeSchedule
import app.orbit.notify.NudgeScheduler
import app.orbit.testutil.MainDispatcherRule
import app.orbit.ui.screens.picker.SnackbarEvent
import java.time.DayOfWeek
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
 * Recording subclass of [NudgeScheduler] that captures [schedule] calls without
 * touching WorkManager. Extends the open class directly (no Mockito in catalog).
 * Robolectric provides the Application context; the overridden [schedule] function
 * never calls super, so WorkManager is never invoked.
 */
private class RecordingNudgeScheduler : NudgeScheduler(
    context = ApplicationProvider.getApplicationContext<Context>(),
    listRepo = FakeListRepository(),
) {
    data class ScheduleCall(val listId: Long, val schedule: NudgeSchedule, val activeHoursStart: LocalTime?)

    val scheduleCalls: MutableList<ScheduleCall> = mutableListOf()

    override fun schedule(listId: Long, schedule: NudgeSchedule, activeHoursStart: LocalTime?) {
        scheduleCalls += ScheduleCall(listId, schedule, activeHoursStart)
    }
}

/**
 * Behavioral tests for the rewritten [ListConfigViewModel] covering all five
 * save-on-change setters plus the override-JSON round-trip:
 *  1. setRuleTemplate resolves RuleKind → seeded row id
 *  2. [setRuleParamsOverrideJson_writes_override_column]
 *  3. [setActiveHours_writes_LocalTime_pair_including_null]
 *  4. [setNotificationsEnabled_flips_boolean]
 *  5. [setSmartRuleJson_round_trips_through_polymorphic_json]
 *  6. [vm_resolves_override_in_uiState] — override beats template default
 *
 * Pattern mirrors HomeViewModelTest / CardViewViewModelTest:
 *   - Real VM over fake repositories with argument-capture lists.
 *   - Real [SmartListEngine] (engine logic is light; covered separately by
 *     [SmartListEngineTest] and [SmartRuleEditIntegrationTest]).
 *   - [MainDispatcherRule] (UnconfinedTestDispatcher) so `viewModelScope.launch`
 *     dispatches synchronously.
 *   - [app.cash.turbine.test] asserts terminal state per ARCH-02.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ListConfigViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val json = JsonProvider.json

    /**
     * Builds a VM with a default list seeded under id=1L and a single
     * KEEP_IN_TOUCH template (id=1L) so the override-vs-default resolution
     * path is exercisable.
     */
    private fun fixture(
        savedStateListId: String? = "1",
        list: app.orbit.data.entity.ListEntity = listFixture(
            id = 1L,
            name = "Inner orbit",
            type = ListType.STATIC,
            ruleTemplateId = 1L,
        ),
        templates: List<app.orbit.data.entity.RuleTemplateEntity> = listOf(
            ruleTemplateFixture(id = 1L, kind = RuleKind.KEEP_IN_TOUCH, params = RuleParams.KeepInTouch()),
            ruleTemplateFixture(id = 2L, kind = RuleKind.LATE_NIGHT, params = RuleParams.LateNight()),
        ),
    ): Setup {
        val listRepo = FakeListRepository().apply { seed(listOf(list)) }
        val templateRepo = FakeRuleTemplateRepository().apply { seed(templates) }
        val contactRepo = FakeContactRepository()
        val callEventRepo = FakeCallEventRepository()
        val clock = TestClock()
        val engine = SmartListEngine(contactRepo, callEventRepo, clock)
        val passThruTx = object : TransactionRunner {
            override suspend fun <T> withTransaction(block: suspend () -> T): T = block()
        }
        val recDao = RecordingListMembershipDao()
        val undoStack = UndoStack()
        val nudgeScheduler = RecordingNudgeScheduler()
        val vm = ListConfigViewModel(
            listRepo = listRepo,
            ruleTemplateRepo = templateRepo,
            contactRepo = contactRepo,
            smartListEngine = engine,
            bulkRemoveFromListUseCase = BulkRemoveFromListUseCase(passThruTx, recDao, listRepo, clock),
            undoStack = undoStack,
            nudgeScheduler = nudgeScheduler,
            savedStateHandle = SavedStateHandle(mapOf("listId" to savedStateListId)),
        )
        return Setup(vm, listRepo, templateRepo, contactRepo, recDao, undoStack, nudgeScheduler)
    }

    private data class Setup(
        val vm: ListConfigViewModel,
        val listRepo: FakeListRepository,
        val templateRepo: FakeRuleTemplateRepository,
        val contactRepo: FakeContactRepository,
        val recDao: RecordingListMembershipDao,
        val undoStack: UndoStack,
        val nudgeScheduler: RecordingNudgeScheduler = RecordingNudgeScheduler(),
    )

    // ────────────────────────────────────────────────────────────────────────
    // Test 1 — LIST-06: rule-template change writes ruleTemplateId.
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `setRuleTemplate resolves the kind and writes ruleTemplateId`() = runTest {
        // The setter takes a RuleKind and resolves the seeded
        // row via RuleTemplateRepository.getByKind; an unknown template id is
        // structurally impossible from the UI side.
        val (vm, listRepo, _, _) = fixture()
        vm.setRuleTemplate(RuleKind.LATE_NIGHT)
        // viewModelScope.launch under UnconfinedTestDispatcher runs eagerly.
        // The VM uses the granular `updateRuleTemplate(listId, templateId)`
        // setter on ListRepository (not `update(ListEntity)`) — assert against
        // the matching capture list on the fake.
        val updated = listRepo.updateRuleTemplateCalls.lastOrNull()
        assertNotNull(updated, "expected listRepo.updateRuleTemplate() to have been called")
        assertEquals(1L, updated.first, "listId should be the seeded list")
        assertEquals(2L, updated.second, "ruleTemplateId should resolve to the LATE_NIGHT row")
    }

    @Test
    fun `setRuleTemplate surfaces a snackbar when the seed row is missing`() = runTest {
        // The old silent `?: return` is gone: a missing seed row
        // (the only remaining failure mode) throws inside runMutation and
        // surfaces as the standard failure snackbar.
        val s = fixture(templates = emptyList())
        s.vm.snackbarEvents.test(timeout = 2.seconds) {
            s.vm.setRuleTemplate(RuleKind.ENERGIZE)
            val event = awaitItem()
            assertEquals(SnackbarEvent("Couldn't update list"), event)
            cancel()
        }
        assertTrue(
            s.listRepo.updateRuleTemplateCalls.isEmpty(),
            "no write when the template row cannot be resolved",
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // Rule-correctness fix — switching templates clears the per-list params
    // override; re-selecting the active template preserves it. Without the
    // clear, resolveRuleParams kept returning the old template's tuning and
    // the switch was a silent no-op.
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `setRuleTemplate clears params override when switching templates`() = runTest {
        val overrideJson = json.encodeToString(
            RuleParams.serializer(),
            RuleParams.KeepInTouch().withIntervalHours(30 * 24),
        )
        val list = listFixture(
            id = 1L,
            name = "Inner orbit",
            type = ListType.STATIC,
            ruleTemplateId = 1L,
            ruleParamsOverrideJson = overrideJson,
        )
        val (vm, listRepo, _, _) = fixture(list = list)
        vm.setRuleTemplate(RuleKind.LATE_NIGHT)
        val cleared = listRepo.setRuleParamsOverrideJsonCalls.lastOrNull()
        assertNotNull(cleared, "expected setRuleParamsOverrideJson to be called on switch")
        assertEquals(1L, cleared.first)
        assertNull(cleared.second, "switching templates must clear the override")
        val updated = listRepo.updateRuleTemplateCalls.last()
        assertEquals(1L to 2L, updated)
    }

    @Test
    fun `setRuleTemplate preserves override when re-selecting the current template`() = runTest {
        val overrideJson = json.encodeToString(
            RuleParams.serializer(),
            RuleParams.KeepInTouch().withIntervalHours(30 * 24),
        )
        val list = listFixture(
            id = 1L,
            name = "Inner orbit",
            type = ListType.STATIC,
            ruleTemplateId = 1L,
            ruleParamsOverrideJson = overrideJson,
        )
        val (vm, listRepo, _, _) = fixture(list = list)
        vm.setRuleTemplate(RuleKind.KEEP_IN_TOUCH)
        assertTrue(
            listRepo.setRuleParamsOverrideJsonCalls.isEmpty(),
            "re-selecting the active template must not clear the user's tuning",
        )
        assertTrue(
            listRepo.updateRuleTemplateCalls.isEmpty(),
            "re-selecting the active template is a write no-op",
        )
    }

    @Test
    fun `uiState decodes to the new template defaults after a switch clears the override`() = runTest {
        val overrideJson = json.encodeToString(
            RuleParams.serializer(),
            RuleParams.KeepInTouch().withIntervalHours(30 * 24),
        )
        val list = listFixture(
            id = 1L,
            name = "Inner orbit",
            type = ListType.STATIC,
            ruleTemplateId = 1L,
            ruleParamsOverrideJson = overrideJson,
        )
        val (vm, _, _, _) = fixture(list = list)
        vm.uiState.test(timeout = 2.seconds) {
            val initial = awaitReady { it.ruleParams is RuleParams.KeepInTouch }
            assertEquals(
                30 * 24,
                (initial.ruleParams as RuleParams.KeepInTouch).cooldownMinHours,
                "before the switch, the override carries the tuned interval",
            )

            vm.setRuleTemplate(RuleKind.LATE_NIGHT)

            val after = awaitReady { it.ruleKind == RuleKind.LATE_NIGHT }
            assertEquals(
                RuleParams.LateNight(),
                after.ruleParams,
                "after the switch, the cleared override decodes through to the template defaults",
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** Awaits the next [ListConfigUiState.Ready] emission matching [predicate]. */
    private suspend fun ReceiveTurbine<ListConfigUiState>.awaitReady(
        predicate: (ListConfigUiState.Ready) -> Boolean,
    ): ListConfigUiState.Ready {
        while (true) {
            val state = awaitItem()
            if (state is ListConfigUiState.Ready && predicate(state)) return state
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 2 — LIST-04: setRuleParamsOverrideJson writes the override column.
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `setRuleParamsOverrideJson writes override column`() = runTest {
        val (vm, listRepo, _, _) = fixture()
        val override = RuleParams.KeepInTouch(cooldownMinHours = 720)
        val encoded = json.encodeToString(RuleParams.serializer(), override)
        vm.setRuleParamsOverrideJson(encoded)
        val (writtenListId, writtenJson) = listRepo.setRuleParamsOverrideJsonCalls.last()
        assertEquals(1L, writtenListId)
        assertNotNull(writtenJson)
        val decoded = json.decodeFromString(RuleParams.serializer(), writtenJson)
        assertTrue(decoded is RuleParams.KeepInTouch, "override decodes to KeepInTouch")
        assertEquals(720, decoded.cooldownMinHours, "cooldownMinHours round-trips")
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 3 — LIST-05: active-hours window writes LocalTime pair, including
    // the null/null "always active" case.
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `setActiveHours writes LocalTime pair including null`() = runTest {
        val (vm, listRepo, _, _) = fixture()
        // Overnight pair — assert via the granular `updateActiveHoursCalls`
        // capture list (the VM uses the per-field setter, not the broad
        // `update(ListEntity)` setter).
        vm.setActiveHours(LocalTime.of(21, 0), LocalTime.of(2, 0))
        val first = listRepo.updateActiveHoursCalls.last()
        assertEquals(1L, first.first, "listId should be the seeded list")
        assertEquals(LocalTime.of(21, 0), first.second)
        assertEquals(LocalTime.of(2, 0), first.third)
        // Always active — both null
        vm.setActiveHours(null, null)
        val second = listRepo.updateActiveHoursCalls.last()
        assertNull(second.second)
        assertNull(second.third)
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 4 — LIST-05: notifications flag flips on the persisted entity.
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `setNotificationsEnabled flips boolean`() = runTest {
        val (vm, listRepo, _, _) = fixture()
        vm.setNotificationsEnabled(false)
        val updated = listRepo.updateNotificationsEnabledCalls.last()
        assertEquals(1L, updated.first, "listId should be the seeded list")
        assertEquals(false, updated.second)
        vm.setNotificationsEnabled(true)
        val updatedAgain = listRepo.updateNotificationsEnabledCalls.last()
        assertEquals(true, updatedAgain.second)
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 5 — SMART-06: setSmartRuleJson round-trips through polymorphic
    // JSON (classDiscriminator = "type", per JsonProvider).
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `setSmartRuleJson round-trips through polymorphic json`() = runTest {
        val smartList = listFixture(
            id = 1L,
            name = "Recently added, not called",
            type = ListType.SMART,
            ruleTemplateId = null,
            smartRuleJson = json.encodeToString(
                SmartListRule.serializer(),
                SmartListRule.RecentlyAddedNotCalled(daysWindow = 30),
            ),
        )
        val (vm, listRepo, _, _) = fixture(list = smartList)
        val updated = SmartListRule.RecentlyAddedNotCalled(daysWindow = 60)
        val encoded = json.encodeToString(SmartListRule.serializer(), updated)
        vm.setSmartRuleJson(encoded)
        val (writtenListId, writtenJson) = listRepo.setSmartRuleJsonCalls.last()
        assertEquals(1L, writtenListId)
        assertNotNull(writtenJson)
        val decoded = json.decodeFromString(SmartListRule.serializer(), writtenJson)
        assertTrue(decoded is SmartListRule.RecentlyAddedNotCalled)
        assertEquals(60, decoded.daysWindow)
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 6 — LIST-04: VM resolves override into Ready.ruleParams (override
    // wins over template default; mirrors OverrideResolver behaviour at the
    // VM boundary).
    // ────────────────────────────────────────────────────────────────────────

    // ────────────────────────────────────────────────────────────────────────
    // Test 7 — LIST-08: confirmConvert dispatches the listId to
    // ListRepository.convertSmartToStatic. Atomicity is owned by the repo
    // @Transaction wrap; this test only verifies the VM bridge.
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `confirmConvert dispatches listId to convertSmartToStatic`() = runTest {
        val smartList = listFixture(
            id = 1L,
            name = "Recently added, not called",
            type = ListType.SMART,
            ruleTemplateId = null,
            smartRuleJson = json.encodeToString(
                SmartListRule.serializer(),
                SmartListRule.RecentlyAddedNotCalled(daysWindow = 30),
            ),
        )
        val (vm, listRepo, _, _) = fixture(list = smartList)
        vm.confirmConvert()
        // viewModelScope.launch under UnconfinedTestDispatcher runs eagerly.
        assertEquals(
            listOf(1L),
            listRepo.convertSmartToStaticCalls,
            "confirmConvert should dispatch the listId exactly once",
        )
    }

    @Test
    fun `confirmConvert is a no-op when listId did not parse`() = runTest {
        val (vm, listRepo, _, _) = fixture(savedStateListId = "not-a-number")
        vm.confirmConvert()
        assertTrue(
            listRepo.convertSmartToStaticCalls.isEmpty(),
            "no convert dispatch when listId is null",
        )
    }

    @Test
    fun `vm resolves override in uiState`() = runTest {
        val overrideJson = json.encodeToString(
            RuleParams.serializer(),
            RuleParams.KeepInTouch(cooldownMinHours = 720),
        )
        val list = listFixture(
            id = 1L,
            name = "Overridden",
            type = ListType.STATIC,
            ruleTemplateId = 1L,
            ruleParamsOverrideJson = overrideJson,
        )
        val (vm, _, _, _) = fixture(list = list)
        vm.uiState.test(timeout = 2.seconds) {
            val state = awaitItem()
            assertTrue(state is ListConfigUiState.Ready, "expected Ready, got $state")
            val params = state.ruleParams
            assertTrue(params is RuleParams.KeepInTouch, "ruleParams resolves to KeepInTouch")
            assertEquals(
                720,
                params.cooldownMinHours,
                "override beats template default — Ready.ruleParams carries the override value",
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // The 20-member projection cap is gone: the count must be the true total
    // and every member must be reachable for removal (rows 21+ were unremovable
    // under the old cap).
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `members projection is uncapped and carries the true total`() = runTest {
        val s = fixture()
        val ids = (1L..25L)
        s.contactRepo.seed(ids.map { contactFixture(id = it) })
        s.listRepo.seedMemberships(ids.map { membershipFixture(contactId = it, listId = 1L) })

        s.vm.uiState.test(timeout = 2.seconds) {
            while (true) {
                val state = awaitItem()
                if (state is ListConfigUiState.Ready && state.members.size == 25) {
                    assertEquals(
                        ids.toList(),
                        state.members.map { it.id },
                        "all 25 members projected, id ASC — none truncated",
                    )
                    break
                }
            }
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // F-6 — member-preview inline remove + Undo flow.
    // Tests 8–13 cover onRemoveMember dispatch, snackbar emission, undo-stack
    // push, onUndo replay through the use case's inverse, the empty-stack
    // no-op, and the unparsed-listId no-op.
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `onRemoveMember dispatches BulkRemoveFromListUseCase for the given contact`() = runTest {
        val s = fixture()
        s.listRepo.seedMemberships(
            listOf(
                membershipFixture(contactId = 5L, listId = 1L),
                membershipFixture(contactId = 9L, listId = 1L),
            ),
        )
        s.recDao.seed(
            membershipFixture(contactId = 5L, listId = 1L),
            membershipFixture(contactId = 9L, listId = 1L),
        )
        s.vm.onRemoveMember(contactId = 5L, contactName = "Mom")
        assertEquals(1, s.recDao.removeCalls.size, "expected one removeAll dispatch")
        val call = s.recDao.removeCalls.last()
        assertEquals(1L, call.fromListId)
        assertEquals(listOf(5L), call.ids)
    }

    @Test
    fun `onRemoveMember emits snackbar event with action label Undo`() = runTest {
        val s = fixture()
        s.listRepo.seedMemberships(listOf(membershipFixture(contactId = 5L, listId = 1L)))
        s.recDao.seed(membershipFixture(contactId = 5L, listId = 1L))
        s.vm.snackbarEvents.test(timeout = 2.seconds) {
            s.vm.onRemoveMember(contactId = 5L, contactName = "Mom")
            val event = awaitItem()
            assertEquals(SnackbarEvent("Removed Mom", "Undo"), event)
            cancel()
        }
    }

    @Test
    fun `onRemoveMember puts an inverse on UndoStack`() = runTest {
        val s = fixture()
        s.listRepo.seedMemberships(listOf(membershipFixture(contactId = 5L, listId = 1L)))
        s.recDao.seed(membershipFixture(contactId = 5L, listId = 1L))
        s.vm.onRemoveMember(contactId = 5L, contactName = "Mom")
        val pending = s.undoStack.peek()
        assertNotNull(pending, "expected a PendingUndo on the stack after onRemoveMember")
        assertEquals("Removed 1 from Inner orbit", pending.label)
    }

    @Test
    fun `onUndo replays the use case's inverse closure`() = runTest {
        val s = fixture()
        s.listRepo.seedMemberships(listOf(membershipFixture(contactId = 5L, listId = 1L)))
        s.recDao.seed(membershipFixture(contactId = 5L, listId = 1L))
        s.vm.onRemoveMember(contactId = 5L, contactName = "Mom")
        // Drop forward dispatches so the next assertion isolates the inverse.
        s.recDao.clearCalls()
        s.vm.onUndo()
        assertEquals(1, s.recDao.insertCalls.size, "expected one insertAll from the inverse closure")
        val reinserted = s.recDao.insertCalls.last().memberships
        assertEquals(1, reinserted.size)
        assertEquals(5L, reinserted[0].contactId)
        assertEquals(1L, reinserted[0].listId)
    }

    @Test
    fun `onUndo is a no-op when stack is empty`() = runTest {
        val s = fixture()
        s.vm.onUndo()
        assertTrue(s.recDao.insertCalls.isEmpty(), "no insertAll when undo stack is empty")
        assertTrue(s.recDao.removeCalls.isEmpty(), "no removeAll when undo stack is empty")
        assertNull(s.undoStack.peek(), "stack remains empty after a no-op onUndo")
    }

    @Test
    fun `onRemoveMember is a no-op when listId did not parse`() = runTest {
        val s = fixture(savedStateListId = "not-a-number")
        s.vm.onRemoveMember(contactId = 5L, contactName = "Mom")
        assertTrue(s.recDao.removeCalls.isEmpty(), "no remove dispatch when listId is null")
        assertNull(s.undoStack.peek(), "no undo entry when listId is null")
    }

    @Test
    fun `setName trims whitespace and dispatches to ListRepository updateName`() = runTest {
        val (vm, listRepo, _, _) = fixture()
        val before = listRepo.updateNameCalls.size
        vm.setName("  Inner orbit  ")
        assertEquals(before + 1, listRepo.updateNameCalls.size)
        val (writtenId, writtenName) = listRepo.updateNameCalls.last()
        assertEquals(1L, writtenId)
        assertEquals("Inner orbit", writtenName)
    }

    @Test
    fun `setName drops blank input as a no-op`() = runTest {
        val (vm, listRepo, _, _) = fixture()
        val before = listRepo.updateNameCalls.size
        vm.setName("   ")
        assertEquals(before, listRepo.updateNameCalls.size, "blank input must not dispatch updateName")
    }

    @Test
    fun `setName drops empty string as a no-op`() = runTest {
        val (vm, listRepo, _, _) = fixture()
        val before = listRepo.updateNameCalls.size
        vm.setName("")
        assertEquals(before, listRepo.updateNameCalls.size, "empty input must not dispatch updateName")
    }

    @Test
    fun `setName dispatches once per non-blank call`() = runTest {
        val (vm, listRepo, _, _) = fixture()
        val before = listRepo.updateNameCalls.size
        vm.setName("Foo")
        vm.setName("Bar")
        assertEquals(before + 2, listRepo.updateNameCalls.size)
        assertEquals("Foo", listRepo.updateNameCalls[before].second)
        assertEquals("Bar", listRepo.updateNameCalls[before + 1].second)
    }

    @Test
    fun `setName is a no-op when listId did not parse`() = runTest {
        val s = fixture(savedStateListId = "not-a-number")
        val before = s.listRepo.updateNameCalls.size
        s.vm.setName("Inner orbit")
        assertEquals(before, s.listRepo.updateNameCalls.size, "no dispatch when listId is null")
    }

    // ────────────────────────────────────────────────────────────────────────
    // NOTIF-10/11 — onNudgeScheduleChange writes JSON via setNudgeScheduleJson
    // AND calls nudgeScheduler.schedule with the list's activeHoursStart forwarded.
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `onNudgeScheduleChange writes JSON and reschedules with activeHoursStart`() = runTest {
        // Seed a list with a known activeHoursStart so the D-09 forwarding assertion
        // is meaningful — null would vacuously pass even if forwarding were broken.
        val list = listFixture(
            id = 1L,
            name = "Inner orbit",
            type = ListType.STATIC,
            ruleTemplateId = 1L,
            activeHoursStart = LocalTime.of(9, 0),
            activeHoursEnd = LocalTime.of(17, 0),
        )
        val s = fixture(list = list)

        val newSchedule = NudgeSchedule(
            days = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
            times = listOf(LocalTime.of(8, 30)),
        )
        s.vm.onNudgeScheduleChange(newSchedule)

        // Assert Room write
        val write = s.listRepo.setNudgeScheduleJsonCalls.lastOrNull()
        assertNotNull(write, "expected setNudgeScheduleJson to be called")
        assertEquals(1L, write.first, "listId should be the seeded list")
        val decoded = json.decodeFromString(NudgeSchedule.serializer(), write.second!!)
        assertEquals(newSchedule, decoded, "JSON round-trips to the supplied schedule")

        // Assert reschedule call with activeHoursStart forwarded (D-09)
        val sched = s.nudgeScheduler.scheduleCalls.lastOrNull()
        assertNotNull(sched, "expected nudgeScheduler.schedule() to be called")
        assertEquals(1L, sched.listId)
        assertEquals(newSchedule, sched.schedule)
        assertEquals(
            LocalTime.of(9, 0),
            sched.activeHoursStart,
            "activeHoursStart must be forwarded to preserve the D-09 implicit slot",
        )
    }

    @Test
    fun `onNudgeScheduleChange is a no-op when listId did not parse`() = runTest {
        val s = fixture(savedStateListId = "not-a-number")
        s.vm.onNudgeScheduleChange(NudgeSchedule.DEFAULT)
        assertTrue(
            s.listRepo.setNudgeScheduleJsonCalls.isEmpty(),
            "no write dispatch when listId is null",
        )
        assertTrue(
            s.nudgeScheduler.scheduleCalls.isEmpty(),
            "no reschedule when listId is null",
        )
    }
}
