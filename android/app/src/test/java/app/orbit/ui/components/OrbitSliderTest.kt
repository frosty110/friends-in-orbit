package app.orbit.ui.components

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.orbit.ui.theme.OrbitTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * DESIGN.md: OrbitSlider carries "a `valueDescription` TalkBack reads in
 * words ('Every 14 days')". Material's slider announces a bare percentage of
 * its track otherwise ("10 percent"), which means nothing on a rhythm
 * setting (rubric D8). The gallery's audit now flags a slider with no state
 * description; this pins that OrbitSlider's is the words it was given.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class OrbitSliderTest {

    @get:Rule val compose = createComposeRule()

    private val slider = SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)

    @Test
    fun the_state_description_is_the_words_given() {
        compose.setContent {
            OrbitTheme {
                OrbitSlider(
                    value = 14f,
                    onValueChange = {},
                    valueRange = 1f..60f,
                    valueDescription = "Every 14 days",
                )
            }
        }

        compose.onNode(slider)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Every 14 days"))
    }

    @Test
    fun a_label_becomes_the_sliders_name() {
        compose.setContent {
            OrbitTheme {
                OrbitSlider(
                    value = 2f,
                    onValueChange = {},
                    valueRange = 1f..8f,
                    valueDescription = "Every 2 weeks",
                    label = "Rhythm",
                )
            }
        }

        compose.onNode(slider).assertContentDescriptionEquals("Rhythm")
    }
}
