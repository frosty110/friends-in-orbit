package app.orbit.ui.screens.card

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.orbit.data.Contact
import app.orbit.ui.components.LocalPrivacyCurtain
import app.orbit.ui.theme.OrbitTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PRIV-03 on the card face. Regression: with the app in the background the
 * Card view title read "Contact" while the face below it still showed the real
 * name and initials.
 */
@RunWith(AndroidJUnit4::class)
class CardFaceCurtainTest {

    @get:Rule val composeTestRule = createComposeRule()

    private val contact = Contact(
        id = "c-1",
        name = "Avery Quinn",
        phone = "+1 555 0100",
        lastCalledLabel = "11 days ago",
        avgLengthLabel = "14 min",
        pickupRateLabel = "",
        totalCalls = 12,
        due = true,
        listIds = listOf("1"),
        bestWindowLabel = "Evenings",
        heat = FloatArray(24),
        history = emptyList(),
        notes = emptyList(),
        patternNote = "",
    )

    private fun face(curtain: Boolean) {
        composeTestRule.setContent {
            OrbitTheme {
                CompositionLocalProvider(LocalPrivacyCurtain provides curtain) {
                    ContactCardFace(
                        contact = contact,
                        listContext = "Inner orbit",
                        nowHour = 19,
                        isAheadOfToday = false,
                        whyNowLine = "It's been 11 days.",
                    )
                }
            }
        }
    }

    @Test
    fun curtain_masks_the_name_and_initials() {
        face(curtain = true)
        composeTestRule.onNodeWithText("Contact").assertIsDisplayed()
        composeTestRule.onNodeWithText("Avery Quinn").assertDoesNotExist()
        composeTestRule.onNodeWithText("AQ").assertDoesNotExist()
    }

    @Test
    fun without_the_curtain_the_real_name_shows() {
        face(curtain = false)
        composeTestRule.onNodeWithText("Avery Quinn").assertIsDisplayed()
    }
}
