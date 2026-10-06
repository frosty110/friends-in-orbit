package app.orbit.ui.components

import android.app.Application
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.orbit.ui.theme.OrbitTheme
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The two private copies of this notice had drifted: Call history's action
 * announced as a button and Card view's did not (calllog-13). The shared one
 * always does, and keeps the 48dp floor (rules.md §Design 3).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class OrbitInlineNoticeTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun the_action_is_a_48dp_button_that_fires() {
        var opened = false
        compose.setContent {
            OrbitTheme {
                OrbitInlineNotice(
                    text = "Orbit can't see your calls",
                    actionLabel = "Open settings",
                    onAction = { opened = true },
                )
            }
        }

        compose.onNodeWithText("Open settings")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        compose.runOnIdle { assertTrue(opened) }
    }
}
