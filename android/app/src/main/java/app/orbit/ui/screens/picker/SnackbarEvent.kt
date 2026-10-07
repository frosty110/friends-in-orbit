package app.orbit.ui.screens.picker

import app.orbit.R
import app.orbit.ui.util.UiText

/**
 * One-shot snackbar event emitted by ViewModels and collected by their screen
 * (or, for picker commits, by the app-level [PickerCommitSnackbarHost] via
 * [PickerCommitBus]). Emitters use a `MutableSharedFlow` with
 * `extraBufferCapacity` so `tryEmit` works from non-suspend contexts.
 *
 * Lived at the bottom of `ContactPickerViewModel.kt` until the picker-commit
 * fix; moved to its own file because the type is shared by Browse, List
 * settings, Contact Detail, Settings > Ignored and both pickers. Same package,
 * so the existing `app.orbit.ui.screens.picker.SnackbarEvent` imports are
 * untouched.
 *
 * [message] and [actionLabel] are [UiText] (UX rubric 3.4): ViewModels hold
 * no Context, so they say which resource they mean and the collector resolves
 * it with `asString(context)` when it shows the snackbar. Use [undoable] for
 * the common "{what happened} · Undo" shape.
 *
 * `actionLabel` is nullable with default null so VMs that need to
 * surface a no-action failure toast (e.g. "Couldn't save your change" from
 * `runMutation`) don't have to invent a fake action label.
 *
 * `actionPayload` is an optional Long that lets a VM
 * carry context across the snackbar boundary without inventing a parallel
 * channel. The collector in the screen reads `actionPayload` when the user taps
 * the action and routes back to a typed VM method (e.g. `unarchiveList(id)`).
 * Defaults to null so the existing emit sites keep their current shape.
 */
data class SnackbarEvent(
    val message: UiText,
    val actionLabel: UiText? = null,
    /**
     * Optional metadata for the action callback (default null). Future
     * emitters that use a semantic Long must coordinate with their
     * corresponding screen-side collector: there is no central registry
     * binding the payload shape to the action label.
     */
    val actionPayload: Long? = null,
) {
    companion object {
        /** "{message} · Undo": the action reverses what [message] announced. */
        fun undoable(message: UiText): SnackbarEvent =
            SnackbarEvent(message, UiText.res(R.string.components_action_undo))
    }
}
