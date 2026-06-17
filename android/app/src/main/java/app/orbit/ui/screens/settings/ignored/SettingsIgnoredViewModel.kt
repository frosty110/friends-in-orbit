package app.orbit.ui.screens.settings.ignored

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.orbit.data.entity.ContactEntity
import app.orbit.data.repository.ContactRepository
import app.orbit.domain.clock.Clock
import app.orbit.domain.undo.UndoStack
import app.orbit.domain.usecase.IgnoreContactUseCase
import app.orbit.domain.usecase.UnignoreContactUseCase
import app.orbit.ui.screens.picker.SnackbarEvent
import app.orbit.ui.util.formatRelative
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * IGNORE-06 — read + un-ignore surface for the Settings → Ignored route.
 *
 * Sorted by ignoredAt DESC (DAO-enforced via `ContactRepository.observeIgnored`).
 * Empty state when the visible list is empty (warm locked copy).
 *
 * Explicit `!isArchived` filter so archived contacts never pollute this view.
 * Archive is a separate hide mechanism. Defensive even though
 * `ArchiveContactUseCase` doesn't touch `isIgnored` today; future codepaths
 * might ignore-then-archive and we don't want them leaking into the Ignored
 * management surface.
 *
 * Un-ignore re-uses [UnignoreContactUseCase] (drift restore via
 * pre-ignore membership snapshot). The snackbar Undo path
 * re-ignores via [IgnoreContactUseCase] wrapped through [UndoStack] — note
 * that we deliberately do NOT use `IgnoreContactUseCase.Result.inverse` for
 * the Undo, because that closure UN-ignores; here the Undo intent is to
 * RE-ignore (the user just un-ignored and is reverting that).
 *
 * `WhileSubscribed(5_000L)` keeps the upstream observe-flow alive across
 * rotation / dark-mode toggle so the row list survives config changes
 * without re-querying Room.
 */
@HiltViewModel
class SettingsIgnoredViewModel @Inject constructor(
    private val contactRepo: ContactRepository,
    private val ignoreContactUseCase: IgnoreContactUseCase,
    private val unignoreContactUseCase: UnignoreContactUseCase,
    private val undoStack: UndoStack,
    private val clock: Clock,
) : ViewModel() {

    val uiState: StateFlow<SettingsIgnoredUiState> =
        contactRepo.observeIgnored()
            .map { entities ->
                // Filter archived contacts out of the Ignored view.
                val visible = entities.filter { !it.isArchived }
                if (visible.isEmpty()) {
                    SettingsIgnoredUiState.Empty
                } else {
                    SettingsIgnoredUiState.Ready(
                        ignored = visible.map { it.toRow(now = clock.now()) },
                    )
                }
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000L),
                initialValue = SettingsIgnoredUiState.Loading,
            )

    private val _snackbarEvents = MutableSharedFlow<SnackbarEvent>(extraBufferCapacity = 1)
    val snackbarEvents: SharedFlow<SnackbarEvent> = _snackbarEvents.asSharedFlow()

    /**
     * IGNORE-09 — un-ignore a contact and queue a re-ignore Undo.
     *
     * Two writes happen on the snackbar dance:
     *   1. Immediate: [UnignoreContactUseCase] flips `isIgnored = false` and
     *      restores any drift-affected list memberships from the pre-ignore
     *      snapshot.
     *   2. On Undo tap: pop [UndoStack] and run the inverse, which dispatches
     *      [IgnoreContactUseCase] — re-snapshots current memberships and flips
     *      `isIgnored = true`.
     */
    fun onUnignore(contactId: Long, name: String) = viewModelScope.launch {
        unignoreContactUseCase(contactId)
        undoStack.put(
            UndoStack.PendingUndo(
                inverse = { ignoreContactUseCase(contactId, name) },
                label = "Restored $name",
            ),
        )
        _snackbarEvents.tryEmit(SnackbarEvent("Restored $name", "Undo"))
    }

    /** Snackbar "Undo" tap — replay the inverse closure recorded on [UndoStack]. */
    fun onUndo() = viewModelScope.launch {
        undoStack.take()?.inverse?.invoke()
    }

    private fun ContactEntity.toRow(now: Instant): IgnoredContactRow {
        val ignoredInstant = ignoredAt ?: Instant.EPOCH
        return IgnoredContactRow(
            id = id,
            name = displayName,
            photoUri = photoUri,
            ignoredAtMs = ignoredInstant.toEpochMilli(),
            ignoredRelativeLabel = "Ignored " + formatRelative(ignoredInstant, now),
        )
    }
}
