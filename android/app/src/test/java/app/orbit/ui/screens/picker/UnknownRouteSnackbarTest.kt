package app.orbit.ui.screens.picker

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.orbit.R
import app.orbit.domain.undo.UndoStack
import app.orbit.ui.util.UiText
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The nav host's "Couldn't open that." reaches the app-level snackbar bus
 * once per refused route and never for a count that has not risen. The
 * composable is given the host's ViewModel by hand (no Hilt), the way
 * `OrbitNavScreens.Real` resolves it; `OrbitNavHostTest` covers the count
 * the host hands over, this covers what the count becomes.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class UnknownRouteSnackbarTest {

    @get:Rule val compose = createComposeRule()

    private val bus = PickerCommitBus()
    private val vm = PickerCommitSnackbarHostViewModel(
        commitBus = bus,
        undoStack = UndoStack(),
        appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    )
    private val received = mutableListOf<SnackbarEvent>()
    private val collector = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).launch {
        bus.events.collect { received += it }
    }
    private var occurrences by mutableIntStateOf(0)

    private val openFailed = SnackbarEvent(UiText.res(R.string.components_snackbar_open_failed))

    @After
    fun stopCollecting() {
        collector.cancel()
    }

    @Test
    fun says_couldnt_open_that_once_per_refused_route_and_nothing_before_one() {
        compose.setContent { UnknownRouteSnackbar(occurrences = occurrences, vm = vm) }
        compose.waitForIdle()
        assertEquals(emptyList(), received, "nothing has been refused yet")

        compose.runOnUiThread { occurrences = 1 }
        compose.waitForIdle()
        assertEquals(listOf(openFailed), received)

        // The same count again (a recomposition for any other reason) is quiet.
        compose.runOnUiThread { occurrences = 1 }
        compose.waitForIdle()
        assertEquals(listOf(openFailed), received)

        compose.runOnUiThread { occurrences = 2 }
        compose.waitForIdle()
        assertEquals(listOf(openFailed, openFailed), received, "a second refused route is said too")
    }
}
