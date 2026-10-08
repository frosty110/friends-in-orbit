package app.orbit.ui.screens.settings.export

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.input.ImeAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.orbit.ui.theme.OrbitTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The export password fields: each named by its own label, masked, Next
 * from the first to the second, and Done on the second exporting once the
 * two agree (and only then), as the button does.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class ExportPassphraseContentTest {

    @get:Rule val compose = createComposeRule()

    private val submitted = mutableListOf<String>()
    private val password = hasSetTextAction() and hasText("Password", substring = true)
    private val confirm = hasSetTextAction() and hasText("Type it again", substring = true)

    private fun sheet() {
        compose.setContent {
            OrbitTheme { ExportPassphraseContent(onSubmit = { submitted += String(it) }, onCancel = {}) }
        }
    }

    @Test
    fun both_fields_are_named_masked_and_chained() {
        sheet()
        compose.onNode(password)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, ImeAction.Next))
        compose.onNode(confirm)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, ImeAction.Done))
    }

    @Test
    fun done_exports_once_the_two_agree() {
        sheet()
        compose.onNode(password).performTextInput("orbit-pass")
        compose.onNode(confirm).performTextInput("orbit-pass")
        compose.onNode(confirm).performImeAction()

        compose.runOnIdle { assertEquals(listOf("orbit-pass"), submitted) }
    }

    @Test
    fun done_with_a_mismatch_exports_nothing() {
        sheet()
        compose.onNode(password).performTextInput("orbit-pass")
        compose.onNode(confirm).performTextInput("orbit-pas")
        compose.onNode(confirm).performImeAction()

        compose.runOnIdle { assertEquals(emptyList(), submitted) }
    }
}
