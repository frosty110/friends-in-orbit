package app.orbit.ui.screens.note

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.orbit.R
import app.orbit.data.entity.CallEventEntity
import app.orbit.data.entity.CallSource
import app.orbit.data.repository.CallEventRepository
import app.orbit.data.repository.ContactRepository
import app.orbit.domain.clock.Clock
import app.orbit.domain.usecase.AddNoteUseCase
import app.orbit.ui.screens.picker.PickerCommitBus
import app.orbit.ui.screens.picker.SnackbarEvent
import app.orbit.ui.util.UiText
import app.orbit.ui.util.formatDayHeader
import app.orbit.ui.util.formatDuration
import app.orbit.ui.util.formatWallClock
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * NOTE-04: the page for writing about a call.
 *
 * Reads the route's `contactId` (bare or "c-" form) and optional
 * `callEventId` from [SavedStateHandle]. Two things live in the handle so
 * that rotation and process death keep them: the moment the page opened
 * ([startedAt], the timer counts from it) and the draft (written one way by
 * the field, [onDraftChange]). Save writes an ordinary note
 * through [AddNoteUseCase], the same shape Contact detail's note field writes
 * (NOTE-01), so the note is just a note: on the person's page, in the card's
 * "Your note", and it ends the call's wait on Home (NOTE-05) and its
 * notification (NOTIF-16), both of which watch the notes table.
 *
 * The write runs on `viewModelScope`, not the app scope (rules.md Code 6):
 * the page leaves only once the write has landed, because a failed save must
 * keep the words on the page; nothing navigates away mid-flight.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PostCallNoteViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val contactRepo: ContactRepository,
    private val callEventRepo: CallEventRepository,
    private val addNote: AddNoteUseCase,
    private val commitBus: PickerCommitBus,
    private val clock: Clock,
) : ViewModel() {

    private val contactId: Long? =
        savedStateHandle.get<String>(ARG_CONTACT_ID)?.removePrefix("c-")?.toLongOrNull()
    private val callEventId: Long? = savedStateHandle.get<String>(ARG_CALL_EVENT_ID)?.toLongOrNull()

    /**
     * When the page opened: the timer in the app bar counts up from here. Set
     * once, on the first creation, and read back from the handle after that,
     * so turning the phone or the process dying never restarts the count.
     */
    val startedAt: Instant =
        savedStateHandle.get<Long>(KEY_STARTED_AT)?.let(Instant::ofEpochMilli)
            ?: clock.now().also { savedStateHandle[KEY_STARTED_AT] = it.toEpochMilli() }

    /** What the field starts with: the draft that survived a process death, or nothing. */
    val initialDraft: String = savedStateHandle.get<String>(KEY_DRAFT).orEmpty()

    /** The field's text, handed over on every change (one way: nothing writes it back). */
    fun onDraftChange(text: String) {
        savedStateHandle[KEY_DRAFT] = text
    }

    /** Discard in "Discard this note?": the words are gone, from process death too. */
    fun onDiscard() {
        savedStateHandle.remove<String>(KEY_DRAFT)
    }

    private val retry = MutableStateFlow(0)
    private val saving = MutableStateFlow(false)
    private val saved = MutableStateFlow(false)

    /** The Error state's Try again: re-reads the person and the call. */
    fun onRetry() = retry.update { it + 1 }

    private val _events = MutableSharedFlow<UiText>(extraBufferCapacity = 4)

    /** One-shot snackbars shown on the page itself: only the failed save. */
    val events: SharedFlow<UiText> = _events.asSharedFlow()

    // Read once, as CallLogViewModel does: the zone a call's day is worded in.
    private val zone: ZoneId = ZoneId.systemDefault()

    val uiState: StateFlow<PostCallNoteUiState> = retry
        .flatMapLatest {
            val cid = contactId ?: return@flatMapLatest flowOf(PostCallNoteUiState.NotFound)
            combine(contactRepo.observeById(cid), callFor(cid), saving, saved) { contact, call, saving, saved ->
                if (contact == null) {
                    PostCallNoteUiState.NotFound
                } else {
                    PostCallNoteUiState.Ready(
                        contactId = cid,
                        firstName = contact.displayName.trim().substringBefore(' ').ifBlank { contact.displayName },
                        call = call?.toNoteCall(),
                        saving = saving,
                        saved = saved,
                    )
                }
            }.catch { emit(PostCallNoteUiState.Error) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), PostCallNoteUiState.Loading)

    /**
     * The call the page describes: the one the route names, when it is this
     * person's; otherwise their latest connected call (Card view knows who
     * was called, not which row the call log wrote). A connection logged by
     * hand or an attempt is never described as "You called Kai · 14 min".
     * Read once: a call row does not change while the page is open.
     */
    private fun callFor(cid: Long): Flow<CallEventEntity?> = flow {
        val named = callEventId?.let { callEventRepo.byId(it) }?.takeIf { it.contactId == cid }
        emit(
            named ?: callEventRepo.observeForContact(cid, LATEST_CALLS_SCANNED).first()
                .firstOrNull { it.source == CallSource.CALL_LOG },
        )
    }

    private fun CallEventEntity.toNoteCall(): NoteCall {
        val today = clock.now().atZone(zone).toLocalDate()
        return NoteCall(
            direction = direction,
            durationLabel = formatDuration(durationSeconds),
            whenLabel = UiText.res(
                R.string.note_call_when,
                formatDayHeader(occurredAt.atZone(zone).toLocalDate(), today),
                formatWallClock(occurredAt, zone),
            ),
        )
    }

    /**
     * Save note. Writes the draft as an ordinary note; on success forgets the
     * draft, says "Note saved" on the screen the page returns to (the
     * app-level bus, because the page is gone by then; PickerCommitBus's
     * precedent) and marks the state saved so the screen leaves. On failure it
     * says so here and keeps every word (rules.md Code 3).
     */
    fun save() {
        val cid = contactId ?: return
        val body = savedStateHandle.get<String>(KEY_DRAFT).orEmpty()
        if (body.isBlank() || saving.value || saved.value) return
        saving.value = true
        viewModelScope.launch {
            try {
                // Never null here: the body is not blank, and blank is the
                // only thing AddNoteUseCase declines.
                checkNotNull(addNote(cid, body)) { "a non-blank note was not written" }
                savedStateHandle.remove<String>(KEY_DRAFT)
                commitBus.publish(SnackbarEvent(UiText.res(R.string.note_snackbar_saved)))
                saved.value = true
            } catch (t: Throwable) {
                if (t is CancellationException) throw t // rules.md Code 5
                _events.tryEmit(UiText.res(R.string.note_snackbar_save_failed))
            } finally {
                saving.value = false
            }
        }
    }

    companion object {
        /** Route arguments ([app.orbit.nav.Routes.PostCallNote]). */
        const val ARG_CONTACT_ID = "contactId"
        const val ARG_CALL_EVENT_ID = "callEventId"

        // Not copy (voice.md "Where copy lives"): SavedStateHandle keys.
        internal const val KEY_STARTED_AT = "note_started_at_ms"
        internal const val KEY_DRAFT = "note_draft"

        /** How far back the latest connected call is looked for, without a named call. */
        private const val LATEST_CALLS_SCANNED = 20
    }
}
