package app.orbit.ui.screens.onboarding

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.orbit.ui.theme.OrbitTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The Done screen's two promises, pinned on the stateless
 * [OnboardingDoneContent] (the outer screen defaults to `hiltViewModel()`):
 *
 *  - ONB-30: with notifications not yet granted, the nudge ask is on this
 *    screen, with its "Allow nudges" button; once answered it is a plain line.
 *  - The CTA gate: "Open Orbit" waits for the completion write, so a relaunch
 *    never lands back in onboarding half-done (ONB-23's clean exit depends
 *    on the flag being written first).
 *
 * Semantics only (labels, text, enabled), so it runs on the JVM under
 * Robolectric like `ContactDetailCurtainTest`. The nudge card sits below the
 * swipe hint in the scaffold's scrolling body, under Robolectric's small
 * default window, so the checks are `assertExists` (the node is in the tree)
 * and a tap scrolls to the button first, as a thumb would.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class OnboardingDoneScreenTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun with_notifications_denied_the_nudge_ask_shows_and_Open_Orbit_waits_for_the_save() {
        var allowTaps = 0
        compose.setContent {
            OrbitTheme {
                OnboardingDoneContent(
                    completed = false,
                    onFinish = {},
                    nudges = NudgeAsk.Ask,
                    onAllowNudges = { allowTaps++ },
                )
            }
        }

        compose.onNodeWithText("Want a gentle nudge when someone is worth a call?").assertExists()
        compose.onNodeWithText("Allow nudges").assertHasClickAction().performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, allowTaps) }

        compose.onNodeWithText("Open Orbit").assertIsNotEnabled()
    }

    @Test
    fun once_the_completion_is_written_Open_Orbit_is_enabled_and_fires() {
        var finished = 0
        compose.setContent {
            OrbitTheme {
                OnboardingDoneContent(completed = true, onFinish = { finished++ }, nudges = NudgeAsk.Ask)
            }
        }

        compose.onNodeWithText("Open Orbit").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, finished) }
    }

    @Test
    fun an_answered_ask_is_a_plain_line_with_no_button() {
        compose.setContent {
            OrbitTheme {
                OnboardingDoneContent(completed = true, onFinish = {}, nudges = NudgeAsk.Declined)
            }
        }

        compose.onNodeWithText("No nudges for now. You can turn them on in Settings.").assertExists()
        compose.onAllNodes(hasText("Allow nudges")).assertCountEquals(0)
    }

    // onb-5: the screen names itself to TalkBack (pane title) and its title is
    // a heading, so a screen change is announced and reachable by heading.
    @Test
    fun the_screen_has_a_pane_title_and_a_heading() {
        compose.setContent {
            OrbitTheme {
                OnboardingDoneContent(completed = true, onFinish = {}, nudges = NudgeAsk.On)
            }
        }

        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "You're set up."), useUnmergedTree = true)
            .assertExists()
        compose.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
                .and(hasText("You're set up.")),
            useUnmergedTree = true,
        ).assertExists()
    }
}
