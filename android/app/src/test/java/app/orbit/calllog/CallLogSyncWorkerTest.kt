package app.orbit.calllog

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import app.orbit.data.AppPrefs
import app.orbit.data.android.CallLogReader
import app.orbit.data.android.CallRow
import app.orbit.domain.JsonProvider
import app.orbit.domain.clock.Clock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CALL-04 + CALL-06 tests for [CallLogSyncWorker].
 *
 * Test pattern: TestListenableWorkerBuilder + a custom WorkerFactory that
 * constructs the worker with a [FakeReconciler] (overrides reconcile to
 * capture sinceMs) and a [FakeCallLogReader] (overrides readAll to return
 * canned rows). The injected [AppPrefs] is real (Robolectric DataStore).
 *
 * @Config(application = Application::class) bypasses OrbitApp.onCreate so we
 * don't need a Hilt graph for the test. @Config(sdk = [33]) avoids
 * Robolectric's targetSdk=35 ceiling (project convention; verified across
 * existing AppPrefsTest, SettingsViewModelTest).
 *
 * `FakeReconciler` constructor uses real instances of
 * `StubMarkCalledUseCase` (from Fakes.kt) and `EpochClock` — no eager
 * `throw NotImplementedError(...)` in any constructor-argument position.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class CallLogSyncWorkerTest {

    private lateinit var context: android.content.Context
    private lateinit var appPrefs: AppPrefs
    private val fakeReconciler = FakeReconciler()
    private val fakeReader = FakeCallLogReader()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Application>()
        appPrefs = AppPrefs(context)
        // Reset capture state between tests
        fakeReconciler.callCount = 0
        fakeReconciler.lastSinceMs = -1L
        fakeReader.rows = emptyList()
        // Reset prefs to a known baseline
        runBlocking { appPrefs.setLastCallLogSyncAt(0L) }
    }

    private fun buildWorker(inputData: Data = Data.EMPTY): CallLogSyncWorker =
        TestListenableWorkerBuilder<CallLogSyncWorker>(context)
            .setInputData(inputData)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: android.content.Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = CallLogSyncWorker(
                    appContext, workerParameters,
                    fakeReconciler, fakeReader.asReader(appContext), appPrefs,
                )
            })
            .build()

    // CALL-06: revoked permission → Result.success() (never Result.failure())
    @Test
    fun permission_revoked_returns_success_no_reconcile() = runTest {
        // Robolectric default: no permission granted.
        val result = buildWorker().doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(0, fakeReconciler.callCount, "reconcile should NOT run when permission revoked")
    }

    // Permission granted path: reconciler runs + lastCallLogSyncAt updates.
    @Test
    fun permission_granted_triggers_reconcile_and_updates_lastSync() = runTest {
        Shadows.shadowOf(context as Application).grantPermissions(Manifest.permission.READ_CALL_LOG)
        fakeReader.rows = listOf(CallRow("+14155551234", 1000L, 30, 2))
        appPrefs.setLastCallLogSyncAt(0L)

        val before = System.currentTimeMillis()
        val result = buildWorker().doWork()
        val after = System.currentTimeMillis()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, fakeReconciler.callCount)
        val lastSync = appPrefs.lastCallLogSyncAt.first()
        assertTrue(
            lastSync in before..after,
            "lastCallLogSyncAt not updated; got $lastSync (expected in $before..$after)",
        )
    }

    // CALL-04: fullResync flag uses the full 90-day window, ignoring lastSync.
    @Test
    fun fullResync_flag_ignores_lastCallLogSyncAt() = runTest {
        Shadows.shadowOf(context as Application).grantPermissions(Manifest.permission.READ_CALL_LOG)
        val recent = System.currentTimeMillis() - 60_000L
        appPrefs.setLastCallLogSyncAt(recent)

        buildWorker(workDataOf(ContentObserverController.KEY_FULL_RESYNC to true)).doWork()

        // fullResync should produce sinceMs = now - 90*DAY_MS (ignoring the recent lastSync).
        val now = System.currentTimeMillis()
        val expectedWindowStart = now - 90L * 24 * 60 * 60 * 1000
        // Allow a 5s window to absorb test execution latency.
        assertTrue(
            fakeReconciler.lastSinceMs in (expectedWindowStart - 5000L)..(expectedWindowStart + 5000L),
            "fullResync sinceMs=${fakeReconciler.lastSinceMs}, expected near $expectedWindowStart",
        )
    }

    // CALL-04 incremental path: sinceMs = max(lastSync, windowStart) for the
    // common case where lastSync > windowStart, the worker uses lastSync.
    @Test
    fun incremental_sync_uses_max_lastSync_windowStart() = runTest {
        Shadows.shadowOf(context as Application).grantPermissions(Manifest.permission.READ_CALL_LOG)
        val recent = System.currentTimeMillis() - 60_000L
        appPrefs.setLastCallLogSyncAt(recent)

        buildWorker(workDataOf(ContentObserverController.KEY_FULL_RESYNC to false)).doWork()

        // Incremental → sinceMs = max(recent, windowStart) = recent (recent is much newer
        // than now - 90d).
        assertTrue(
            fakeReconciler.lastSinceMs >= recent - 1000L,
            "incremental sinceMs=${fakeReconciler.lastSinceMs}, expected >= $recent",
        )
    }
}

// ============================================================================
// Test-only doubles for CallLogSyncWorker
// ============================================================================
//
// These reuse the throwing-singleton repos from Fakes.kt so we don't replicate
// the `ThrowingContactRepository` etc. MarkCalledUseCase and CallLogReconciler
// are both `open class`, so `FakeReconciler` can subclass and override
// `reconcile` fully.

/**
 * Subclass of [CallLogReconciler] whose `reconcile` overrides without calling
 * super. The five superclass-constructor params are real instances backed by
 * throwing singletons / a no-op stub — none are exercised because the override
 * is total.
 */
private class FakeReconciler : CallLogReconciler(
    contactRepo = ThrowingContactRepository,
    callEventDao = ThrowingCallEventDaoForWorker,
    markCalledUseCase = StubMarkCalledUseCase(),
    phoneNumberNormalizer = PhoneNumberNormalizer(),
    clock = EpochClockForWorker,
) {
    var callCount: Int = 0
    var lastSinceMs: Long = -1L

    override suspend fun reconcile(sinceMs: Long, rows: List<CallRow>): ReconcileSummary {
        callCount++
        lastSinceMs = sinceMs
        return ReconcileSummary.EMPTY
    }
}

private object ThrowingCallEventDaoForWorker : app.orbit.data.dao.CallEventDao {
    override fun observeByContactId(contactId: Long) = throw NotImplementedError()
    override fun observeForContact(contactId: Long, limit: Int) = throw NotImplementedError()
    override fun observeAggregatesForContacts(ids: List<Long>) = throw NotImplementedError()
    override fun observeRecent(limit: Int) = throw NotImplementedError()
    override fun observeForListContacts(listId: Long) = throw NotImplementedError()
    // Worker never exercises the latest-per-contact aggregate.
    override fun observeLatestPerContactInList(listId: Long) = throw NotImplementedError()
    override suspend fun get(id: Long) = throw NotImplementedError()
    override suspend fun insert(event: app.orbit.data.entity.CallEventEntity): Long =
        throw NotImplementedError()

    override suspend fun update(event: app.orbit.data.entity.CallEventEntity): Int =
        throw NotImplementedError()

    override suspend fun delete(event: app.orbit.data.entity.CallEventEntity): Int =
        throw NotImplementedError()

    override suspend fun existsAt(contactId: Long, occurredAt: Instant): Int =
        throw NotImplementedError()

    // Notes surface — worker never exercises; throw on access.
    override suspend fun latestUnnotedOutgoing(since: Instant): app.orbit.data.entity.CallEventEntity? =
        throw NotImplementedError()

    // Worker never exercises; throw on access.
    override fun observeForLog(limit: Int) = throw NotImplementedError()

    override suspend fun getById(id: Long) = throw NotImplementedError()

    override suspend fun snapshotAll(): List<app.orbit.data.entity.CallEventEntity> =
        throw NotImplementedError()

    override fun observeAggregatesAll(): kotlinx.coroutines.flow.Flow<List<app.orbit.data.dao.CallAggRow>> =
        throw NotImplementedError()
}

private val EpochClockForWorker: Clock = object : Clock {
    override fun now(): Instant = Instant.EPOCH
}

@Suppress("unused") // kept for future callers that may want to assert on JsonProvider parity
private val jsonForParityCheck = JsonProvider.json

/**
 * Test double for [CallLogReader]. Subclasses the now-`open` reader and
 * overrides `readAll` to return canned [CallRow] data.
 */
private class FakeCallLogReader {
    var rows: List<CallRow> = emptyList()
    fun asReader(ctx: android.content.Context): CallLogReader = object : CallLogReader(ctx) {
        override fun readAll(lookbackDays: Int): List<CallRow> = rows
    }
}
