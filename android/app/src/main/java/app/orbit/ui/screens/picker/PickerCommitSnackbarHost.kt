package app.orbit.ui.screens.picker

import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.orbit.di.ApplicationScope
import app.orbit.domain.undo.UndoStack
import app.orbit.ui.theme.OrbitTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

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
                commitBus.publish(SnackbarEvent("Couldn't undo that"))
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

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.events.collect { event ->
                val r = snackbarHostState.showSnackbar(
                    message = event.message,
                    actionLabel = event.actionLabel,
                    duration = SnackbarDuration.Short,
                    withDismissAction = false,
                )
                if (r == SnackbarResult.ActionPerformed) vm.onUndo()
            }
        }
    }

    SnackbarHost(hostState = snackbarHostState, modifier = modifier)
}

// ─── Previews ──────────────────────────────────────────────────────────────────

// The host itself renders nothing until an event arrives, so the preview shows
// the Material 3 snackbar with the locked commit copy + Undo affordance.
@PreviewLightDark
@Composable
private fun PickerCommitSnackbarPreview() {
    OrbitTheme {
        Snackbar(
            action = {
                TextButton(onClick = {}) { Text("Undo") }
            },
        ) {
            Text("Added 3 to In touch")
        }
    }
}
