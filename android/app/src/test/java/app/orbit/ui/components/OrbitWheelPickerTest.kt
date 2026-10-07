package app.orbit.ui.components

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.orbit.ui.theme.OrbitTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The day and number wheel (ADR 0011): one adjustable control for TalkBack,
 * its value in words, one write per gesture, and a value from outside that
 * moves it without writing. Drives it the way TalkBack does, through
 * SetProgress, since the values inside are not separate stops.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class OrbitWheelPickerTest {

    @get:Rule val compose = createComposeRule()

    private val commits = mutableListOf<Int>()
    private val live = mutableListOf<Int>()
    private var saved by mutableIntStateOf(14)

    private fun wheel(range: IntRange = 1..60) {
        compose.setContent {
            OrbitTheme {
                OrbitWheelPicker(
                    value = saved,
                    range = range,
                    onValueChange = { live += it },
                    onValueCommit = { commits += it },
                    label = "How often to aim for",
                    valueDescription = { "Every $it days" },
                )
            }
        }
    }

    private val node get() = compose.onNodeWithContentDescription("How often to aim for")

    @Test
    fun talkback_hears_one_control_with_its_value_in_words() {
        wheel()
        node.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Every 14 days"))
        node.assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo(current = 14f, range = 1f..60f, steps = 58),
            ),
        )
    }

    @Test
    fun an_adjustment_commits_once_and_says_the_new_value() {
        wheel()
        node.performSemanticsAction(SemanticsActions.SetProgress) { it(30f) }
        compose.waitForIdle()

        assertEquals(listOf(30), commits)
        assertEquals(30, live.last())
        node.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Every 30 days"))
    }

    @Test
    fun choosing_the_value_it_already_has_writes_nothing() {
        wheel()
        node.performSemanticsAction(SemanticsActions.SetProgress) { it(14f) }
        compose.waitForIdle()

        assertEquals(emptyList(), commits)
    }

    @Test
    fun a_target_past_either_end_stops_at_the_end() {
        wheel()
        node.performSemanticsAction(SemanticsActions.SetProgress) { it(500f) }
        compose.waitForIdle()

        assertEquals(listOf(60), commits)
    }

    @Test
    fun a_saved_value_from_outside_moves_the_wheel_without_writing() {
        wheel()
        saved = 7
        compose.waitForIdle()

        node.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Every 7 days"))
        assertEquals(emptyList(), commits)
    }

    @Test
    fun a_repeat_before_the_save_returns_does_not_write_twice() {
        // The saved value only comes back after Room round-trips the write;
        // until then `saved` still says 14. A second identical adjustment in
        // that window must not write again.
        wheel()
        node.performSemanticsAction(SemanticsActions.SetProgress) { it(21f) }
        compose.waitForIdle()
        node.performSemanticsAction(SemanticsActions.SetProgress) { it(21f) }
        compose.waitForIdle()

        assertEquals(listOf(21), commits)
    }
}
