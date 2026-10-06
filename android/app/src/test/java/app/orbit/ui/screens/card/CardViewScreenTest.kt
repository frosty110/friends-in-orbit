package app.orbit.ui.screens.card

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.orbit.R
import app.orbit.data.Contact
import app.orbit.ui.theme.OrbitTheme
import app.orbit.ui.util.UiText
import app.orbit.ui.util.formatDuration
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What the rendered Card view owes the user, checked on the JVM against the
 * semantics tree (the ContactDetailCurtainTest / PreviewGalleryTest
 * convention): the face opens details and never dials (CARD-01), and in
 * landscape on a phone the Call button is on screen without scrolling
 * (CARD-06). Drives the stateless [CardViewContent]; [CardViewScreen] takes a
 * Hilt ViewModel and cannot be composed here.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class CardViewScreenTest {

    @get:Rule val compose = createComposeRule()

    private val avery = Contact(
        id = "c-1",
        name = "Avery Quinn",
        phone = "+1 555 0100",
        lastCalledLabel = UiText.plural(R.plurals.time_ago_days, 11, 11),
        avgLengthLabel = formatDuration(14 * 60),
        pickupRateLabel = "",
        totalCalls = 12,
        due = true,
        listIds = listOf("1"),
        bestWindowLabel = null,
        heat = FloatArray(24),
        history = emptyList(),
        notes = emptyList(),
        patternNote = "",
    )

    private val ready = CardViewUiState.Ready(
        contactId = 1L,
        contact = avery,
        listContext = "Inner orbit",
        queueSize = 3,
        nowHour = 19,
    )

    private fun setReady(onTapToCall: (Long, String) -> Unit = { _, _ -> }, onOpenContact: (Long) -> Unit = {}) {
        compose.setContent {
            OrbitTheme {
                CardViewContent(
                    state = ready,
                    listId = "1",
                    callLogDenied = false,
                    messages = MutableSharedFlow<CardMessage>().asSharedFlow(),
                    onBack = {},
                    onBrowse = {},
                    onEditList = {},
                    onAddContacts = {},
                    onTapToCall = onTapToCall,
                    onSwipeLeft = {},
                    onSwipeRight = {},
                    onUndo = {},
                    onRetry = {},
                    onOpenSettings = {},
                    onOpenContact = onOpenContact,
                )
            }
        }
    }

    @Test
    fun `CARD-01 - a tap on the card face opens details and never dials`() {
        val opened = mutableListOf<Long>()
        val dialed = mutableListOf<Long>()
        setReady(onTapToCall = { id, _ -> dialed += id }, onOpenContact = { opened += it })

        // The face is the clickable node whose merged text carries the name.
        compose.onNodeWithText("Avery Quinn").performClick()

        compose.runOnIdle {
            assertEquals(listOf(1L), opened, "the face opens the person's details")
            assertEquals(emptyList(), dialed, "the face never dials")
        }
    }

    @Test
    fun `CARD-01 - only the labelled Call button dials`() {
        val dialed = mutableListOf<Long>()
        setReady(onTapToCall = { id, _ -> dialed += id })

        compose.onNodeWithText("Call Avery").performClick()

        compose.runOnIdle { assertEquals(listOf(1L), dialed) }
    }

    @Test
    @Config(qualifiers = "w740dp-h360dp-land")
    fun `CARD-06 - in landscape on a phone the face and the Call button are on screen`() {
        setReady()

        compose.onNodeWithText("Avery Quinn").assertIsDisplayed()
        compose.onNodeWithText("Call Avery").assertIsDisplayed()
    }
}
