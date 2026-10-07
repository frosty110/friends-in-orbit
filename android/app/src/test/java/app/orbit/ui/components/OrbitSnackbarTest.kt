package app.orbit.ui.components

import android.app.Application
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
 * DESIGN.md: OrbitSnackbar holds its action (the Undo every reversible change
 * offers) to 48dp; Material's default is a 40dp TextButton, under the floor
 * (rules.md §Design 3, rubric gate G2). The gallery's audit found the 40dp
 * one; this keeps it from coming back without a snackbar on screen.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class OrbitSnackbarTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun the_action_is_at_least_48dp_tall_and_fires() {
        var undone = false
        compose.setContent {
            OrbitTheme {
                OrbitSnackbar(message = "Ignored Sam", actionLabel = "Undo", onAction = { undone = true })
            }
        }

        compose.onNodeWithText("Undo").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Undo").performClick()

        compose.runOnIdle { assertTrue(undone) }
    }
}
