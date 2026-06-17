package app.orbit.widget

import app.orbit.data.dao.RecordingListMembershipDao
import app.orbit.data.db.TransactionRunner
import app.orbit.data.entity.CallDirection
import app.orbit.data.entity.CallSource
import app.orbit.data.entity.ListMembershipEntity
import app.orbit.domain.FakeCallEventRepository
import app.orbit.domain.FakeContactRepository
import app.orbit.domain.FakeListRepository
import app.orbit.domain.FakeRuleTemplateRepository
import app.orbit.domain.JsonProvider
import app.orbit.domain.WidgetRefreshTrigger
import app.orbit.domain.callEventFixture
import app.orbit.domain.clock.TestClock
import app.orbit.domain.contactFixture
import app.orbit.domain.listFixture
import app.orbit.domain.membershipFixture
import app.orbit.domain.ruleTemplateFixture
import app.orbit.domain.usecase.BulkRemoveFromListUseCase
import app.orbit.domain.usecase.IgnoreContactUseCase
import app.orbit.domain.usecase.MarkCalledUseCase
import app.orbit.domain.usecase.MoveContactsUseCase
import app.orbit.domain.usecase.SkipContactUseCase
import java.time.Instant
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Widget refresh trigger assertions — WIDGET-06.
 *
 * Plain JVM test (no Robolectric) — asserts against a [RecordingWidgetRefreshTrigger]
 * fake that counts [WidgetRefreshTrigger.scheduleRefresh] calls. No WorkManager or
 * Android context needed here; the seam is the honest boundary.
 *
 * The real WorkManager enqueue (KEEP+30s debounce) is already covered honestly by
 * [WidgetUpdateSchedulerTest] (Robolectric + work-testing). These tests only prove
 * the use-case→trigger wiring.
 *
 * Covers WIDGET-06: update triggers wired at the use-case layer — MarkCalledUseCase,
 * SkipContactUseCase, MoveContactsUseCase, IgnoreContactUseCase each call
 * the injected WidgetRefreshTrigger seam.
 */
class UpdateTriggersTest {

    private val T0: Instant = Instant.parse("2026-01-01T12:00:00Z")

    /** Counts scheduleRefresh() invocations for assertion. */
    private class RecordingWidgetRefreshTrigger : WidgetRefreshTrigger {
        val scheduleCalls: MutableList<Unit> = mutableListOf()
        override fun scheduleRefresh() { scheduleCalls += Unit }
    }

    /** Pass-through TransactionRunner — executes the block directly on the calling coroutine. */
    private val passThruTx = object : TransactionRunner {
        override suspend fun <T> withTransaction(block: suspend () -> T): T = block()
    }

    // ─── Use-case-layer WidgetRefreshTrigger calls ──────────────

    /** MarkCalledUseCase calls the injected WidgetRefreshTrigger.scheduleRefresh() seam. */
    @Test
    fun markCalled_schedulesWidgetRefresh() = runTest {
        val trigger = RecordingWidgetRefreshTrigger()
        val contactRepo = FakeContactRepository(listOf(contactFixture(id = 1L)))
        val listRepo = FakeListRepository(listOf(listFixture(id = 10L, ruleTemplateId = 1L)))
        listRepo.seedMemberships(listOf(membershipFixture(contactId = 1L, listId = 10L)))
        val callEventRepo = FakeCallEventRepository()
        val templateRepo = FakeRuleTemplateRepository(listOf(ruleTemplateFixture(id = 1L)))
        val useCase = MarkCalledUseCase(
            contactRepo = contactRepo,
            listRepo = listRepo,
            callEventRepo = callEventRepo,
            ruleTemplateRepo = templateRepo,
            clock = TestClock(T0),
            json = JsonProvider.json,
            widgetRefreshTrigger = trigger,
        )
        val event = callEventFixture(
            id = 100L,
            contactId = 1L,
            occurredAt = T0,
            direction = CallDirection.OUTGOING,
            durationSeconds = 60,
            source = CallSource.CALL_LOG,
        )

        useCase(contactId = 1L, callEvent = event)

        assertEquals(1, trigger.scheduleCalls.size, "MarkCalledUseCase must call scheduleRefresh() once")
    }

    /** SkipContactUseCase calls the injected WidgetRefreshTrigger.scheduleRefresh() seam. */
    @Test
    fun skipContact_schedulesWidgetRefresh() = runTest {
        val trigger = RecordingWidgetRefreshTrigger()
        val contactRepo = FakeContactRepository(listOf(contactFixture(id = 1L)))
        val listRepo = FakeListRepository(listOf(listFixture(id = 10L, ruleTemplateId = 1L)))
        listRepo.seedMemberships(listOf(membershipFixture(contactId = 1L, listId = 10L)))
        val templateRepo = FakeRuleTemplateRepository(listOf(ruleTemplateFixture(id = 1L)))
        val useCase = SkipContactUseCase(
            contactRepo = contactRepo,
            listRepo = listRepo,
            ruleTemplateRepo = templateRepo,
            clock = TestClock(T0),
            json = JsonProvider.json,
            widgetRefreshTrigger = trigger,
        )

        useCase(contactId = 1L, listId = null)

        assertEquals(1, trigger.scheduleCalls.size, "SkipContactUseCase must call scheduleRefresh() once")
    }

    /** MoveContactsUseCase calls the injected WidgetRefreshTrigger.scheduleRefresh() seam. */
    @Test
    fun moveContacts_schedulesWidgetRefresh() = runTest {
        val trigger = RecordingWidgetRefreshTrigger()
        val listRepo = FakeListRepository(listOf(
            listFixture(id = 10L, name = "Source"),
            listFixture(id = 20L, name = "Target"),
        ))
        // Seed a membership so there is something to move.
        listRepo.seedMemberships(listOf(membershipFixture(contactId = 1L, listId = 10L)))
        val recDao = RecordingListMembershipDao()
        // Seed a membership row in the DAO so the snapshot path finds the source row.
        recDao.seed(ListMembershipEntity(contactId = 1L, listId = 10L, addedAt = T0))
        // Build a minimal ListDao stub that reports both lists as valid + non-archived.
        val listDaoStub = app.orbit.data.dao.TestListDaoStub(
            lists = listOf(
                app.orbit.data.entity.ListEntity(id = 10L, name = "Source", sortOrder = 0),
                app.orbit.data.entity.ListEntity(id = 20L, name = "Target", sortOrder = 1),
            ),
        )
        val useCase = MoveContactsUseCase(
            txRunner = passThruTx,
            listMembershipDao = recDao,
            listDao = listDaoStub,
            listRepo = listRepo,
            clock = TestClock(T0),
            widgetRefreshTrigger = trigger,
        )

        useCase(fromListId = 10L, toListId = 20L, contactIds = listOf(1L), targetListName = "Target")

        assertEquals(1, trigger.scheduleCalls.size, "MoveContactsUseCase must call scheduleRefresh() once")
    }

    /** IgnoreContactUseCase calls the injected WidgetRefreshTrigger.scheduleRefresh() seam. */
    @Test
    fun ignoreContact_schedulesWidgetRefresh() = runTest {
        val trigger = RecordingWidgetRefreshTrigger()
        val contactRepo = FakeContactRepository(listOf(contactFixture(id = 1L)))
        val membershipDao = RecordingListMembershipDao()
        val listRepo = FakeListRepository()
        val useCase = IgnoreContactUseCase(
            txRunner = passThruTx,
            contactRepo = contactRepo,
            listMembershipDao = membershipDao,
            listRepo = listRepo,
            clock = TestClock(T0),
            widgetRefreshTrigger = trigger,
        )

        useCase(contactId = 1L, contactName = "Alex Chen")

        assertEquals(1, trigger.scheduleCalls.size, "IgnoreContactUseCase must call scheduleRefresh() once")
    }

    // ─── Review WR-02: Undo paths must also refresh the widget ──────────────

    /**
     * The ignore inverse (snackbar Undo) flips who-is-due back, so it must
     * fire the trigger too — otherwise the widget keeps showing the ignored
     * state until the hourly sweep (review WR-02, sharpest asymmetry case).
     */
    @Test
    fun ignoreContactUndo_schedulesWidgetRefresh() = runTest {
        val trigger = RecordingWidgetRefreshTrigger()
        val contactRepo = FakeContactRepository(listOf(contactFixture(id = 1L)))
        val membershipDao = RecordingListMembershipDao()
        val listRepo = FakeListRepository()
        val useCase = IgnoreContactUseCase(
            txRunner = passThruTx,
            contactRepo = contactRepo,
            listMembershipDao = membershipDao,
            listRepo = listRepo,
            clock = TestClock(T0),
            widgetRefreshTrigger = trigger,
        )

        val result = useCase(contactId = 1L, contactName = "Alex Chen")
        result.inverse()

        assertEquals(
            2, trigger.scheduleCalls.size,
            "IgnoreContactUseCase inverse must call scheduleRefresh() (forward + undo = 2)",
        )
    }

    /**
     * The move inverse (snackbar Undo) restores the source memberships, so
     * who-is-due changes again — the trigger must mirror the forward path
     * (review WR-02).
     */
    @Test
    fun moveContactsUndo_schedulesWidgetRefresh() = runTest {
        val trigger = RecordingWidgetRefreshTrigger()
        val listRepo = FakeListRepository(listOf(
            listFixture(id = 10L, name = "Source"),
            listFixture(id = 20L, name = "Target"),
        ))
        listRepo.seedMemberships(listOf(membershipFixture(contactId = 1L, listId = 10L)))
        val recDao = RecordingListMembershipDao()
        recDao.seed(ListMembershipEntity(contactId = 1L, listId = 10L, addedAt = T0))
        val listDaoStub = app.orbit.data.dao.TestListDaoStub(
            lists = listOf(
                app.orbit.data.entity.ListEntity(id = 10L, name = "Source", sortOrder = 0),
                app.orbit.data.entity.ListEntity(id = 20L, name = "Target", sortOrder = 1),
            ),
        )
        val useCase = MoveContactsUseCase(
            txRunner = passThruTx,
            listMembershipDao = recDao,
            listDao = listDaoStub,
            listRepo = listRepo,
            clock = TestClock(T0),
            widgetRefreshTrigger = trigger,
        )

        val result = useCase(fromListId = 10L, toListId = 20L, contactIds = listOf(1L), targetListName = "Target")
        result.inverse()

        assertEquals(
            2, trigger.scheduleCalls.size,
            "MoveContactsUseCase inverse must call scheduleRefresh() (forward + undo = 2)",
        )
    }
}
