package app.orbit.ui.components

import android.app.Application
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.orbit.ui.theme.OrbitTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * OrbitScreenMessage's optional secondary action (2026-10-06): a state with
 * two honest ways forward gets a Ghost button under the first, so Card view's
 * empty shell no longer needs a layout of its own (state-9). The single-action
 * call is unchanged: one button, nothing else to tap.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class OrbitScreenMessageTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun a_secondary_action_is_a_second_button_that_fires() {
        val fired = mutableListOf<String>()
        compose.setContent {
            OrbitTheme {
                OrbitScreenMessage(
                    title = "No one on this list yet",
                    actionLabel = "Add people",
                    onAction = { fired += "add" },
                    secondaryLabel = "Browse people",
                    onSecondary = { fired += "browse" },
                )
            }
        }

        compose.onAllNodes(hasClickAction()).assertCountEquals(2)
        compose.onNodeWithText("Browse people")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        compose.runOnIdle { assertEquals(listOf("browse"), fired) }
    }

    @Test
    fun without_a_secondary_there_is_one_button() {
        compose.setContent {
            OrbitTheme {
                OrbitScreenMessage(title = "All quiet for now", actionLabel = "Browse people", onAction = {})
            }
        }

        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
    }
}
