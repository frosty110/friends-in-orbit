package app.orbit.ui.components

import android.app.Application
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.orbit.ui.theme.OrbitTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * DESIGN.md: under the curtain "a text field draws the mask over its buffer
 * (`CurtainMask`) and never saves it". The mask is a VisualTransformation,
 * so what the field shows and what it holds are different strings; a field
 * that saves on focus loss (List settings' name) must get the real text
 * back, never "List".
 *
 * What is shown and spoken is the field's `EditableText` (TalkBack reads it,
 * and the gallery's curtain audit checks it). The buffer also surfaces as
 * `InputText`, a property for autofill that the test matcher `hasText`
 * matches too; it is the real text on purpose, so these assertions name the
 * properties rather than using `onNodeWithText`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class CurtainMaskTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun the_mask_replaces_whatever_the_field_holds() {
        val masked = CurtainMask("Contact").filter(AnnotatedString("Maya Ahmed"))

        assertEquals("Contact", masked.text.text)
    }

    @Test
    fun the_value_callback_receives_the_typed_text_not_the_mask() {
        val reported = mutableListOf<String>()
        compose.setContent {
            OrbitTheme {
                var value by remember { mutableStateOf("") }
                BasicTextField(
                    value = value,
                    onValueChange = {
                        reported += it
                        value = it
                    },
                    visualTransformation = CurtainMask("Contact"),
                )
            }
        }

        compose.onNode(hasSetTextAction()).performTextInput("Maya")

        compose.runOnIdle { assertEquals("Maya", reported.last()) }
        // What TalkBack and the eye get is the mask.
        compose.onNode(hasSetTextAction()).assert(editableTextIs("Contact"))
    }

    private fun editableTextIs(expected: String) = SemanticsMatcher("editable text is \"$expected\"") { node ->
        node.config.getOrNull(SemanticsProperties.EditableText)?.text == expected
    }
}
