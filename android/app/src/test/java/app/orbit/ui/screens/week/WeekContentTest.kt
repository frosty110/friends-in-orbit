package app.orbit.ui.screens.week

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasNoClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.orbit.R
import app.orbit.data.entity.CallDirection
import app.orbit.ui.components.LocalPrivacyCurtain
import app.orbit.ui.screens.home.RhythmCall
import app.orbit.ui.screens.home.RhythmDay
import app.orbit.ui.theme.OrbitTheme
import app.orbit.ui.util.formatClockTime
import app.orbit.ui.util.formatDayHeader
import app.orbit.ui.util.formatDuration
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * HOME-13: what the Week screen offers, read from the semantics tree under
 * Robolectric (development-cycle.md): the arrows' names and when they are
 * off, stepping back and "This week", a day as one TalkBack node that opens
 * its sheet, a short call's 48dp target, the curtain, and the empty and
 * error states.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class WeekContentTest {

    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()

    // A Wednesday; this week runs Thursday 1 to Wednesday 7 October.
    private val today: LocalDate = LocalDate.of(2026, 10, 7)

    private fun call(id: Long, contactId: Long, name: String, h: Int, m: Int, minutes: Int, dir: CallDirection) =
        RhythmCall(
            callEventId = id,
            contactId = contactId,
            contactName = name,
            photoUri = null,
            durationSeconds = minutes * 60,
            direction = dir,
            durationLabel = formatDuration(minutes * 60),
            timeLabel = formatClockTime(LocalTime.of(h, m), use24Hour = false),
            minuteOfDay = h * 60 + m,
        )

    private val saturday = listOf(
        call(2L, 2L, "Mara Ellis", 13, 15, 9, CallDirection.INCOMING),
        call(3L, 3L, "Sam Okafor", 18, 40, 52, CallDirection.OUTGOING),
    )

    private val thisWeek = WeekPage(
        firstDay = today.minusDays(6),
        days = listOf(
            RhythmDay(listOf(call(1L, 1L, "Kai Mensah", 8, 15, 14, CallDirection.OUTGOING))),
            RhythmDay(emptyList()),
            RhythmDay(saturday),
            // A three-minute call: a short block with a full target.
            RhythmDay(listOf(call(4L, 1L, "Kai Mensah", 12, 0, 3, CallDirection.OUTGOING))),
            RhythmDay(emptyList()),
            RhythmDay(emptyList()),
            RhythmDay(emptyList()),
        ),
    )
    private val lastWeek = WeekPage(today.minusDays(13), List(7) { RhythmDay(emptyList()) })

    private val ready = WeekUiState.Ready(listName = "Inner orbit", today = today, weeks = listOf(thisWeek, lastWeek))

    private var opened: Long? = null
    private var backs = 0

    private fun setWeek(state: WeekUiState = ready, curtain: Boolean = false) {
        compose.setContent {
            CompositionLocalProvider(LocalPrivacyCurtain provides curtain) {
                OrbitTheme {
                    WeekContent(
                        state = state,
                        onBack = { backs++ },
                        onRetry = {},
                        onOpenContact = { opened = it },
                        use24Hour = false,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun string(id: Int) = context.getString(id)

    @Test
    fun the_arrows_are_named_and_next_is_off_on_this_week() {
        setWeek()

        compose.onNodeWithText(string(R.string.home_week_this_week)).assertExists()
        compose.onNodeWithContentDescription(string(R.string.home_week_previous)).assertIsEnabled()
        compose.onNodeWithContentDescription(string(R.string.home_week_next)).assertIsNotEnabled()
    }

    @Test
    fun previous_steps_back_a_week_and_This_week_comes_back() {
        setWeek()

        compose.onNodeWithContentDescription(string(R.string.home_week_previous)).performClick()
        compose.waitForIdle()

        compose.onNodeWithText("24 Sep to 30 Sep").assertExists()
        // The earliest week: nothing further back.
        compose.onNodeWithContentDescription(string(R.string.home_week_previous)).assertIsNotEnabled()
        compose.onNodeWithContentDescription(string(R.string.home_week_next)).assertIsEnabled()

        compose.onNodeWithText(string(R.string.home_week_this_week)).performClick()
        compose.waitForIdle()

        compose.onNodeWithText(string(R.string.home_week_this_week)).assertExists()
        compose.onAllNodesWithText("24 Sep to 30 Sep").assertCountEquals(0)
    }

    @Test
    fun a_day_with_calls_is_one_node_that_says_them_and_opens_its_sheet() {
        setWeek()
        val day = today.minusDays(4)
        val expected = weekDayDescription(context, formatDayHeader(day, today).asString(context), saturday, curtain = false)
        assertTrue(expected.startsWith("Saturday 3 October, 2 calls: "), expected)

        compose.onNodeWithContentDescription(expected).assertHasClickAction().performClick()
        compose.waitForIdle()

        // The strip's own day sheet, with no way to the week it is already on.
        compose.onNodeWithText("Saturday 3 October").assertExists()
        compose.onAllNodesWithText(string(R.string.home_rhythm_see_whole_week)).assertCountEquals(0)
    }

    @Test
    fun a_quiet_day_says_No_calls_and_cannot_be_tapped() {
        setWeek()
        val expected = context.getString(
            R.string.home_rhythm_day_quiet_a11y,
            formatDayHeader(today.minusDays(5), today).asString(context),
            string(R.string.home_direction_none),
        )

        compose.onNodeWithContentDescription(expected).assert(hasNoClickAction())
    }

    @Test
    fun a_three_minute_call_keeps_a_48dp_target_that_opens_the_person() {
        setWeek()
        val block = compose.onNodeWithTag(weekCallTag(4L), useUnmergedTree = true)
        val height = block.fetchSemanticsNode().size.height / compose.density.density

        assertTrue(height >= 48f - 0.5f, "touch target is ${height}dp tall")
        // A pointer shortcut: the day's head already says the call to TalkBack.
        block.assert(hasNoClickAction())

        block.performTouchInput { click() }
        assertEquals(1L, opened)
    }

    // The chip is placed only when it fits: Sam's 52 minutes have room, Kai's
    // 14 and 3 minutes do not, so his name is never drawn half in a block.
    @Test
    fun a_long_block_shows_the_first_name_and_a_short_one_does_not() {
        setWeek()
        compose.onNodeWithText("Sam", useUnmergedTree = true).assertIsDisplayed()
        val kai = compose.onAllNodesWithText("Kai", useUnmergedTree = true)
        repeat(kai.fetchSemanticsNodes().size) { kai[it].assertIsNotDisplayed() }
    }

    // PRIV-03: no name on a block, in the title or to TalkBack.
    @Test
    fun under_the_curtain_nobody_is_named() {
        setWeek(curtain = true)

        compose.onNodeWithText(string(R.string.components_curtain_list)).assertExists()
        compose.onAllNodesWithText("Sam", substring = true, useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithText("Inner orbit", substring = true, useUnmergedTree = true).assertCountEquals(0)
        val masked = weekDayDescription(
            context,
            formatDayHeader(today.minusDays(4), today).asString(context),
            saturday,
            curtain = true,
        )
        compose.onNodeWithContentDescription(masked).assertExists()
    }

    @Test
    fun an_empty_week_says_so_over_the_grid() {
        setWeek(state = ready.copy(weeks = listOf(lastWeek.copy(firstDay = today.minusDays(6)))))

        compose.onNodeWithText(string(R.string.home_week_empty)).assertExists()
        compose.onNodeWithContentDescription(string(R.string.home_week_previous)).assertIsNotEnabled()
    }

    @Test
    fun with_nothing_to_retry_the_one_way_out_is_Go_back() {
        setWeek(state = WeekUiState.Error(canRetry = false))

        compose.onAllNodesWithText(string(R.string.components_error_retry)).assertCountEquals(0)
        compose.onNodeWithText(string(R.string.components_action_go_back)).performClick()
        assertEquals(1, backs)
    }
}
