package app.orbit.ui.screens.calllog

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.orbit.R
import app.orbit.ui.components.LocalPrivacyCurtain
import app.orbit.ui.theme.OrbitSpacing
import app.orbit.ui.theme.OrbitTheme
import app.orbit.ui.util.UiText
import app.orbit.ui.util.formatDuration
import kotlin.math.abs
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What TalkBack hears from Call history, read from the semantics tree on the
 * JVM (the `ContactDetailCurtainTest` precedent):
 *
 *  - the pane title in every state of one person's log. Until 2026-10-06 the
 *    bar was blank whenever the person's name had not loaded, which is
 *    momentary while Loading but permanent in Error (a read that failed
 *    before its first emission, the case Retry exists for), so TalkBack
 *    announced no screen at all;
 *  - the row's "More actions for {name}" button, named for the person it acts
 *    on and masked under the privacy curtain like every other name (PRIV-03);
 *  - an ignored person's row (IGNORE-09, amended 2026-10-08): the name plain,
 *    and "Ignored" as the first segment of the second line. Until then the
 *    name carried "(ignored)", a tag on the title (rules.md Design 9). The
 *    row is still greyed, but grey alone would leave colour as the only
 *    signal, so the word stays;
 *  - no glyph names a row's kind (rules.md Design 9, the owner's choice of
 *    2026-10-08): everyone's log ends the second line with the kind's word
 *    and the row with the time alone, and one person's log starts each row
 *    with what happened. A direction icon is decorative (`PhIcon` gives it
 *    no description), so it has no node to look for; these tests read where
 *    it sat from the layout instead, and each fails with the icon back.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class CallLogContentTest {

    @get:Rule val compose = createComposeRule()

    private val paneTitle = SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle)

    private fun titled(title: String) = SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, title)

    private fun show(state: CallLogUiState, curtain: Boolean = false) {
        compose.setContent {
            CompositionLocalProvider(LocalPrivacyCurtain provides curtain) {
                OrbitTheme {
                    CallLogContent(
                        state = state,
                        onBack = {},
                        onOpenContact = { _, _ -> },
                        onCallAgain = {},
                        onFilterChange = {},
                        onShowMore = {},
                        onOpenSettings = {},
                        onRetry = {},
                    )
                }
            }
        }
    }

    @Test
    fun everyone_is_call_history() {
        show(CallLogUiState.Loading())
        compose.onNode(titled("Call history")).assertExists()
    }

    @Test
    fun one_person_still_loading_has_no_title_yet() {
        // The moment the person's row takes to arrive: blank rather than a
        // false "Call history" for everyone, and no empty pane title.
        show(CallLogUiState.Loading(CallLogScope.Person(contactId = 1L)))
        compose.onAllNodes(paneTitle).assertCountEquals(0)
    }

    @Test
    fun one_person_whose_read_failed_before_the_name_loaded_is_titled_call_history() {
        show(CallLogUiState.Error(CallLogScope.Person(contactId = 1L)))
        compose.onNode(titled("Call history")).assertExists()
    }

    @Test
    fun one_person_denied_before_the_name_loaded_is_titled_call_history() {
        show(CallLogUiState.PermissionDenied(CallLogScope.Person(contactId = 1L)))
        compose.onNode(titled("Call history")).assertExists()
    }

    @Test
    fun one_person_with_a_name_is_titled_calls_with_them() {
        show(CallLogUiState.Error(CallLogScope.Person(contactId = 1L, name = "Avery Quinn")))
        compose.onNode(titled("Calls with Avery Quinn")).assertExists()
    }

    @Test
    fun the_more_button_is_named_for_the_person() {
        show(ready())
        compose.onNodeWithContentDescription("More actions for Jordan Lee").assertExists()
    }

    @Test
    fun under_the_curtain_the_more_button_names_no_one() {
        show(ready(), curtain = true)
        compose.onNodeWithContentDescription("More actions for Contact").assertExists()
        compose.onAllNodes(SemanticsMatcher("names Jordan") { node ->
            node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.any { "Jordan" in it }
        }).assertCountEquals(0)
    }

    @Test
    fun an_ignored_row_keeps_the_name_plain_and_says_ignored_first_on_its_second_line() {
        show(ready(isIgnored = true, durationMinutes = 12))
        compose.onNodeWithText("Jordan Lee", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Ignored · from Inner orbit · 12 min · Outgoing", useUnmergedTree = true)
            .assertIsDisplayed()
        compose.onAllNodesWithText("(ignored)", substring = true, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun under_the_curtain_an_ignored_row_still_says_ignored_and_names_no_list() {
        show(ready(isIgnored = true, durationMinutes = 12), curtain = true)
        compose.onNodeWithText("Contact", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Ignored · 12 min · Outgoing", useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithText("Inner orbit", substring = true, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun a_row_for_someone_not_ignored_does_not_say_ignored() {
        show(ready(durationMinutes = 12))
        compose.onNodeWithText("from Inner orbit · 12 min · Outgoing", useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithText("Ignored", substring = true, ignoreCase = true, useUnmergedTree = true)
            .assertCountEquals(0)
    }

    // ─── No glyph for the kind (rules.md Design 9, 2026-10-08) ─────────────

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun everyones_rows_say_their_kind_in_words_and_end_in_the_time_alone() {
        show(
            CallLogUiState.Ready(
                sections = listOf(
                    section(
                        row(1L, "Jordan Lee", CallLogKind.Outgoing, durationMinutes = 12, timeLabel = "4:30pm"),
                        row(2L, "Sam Okafor", CallLogKind.Incoming, durationMinutes = 12, timeLabel = "3:10pm"),
                        row(3L, "Priya Anand", CallLogKind.Logged, durationMinutes = null, timeLabel = "1:05pm"),
                        row(4L, "Avery Quinn", CallLogKind.Attempted, durationMinutes = null, timeLabel = "9:40am"),
                    ),
                ),
            ),
        )
        mapOf(
            "Jordan Lee" to ("from Inner orbit · 12 min · Outgoing" to "4:30pm"),
            "Sam Okafor" to ("from Inner orbit · 12 min · Incoming" to "3:10pm"),
            "Priya Anand" to ("from Inner orbit · Logged" to "1:05pm"),
            "Avery Quinn" to ("from Inner orbit · Attempted" to "9:40am"),
        ).forEach { (name, expected) ->
            val (line, time) = expected
            compose.onNodeWithText(line, useUnmergedTree = true).assertIsDisplayed()
            val rowBounds = compose.onNode(hasClickAction() and hasText(name)).getBoundsInRoot()
            val timeBounds = compose.onNodeWithText(time, useUnmergedTree = true).getBoundsInRoot()
            val more = compose.onNodeWithContentDescription("More actions for $name").getBoundsInRoot()
            // The time is the only thing in its slot, so the row centres it.
            // With the 18dp icon back above it, it sat 11dp below the middle.
            val offCentre = ((timeBounds.top + timeBounds.bottom) - (rowBounds.top + rowBounds.bottom)) / 2
            assertTrue(abs(offCentre.value) < 1f, "$name's time is alone at the row's end, $offCentre off centre")
            assertTrue(timeBounds.right <= more.left, "$name's time is still at the row's end, before its menu button")
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun one_persons_rows_start_with_what_happened_where_the_glyph_was() {
        show(
            CallLogUiState.Ready(
                sections = listOf(
                    section(
                        row(1L, "Jordan Lee", CallLogKind.Outgoing, durationMinutes = 12, timeLabel = "4:30pm"),
                        row(2L, "Jordan Lee", CallLogKind.Incoming, durationMinutes = 12, timeLabel = "3:10pm"),
                        row(3L, "Jordan Lee", CallLogKind.Logged, durationMinutes = null, timeLabel = "1:05pm"),
                        row(4L, "Jordan Lee", CallLogKind.Attempted, durationMinutes = null, timeLabel = "9:40am"),
                    ),
                ),
                scope = CallLogScope.Person(contactId = 1L, name = "Jordan Lee"),
            ),
        )
        val rowStart = OrbitSpacing().x4
        listOf("You called", "Jordan called", "You logged a connection", "You tried to reach them").forEach { title ->
            val rowBounds = compose.onNode(hasClickAction() and hasText(title)).getBoundsInRoot()
            val titleBounds = compose.onNodeWithText(title, useUnmergedTree = true)
                .assertIsDisplayed()
                .getBoundsInRoot()
            // The row's own start padding, where the day headers start too.
            // With the 44dp circle back in the face's slot it started 72dp in.
            val inset = titleBounds.left - rowBounds.left
            assertTrue(abs((inset - rowStart).value) < 0.5f, "\"$title\" starts the row, $inset in")
        }
    }

    private fun section(vararg rows: CallLogRow) = CallLogDaySection(
        epochDay = 20_500L,
        label = UiText.res(R.string.time_day_today),
        rows = rows.toList(),
    )

    private fun row(
        id: Long,
        name: String,
        kind: CallLogKind,
        durationMinutes: Int?,
        timeLabel: String = "4:30pm",
        isIgnored: Boolean = false,
    ) = CallLogRow(
        callEventId = id,
        contactId = id,
        name = name,
        phone = "+1555000$id",
        photoUri = null,
        listName = "Inner orbit",
        durationLabel = durationMinutes?.let { formatDuration(it * 60) },
        timeLabel = timeLabel,
        isIgnored = isIgnored,
        kind = kind,
    )

    private fun ready(isIgnored: Boolean = false, durationMinutes: Int? = null): CallLogUiState = CallLogUiState.Ready(
        sections = listOf(
            section(row(1L, "Jordan Lee", CallLogKind.Outgoing, durationMinutes, isIgnored = isIgnored)),
        ),
    )
}
