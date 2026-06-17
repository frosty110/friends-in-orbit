package app.orbit.ui.screens.picker

/**
 * One-shot snackbar event emitted by ViewModels and collected by their screen
 * (or, for picker commits, by the app-level [PickerCommitSnackbarHost] via
 * [PickerCommitBus]). Emitters use a `MutableSharedFlow` with
 * `extraBufferCapacity` so `tryEmit` works from non-suspend contexts.
 *
 * Lived at the bottom of `ContactPickerViewModel.kt` until the picker-commit
 * fix; moved to its own file because the type is shared by Home, Card, Browse,
 * Lists, Contact Detail, and Settings — same package, so the existing
 * `app.orbit.ui.screens.picker.SnackbarEvent` imports are untouched.
 *
 * `actionLabel` is nullable with default null so VMs that need to
 * surface a no-action failure toast (e.g. "Couldn't save your change" from
 * `runMutation`) don't have to invent a fake action label. Material 3's
 * `SnackbarHostState.showSnackbar` accepts `actionLabel: String?` natively, so
 * the screen-side collectors continue to work unchanged.
 *
 * `actionPayload` is an optional Long that lets a VM
 * carry context across the snackbar boundary without inventing a parallel
 * channel. The collector in the screen reads `actionPayload` when the user taps
 * the action and routes back to a typed VM method (e.g. `unarchiveList(id)`).
 * Defaults to null so the existing emit sites keep their current shape; only
 * the archive-Undo flow in [app.orbit.ui.screens.lists.ListsManagerScreen] uses it.
 */
data class SnackbarEvent(
    val message: String,
    val actionLabel: String? = null,
    /**
     * Optional metadata for the action callback (default null). Used today only
     * by the Lists Manager archive Undo flow to pass the archived listId to
     * `onUndoArchive` via the screen-side collector. Future emitters that use a
     * different semantic Long must coordinate with their corresponding
     * screen-side collector — there is no central registry binding the payload
     * shape to the action label.
     */
    val actionPayload: Long? = null,
)
