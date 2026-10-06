package app.orbit.calllog

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import app.orbit.data.AppPrefs
import app.orbit.data.android.ContactsReader
import app.orbit.data.dao.RecordingContactDao
import app.orbit.data.db.TransactionRunner
import app.orbit.domain.clock.TestClock
import app.orbit.domain.usecase.IngestPhoneContactsUseCase
import app.orbit.domain.usecase.IngestSummary
import app.orbit.testutil.newPrefs
import java.time.Instant
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * onb-2: an ingest that inserted people asks for a full call-log resync, and
 * only then. The reconciler skips calls whose person is not in Room yet, and
 * every later sync is incremental from the watermark, so without this request
 * a call that landed before its person did was never matched: onboarding's
 * count and the suggested first list silently undercounted.
 *
 * Contract:
 *  - inserted > 0 and READ_CALL_LOG held: one `enqueueImmediateSync(true)`
 *  - inserted > 0 without READ_CALL_LOG: nothing (there is no log to read)
 *  - inserted == 0 (a refresh-only pass): nothing; the watermark still covers it
 *
 * The TTL and force paths are `ContactsIngestWorkerTest`'s. Same fixture
 * shape: `TestListenableWorkerBuilder` with a factory handing the worker a
 * counting use case; the resync trigger is a recording lambda, the seam
 * `CallLogResyncTrigger` exists for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ContactsIngestWorkerResyncTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefs: AppPrefs by lazy { tmp.newPrefs(storeScope) }
    private val clock = TestClock(Instant.parse("2026-10-06T12:00:00Z"))
    private val resyncRequests = mutableListOf<Boolean>()

    @After
    fun tearDown() {
        storeScope.cancel()
    }

    /** Returns [summary] without touching the (inert) superclass dependencies. */
    private class StubIngestUseCase(
        context: Context,
        clock: TestClock,
        private val summary: IngestSummary,
    ) : IngestPhoneContactsUseCase(
        contactsReader = ContactsReader(context),
        contactDao = RecordingContactDao(),
        txRunner = object : TransactionRunner {
            override suspend fun <T> withTransaction(block: suspend () -> T): T = block()
        },
        clock = clock,
    ) {
        override suspend fun invoke(): IngestSummary = summary
    }

    private fun buildWorker(summary: IngestSummary): ContactsIngestWorker =
        TestListenableWorkerBuilder<ContactsIngestWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = ContactsIngestWorker(
                    appContext, workerParameters,
                    StubIngestUseCase(appContext, clock, summary), clock, prefs,
                    callLogResync = { fullResync -> resyncRequests += fullResync },
                )
            })
            .build()

    private fun grantCallLog() {
        Shadows.shadowOf(context as Application).grantPermissions(Manifest.permission.READ_CALL_LOG)
    }

    @Test
    fun inserting_people_with_call_log_access_requests_one_full_resync() = runTest {
        grantCallLog()

        val result = buildWorker(IngestSummary(inserted = 12, refreshed = 3, orphaned = 0, restored = 0)).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(listOf(true), resyncRequests, "a full pass, so calls older than the watermark are matched")
    }

    @Test
    fun without_call_log_access_no_resync_is_requested() = runTest {
        val result = buildWorker(IngestSummary(inserted = 12, refreshed = 0, orphaned = 0, restored = 0)).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(emptyList(), resyncRequests)
    }

    @Test
    fun a_pass_that_inserted_nobody_requests_no_resync() = runTest {
        grantCallLog()

        val result = buildWorker(IngestSummary(inserted = 0, refreshed = 5, orphaned = 1, restored = 1)).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(emptyList(), resyncRequests)
    }
}
