package app.orbit.ui.screens.contact

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.orbit.ui.theme.OrbitTheme
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CONTACT-06 — OrphanBanner rendering + tap-callback contract.
 *
 * The component is stateless (caller manages visibility from
 * `ContactDetailUiState.Orphaned`) so this test class composes the banner
 * directly, asserts the verbatim heading + Re-link + Archive button labels
 * render, and asserts each button invokes its corresponding callback.
 *
 * `androidx.compose.ui:ui-test-junit4` + `ui-test-manifest` are wired into
 * the catalog + androidTest source set, and [OrphanBanner]'s visibility is
 * `internal` so this sibling test can reference the composable directly.
 *
 * VM-side coverage (orphan flag → state.Orphaned → callbacks dispatch the
 * use case + nav event) is unit-tested by ContactDetailViewModelTest; this
 * test focuses on the composable's rendering + click contract.
 */
@RunWith(AndroidJUnit4::class)
class OrphanBannerTest {

    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun banner_renders_relink_and_archive_buttons() {
        composeTestRule.setContent {
            OrbitTheme {
                OrphanBanner(onRelink = {}, onArchive = {})
            }
        }
        composeTestRule.onNodeWithText("This contact was deleted from your phone").assertIsDisplayed()
        composeTestRule.onNodeWithText("Re-link").assertIsDisplayed()
        composeTestRule.onNodeWithText("Archive").assertIsDisplayed()
    }

    @Test
    fun tap_relink_invokes_callback() {
        var relinkCalled = false
        composeTestRule.setContent {
            OrbitTheme {
                OrphanBanner(onRelink = { relinkCalled = true }, onArchive = {})
            }
        }
        composeTestRule.onNodeWithText("Re-link").performClick()
        assertTrue(relinkCalled, "onRelink should fire when Re-link is tapped")
    }

    @Test
    fun tap_archive_invokes_callback() {
        var archiveCalled = false
        composeTestRule.setContent {
            OrbitTheme {
                OrphanBanner(onRelink = {}, onArchive = { archiveCalled = true })
            }
        }
        composeTestRule.onNodeWithText("Archive").performClick()
        assertTrue(archiveCalled, "onArchive should fire when Archive is tapped")
    }
}
