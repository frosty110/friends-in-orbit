package app.orbit.ui.screens.home

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertAll
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasNoClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
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
 * had no order test on Home, the call button's label and its curtain mask had
 * none, and a day column announced its one-letter weekday ("T") instead of
 * the day.
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
                nextUp = NextUp(1L, "Kai Mensah", null, UiText.res(R.string.home_why_never), phone = "+1 555 0100"),
                rhythm = rhythm,
            ),
            ListTileState(
                id = 2L, name = "Late night", dueCount = 0, type = ListType.SMART, memberCount = 5,
                nextUp = null,
                rhythm = List(7) { RhythmDay(emptyList()) },
            ),
        ),
    )

    private fun setHome(curtain: Boolean = false) {
        compose.setContent {
            CompositionLocalProvider(LocalPrivacyCurtain provides curtain) {
                OrbitTheme {
                    HomeContent(
                        state = state,
                        today = today,
                        onOpenList = {},
                        onOpenSearch = {},
                        onOpenSettings = {},
                        onOpenLists = {},
                        onCreateList = {},
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
        // Long-press the name block, away from the call button and the day
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

    @Test
    fun the_call_button_names_the_person_by_first_name() {
        setHome()
        compose.onNodeWithContentDescription(context.getString(R.string.home_next_up_call, "Kai"))
            .assertHasClickAction()
    }

    // PRIV-03: a label is as public as text, so the button's name masks too.
    @Test
    fun under_the_curtain_the_call_button_says_Someone() {
        setHome(curtain = true)
        val someone = context.getString(R.string.components_curtain_someone)
        compose.onNodeWithContentDescription(context.getString(R.string.home_next_up_call, someone))
            .assertHasClickAction()
        compose.onAllNodesWithContentDescription(context.getString(R.string.home_next_up_call, "Kai"))
            .assertCountEquals(0)
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

    // The glyph is decorative; the card says "Smart list" for it (LIST-07).
    @Test
    fun a_smart_list_card_says_so() {
        setHome()
        compose.onNodeWithText("Late night")
            .assert(hasContentDescription(context.getString(R.string.lists_row_smart_chip)))
        compose.onNodeWithText("Inner orbit")
            .assert(!hasContentDescription(context.getString(R.string.lists_row_smart_chip)))
    }
}
