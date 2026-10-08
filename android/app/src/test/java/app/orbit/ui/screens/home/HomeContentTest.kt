package app.orbit.ui.screens.home

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertAll
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasNoClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.orbit.R
import app.orbit.data.entity.CallDirection
import app.orbit.data.entity.ListType
import app.orbit.ui.components.LocalPrivacyCurtain
import app.orbit.ui.theme.OrbitTheme
import app.orbit.ui.util.UiText
import app.orbit.ui.util.formatDayHeader
import app.orbit.ui.util.formatDuration
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What Home's cards say to TalkBack and offer on a long-press, read from the
 * semantics tree under Robolectric (development-cycle.md: a check that only
 * reads the tree runs on the JVM and gates every push).
 *
 * Pinned here because each regressed silently before 2026-10-06: the menu
 * had no order test on Home, and a day column announced its one-letter
 * weekday ("T") instead of the day. Since 2026-10-08 it also pins the card's
 * zones (HOME-5: the name row holds the name and the count, Next up is a row
 * of its own) and that Next up has no call button (HOME-9).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class HomeContentTest {

    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()

    // A fixed Wednesday, so the spoken days are stable whatever day the suite runs.
    private val today: LocalDate = LocalDate.of(2026, 10, 7)

    private fun call(id: Long, direction: CallDirection) = RhythmCall(
        callEventId = id,
        contactId = 1L,
        contactName = "Kai Mensah",
        photoUri = null,
        durationSeconds = 600,
        direction = direction,
        durationLabel = formatDuration(600),
        timeLabel = "4:30pm",
        minuteOfDay = 16 * 60 + 30,
    )

    // index 0 = six days ago (one call, tappable), index 1 = five days ago (quiet).
    private val rhythm = listOf(
        RhythmDay(listOf(call(1L, CallDirection.OUTGOING))),
        RhythmDay(emptyList()),
        RhythmDay(emptyList()),
        RhythmDay(emptyList()),
        RhythmDay(emptyList()),
        RhythmDay(emptyList()),
        RhythmDay(listOf(call(2L, CallDirection.INCOMING))),
    )

    private val state = HomeUiState.Ready(
        lists = listOf(
            ListTileState(
                id = 1L, name = "Inner orbit", dueCount = 0, type = ListType.STATIC, memberCount = 3,
                nextUp = NextUp(1L, "Kai Mensah", null, UiText.res(R.string.home_why_never)),
                rhythm = rhythm,
            ),
            ListTileState(
                id = 2L, name = "Late night", dueCount = 0, type = ListType.SMART, memberCount = 5,
                nextUp = null,
                rhythm = List(7) { RhythmDay(emptyList()) },
            ),
        ),
    )

    private val weeksOpened = mutableListOf<Long>()
    private val listsOpened = mutableListOf<String>()

    private fun setHome(curtain: Boolean = false) {
        compose.setContent {
            CompositionLocalProvider(LocalPrivacyCurtain provides curtain) {
                OrbitTheme {
                    HomeContent(
                        state = state,
                        today = today,
                        onOpenList = { listsOpened += it },
                        onOpenSearch = {},
                        onOpenSettings = {},
                        onOpenLists = {},
                        onCreateList = {},
                        onOpenWeek = { weeksOpened += it },
                    )
                }
            }
        }
    }

    private fun spokenDay(daysAgo: Long): String =
        formatDayHeader(today.minusDays(daysAgo), today).asString(context)

    @Test
    fun long_press_opens_the_menu_in_the_pinned_order() {
        setHome()
        // Long-press the name row, away from "See your week" and the day
        // columns, which are tap targets of their own.
        compose.onNodeWithText("Inner orbit").performTouchInput {
            longClick(Offset(width * 0.2f, height * 0.08f))
        }
        compose.waitForIdle()

        val expected = listOf("Add people", "List settings", "Pause nudges", "Archive", "Delete")
        val byPosition = expected
            .map { label -> label to compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot.top }
            .sortedBy { it.second }
            .map { it.first }
        assertEquals(expected, byPosition)
    }

    // HOME-9, amended 2026-10-08 (the owner: "No need for this icon"). Until
    // then the Next up row ended in a "Call Kai" button; now the row has no
    // control, and a tap on it is the card's tap, which opens the deck. Fails
    // with the button back: its "Call Kai" node is found.
    @Test
    fun next_up_has_no_call_button_and_its_tap_opens_the_deck() {
        setHome()
        compose.onAllNodesWithContentDescription("Call Kai", useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodes(hasClickAction() and hasContentDescription("Call", substring = true), useUnmergedTree = true)
            .assertCountEquals(0)

        compose.onNodeWithText("Kai", useUnmergedTree = true).performClick()

        assertEquals(listOf("1"), listsOpened)
    }

    // HOME-5, amended 2026-10-08 (the owner: "The name can be it's own row
    // with it's own background ... And compact"). The name and the count share
    // one line across the top, the count after the name, and Next up is a row
    // of its own under them. Until then the name sat over the count in a
    // column beside Next up, so on this 411dp window the count was under the
    // name and Next up beside it: both checks below failed.
    @Test
    fun the_name_row_holds_the_name_and_the_count_on_one_line_above_next_up() {
        setHome()
        fun bounds(text: String) =
            compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val name = bounds("Inner orbit")
        val count = bounds(context.resources.getQuantityString(R.plurals.home_member_count, 3, 3))
        val nextUp = bounds(context.getString(R.string.home_next_up_eyebrow))

        assertTrue(count.left >= name.right, "the count comes after the name: $name, $count")
        assertTrue(count.top < name.bottom && name.top < count.bottom, "on the same line: $name, $count")
        assertTrue(nextUp.top >= maxOf(name.bottom, count.bottom), "Next up is a row below: $nextUp")
    }

    // PRIV-03: the open menu's Archive line is text like any other, so the
    // screen hands the builder the masked name (HomeScreen's displayName),
    // not tile.name. Both cards read "List" under the curtain and each card's
    // texts merge into one node, so the first is long-pressed by position.
    @Test
    fun under_the_curtain_the_menu_names_the_list_as_List() {
        setHome(curtain = true)
        compose.onAllNodesWithText("List").onFirst().performTouchInput {
            longClick(Offset(width * 0.2f, height * 0.08f))
        }
        compose.waitForIdle()

        val masked = context.getString(R.string.components_curtain_list)
        compose.onNodeWithText(context.getString(R.string.components_menu_archive_supporting, masked))
            .assertExists()
        compose.onAllNodesWithText("Inner orbit", substring = true).assertCountEquals(0)
    }

    // HOME-8 acceptance: one node per day, named by the day, not the letter.
    @Test
    fun a_day_with_calls_announces_the_full_day_and_what_happened() {
        setHome()
        val expected = context.getString(
            R.string.home_rhythm_day_a11y,
            spokenDay(6),
            context.resources.getQuantityString(R.plurals.home_rhythm_day_calls, 1, 1),
            directionSummary(rhythm[0].calls).asString(context),
        )
        compose.onNodeWithContentDescription(expected).assertHasClickAction()
    }

    // Both cards are quiet five days ago, so two nodes: one per card, each a
    // node of its own (a card's merged announcement carries a click action;
    // a quiet day folded into it would fail the second assertion).
    @Test
    fun a_quiet_day_announces_No_calls_and_is_inert() {
        setHome()
        val expected = context.getString(
            R.string.home_rhythm_day_quiet_a11y,
            spokenDay(5),
            context.getString(R.string.home_direction_none),
        )
        compose.onAllNodesWithContentDescription(expected)
            .assertCountEquals(2)
            .assertAll(hasNoClickAction())
    }

    // HOME-13: the strip's header line is the way to the list's Week screen,
    // one per card, each opening its own list's week.
    @Test
    fun the_strips_header_is_a_button_to_that_lists_week() {
        setHome()
        val buttons = compose.onAllNodesWithText(context.getString(R.string.home_rhythm_see_week))
        buttons.assertCountEquals(2)
        buttons.assertAll(hasClickAction())

        buttons.onFirst().performClick()

        assertEquals(listOf(1L), weeksOpened)
    }

    // HOME-13: the day sheet ends in "See the whole week", which closes the
    // sheet and opens the week that holds the day.
    @Test
    fun the_day_sheet_links_to_the_whole_week() {
        setHome()
        val day = context.getString(
            R.string.home_rhythm_day_a11y,
            spokenDay(6),
            context.resources.getQuantityString(R.plurals.home_rhythm_day_calls, 1, 1),
            directionSummary(rhythm[0].calls).asString(context),
        )
        compose.onNodeWithContentDescription(day).performClick()
        compose.waitForIdle()

        // The sheet is a window of its own, which Robolectric does not route
        // an injected touch into; the click action is what a tap runs.
        compose.onNodeWithText(context.getString(R.string.home_rhythm_see_whole_week))
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()

        assertEquals(listOf(1L), weeksOpened)
        compose.onAllNodesWithText(context.getString(R.string.home_rhythm_see_whole_week)).assertCountEquals(0)
    }

    // The glyph is decorative; the card says "Smart list" for it (LIST-07),
    // with Home's own label since the Lists screen dropped its chip.
    @Test
    fun a_smart_list_card_says_so() {
        setHome()
        compose.onNodeWithText("Late night")
            .assert(hasContentDescription(context.getString(R.string.home_tile_smart_list)))
        compose.onNodeWithText("Inner orbit")
            .assert(!hasContentDescription(context.getString(R.string.home_tile_smart_list)))
    }
}
