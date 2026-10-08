package app.orbit.ui.screens.note

import androidx.compose.runtime.Immutable
import app.orbit.data.entity.CallDirection
import app.orbit.ui.util.UiText

/**
 * NOTE-04: the one state contract of the post-call note page (ARCH-02).
 *
 * The draft is not in here: the writing field owns its text in the
 * composable and hands it one way to [PostCallNoteViewModel], which keeps a
 * copy in SavedStateHandle for saving and for process death (rules.md
 * Code 7; the search box's precedent in `GlobalSearchScreen`). Nor is the
 * timer: its start is a constant of the page ([PostCallNoteViewModel.startedAt])
 * and the screen ticks it.
 */
sealed interface PostCallNoteUiState {

    /** The person has not been read yet: quiet chrome, never a false NotFound. */
    @Immutable
    data object Loading : PostCallNoteUiState

    /**
     * [firstName] is what the title and the call line say ("Your call with
     * Kai"); [call] is null when the person has no call to describe. [saving]
     * holds Save while the write is in flight, so a second tap cannot write a
     * second note; [saved] is the write landing, which the screen turns into
     * leaving the page. It is state rather than a one-shot event so a
     * rotation during the write cannot lose it and leave the page open over a
     * note that is already saved.
     */
    @Immutable
    data class Ready(
        val contactId: Long,
        val firstName: String,
        val call: NoteCall?,
        val saving: Boolean = false,
        val saved: Boolean = false,
    ) : PostCallNoteUiState

    /** The person is not in Orbit (anymore): the app's not-found message, with "Go back". */
    @Immutable
    data object NotFound : PostCallNoteUiState

    /** The person could not be read: "Couldn't load this person" with Try again. */
    @Immutable
    data object Error : PostCallNoteUiState
}

/** The call the page is about, worded: "14 min", "Today at 4:30pm". */
@Immutable
data class NoteCall(
    val direction: CallDirection,
    val durationLabel: UiText,
    val whenLabel: UiText,
)
