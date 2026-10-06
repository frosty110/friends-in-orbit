package app.orbit.ui.screens.picker

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The app-level snackbar bus: an app-lifetime channel for outcomes whose
 * origin screen may be gone by the time there is something to say.
 *
 * Why this exists: both pickers pop via the caller's `onCommit` lambda the
 * moment the user taps the commit CTA, so a snackbar hosted on the picker
 * screen dies with it — "Added N · Undo" was unreachable in every production
 * path. The picker VMs publish the commit outcome here instead, and the
 * app-level [PickerCommitSnackbarHost] (mounted above the NavHost in
 * `OrbitNavHost`) shows it on whatever screen the pop lands on. The same
 * host carries the nav host's "Couldn't open that." for a route the graph
 * refused ([UnknownRouteSnackbar]), and it is where a reset's failure
 * belongs, since a reset finishes after the Settings screen has gone (the
 * settings package). The name stays "picker commit" after its first
 * publisher.
 *
 * Pattern choice: an injected `@Singleton` bus over the
 * `previousBackStackEntry.savedStateHandle` nav result pattern because the
 * picker has six distinct callers (Home, Card, Browse, Lists Manager, List
 * Config, Contact Detail) — the SavedStateHandle pattern would need a reader
 * + snackbar plumbing in every caller, while one always-subscribed host
 * covers them all.
 *
 * Delivery semantics: `replay = 0` (a re-subscribe after backgrounding must
 * not replay an already-shown snackbar) with a small `extraBufferCapacity`
 * so an event published while the host is busy showing a prior snackbar
 * (`showSnackbar` suspends for the snackbar's duration) queues instead of
 * dropping. An event published while the app is backgrounded is dropped —
 * same deliberate tradeoff as the per-screen collectors; the write itself has
 * already landed.
 */
@Singleton
class PickerCommitBus @Inject constructor() {

    private val _events = MutableSharedFlow<SnackbarEvent>(extraBufferCapacity = 4)

    /** Hot stream of commit outcomes; collected by [PickerCommitSnackbarHost]. */
    val events: SharedFlow<SnackbarEvent> = _events.asSharedFlow()

    /** Non-suspending publish — safe from any dispatcher, including the app scope. */
    fun publish(event: SnackbarEvent) {
        _events.tryEmit(event)
    }
}
