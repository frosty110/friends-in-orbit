package app.orbit.ui.screens.picker

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.orbit.ui.theme.OrbitTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The commit bar's button (PICK-06) shows a bare verb and tells TalkBack the
 * whole sentence. It showed "Add 3 people to Inner orbit" beside "3 selected"
 * until 2026-10-07, which said the count twice and squeezed the bar.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class BatchCounterTest {

    @get:Rule val compose = createComposeRule()

    private fun bar(mode: PickerMode, count: Int, target: String) {
        compose.setContent {
            OrbitTheme {
                BatchCounter(
                    selectionCount = count,
                    targetListName = target,
                    mode = mode,
                    isCommitting = false,
                    onClear = {},
                    onCommit = {}
                )
            }
        }
    }

    @Test
    fun the_add_button_says_Add_and_TalkBack_hears_the_sentence() {
        bar(PickerMode.Add, count = 3, target = "Inner orbit")

        compose.onNodeWithText("Add").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ContentDescription,
                listOf("Add 3 people to Inner orbit")
            )
        )
        compose.onNodeWithText("Add 3 people to Inner orbit").assertDoesNotExist()
        compose.onNodeWithText("3 selected").assertExists()
    }

    @Test
    fun relink_keeps_the_name_on_the_button() {
        // One person, no count to repeat: the name is what the button is for.
        bar(PickerMode.Relink, count = 1, target = "Sarah")

        compose.onNodeWithText("Re-link Sarah").assertExists()
    }
}
