package app.orbit.ui.screens.browse

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What the rendered Browse error shell owes the user, read from the semantics
 * tree on the JVM (the CardViewScreenTest / CallLogContentTest convention),
 * through the same host the gallery's previews use.
 *
 * A failed feed offers "Try again" (BROWSE-06). A route whose list id never
 * parsed has no feed to re-subscribe, so that Error offers "Go back" alone:
 * until 2026-10-06 it rendered Retry as the screen's accent and the tap
 * changed nothing (rules.md Code 3, a control that does nothing).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class BrowseErrorShellTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun a_failed_feed_offers_try_again() {
        compose.setContent { BrowsePreviewHost(BrowseUiState.Error()) }

        compose.onNodeWithText("Couldn't load this list").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertIsDisplayed()
        compose.onNodeWithText("Go back").assertDoesNotExist()
    }

    @Test
    fun a_list_id_that_never_parsed_offers_go_back_and_no_try_again() {
        compose.setContent { BrowsePreviewHost(BrowseUiState.Error(canRetry = false)) }

        compose.onNodeWithText("Couldn't load this list").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertDoesNotExist()
        compose.onNodeWithText("Go back").assertIsDisplayed()
    }
}
