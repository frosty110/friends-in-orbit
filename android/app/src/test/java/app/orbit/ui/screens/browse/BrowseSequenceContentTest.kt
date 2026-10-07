package app.orbit.ui.screens.browse

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.orbit.R
import app.orbit.data.Contact
import app.orbit.ui.components.LocalPrivacyCurtain
import app.orbit.ui.screens.picker.SnackbarEvent
import app.orbit.ui.util.UiText
import app.orbit.ui.util.formatSpan
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What Browse's sequence owes a reader, from the semantics tree on the JVM
 * (the BrowseErrorShellTest convention), through the gallery's host:
 * BROWSE-07's when labels and groups, BROWSE-08's handle ("Reorder {name}",
 * "Reorder" under the curtain) and TalkBack's "Move up" / "Move down" with
 * what each one asks for, none of it on paused or ignored rows or while
 * selecting, and BROWSE-09's mark, scrolled into view.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w411dp-h891dp")
class BrowseSequenceContentTest {

    @get:Rule val compose = createComposeRule()

    private fun person(id: Long, name: String) = Contact(
        id = "c-$id",
        name = name,
        phone = "+1 555 0100",
        lastCalledLabel = null,
        avgLengthLabel = null,
        pickupRateLabel = "",
        totalCalls = 0,
        due = false,
        listIds = emptyList(),
        bestWindowLabel = null,
        heat = FloatArray(24) { 0f },
        history = emptyList(),
        notes = emptyList(),
        patternNote = ""
    )

    private val sequence = BrowseUiState.Ready(
        contacts = listOf(
            person(1, "Avery Quinn"),
            person(2, "Sam Patel"),
            person(3, "Jordan Lee"),
            person(4, "Priya Anand"),
            person(5, "Kai Moreno")
        ),
        searchQuery = "",
        activeFilters = emptySet(),
        callLogPermissionDenied = false,
        dueIds = setOf("c-1"),
        rowStatus = mapOf("c-4" to BrowseRowStatus.Paused, "c-5" to BrowseRowStatus.Ignored),
        queuePositions = mapOf("c-1" to 1, "c-2" to 2, "c-3" to 3),
        whenLabels = mapOf(
            "c-1" to UiText.res(R.string.browse_when_up_now),
            "c-2" to UiText.res(R.string.browse_when_tomorrow),
            "c-3" to UiText.res(R.string.browse_when_in_span, formatSpan(14))
        ),
        untilLabels = mapOf("c-4" to UiText.res(R.string.browse_row_until_unpause)),
        onYourCardId = "c-1"
    )

    private val reorders = mutableListOf<Triple<Long, Long?, String>>()

    private fun show(
        state: BrowseUiState.Ready,
        curtain: Boolean = false,
        snackbarEvents: SharedFlow<SnackbarEvent> = MutableSharedFlow()
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalPrivacyCurtain provides curtain) {
                BrowsePreviewHost(
                    state,
                    onReorder = { id, after, name -> reorders += Triple(id, after, name) },
                    snackbarEvents = snackbarEvents
                )
            }
        }
    }

    /** The TalkBack actions on the row TalkBack focuses for [name], in order. */
    private fun actionsOn(name: String): List<String> =
        compose.onNodeWithText(name).fetchSemanticsNode()
            .config.getOrElseNullable(SemanticsActions.CustomActions) { null }
            .orEmpty().map { it.label }

    private fun perform(name: String, label: String) {
        compose.onNodeWithText(name).fetchSemanticsNode()
            .config[SemanticsActions.CustomActions].single { it.label == label }.action()
        compose.waitForIdle()
    }

    @Test
    fun the_sequence_reads_when_and_the_groups_follow_it() {
        show(sequence)

        compose.onNodeWithText("Next up").assertIsDisplayed()
        compose.onNodeWithText("Up now · Never called").assertIsDisplayed()
        compose.onNodeWithText("Tomorrow · Never called").assertIsDisplayed()
        compose.onNodeWithText("In 2 weeks · Never called").assertIsDisplayed()
        compose.onNodeWithText(
            "Drag to change who comes up next. A call, Later or Sooner moves people again."
        )
            .assertIsDisplayed()
        // The headings; "Paused" and "Ignored" are also the rows' own words.
        compose.onNode(hasText("Paused") and isHeading()).assertIsDisplayed()
        compose.onNodeWithText("Until you unpause · Never called").assertIsDisplayed()
        compose.onNode(hasText("Ignored") and isHeading()).assertIsDisplayed()
    }

    @Test
    fun each_row_in_the_sequence_has_a_named_handle_and_paused_and_ignored_rows_have_none() {
        show(sequence)

        compose.onNodeWithContentDescription("Reorder Avery Quinn").assertExists()
        compose.onNodeWithContentDescription("Reorder Sam Patel").assertExists()
        compose.onNodeWithContentDescription("Reorder Jordan Lee").assertExists()
        compose.onNodeWithContentDescription("Reorder Priya Anand").assertDoesNotExist()
        compose.onNodeWithContentDescription("Reorder Kai Moreno").assertDoesNotExist()
        assertEquals(listOf("Call Priya"), actionsOn("Priya Anand"))
        assertEquals(listOf("Call Kai"), actionsOn("Kai Moreno"))
    }

    @Test
    fun talkback_moves_a_row_up_or_down_where_there_is_somewhere_to_go() {
        show(sequence)

        // After "Call", which they must not replace: set on the row's outer
        // node, the moves dropped "Call {name}" from the merged row.
        assertEquals(listOf("Call Avery", "Move down"), actionsOn("Avery Quinn"))
        assertEquals(listOf("Call Sam", "Move up", "Move down"), actionsOn("Sam Patel"))
        assertEquals(listOf("Call Jordan", "Move up"), actionsOn("Jordan Lee"))

        // Up from second: first, so it follows no one. Up from third: after
        // the first. Down from first: after the second.
        perform("Sam Patel", "Move up")
        perform("Jordan Lee", "Move up")
        perform("Avery Quinn", "Move down")
        assertEquals(
            listOf(
                Triple(2L, null, "Sam Patel"),
                Triple(3L, 1L, "Jordan Lee"),
                Triple(1L, 2L, "Avery Quinn")
            ),
            reorders
        )
    }

    @Test
    fun a_drag_to_the_top_asks_for_it_and_a_failed_drop_puts_the_row_back() {
        val answers = MutableSharedFlow<SnackbarEvent>(extraBufferCapacity = 1)
        show(sequence, snackbarEvents = answers)
        fun top(name: String) = compose.onNodeWithText(name).fetchSemanticsNode().boundsInRoot.top

        // Drag Jordan (third) by the handle, in small steps, past Avery.
        compose.onNodeWithContentDescription("Reorder Jordan Lee", useUnmergedTree = true).performTouchInput {
            down(center)
            repeat(30) { moveBy(Offset(0f, -height / 6f)) }
            up()
        }
        compose.waitForIdle()

        assertEquals(listOf(Triple(3L, null as Long?, "Jordan Lee")), reorders, "to the top: follows no one")
        // This host's state never changes, as when a failed write shows and
        // withdraws the ViewModel's overlay inside one frame: the dropped
        // order holds until the drop is answered...
        assertTrue(top("Jordan Lee") < top("Avery Quinn"))
        // ...and the answer, "Couldn't save your change", puts the row back.
        answers.tryEmit(SnackbarEvent(UiText.res(R.string.components_snackbar_save_failed)))
        compose.waitForIdle()
        assertTrue(top("Avery Quinn") < top("Jordan Lee"))
    }

    @Test
    fun while_selecting_no_row_moves() {
        show(sequence.copy(isMultiSelect = true))

        compose.onAllNodes(hasContentDescription("Reorder", substring = true)).assertCountEquals(0)
        assertEquals(
            emptyList(),
            actionsOn("Sam Patel"),
            "a selecting row is a checkbox, with no dial and no moves"
        )
        compose.onNodeWithText(
            "Drag to change who comes up next. A call, Later or Sooner moves people again."
        )
            .assertDoesNotExist()
    }

    @Test
    fun under_the_curtain_the_handle_says_reorder_alone() {
        show(sequence, curtain = true)

        compose.onAllNodesWithContentDescription("Reorder").assertCountEquals(3)
        compose.onNodeWithContentDescription("Reorder Avery Quinn").assertDoesNotExist()
        compose.onNodeWithText("Avery Quinn").assertDoesNotExist()
    }

    @Test
    fun the_cards_person_is_marked_and_scrolled_into_view() {
        // Thirty people; the card shows the twenty-fifth (the deck moved
        // between the card's read and this one), far below the first screen.
        val people = (1L..30L).map { person(it, "Person $it") }
        val tomorrow = UiText.res(R.string.browse_when_tomorrow)
        show(
            sequence.copy(
                contacts = people,
                rowStatus = emptyMap(),
                dueIds = emptySet(),
                queuePositions = people.withIndex().associate { (i, c) -> c.id to i + 1 },
                whenLabels = people.associate { it.id to tomorrow },
                untilLabels = emptyMap(),
                onYourCardId = "c-25"
            )
        )

        compose.onNodeWithText("On your card").assertIsDisplayed()
        compose.onNodeWithText("Person 25").assertIsDisplayed()
    }
}
