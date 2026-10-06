package app.orbit.ui.screens.contact

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.orbit.R
import app.orbit.data.CallDirection
import app.orbit.data.CallEntry
import app.orbit.ui.util.UiText
import app.orbit.ui.util.formatDuration
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Contact detail's semantics on the JVM (the ContactDetailCurtainTest
 * precedent): what is on screen and what TalkBack hears, over the stateless
 * content with no ViewModel.
 *
 * The deep-link test is the regression for contact-detail-1: arriving from
 * Call history scrolled one item too far for a person on one list, because
 * the index was hand-counted ("8 = max items before recent calls"), so the
 * target call and its "Add note to this call" field sat just above the fold.
 * The rows are now keyed by call-event id and the index comes from the same
 * key list that builds the LazyColumn. Robolectric's default window is
 * 320x470dp, so twenty calls are well past the fold.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class ContactDetailScreenTest {

    @get:Rule val compose = createComposeRule()

    /** [count] calls, newest first, each "N days ago" so every row reads differently. */
    private fun calls(count: Int): Pair<List<CallEntry>, List<Long>> {
        val entries = (1..count).map { i ->
            CallEntry(
                direction = if (i % 2 == 0) CallDirection.Incoming else CallDirection.Outgoing,
                relativeWhen = UiText.plural(R.plurals.time_ago_days, i, i),
                lengthLabel = formatDuration(i * 60)
            )
        }
        return entries to (1..count).map { 100L + it }
    }

    private fun scrollTo(key: String) {
        compose.onNode(hasScrollToKeyAction()).performScrollToKey(key)
    }

    @Test
    fun arriving_from_a_call_shows_that_row_and_add_note_to_this_call() {
        val (recentCalls, ids) = calls(20)
        // The fifteenth call: on a single list it was the one row that the
        // old arithmetic scrolled past.
        val target = ids[14]
        compose.setContent {
            ContactDetailPreviewHost(
                previewState.copy(
                    listsOn = listOf("Inner orbit"),
                    recentCalls = recentCalls,
                    recentCallEventIds = ids,
                    scrollToCallEventId = target,
                    retroNoteAffordanceFor = target
                )
            )
        }
        compose.onNodeWithText("15 days ago").assertIsDisplayed()
        compose.onNodeWithText("Add note to this call").assertIsDisplayed()
    }

    @Test
    fun call_direction_is_in_words_for_the_screen_reader() {
        // The fixture has one outgoing and one incoming call.
        compose.setContent { ContactDetailPreviewHost(previewState) }
        scrollTo(ContactDetailItemKey.RECENT_CALLS)
        compose.onNodeWithContentDescription("You called", useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("Avery called", useUnmergedTree = true).assertExists()
    }

    @Test
    fun the_pane_is_titled_with_the_name_and_the_name_is_a_heading() {
        compose.setContent { ContactDetailPreviewHost(previewState) }
        compose.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, CURTAIN_FIXTURE_NAME),
            useUnmergedTree = true
        ).assertExists()
        compose.onNodeWithText(CURTAIN_FIXTURE_NAME, useUnmergedTree = true)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    }

    @Test
    fun without_call_log_access_nothing_claims_never_called() {
        compose.setContent {
            ContactDetailPreviewHost(
                ContactDetailUiState.Ready(
                    contact = previewContact.copy(
                        lastCalledLabel = null,
                        totalCalls = 0,
                        measuredCalls = 0,
                        avgLengthLabel = null,
                        bestWindowLabel = null
                    ),
                    notes = emptyList(),
                    listsOn = emptyList(),
                    recentCalls = emptyList(),
                    longestGapLabel = null,
                    callLogDenied = true
                )
            )
        }
        scrollTo(ContactDetailItemKey.STATS)
        compose.onNodeWithText("Orbit can't see your phone calls").assertIsDisplayed()
        compose.onNodeWithText("Open settings").assertIsDisplayed()
        compose.onNodeWithText("Total calls").assertIsDisplayed()
        compose.onNodeWithText("Never called").assertDoesNotExist()
        compose.onNodeWithText("Not enough calls yet").assertDoesNotExist()
        scrollTo(ContactDetailItemKey.RECENT_CALLS)
        compose.onNodeWithText("Recent calls").assertIsDisplayed()
        compose.onNodeWithText("No calls yet.").assertDoesNotExist()
    }

    @Test
    fun average_length_waits_for_three_measured_calls_not_three_calls() {
        // One carrier call and two logged connections: three calls, one length.
        // The fixture's longest gap and Usually have values, so the one
        // "Not enough calls yet" is Average length's.
        compose.setContent {
            ContactDetailPreviewHost(
                previewState.copy(
                    contact = previewContact.copy(totalCalls = 3, measuredCalls = 1)
                )
            )
        }
        scrollTo(ContactDetailItemKey.STATS)
        compose.onNodeWithText("Average length").assertIsDisplayed()
        compose.onAllNodesWithText("Not enough calls yet").assertCountEquals(1)
    }

    @Test
    fun average_length_shows_once_three_calls_were_measured() {
        compose.setContent {
            ContactDetailPreviewHost(
                previewState.copy(
                    contact = previewContact.copy(totalCalls = 3, measuredCalls = 3)
                )
            )
        }
        scrollTo(ContactDetailItemKey.STATS)
        compose.onNodeWithText("Average length").assertIsDisplayed()
        compose.onAllNodesWithText("Not enough calls yet").assertCountEquals(0)
    }

    @Test
    fun an_ignored_person_says_so_under_the_number() {
        compose.setContent { ContactDetailPreviewHost(previewState.copy(isIgnored = true)) }
        compose.onNodeWithText("Ignored").assertIsDisplayed()
    }

    @Test
    fun an_archived_person_says_so_under_the_number() {
        compose.setContent { ContactDetailPreviewHost(previewState.copy(isArchived = true)) }
        compose.onNodeWithText("Archived").assertIsDisplayed()
    }

    @Test
    fun an_orphaned_person_keeps_their_notes_readable_but_not_editable() {
        compose.setContent {
            ContactDetailPreviewHost(
                ContactDetailUiState.Orphaned(
                    contact = previewContact,
                    listsOn = previewState.listsOn,
                    recentCalls = previewState.recentCalls,
                    longestGapLabel = previewState.longestGapLabel,
                    notes = previewState.notes,
                    recentCallEventIds = previewState.recentCallEventIds
                )
            )
        }
        scrollTo(ContactDetailItemKey.NOTES)
        compose.onNodeWithText(previewState.notes.single().body).assertIsDisplayed()
        compose.onNodeWithText("Add a note").assertDoesNotExist()
        compose.onNodeWithContentDescription("More actions for this note").assertDoesNotExist()
    }
}
