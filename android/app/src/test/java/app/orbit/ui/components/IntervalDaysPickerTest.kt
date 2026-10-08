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

    private fun picker(hours: Int, valueIsSet: Boolean = true) {
        compose.setContent {
            OrbitTheme { IntervalDaysPicker(currentHours = hours, onCommit = { commits += it }, valueIsSet = valueIsSet) }
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
    fun one_day_is_reachable_and_reads_every_day() {
        // "every 1 day" is not how anyone says it (LIST-30).
        picker(hours = 24)
        compose.onNodeWithText("Aim for every day").assertExists()
        compose.onNodeWithContentDescription("How often to aim for")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Every day"))
    }

    @Test
    fun choosing_the_saved_value_again_writes_nothing() {
        picker(hours = 48)
        compose.onNodeWithContentDescription("How often to aim for")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(2f) }
        compose.waitForIdle()

        assertEquals(emptyList(), commits)
    }

    @Test
    fun with_nothing_saved_the_value_it_opens_on_can_be_chosen() {
        // A list with no rhythm yet opens on 2 days (LIST-30); 2 days must
        // still be choosable, or "This list has no rhythm yet" never goes.
        picker(hours = 48, valueIsSet = false)
        compose.onNodeWithContentDescription("How often to aim for")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(2f) }
        compose.waitForIdle()

        assertEquals(listOf(2), commits)
    }

    @Test
    fun stored_hours_map_to_days_without_reading_under_one() {
        assertEquals(2, intervalDaysFromHours(48))
        assertEquals(1, intervalDaysFromHours(24))
        assertEquals(1, intervalDaysFromHours(0))
        assertEquals(60, intervalDaysFromHours(90 * 24))
    }
}
