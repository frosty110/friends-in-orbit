package app.orbit.ui.screens.contact

import android.app.Application
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
import org.robolectric.annotation.Config

/**
 * CONTACT-06: OrphanBanner rendering + tap-callback contract.
 *
 * The component is stateless (caller manages visibility from
 * `ContactDetailUiState.Orphaned`) so this test composes the banner directly,
 * asserts the heading and the Re-link and Archive labels render, and asserts
 * each button invokes its callback.
 *
 * On the JVM under Robolectric (the ContactDetailCurtainTest precedent) since
 * 2026-10-06, so it gates every push; it lived under androidTest before, where
 * only the emulator job ran it. The VM side (an orphaned row emits `Orphaned`
 * with its notes, Re-link emits the nav event, Archive writes with Undo) is
 * pinned in ContactDetailViewModelTest.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class OrphanBannerTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun banner_renders_relink_and_archive_buttons() {
        compose.setContent {
            OrbitTheme {
                OrphanBanner(onRelink = {}, onArchive = {})
            }
        }
        compose.onNodeWithText("This contact was deleted from your phone").assertIsDisplayed()
        compose.onNodeWithText("Re-link").assertIsDisplayed()
        compose.onNodeWithText("Archive").assertIsDisplayed()
    }

    @Test
    fun tap_relink_invokes_callback() {
        var relinkCalled = false
        compose.setContent {
            OrbitTheme {
                OrphanBanner(onRelink = { relinkCalled = true }, onArchive = {})
            }
        }
        compose.onNodeWithText("Re-link").performClick()
        assertTrue(relinkCalled, "onRelink should fire when Re-link is tapped")
    }

    @Test
    fun tap_archive_invokes_callback() {
        var archiveCalled = false
        compose.setContent {
            OrbitTheme {
                OrphanBanner(onRelink = {}, onArchive = { archiveCalled = true })
            }
        }
        compose.onNodeWithText("Archive").performClick()
        assertTrue(archiveCalled, "onArchive should fire when Archive is tapped")
    }
}
