package app.orbit.ui.screens.contact

import android.app.Application
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.orbit.ui.screens.contact.sections.UnpauseBanner
import app.orbit.ui.theme.OrbitTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * CONTACT-05: UnpauseBanner rendering + dismiss contract.
 *
 * The component is stateless (caller manages visibility via
 * AnimatedVisibility) so this test composes the banner directly: the heading
 * and body render, the curtain variant renders the generic heading, and both
 * the x and the row itself fire `onUnpause`. The row is pinned as a button
 * named "Dismiss unpause notice": it was a bare clickable, so TalkBack read
 * the copy and then "double tap to activate" with no hint of what that did.
 *
 * On the JVM under Robolectric since 2026-10-06 (it lived under androidTest
 * before, where only the emulator job ran it). The VM side (a lapsed timed
 * pause sets `unpausePromptVisible`, never an indefinite one) is pinned in
 * ContactDetailViewModelTest.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class UnpauseBannerTest {

    @get:Rule val compose = createComposeRule()

    private fun show(curtain: Boolean = false, onUnpause: () -> Unit = {}) {
        compose.setContent {
            OrbitTheme {
                UnpauseBanner(
                    contactName = "Alex Chen",
                    curtain = curtain,
                    onUnpause = onUnpause
                )
            }
        }
    }

    @Test
    fun banner_displays_heading_with_contact_name_and_the_body() {
        show()
        compose.onNodeWithText("Alex Chen is unpaused").assertIsDisplayed()
        // "Come up", not "surface", and "their lists", not "this list".
        compose.onNodeWithText("They'll come up again on their lists.").assertIsDisplayed()
    }

    @Test
    fun banner_curtain_renders_generic_heading() {
        show(curtain = true)
        compose.onNodeWithText("Contact is unpaused").assertIsDisplayed()
    }

    @Test
    fun tap_dismiss_x_invokes_callback() {
        var unpauseCalls = 0
        show(onUnpause = { unpauseCalls++ })
        compose.onNodeWithContentDescription("Dismiss unpause notice").performClick()
        assertEquals(1, unpauseCalls)
    }

    @Test
    fun the_row_is_a_button_named_for_what_it_does_and_fires_too() {
        var unpauseCalls = 0
        show(onUnpause = { unpauseCalls++ })
        val row = compose.onNode(hasText("Alex Chen is unpaused"))
        row.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        row.assert(
            SemanticsMatcher("click label is the dismiss label") { node ->
                node.config.getOrNull(SemanticsActions.OnClick)?.label == "Dismiss unpause notice"
            }
        )
        row.performClick()
        assertEquals(1, unpauseCalls)
    }
}
