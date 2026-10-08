package app.orbit.ui.components

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.orbit.ui.theme.OrbitTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * "Aim for every N days" on the day wheel, the one control List settings and
 * Contact detail's custom schedule share (ADR 0011). Replaces
 * IntervalScaleLabelsTest, which pinned where the slider's tick labels sat.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class IntervalDaysPickerTest {

    @get:Rule val compose = createComposeRule()

    private val commits = mutableListOf<Int>()

    private fun picker(hours: Int) {
        compose.setContent {
            OrbitTheme { IntervalDaysPicker(currentHours = hours, onCommit = { commits += it }) }
        }
    }

    @Test
    fun the_two_day_default_reads_two_days() {
        // The slider drew this flush against its "1 day" end (ADR 0010).
        picker(hours = 48)
        compose.onNodeWithText("Aim for every 2 days").assertExists()
        compose.onNodeWithContentDescription("How often to aim for")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Every 2 days"))
    }

    @Test
    fun turning_it_commits_whole_days_once_and_the_sentence_follows() {
        picker(hours = 48)
        compose.onNodeWithContentDescription("How often to aim for")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(14f) }
        compose.waitForIdle()

        assertEquals(listOf(14), commits)
        compose.onNodeWithText("Aim for every 14 days").assertExists()
    }

    @Test
    fun one_day_is_reachable_and_singular() {
        picker(hours = 24)
        compose.onNodeWithText("Aim for every 1 day").assertExists()
    }

    @Test
    fun stored_hours_map_to_days_without_reading_under_one() {
        assertEquals(2, intervalDaysFromHours(48))
        assertEquals(1, intervalDaysFromHours(24))
        assertEquals(1, intervalDaysFromHours(0))
        assertEquals(60, intervalDaysFromHours(90 * 24))
    }
}
