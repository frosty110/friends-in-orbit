package app.orbit.ui.screens.settings.ignored

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.orbit.R
import app.orbit.data.entity.ContactEntity
import app.orbit.data.repository.ContactRepository
import app.orbit.domain.clock.Clock
import app.orbit.domain.undo.UndoStack
import app.orbit.domain.usecase.IgnoreContactUseCase
import app.orbit.domain.usecase.UnignoreContactUseCase
import app.orbit.ui.screens.picker.SnackbarEvent
import app.orbit.ui.util.UiText
import app.orbit.ui.util.formatRelative
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * IGNORE-06: read + un-ignore surface for the Settings → Ignored route.
 *
 * Sorted by ignoredAt DESC (DAO-enforced via `ContactRepository.observeIgnored`).
 * Empty state when the visible list is empty (warm locked copy).
 *
 * Archived contacts are excluded by the query itself (`ContactDao.observeIgnored`),
 * so this list and Settings' "{N} ignored" count always agree. Archive is a
 * separate hide mechanism; an ignore-then-archive path must never leak into
 * the Ignored management surface.
 *
 * Un-ignore re-uses [UnignoreContactUseCase] (drift restore via
 * pre-ignore membership snapshot). The snackbar Undo path
 * re-ignores via [IgnoreContactUseCase] wrapped through [UndoStack]; note
 * that we deliberately do NOT use `IgnoreContactUseCase.Result.inverse` for
 * the Undo, because that closure UN-ignores; here the Undo intent is to
 * RE-ignore (the user just un-ignored and is reverting that).
 *
 * A failing read becomes [SettingsIgnoredUiState.Error] rather than a crash
 * or a screen stuck on its skeleton, and [onRetry] re-subscribes (the same
 * `retryCount.flatMapLatest` shape as SettingsViewModel's SET-11); cancellation
 * is rethrown, never swallowed (rules.md Code 5).
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
    private val clock: Clock
) : ViewModel() {

    // Bumped by [onRetry] to re-subscribe after a failure.
    private val retryCount = MutableStateFlow(0)

    /** The Error state's Try again. */
    fun onRetry() {
        retryCount.update { it + 1 }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<SettingsIgnoredUiState> =
        retryCount.flatMapLatest {
            contactRepo.observeIgnored()
                .map<List<ContactEntity>, SettingsIgnoredUiState> { visible ->
                    if (visible.isEmpty()) {
                        SettingsIgnoredUiState.Empty
                    } else {
                        SettingsIgnoredUiState.Ready(
                            ignored = visible.map { it.toRow(now = clock.now()) }
                        )
                    }
                }
                .catch { t ->
                    if (t is CancellationException) throw t
                    emit(SettingsIgnoredUiState.Error)
                }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = SettingsIgnoredUiState.Loading
        )

    private val _snackbarEvents = MutableSharedFlow<SnackbarEvent>(extraBufferCapacity = 1)
    val snackbarEvents: SharedFlow<SnackbarEvent> = _snackbarEvents.asSharedFlow()

    /**
     * IGNORE-09: un-ignore a contact and queue a re-ignore Undo.
     *
     * Two writes happen on the snackbar dance:
     *   1. Immediate: [UnignoreContactUseCase] flips `isIgnored = false` and
     *      restores any drift-affected list memberships from the pre-ignore
     *      snapshot.
     *   2. On Undo tap: pop [UndoStack] and run the inverse, which dispatches
     *      [IgnoreContactUseCase], which re-snapshots current memberships and flips
     *      `isIgnored = true`.
     *
     * The snackbar says "Unignored {name}", the one word for the inverse of
     * Ignore everywhere (voice.md glossary; the picker's row says the same).
     */
    fun onUnignore(contactId: Long, name: String) = viewModelScope.launch {
        unignoreContactUseCase(contactId)
        undoStack.put(UndoStack.PendingUndo(inverse = { ignoreContactUseCase(contactId) }))
        _snackbarEvents.tryEmit(SnackbarEvent.undoable(UiText.res(R.string.components_snackbar_unignored, name)))
    }

    /** Snackbar "Undo" tap: replay the inverse closure recorded on [UndoStack]. */
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
            // "Ignored {3 days ago}": formatRelative's UiText nests as the argument.
            ignoredRelativeLabel = UiText.res(R.string.settings_ignored_relative, formatRelative(ignoredInstant, now))
        )
    }
}
