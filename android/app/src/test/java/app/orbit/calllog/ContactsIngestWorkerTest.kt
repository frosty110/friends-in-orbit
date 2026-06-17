package app.orbit.calllog

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import app.orbit.data.AppPrefs
import app.orbit.data.android.ContactsReader
import app.orbit.data.dao.RecordingContactDao
import app.orbit.data.db.TransactionRunner
import app.orbit.domain.clock.TestClock
import app.orbit.domain.usecase.IngestPhoneContactsUseCase
import app.orbit.domain.usecase.IngestSummary
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * TTL gating coverage for [ContactsIngestWorker].
 *
 * Contract under test:
 *  - within the 24h TTL, an UNFORCED run skips ingestion (cold-start dedup)
 *  - within the 24h TTL, a FORCED run (observer fire — the address book just
 *    changed) bypasses the TTL and ingests
 *  - past the TTL, an unforced run ingests
 *  - never-ingested (null TTL anchor) runs unconditionally
 *  - a successful run (forced or not) refreshes [AppPrefs.lastContactsIngestedAt]
 *
 * Pattern mirrors [CallLogSyncWorkerTest]: TestListenableWorkerBuilder + a
 * custom WorkerFactory constructing the worker with a counting
 * [StubIngestUseCase] ([IngestPhoneContactsUseCase] was widened to `open` for
 * exactly this seam). AppPrefs is real (Robolectric DataStore).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ContactsIngestWorkerTest {

    private lateinit var context: android.content.Context
    private lateinit var appPrefs: AppPrefs
    private lateinit var stubIngest: StubIngestUseCase
    private val clock = TestClock(Instant.parse("2026-06-09T12:00:00Z"))

    /**
     * Counting stub — overrides invoke() fully; never touches the superclass
     * dependencies (real-but-inert ContactsReader + permissive RecordingContactDao).
     */
    private class StubIngestUseCase(
        context: android.content.Context,
        clock: TestClock,
    ) : IngestPhoneContactsUseCase(
        contactsReader = ContactsReader(context),
        contactDao = RecordingContactDao(),
        txRunner = object : TransactionRunner {
            override suspend fun <T> withTransaction(block: suspend () -> T): T = block()
        },
        clock = clock,
    ) {
        var invocationCount: Int = 0

        override suspend fun invoke(): IngestSummary {
            invocationCount++
            return IngestSummary(inserted = 1, refreshed = 0, orphaned = 0, restored = 0)
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Application>()
        appPrefs = AppPrefs(context)
        stubIngest = StubIngestUseCase(context, clock)
        // Reset the TTL anchor to "never ingested" between tests.
        runBlocking { appPrefs.resetAll() }
    }

    private fun buildWorker(inputData: Data = Data.EMPTY): ContactsIngestWorker =
        TestListenableWorkerBuilder<ContactsIngestWorker>(context)
            .setInputData(inputData)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: android.content.Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = ContactsIngestWorker(
                    appContext, workerParameters,
                    stubIngest, clock, appPrefs,
                )
            })
            .build()

    @Test
    fun within_ttl_unforced_run_skips_ingest() = runTest {
        appPrefs.setLastContactsIngestedAt(clock.now().minus(Duration.ofHours(1)))

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(0, stubIngest.invocationCount, "unforced run inside the TTL must skip")
    }

    @Test
    fun within_ttl_forced_run_bypasses_ttl() = runTest {
        appPrefs.setLastContactsIngestedAt(clock.now().minus(Duration.ofHours(1)))

        val result = buildWorker(
            workDataOf(ContactsIngestWorker.KEY_FORCE to true),
        ).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(
            1, stubIngest.invocationCount,
            "observer-forced run must ingest even inside the TTL (new contacts visible now)",
        )
    }

    @Test
    fun past_ttl_unforced_run_ingests() = runTest {
        appPrefs.setLastContactsIngestedAt(
            clock.now().minus(Duration.ofHours(ContactsIngestWorker.TTL_HOURS + 1)),
        )

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, stubIngest.invocationCount)
    }

    @Test
    fun never_ingested_runs_unconditionally() = runTest {
        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, stubIngest.invocationCount)
    }

    @Test
    fun successful_forced_run_refreshes_ttl_anchor() = runTest {
        appPrefs.setLastContactsIngestedAt(clock.now().minus(Duration.ofHours(1)))

        buildWorker(workDataOf(ContactsIngestWorker.KEY_FORCE to true)).doWork()

        assertEquals(
            clock.now(), appPrefs.lastContactsIngestedAt.first(),
            "forced success must restart the TTL window",
        )
    }
}
