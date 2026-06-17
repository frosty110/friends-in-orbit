package app.orbit.ui.screens.contact

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.orbit.ui.screens.contact.sections.UnpauseBanner
import app.orbit.ui.theme.OrbitTheme
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CONTACT-05 — UnpauseBanner visibility + tap-to-clear contract.
 *
 * The component is stateless (caller manages visibility via
 * AnimatedVisibility) so this test class composes the banner directly,
 * asserts the heading + body strings render, asserts the curtain variant
 * renders the generic heading, and asserts the dismiss-x affordance fires
 * the `onUnpause` callback.
 *
 * The `androidx.compose.ui:ui-test-junit4` + `ui-test-manifest` deps are
 * wired into the catalog + androidTest source set so `createComposeRule()`
 * is available; the OrphanBannerTest sibling adopts the same pattern.
 *
 * VM-side coverage (pausedUntil <= now → state.unpausePromptVisible) is
 * unit-tested by ContactDetailViewModelTest; this test focuses on the
 * composable's rendering + click contract.
 */
@RunWith(AndroidJUnit4::class)
class UnpauseBannerTest {

    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun banner_displays_heading_with_contact_name() {
        composeTestRule.setContent {
            OrbitTheme {
                UnpauseBanner(
                    contactName = "Alex Chen",
                    curtain = false,
                    onUnpause = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Alex Chen is unpaused").assertIsDisplayed()
        composeTestRule.onNodeWithText("They'll surface again on this list.").assertIsDisplayed()
    }

    @Test
    fun banner_curtain_renders_generic_heading() {
        composeTestRule.setContent {
            OrbitTheme {
                UnpauseBanner(
                    contactName = "Alex Chen",
                    curtain = true,
                    onUnpause = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Contact is unpaused").assertIsDisplayed()
    }

    @Test
    fun tap_dismiss_x_invokes_callback() {
        var unpauseCalled = false
        composeTestRule.setContent {
            OrbitTheme {
                UnpauseBanner(
                    contactName = "Alex Chen",
                    curtain = false,
                    onUnpause = { unpauseCalled = true },
                )
            }
        }
        composeTestRule.onNodeWithContentDescription("Dismiss unpause notice").performClick()
        assertTrue(unpauseCalled, "onUnpause should fire when dismiss-x is tapped")
    }
}
