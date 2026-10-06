package app.orbit.ui.screens.settings.export

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.orbit.domain.FakeCallEventRepository
import app.orbit.domain.FakeContactRepository
import app.orbit.domain.FakeListRepository
import app.orbit.domain.FakeNoteRepository
import app.orbit.domain.FakeRuleTemplateRepository
import app.orbit.domain.export.ExportService
import app.orbit.domain.export.ExportSummary
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
 * [ExportViewModel] handoff tests (SET-05 / EXPORT-01): the SAF request, the
 * passphrase's lifetime, and the InFlight → Idle + snackbar sequence on
 * success and failure. The crypto and the envelope are pinned by
 * [app.orbit.domain.export.PassphraseEncryptorTest] and
 * [app.orbit.domain.export.ImportServiceTest]; here [ExportService] is faked
 * so each transition can be asserted in isolation.
 *
 * The app scope handed to the ViewModel is `Dispatchers.Unconfined`, so the
 * export coroutine runs inline up to the fake's gate: InFlight is observable
 * while the gate is open, and completing it drives the rest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ExportViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val context: android.content.Context get() =
        ApplicationProvider.getApplicationContext()

    private val uri: Uri = Uri.parse("content://test/orbit-export.bin")
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    /** Fake service: counts exports, optionally waits on [gate], optionally throws. */
    private inner class FakeExportService : ExportService(
        context,
        FakeListRepository(),
        FakeContactRepository(),
        FakeCallEventRepository(),
        FakeNoteRepository(),
        FakeRuleTemplateRepository(),
    ) {
        var exportCount: Int = 0
        var lastUri: Uri? = null
        var gate: CompletableDeferred<Unit>? = null
        var failWith: Throwable? = null

        override suspend fun export(uri: Uri, passphrase: CharArray): ExportSummary {
            gate?.await()
            exportCount++
            lastUri = uri
            failWith?.let { throw it }
            return ExportSummary(0, 0, 0, 0)
        }
    }

    private fun buildVm(service: FakeExportService = FakeExportService()): Pair<ExportViewModel, FakeExportService> =
        ExportViewModel(service, appScope) to service

    @After
    fun tearDown() {
        appScope.cancel()
    }

    private fun CharArray.isZeroed(): Boolean = all { it == 0.toChar() }

    @Test
    fun `submitting the passphrase asks for a SAF file named orbit-export dot bin`() = runBlocking {
        val (vm, _) = buildVm()
        val request = async { withTimeout(30_000L) { vm.safLaunchRequests.first() } }
        delay(50)

        vm.onPassphraseSubmitted("correct horse".toCharArray())

        val name = request.await()
        assertTrue(name.startsWith("orbit-export-"), "default name must be orbit-export-{epoch}, got $name")
        assertTrue(name.endsWith(".bin"), "default name must end in .bin, got $name")
        assertEquals(ExportUiState.Idle, vm.uiState.value, "nothing runs until the picker returns")
    }

    @Test
    fun `cancelling the picker sends no snackbar and zeroes the passphrase`() = runBlocking {
        val (vm, service) = buildVm()
        val snackbar = async { vm.snackbarEvents.first() }
        delay(50)
        val passphrase = "correct horse".toCharArray()
        vm.onPassphraseSubmitted(passphrase)

        vm.onExportDestinationPicked(null)

        delay(50)
        assertFalse(snackbar.isCompleted, "a cancelled picker is not an error, so no snackbar")
        snackbar.cancel()
        assertTrue(passphrase.isZeroed(), "the passphrase must not outlive the cancelled export")
        assertEquals(0, service.exportCount, "nothing is written without a destination")
        assertEquals(ExportUiState.Idle, vm.uiState.value)
    }

    @Test
    fun `a successful export runs InFlight then Idle, says saved, and zeroes the passphrase`() = runBlocking {
        val service = FakeExportService().apply { gate = CompletableDeferred() }
        val (vm, _) = buildVm(service)
        val snackbar = async { withTimeout(30_000L) { vm.snackbarEvents.first() } }
        delay(50)
        val passphrase = "correct horse".toCharArray()
        vm.onPassphraseSubmitted(passphrase)

        vm.onExportDestinationPicked(uri)
        assertEquals(ExportUiState.InFlight, vm.uiState.value, "the rows read Saving… while the file is written")
        assertFalse(passphrase.isZeroed(), "the passphrase is still needed while the export runs")

        service.gate!!.complete(Unit)

        assertEquals(ExportSnackbar.Success, snackbar.await())
        assertEquals(ExportUiState.Idle, vm.uiState.value)
        assertEquals(1, service.exportCount)
        assertEquals(uri, service.lastUri)
        assertTrue(passphrase.isZeroed(), "the passphrase is wiped once the export returns")
    }

    @Test
    fun `a failing export says so, returns to Idle, and zeroes the passphrase`() = runBlocking {
        val service = FakeExportService().apply { failWith = IllegalStateException("stream closed") }
        val (vm, _) = buildVm(service)
        val snackbar = async { withTimeout(30_000L) { vm.snackbarEvents.first() } }
        delay(50)
        val passphrase = "correct horse".toCharArray()
        vm.onPassphraseSubmitted(passphrase)

        vm.onExportDestinationPicked(uri)

        assertEquals(ExportSnackbar.Failure, snackbar.await())
        assertEquals(ExportUiState.Idle, vm.uiState.value, "the row is the way to try again")
        assertTrue(passphrase.isZeroed(), "the passphrase is wiped on failure too")
    }
}
