package app.orbit.ui.screens.lists

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.orbit.ui.components.LocalPrivacyCurtain
import app.orbit.ui.theme.OrbitTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * PRIV-03: the rename dialog was the one list-name field without the curtain
 * mask, so a list name stayed readable in the app switcher while the dialog
 * was open (lists-6). The gallery cannot render this dialog (its focused
 * field never reports idle there; see PreviewGalleryTest.NEVER_IDLE), so the
 * check lives here: with the curtain down, what the field shows and speaks
 * (its EditableText) is "List", while Save still gets the real buffer
 * (CurtainMaskTest pins that half for the mask itself).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class RenameListDialogCurtainTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun under_the_curtain_the_field_shows_list_not_the_name() {
        compose.setContent {
            OrbitTheme {
                CompositionLocalProvider(LocalPrivacyCurtain provides true) {
                    RenameListDialog(currentName = "Inner orbit", onSave = {}, onDismiss = {})
                }
            }
        }

        compose.onNode(hasSetTextAction()).assert(editableTextIs("List"))
    }

    private fun editableTextIs(expected: String) =
        SemanticsMatcher("editable text is \"$expected\"") { node ->
            node.config.getOrNull(SemanticsProperties.EditableText)?.text == expected
        }
}
