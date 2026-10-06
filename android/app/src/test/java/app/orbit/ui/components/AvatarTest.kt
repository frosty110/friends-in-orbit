package app.orbit.ui.components

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.orbit.ui.theme.OrbitTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * DESIGN.md: the Avatar is "hidden from TalkBack (the name is beside it)". It
 * used to read its initials aloud ("A Q") before the name, so every row said
 * the person twice.
 *
 * Checked on the merged tree, which is what accessibility services are given:
 * `clearAndSetSemantics` drops its descendants from it. The unmerged tree is
 * a debugging view that still lists what was cleared (the "AQ" text node
 * under a `ClearAndSetSemantics = true` parent), so it cannot tell a hidden
 * monogram from a spoken one.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class AvatarTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun neither_the_initials_nor_a_description_reach_talkback() {
        compose.setContent { OrbitTheme { Avatar(name = "Avery Quinn") } }

        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)).assertCountEquals(0)
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription)).assertCountEquals(0)
    }
}
