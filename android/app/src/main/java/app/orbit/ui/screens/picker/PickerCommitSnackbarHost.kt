package app.orbit.ui.screens.picker

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.orbit.R
import app.orbit.di.ApplicationScope
import app.orbit.domain.undo.UndoStack
import app.orbit.ui.components.OrbitSnackbar
import app.orbit.ui.components.OrbitSnackbarHost
import app.orbit.ui.theme.OrbitTheme
import app.orbit.ui.util.UiText
import app.orbit.ui.util.asString
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Picker-commit lifecycle — ViewModel behind [PickerCommitSnackbarHost].
 *
 * Resolved via `hiltViewModel()` at the NavHost level (outside any
 * destination), so it scopes to the Activity and lives for the whole nav
 * graph — exactly the lifetime the post-pop snackbar needs.
 *
 * Undo runs on the injected [ApplicationScope] for the same reason the
 * forward write does: the inverse must survive whatever screen transition
 * the user triggers while the snackbar is on screen.
 */
@HiltViewModel
class PickerCommitSnackbarHostViewModel @Inject constructor(
    private val commitBus: PickerCommitBus,
    private val undoStack: UndoStack,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    /** Commit outcomes published by the picker VMs. */
    val events: SharedFlow<SnackbarEvent> = commitBus.events

    /**
     * Puts [event] on the bus for the host to show. For an outcome that
     * belongs to no screen's ViewModel: the nav host's "Couldn't open that."
     * ([UnknownRouteSnackbar]) arrives here.
     */
    fun publish(event: SnackbarEvent) = commitBus.publish(event)

    /**
     * Replays the depth-1 inverse recorded by the commit (removes the
     * just-added memberships). No-op when nothing is pending — e.g. a second
     * Undo tap racing the first. A failed inverse surfaces on the bus instead
     * of crashing; [CancellationException] is rethrown per codebase convention.
     */
    fun onUndo() {
        val pending = undoStack.take() ?: return
        appScope.launch {
            try {
                pending.inverse()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                commitBus.publish(SnackbarEvent(UiText.res(R.string.picker_snackbar_undo_failed)))
            }
        }
    }
}

/**
 * App-level snackbar host for picker commit results. Mounted once in
 * `OrbitNavHost`, overlaying the NavHost — it outlives the picker screen
 * (which pops on commit), so "Added N to X · Undo" and "Couldn't save that"
 * land on whatever screen the user returns to, with a tappable Undo.
 *
 * Collection is gated by [Lifecycle.State.STARTED] so events published while
 * the app is backgrounded drop instead of firing a stale snackbar on resume.
 */
@Composable
fun PickerCommitSnackbarHost(
    modifier: Modifier = Modifier,
    vm: PickerCommitSnackbarHostViewModel = hiltViewModel(),
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current
    // Event copy is UiText (strings_picker.xml and shared); resolved when shown.
    val context = LocalContext.current

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.events.collect { event ->
                val r = snackbarHostState.showSnackbar(
                    message = event.message.asString(context),
                    actionLabel = event.actionLabel?.asString(context),
                    duration = SnackbarDuration.Short,
                    withDismissAction = false,
                )
                if (r == SnackbarResult.ActionPerformed) vm.onUndo()
            }
        }
    }

    OrbitSnackbarHost(hostState = snackbarHostState, modifier = modifier)
}

/**
 * Says "Couldn't open that." once per route the nav host was handed and could
 * not open (`OrbitNavScreens.UnknownRouteNotice`; rules.md Code 3). It draws
 * nothing itself: it publishes on the bus [PickerCommitSnackbarHost] collects,
 * so the message lands over whatever screen is open. [occurrences] keys the
 * effect, so a recomposition with the same count says nothing again and 0,
 * nothing refused yet, is quiet. The ViewModel is the host's own,
 * Activity-scoped, so this is one more publisher on the same stream, not a
 * second host.
 */
@Composable
fun UnknownRouteSnackbar(
    occurrences: Int,
    vm: PickerCommitSnackbarHostViewModel = hiltViewModel(),
) {
    LaunchedEffect(occurrences) {
        if (occurrences > 0) {
            vm.publish(SnackbarEvent(UiText.res(R.string.components_snackbar_open_failed)))
        }
    }
}

// ─── Previews ──────────────────────────────────────────────────────────────────

// The host itself renders nothing until an event arrives, so the preview shows
// Orbit's snackbar with the locked commit copy + Undo affordance.
@PreviewLightDark
@Composable
private fun PickerCommitSnackbarPreview() {
    OrbitTheme {
        OrbitSnackbar(
            message = pluralStringResource(R.plurals.picker_snackbar_added, 3, 3, "In touch"),
            actionLabel = stringResource(R.string.components_action_undo),
            onAction = {},
        )
    }
}
