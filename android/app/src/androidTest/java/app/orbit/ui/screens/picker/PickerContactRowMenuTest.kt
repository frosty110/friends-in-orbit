package app.orbit.ui.screens.picker

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.orbit.ui.theme.OrbitTheme
import java.time.Instant
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The picker row's action menu: reachable, and correctly gated.
 *
 * "Ignore" existed before this but only behind a long-press, which is where
 * discoverable actions go to die — so the row now carries a visible ⋮ button.
 * These tests pin that the button opens the menu, that both actions fire, and
 * that "Open in Contacts" is hidden for a row with no
 * [PickerContact.phoneContactId] behind it (a call-log-only contact, whose
 * contact URI would dead-end).
 */
@RunWith(AndroidJUnit4::class)
class PickerContactRowMenuTest {

    @get:Rule val composeTestRule = createComposeRule()

    private fun contact(
        phoneContactId: Long? = 42L,
        isIgnored: Boolean = false,
    ) = PickerContact(
        contactId = 1L,
        displayName = "Sarah Levin",
        phone = "+15555550101",
        photoUri = null,
        phoneContactId = phoneContactId,
        isIgnored = isIgnored,
        callCount = 2,
        lastCallAt = Instant.parse("2026-01-01T00:00:00Z"),
        firstSeenByAppAt = Instant.parse("2025-01-01T00:00:00Z"),
        listIds = emptySet(),
        listNames = emptyList(),
        isCommonlyCalled = false,
        isRarelyCalled = false,
        isRecentlyAdded = false,
        isLongGap = false,
    )

    @Test
    fun overflow_button_opens_the_menu() {
        composeTestRule.setContent {
            OrbitTheme {
                PickerContactRow(
                    contact = contact(),
                    isSelected = false,
                    onToggle = {},
                    onIgnore = {},
                    onOpenInPhone = {},
                )
            }
        }

        composeTestRule
            .onNodeWithContentDescription("More actions for Sarah Levin")
            .performClick()

        composeTestRule.onNodeWithText("Open in Contacts").assertIsDisplayed()
        composeTestRule.onNodeWithText("Ignore").assertIsDisplayed()
    }

    @Test
    fun open_in_contacts_fires_with_the_row_contact() {
        var opened: PickerContact? = null
        composeTestRule.setContent {
            OrbitTheme {
                PickerContactRow(
                    contact = contact(),
                    isSelected = false,
                    onToggle = {},
                    onIgnore = {},
                    onOpenInPhone = { opened = it },
                )
            }
        }

        composeTestRule
            .onNodeWithContentDescription("More actions for Sarah Levin")
            .performClick()
        composeTestRule.onNodeWithText("Open in Contacts").performClick()

        composeTestRule.runOnIdle {
            assertEquals(1L, opened?.contactId)
            assertEquals(42L, opened?.phoneContactId)
        }
    }

    @Test
    fun ignore_fires_and_keeps_its_locked_supporting_line() {
        var ignored: PickerContact? = null
        composeTestRule.setContent {
            OrbitTheme {
                PickerContactRow(
                    contact = contact(),
                    isSelected = false,
                    onToggle = {},
                    onIgnore = { ignored = it },
                    onOpenInPhone = {},
                )
            }
        }

        composeTestRule
            .onNodeWithContentDescription("More actions for Sarah Levin")
            .performClick()

        // The promise that ignoring never touches the phone's address book is
        // the reason the action is safe to offer inline — keep it verbatim.
        composeTestRule
            .onNodeWithText(
                "Hide Sarah Levin from Orbit. They stay in your phone's contacts.",
            )
            .assertIsDisplayed()

        composeTestRule.onNodeWithText("Ignore").performClick()

        composeTestRule.runOnIdle {
            assertEquals(1L, ignored?.contactId)
        }
    }

    @Test
    fun open_in_contacts_is_hidden_without_a_device_contact() {
        composeTestRule.setContent {
            OrbitTheme {
                PickerContactRow(
                    contact = contact(phoneContactId = null),
                    isSelected = false,
                    onToggle = {},
                    onIgnore = {},
                    // The screen gates this to null for a row with no
                    // phoneContactId; mirror that wiring here.
                    onOpenInPhone = null,
                )
            }
        }

        composeTestRule
            .onNodeWithContentDescription("More actions for Sarah Levin")
            .performClick()

        composeTestRule.onNodeWithText("Open in Contacts").assertDoesNotExist()
        composeTestRule.onNodeWithText("Ignore").assertIsDisplayed()
    }

    @Test
    fun ignored_row_offers_unignore() {
        var unignored: PickerContact? = null
        composeTestRule.setContent {
            OrbitTheme {
                PickerContactRow(
                    contact = contact(isIgnored = true),
                    isSelected = false,
                    onToggle = {},
                    onUnignore = { unignored = it },
                    onOpenInPhone = {},
                )
            }
        }

        composeTestRule
            .onNodeWithContentDescription("More actions for Sarah Levin")
            .performClick()
        composeTestRule.onNodeWithText("Unignore").performClick()

        composeTestRule.runOnIdle {
            assertEquals(1L, unignored?.contactId)
        }
    }
}
