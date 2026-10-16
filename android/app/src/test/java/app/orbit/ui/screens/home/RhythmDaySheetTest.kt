package app.orbit.ui.screens.home

import android.app.Application
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import app.orbit.data.entity.CallDirection
import app.orbit.ui.theme.OrbitSpacing
import app.orbit.ui.theme.OrbitTheme
import app.orbit.ui.util.formatDuration
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * HOME-8 — the rhythm day sheet's summary line.
 *
 * The assertions here are voice rules as much as formatting rules: the two
 * directions must read symmetrically (same verb shape, same weight, no
 * "only"), a zero side must be omitted rather than printed, and no ratio or
 * target may appear. See `vision/00-home/00-home.md` §HOME-8.
 *
 * The copy lives in string resources (strings_home.xml), so the summary is a
 * UiText resolved here against real resources under Robolectric.
 *
 * The rows themselves (2026-10-08, rules.md Design 9): no phone icon trails a
 * call row. The meta line says the direction in words and the avatar's rim
 * draws it; the icon only named the kind a third time, and went at the
 * owner's choice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class RhythmDaySheetTest {

    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun call(id: Long, direction: CallDirection) = RhythmCall(
        callEventId = id,
        contactId = id,
        contactName = "Kai",
        photoUri = null,
        durationSeconds = 600,
        direction = direction,
        durationLabel = formatDuration(600),
        timeLabel = "4:30pm",
        minuteOfDay = 16 * 60 + 30,
    )

    private fun summary(calls: List<RhythmCall>): String = directionSummary(calls).asString(context)

    @Test
    fun `both directions render symmetrically`() {
        val summary = summary(
            listOf(
                call(1L, CallDirection.OUTGOING),
                call(2L, CallDirection.OUTGOING),
                call(3L, CallDirection.INCOMING),
            ),
        )
        assertEquals("You called 2 · They called 1", summary)
    }

    @Test
    fun `all-outgoing day omits the empty half instead of printing a zero`() {
        val summary = summary(listOf(call(1L, CallDirection.OUTGOING)))
        assertEquals("You called 1", summary)
    }

    @Test
    fun `all-incoming day omits the empty half instead of printing a zero`() {
        val summary = summary(listOf(call(1L, CallDirection.INCOMING)))
        assertEquals("They called 1", summary)
    }

    /**
     * The sheet never opens on a quiet day, but the summary is also the a11y
     * label for a day column, so it must degrade to something sayable.
     */
    @Test
    fun `empty day reads as a fact, not a shortfall`() {
        assertEquals("No calls", summary(emptyList()))
    }

    @Test
    fun `direction words are the same shape for both sides`() {
        assertEquals("You called", context.getString(directionWord(CallDirection.OUTGOING)))
        assertEquals("They called", context.getString(directionWord(CallDirection.INCOMING)))
    }

    /**
     * The icon was decorative ([app.orbit.ui.components.PhIcon] gives it no
     * description), so it has no node to look for; this reads where it sat
     * from the layout. A name too long for its row is cut short at the end of
     * the space it is given, so its right edge is where the row's words stop.
     * With nothing after them that is the row's own end padding; with the
     * 18dp icon and its 8dp spacer back, the words stopped 26dp sooner.
     */
    @Test
    fun `a call row says which way in words and nothing trails them`() {
        val longName = "Kai Mensah-Abernathy Oyelaran-Whitfield of the very long surname"
        compose.setContent {
            OrbitTheme {
                RhythmDaySheet(
                    dayLabel = "Today",
                    calls = listOf(
                        call(1L, CallDirection.OUTGOING).copy(contactName = longName),
                        call(2L, CallDirection.INCOMING),
                    ),
                    curtain = false,
                    onOpenContact = {},
                    onDismiss = {},
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("You called · 10 min · 4:30pm", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("They called · 10 min · 4:30pm", useUnmergedTree = true).assertExists()

        val row = compose.onNode(hasClickAction() and hasText(longName)).getBoundsInRoot()
        val name = compose.onNodeWithText(longName, useUnmergedTree = true).getBoundsInRoot()
        val endPadding = OrbitSpacing().x2
        val gap = row.right - name.right
        assertTrue(
            "the name runs to the row's end padding, with no icon after it ($gap from the edge)",
            abs((gap - endPadding).value) < 0.5f,
        )
    }
}
