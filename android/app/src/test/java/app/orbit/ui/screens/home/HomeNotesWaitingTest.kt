package app.orbit.ui.screens.home

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.orbit.R
import app.orbit.data.entity.CallDirection
import app.orbit.data.entity.ListType
import app.orbit.ui.components.LocalPrivacyCurtain
import app.orbit.ui.components.NoteWaiting
import app.orbit.ui.theme.OrbitTheme
import app.orbit.ui.util.UiText
import app.orbit.ui.util.formatDuration
import java.time.LocalDate
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * HOME-14: the calls waiting for a note at the top of Home, read from the
 * semantics tree under Robolectric (what TalkBack would hear, and what each
 * button does). One call is a card; three are a pile that opens and folds;
 * Dismiss and Dismiss all hand Home the right calls; the curtain hides names.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class HomeNotesWaitingTest {

    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private val state = HomeUiState.Ready(
        lists = listOf(
            ListTileState(
                id = 1L, name = "Inner orbit", dueCount = 0, type = ListType.STATIC, memberCount = 3,
                nextUp = null,
                rhythm = List(7) { RhythmDay(emptyList()) },
            ),
        ),
    )

    private fun waiting(id: Long, name: String, direction: CallDirection = CallDirection.OUTGOING) = NoteWaiting(
        callEventId = id,
        contactId = id + 100,
        name = name,
        photoUri = null,
        direction = direction,
        meta = UiText.res(
            R.string.components_notes_waiting_meta,
            formatDuration(14 * 60),
            UiText.plural(R.plurals.time_ago_hours, 2, 2),
        ),
    )

    private val kai = waiting(1L, "Kai Mensah")
    private val three = listOf(kai, waiting(2L, "Mara Ellis", CallDirection.INCOMING), waiting(3L, "Sam Okafor"))

    private val addedNotes = mutableListOf<NoteWaiting>()
    private val dismissed = mutableListOf<List<Long>>()

    private fun setHome(calls: List<NoteWaiting>, curtain: Boolean = false) {
        compose.setContent {
            CompositionLocalProvider(LocalPrivacyCurtain provides curtain) {
                OrbitTheme {
                    HomeContent(
                        state = state,
                        today = LocalDate.of(2026, 10, 7),
                        onOpenList = {},
                        onOpenSearch = {},
                        onOpenSettings = {},
                        onOpenLists = {},
                        onCreateList = {},
                        notesWaiting = calls,
                        onAddNoteForCall = { addedNotes += it },
                        onDismissWaiting = { dismissed += it },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun string(id: Int, vararg args: Any) = context.getString(id, *args)
    private fun countLine(n: Int) = context.resources.getQuantityString(R.plurals.components_notes_waiting_count, n, n)
    private fun state(words: Int) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, string(words))
    private val isButton = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    // ── one call ──────────────────────────────────────────────────────────────

    @Test
    fun one_waiting_call_is_a_single_card_with_buttons_that_name_the_person() {
        setHome(listOf(kai))

        compose.onNodeWithText(string(R.string.components_notes_waiting_you_called, "Kai")).assertExists()
        compose.onNodeWithText("14 min · 2 hours ago").assertExists()
        compose.onAllNodesWithContentDescription(countLine(1)).assertCountEquals(0)

        compose.onNodeWithContentDescription(string(R.string.components_notes_waiting_add_note_named, "Kai"))
            .assertHasClickAction()
            .performClick()
        assertEquals(listOf(kai), addedNotes)

        compose.onNodeWithContentDescription(string(R.string.components_notes_waiting_dismiss_named, "Kai"))
            .performClick()
        assertEquals(listOf(listOf(1L)), dismissed)
    }

    @Test
    fun a_call_they_made_says_so() {
        setHome(listOf(waiting(2L, "Mara Ellis", CallDirection.INCOMING)))

        compose.onNodeWithText(string(R.string.components_notes_waiting_they_called, "Mara")).assertExists()
    }

    // ── three calls ───────────────────────────────────────────────────────────

    @Test
    fun three_waiting_calls_are_a_closed_pile_one_button_that_says_how_many() {
        setHome(three)

        compose.onNodeWithContentDescription(countLine(3))
            .assert(state(R.string.components_notes_waiting_collapsed))
            .assert(isButton)
            .assertHasClickAction()
        // Closed, the rows and their buttons are not there to be found.
        compose.onAllNodesWithContentDescription(string(R.string.components_notes_waiting_dismiss_named, "Mara"))
            .assertCountEquals(0)
    }

    @Test
    fun the_pile_opens_into_one_row_per_call_and_folds_again() {
        setHome(three)

        compose.onNodeWithContentDescription(countLine(3)).performClick()
        compose.waitForIdle()

        compose.onNodeWithContentDescription(countLine(3)).assert(state(R.string.components_notes_waiting_expanded))
        listOf("Kai", "Mara", "Sam").forEach { name ->
            compose.onNodeWithContentDescription(string(R.string.components_notes_waiting_add_note_named, name)).assertExists()
            compose.onNodeWithContentDescription(string(R.string.components_notes_waiting_dismiss_named, name)).assertExists()
        }
        compose.onNodeWithText(string(R.string.components_notes_waiting_dismiss_all)).assertExists()

        compose.onNodeWithContentDescription(countLine(3)).performClick()
        compose.waitForIdle()

        compose.onNodeWithContentDescription(countLine(3)).assert(state(R.string.components_notes_waiting_collapsed))
        compose.onAllNodesWithText(string(R.string.components_notes_waiting_dismiss_all)).assertCountEquals(0)
    }

    @Test
    fun in_the_open_pile_dismiss_closes_one_call_and_dismiss_all_closes_every_one() {
        setHome(three)
        compose.onNodeWithContentDescription(countLine(3)).performClick()
        compose.waitForIdle()

        compose.onNodeWithContentDescription(string(R.string.components_notes_waiting_dismiss_named, "Mara")).performClick()
        compose.onNodeWithText(string(R.string.components_notes_waiting_dismiss_all)).performClick()

        assertEquals(listOf(listOf(2L), listOf(1L, 2L, 3L)), dismissed)
    }

    // ── the curtain (PRIV-03) ─────────────────────────────────────────────────

    @Test
    fun under_the_curtain_no_name_reaches_text_or_TalkBack() {
        setHome(three, curtain = true)
        compose.onNodeWithContentDescription(countLine(3)).performClick()
        compose.waitForIdle()

        compose.onAllNodesWithText(string(R.string.components_notes_waiting_you_called_someone)).assertCountEquals(2)
        compose.onNodeWithText(string(R.string.components_notes_waiting_someone_called)).assertExists()
        compose.onAllNodesWithContentDescription(string(R.string.components_notes_waiting_add_note_masked)).assertCountEquals(3)
        compose.onAllNodesWithContentDescription(string(R.string.components_notes_waiting_dismiss_masked)).assertCountEquals(3)
        listOf("Kai", "Mara", "Sam").forEach { name ->
            compose.onAllNodesWithText(name, substring = true, useUnmergedTree = true).assertCountEquals(0)
            compose.onAllNodesWithContentDescription(name, substring = true, useUnmergedTree = true).assertCountEquals(0)
        }
    }
}
