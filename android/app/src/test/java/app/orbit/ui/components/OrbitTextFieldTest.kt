package app.orbit.ui.components

import android.app.Application
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.text.input.ImeAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.orbit.ui.theme.OrbitTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The shared text field: the label above it is part of the field for
 * TalkBack (so "Password, edit box", not an unnamed box), an error is said as
 * an error, and a single line ends with Done by default.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class OrbitTextFieldTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun the_label_above_names_the_field() {
        compose.setContent {
            OrbitTheme { OrbitTextField(value = "", onValueChange = {}, label = "Password") }
        }
        compose.onNode(hasSetTextAction() and hasText("Password", substring = true)).assertExists()
    }

    @Test
    fun a_field_with_no_visible_label_is_named_by_its_description() {
        compose.setContent {
            OrbitTheme {
                OrbitTextField(value = "Inner orbit", onValueChange = {}, label = null, contentDescription = "Name")
            }
        }
        compose.onNode(hasSetTextAction()).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Name")),
        )
    }

    @Test
    fun an_error_is_announced_as_one() {
        compose.setContent {
            OrbitTheme {
                OrbitTextField(value = "abc", onValueChange = {}, label = "Password", errorText = "Use at least 8 characters.")
            }
        }
        compose.onNode(hasSetTextAction()).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.Error, "Use at least 8 characters."),
        )
    }

    @Test
    fun one_line_ends_with_done_by_default() {
        var done = 0
        compose.setContent {
            OrbitTheme {
                OrbitTextField(
                    value = "Inner orbit",
                    onValueChange = {},
                    label = "Name",
                    keyboardActions = KeyboardActions(onDone = { done++ }),
                )
            }
        }
        compose.onNode(hasSetTextAction())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, ImeAction.Done))
            .performImeAction()
        compose.runOnIdle { assertEquals(1, done) }
    }
}
