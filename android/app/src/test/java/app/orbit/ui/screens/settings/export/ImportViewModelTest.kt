package app.orbit.ui.screens.settings.export

import android.app.Application
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.orbit.data.db.OrbitDatabase
import app.orbit.domain.export.ExportEnvelope
import app.orbit.domain.export.ImportFormatException
import app.orbit.domain.export.ImportPayload
import app.orbit.domain.export.ImportService
import app.orbit.domain.export.ImportSummary
import app.orbit.domain.export.ImportVersionTooNewException
import app.orbit.testutil.MainDispatcherRule
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ImportViewModel] state-machine + snackbar-mapping tests, plus the guards
 * that keep a second tap or a late SAF result from restarting a flow that is
 * already past that point (SET-05).
 * The domain crypto/apply behavior is pinned by
 * [app.orbit.domain.export.ImportServiceTest]; here the service is faked so
 * each UI-facing transition can be asserted in isolation. The app scope the
 * apply runs on is `Dispatchers.Unconfined`, so it completes inline.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ImportViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val context: android.content.Context get() =
        ApplicationProvider.getApplicationContext()

    private val uri: Uri = Uri.parse("content://test/backup.bin")
    private val otherUri: Uri = Uri.parse("content://test/other.bin")
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private lateinit var db: OrbitDatabase

    private fun emptyPayload(): ImportPayload = ImportPayload(
        envelopeVersion = ExportEnvelope.CURRENT_VERSION,
        ruleTemplates = emptyList(),
        contacts = emptyList(),
        lists = emptyList(),
        contactPhones = emptyList(),
        memberships = emptyList(),
        callEvents = emptyList(),
        notes = emptyList(),
    )

    /** Fake service: scripted [read] outcome (optionally held at [readGate]) + apply-counting. */
    private inner class FakeImportService(
        private val readOutcome: Result<ImportPayload>,
    ) : ImportService(context, db) {
        var applyCount: Int = 0
        var readCount: Int = 0
        var lastReadUri: Uri? = null
        var readGate: CompletableDeferred<Unit>? = null
        override suspend fun read(uri: Uri, passphrase: CharArray): ImportPayload {
            readGate?.await()
            readCount++
            lastReadUri = uri
            return readOutcome.getOrThrow()
        }
        override suspend fun apply(payload: ImportPayload): ImportSummary {
            applyCount++
            return ImportSummary(0, 0, 0, 0)
        }
    }

    private fun buildVm(readOutcome: Result<ImportPayload>): Pair<ImportViewModel, FakeImportService> {
        db = Room.inMemoryDatabaseBuilder(context, OrbitDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val service = FakeImportService(readOutcome)
        return ImportViewModel(service, appScope) to service
    }

    @After
    fun tearDown() {
        if (this::db.isInitialized) db.close()
        appScope.cancel()
    }

    @Test
    fun `picking a file moves to AwaitingPassphrase, cancelling resets`() {
        val (vm, _) = buildVm(Result.success(emptyPayload()))
        assertEquals(ImportUiState.Idle, vm.uiState.value)

        vm.onImportSourcePicked(uri)
        assertEquals(ImportUiState.AwaitingPassphrase, vm.uiState.value)

        vm.onCancelled()
        assertEquals(ImportUiState.Idle, vm.uiState.value)
    }

    @Test
    fun `null SAF result stays Idle`() {
        val (vm, _) = buildVm(Result.success(emptyPayload()))
        vm.onImportSourcePicked(null)
        assertEquals(ImportUiState.Idle, vm.uiState.value)
    }

    @Test
    fun `valid backup reaches AwaitingConfirm then confirm applies and restores`() = runBlocking {
        val (vm, service) = buildVm(Result.success(emptyPayload()))
        vm.onImportSourcePicked(uri)

        val snackbar = async {
            withTimeout(30_000L) { vm.snackbarEvents.first() }
        }
        delay(50)

        vm.onPassphraseSubmitted("pass".toCharArray())
        assertTrue(
            vm.uiState.value is ImportUiState.AwaitingConfirm,
            "validated backup must wait on the replace confirmation, got=${vm.uiState.value}",
        )

        vm.onReplaceConfirmed()
        assertEquals(ImportSnackbar.Restored, snackbar.await())
        assertEquals(1, service.applyCount, "apply must run exactly once")
        assertEquals(ImportUiState.Idle, vm.uiState.value)
    }

    @Test
    fun `unreadable backup surfaces calm error and never applies`() = runBlocking {
        val (vm, service) = buildVm(
            Result.failure(ImportFormatException("bad tag")),
        )
        vm.onImportSourcePicked(uri)

        val snackbar = async {
            withTimeout(30_000L) { vm.snackbarEvents.first() }
        }
        delay(50)

        vm.onPassphraseSubmitted("wrong".toCharArray())

        assertEquals(ImportSnackbar.Unreadable, snackbar.await())
        assertEquals(ImportUiState.Idle, vm.uiState.value)
        assertEquals(0, service.applyCount, "a refused backup must never apply")
    }

    @Test
    fun `newer-version backup is refused with its own message`() = runBlocking {
        val (vm, service) = buildVm(
            Result.failure(ImportVersionTooNewException(found = 9, supported = 2)),
        )
        vm.onImportSourcePicked(uri)

        val snackbar = async {
            withTimeout(30_000L) { vm.snackbarEvents.first() }
        }
        delay(50)

        vm.onPassphraseSubmitted("pass".toCharArray())

        assertEquals(ImportSnackbar.VersionTooNew, snackbar.await())
        assertEquals(ImportUiState.Idle, vm.uiState.value)
        assertEquals(0, service.applyCount)
    }

    @Test
    fun `a second file pick while waiting for the password is ignored`() = runBlocking {
        val (vm, service) = buildVm(Result.success(emptyPayload()))
        vm.onImportSourcePicked(uri)
        assertEquals(ImportUiState.AwaitingPassphrase, vm.uiState.value)

        // A late SAF result (the picker fired twice, or a result replayed after
        // a config change) must not swap the file under the open sheet.
        vm.onImportSourcePicked(otherUri)
        assertEquals(ImportUiState.AwaitingPassphrase, vm.uiState.value)

        vm.onPassphraseSubmitted("pass".toCharArray())
        assertEquals(uri, service.lastReadUri, "the first file is the one read")
    }

    @Test
    fun `while the file is being checked, a repeat submit, a row tap and a pick are all ignored`() = runBlocking {
        val (vm, service) = buildVm(Result.success(emptyPayload()))
        service.readGate = CompletableDeferred()
        vm.onImportSourcePicked(uri)
        vm.onPassphraseSubmitted("pass".toCharArray())
        assertEquals(ImportUiState.Validating, vm.uiState.value)

        val openRequest = async { vm.safOpenRequests.first() }
        delay(50)
        val again = "again".toCharArray()
        vm.onPassphraseSubmitted(again)
        vm.onImportRequested()
        vm.onImportSourcePicked(otherUri)
        delay(50)

        assertEquals(ImportUiState.Validating, vm.uiState.value, "nothing restarts the flow mid-check")
        assertFalse(openRequest.isCompleted, "the picker must not open again mid-check")
        openRequest.cancel()
        assertTrue(again.all { it == 0.toChar() }, "a refused passphrase is still wiped")

        service.readGate!!.complete(Unit)
        assertEquals(1, service.readCount, "the file is read exactly once")
        assertTrue(vm.uiState.value is ImportUiState.AwaitingConfirm, "the one read still reaches confirmation")
    }

    @Test
    fun `dismissing the confirm dialog drops the pending payload`() = runBlocking {
        val (vm, service) = buildVm(Result.success(emptyPayload()))
        vm.onImportSourcePicked(uri)
        vm.onPassphraseSubmitted("pass".toCharArray())
        // MainDispatcherRule's Unconfined dispatcher drives the validate
        // coroutine to completion synchronously (the fake read suspends nowhere).
        assertTrue(vm.uiState.value is ImportUiState.AwaitingConfirm)

        vm.onCancelled()
        assertEquals(ImportUiState.Idle, vm.uiState.value)

        // A stray confirm after cancel must be a no-op.
        vm.onReplaceConfirmed()
        delay(50)
        assertEquals(0, service.applyCount, "confirm after cancel must not apply")
    }
}
