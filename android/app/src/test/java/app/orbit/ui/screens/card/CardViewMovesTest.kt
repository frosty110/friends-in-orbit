package app.orbit.ui.screens.card

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.orbit.R
import app.orbit.data.Contact
import app.orbit.domain.usecase.LogConnectionWhen
import app.orbit.ui.components.LocalPrivacyCurtain
import app.orbit.ui.theme.LocalReducedMotion
import app.orbit.ui.theme.OrbitTheme
import app.orbit.ui.util.UiText
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The card's moves and the owner review's additions to them, on the rendered
 * [CardViewContent] (Robolectric, the CardViewScreenTest convention):
 *
 * - CARD-08: Later and Sooner play the swipe. A button flies the card off
 *   through [CardSwipeFrame]'s settle, with one haptic and one commit; with
 *   animations off the move lands in a frame, with no flight.
 * - CARD-09: the idle hints are on a real card's clock (asked for after four
 *   untouched seconds, a touch restarts the wait, three at most) and never
 *   reach the semantics tree.
 * - CARD-10: "Log a connection" opens the shared sheet for the person on the
 *   card, and the snackbar after it drops the name under the curtain.
 * - CARD-11: the page for a call worth a note opens without a snackbar, and
 *   the notification after a call for that person is withdrawn.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class CardViewMovesTest {

    @get:Rule val compose = createComposeRule()

    private fun person(id: Long, name: String) = Contact(
        id = "c-$id",
        name = name,
        phone = "+1 555 0100",
        lastCalledLabel = null,
        avgLengthLabel = null,
        pickupRateLabel = "",
        totalCalls = 0,
        due = true,
        listIds = listOf("1"),
        bestWindowLabel = null,
        heat = FloatArray(24),
        history = emptyList(),
        notes = emptyList(),
        patternNote = "",
    )

    private fun ready(id: Long = 1L, name: String = "Avery Quinn") = CardViewUiState.Ready(
        contactId = id,
        contact = person(id, name),
        listContext = "Inner orbit",
        queueSize = 3,
    )

    private class CountingHaptics : HapticFeedback {
        var count = 0
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            count++
        }
    }

    private val hints = CardMoveHints(
        later = UiText.res(R.string.card_hint_later_when, UiText.res(R.string.card_hint_on_day, "Thursday")),
        sooner = UiText.res(R.string.card_hint_sooner_when, UiText.res(R.string.card_hint_tomorrow)),
    )

    private val messages = MutableSharedFlow<CardMessage>(extraBufferCapacity = 4)

    private fun setCard(
        state: () -> CardViewUiState = { ready() },
        reducedMotion: Boolean = false,
        curtain: Boolean = false,
        haptics: HapticFeedback = CountingHaptics(),
        onSwipeLeft: (Long) -> Unit = {},
        onSwipeRight: (Long) -> Unit = {},
        onAddNote: (Long, Long?) -> Unit = { _, _ -> },
        onLogConnection: (Long, LogConnectionWhen, String, Boolean) -> Unit = { _, _, _, _ -> },
        moveHints: suspend (Long) -> CardMoveHints? = { null },
        pinnedHints: CardMoveHints? = null,
    ) {
        compose.setContent {
            OrbitTheme {
                CompositionLocalProvider(
                    LocalReducedMotion provides reducedMotion,
                    LocalPrivacyCurtain provides curtain,
                    LocalHapticFeedback provides haptics,
                ) {
                    CardViewContent(
                        state = state(),
                        listId = "1",
                        callLogDenied = false,
                        messages = messages.asSharedFlow(),
                        onBack = {},
                        onBrowse = { _, _ -> },
                        onEditList = {},
                        onAddContacts = {},
                        onTapToCall = { _, _ -> },
                        onSwipeLeft = onSwipeLeft,
                        onSwipeRight = onSwipeRight,
                        onUndo = {},
                        onRetry = {},
                        onOpenSettings = {},
                        onOpenContact = {},
                        onAddNote = onAddNote,
                        onLogConnection = onLogConnection,
                        moveHints = moveHints,
                        pinnedHints = pinnedHints,
                    )
                }
            }
        }
    }

    private fun frames(n: Int) = repeat(n) { compose.mainClock.advanceTimeByFrame() }

    // CARD-08 ------------------------------------------------------------

    @Test
    fun `CARD-08 - Later flies the card off, then commits once with one haptic`() {
        val lefts = mutableListOf<Long>()
        val haptics = CountingHaptics()
        compose.mainClock.autoAdvance = false
        setCard(haptics = haptics, onSwipeLeft = { lefts += it })
        frames(2)

        compose.onNodeWithText("Later").performClick()
        frames(5)
        assertEquals(emptyList(), lefts, "still in flight: the button plays the swipe, not a jump")

        compose.mainClock.advanceTimeBy(2_000)
        assertEquals(listOf(1L), lefts)
        assertEquals(1, haptics.count, "the swipe's haptic, once")

        // The re-center after no new emission is not a second commit.
        compose.mainClock.advanceTimeBy(3_000)
        assertEquals(listOf(1L), lefts, "exactly once")
        assertEquals(1, haptics.count)
    }

    @Test
    fun `CARD-08 - Sooner flies the card the other way and commits once`() {
        val rights = mutableListOf<Long>()
        val lefts = mutableListOf<Long>()
        compose.mainClock.autoAdvance = false
        setCard(onSwipeLeft = { lefts += it }, onSwipeRight = { rights += it })
        frames(2)

        compose.onNodeWithText("Sooner").performClick()
        frames(5)
        assertEquals(emptyList(), rights)
        compose.mainClock.advanceTimeBy(2_000)
        assertEquals(listOf(1L), rights)
        assertEquals(emptyList(), lefts)
    }

    @Test
    fun `CARD-08 - with animations off the move lands at once, with its haptic`() {
        val lefts = mutableListOf<Long>()
        val haptics = CountingHaptics()
        compose.mainClock.autoAdvance = false
        setCard(reducedMotion = true, haptics = haptics, onSwipeLeft = { lefts += it })
        frames(2)

        compose.onNodeWithText("Later").performClick()
        frames(5)
        assertEquals(listOf(1L), lefts, "no flight: the commit is within a few frames")
        assertEquals(1, haptics.count)

        compose.mainClock.advanceTimeBy(3_000)
        assertEquals(listOf(1L), lefts, "still exactly once")
    }

    // CARD-09 ------------------------------------------------------------

    // Read on the merged tree, which is what accessibility services are given
    // and where clearAndSetSemantics applies (the AvatarTest convention). The
    // unmerged tree, a debugging view that still lists what was cleared, shows
    // the hints are really drawn, so the absence is not for want of a hint.
    @Test
    fun `CARD-09 - the hints never reach the semantics tree`() {
        setCard(pinnedHints = hints)
        compose.waitForIdle()

        compose.onAllNodesWithText("Later · Thursday", useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodesWithText("Sooner · Tomorrow", useUnmergedTree = true).assertCountEquals(1)

        compose.onAllNodesWithText("Thursday", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Later · ", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Sooner · ", substring = true).assertCountEquals(0)
        // Nor through a description: nothing TalkBack is given mentions them.
        val described = compose.onRoot().fetchSemanticsNode().let { root ->
            buildList {
                fun walk(node: androidx.compose.ui.semantics.SemanticsNode) {
                    node.config.getOrNull(SemanticsProperties.ContentDescription)?.let { addAll(it) }
                    node.children.forEach(::walk)
                }
                walk(root)
            }
        }
        assertTrue(described.none { "Thursday" in it || "·" in it }, "content descriptions: $described")
        // The labelled buttons TalkBack uses are still there.
        compose.onAllNodesWithText("Later").assertCountEquals(1)
        compose.onAllNodesWithText("Sooner").assertCountEquals(1)
    }

    @Test
    fun `CARD-09 - on the card, hints come after four untouched seconds and a touch restarts the wait`() {
        var asked = 0
        compose.mainClock.autoAdvance = false
        setCard(moveHints = { asked++; hints })
        frames(2)

        compose.mainClock.advanceTimeBy(SwipeHintTiming.IDLE_MS - 200)
        assertEquals(0, asked, "not before four seconds")
        compose.mainClock.advanceTimeBy(400)
        assertEquals(1, asked, "asked for, for the person on the card")

        // A touch on the card (no drag, no button: a press and a lift).
        compose.onNodeWithText("Avery Quinn").performTouchInput {
            down(center)
            up()
        }
        frames(2)
        compose.mainClock.advanceTimeBy(SwipeHintTiming.IDLE_MS - 200)
        assertEquals(1, asked, "the touch restarted the four seconds")
        compose.mainClock.advanceTimeBy(400)
        assertEquals(2, asked)

        compose.mainClock.advanceTimeBy(10 * SwipeHintTiming.EVERY_MS)
        assertEquals(SwipeHintTiming.MAX_PER_PERSON, asked, "three times at most for one person")
    }

    @Test
    fun `CARD-09 - no hints while the Log a connection sheet covers the card`() {
        var asked = 0
        compose.mainClock.autoAdvance = false
        setCard(moveHints = { asked++; hints })
        frames(2)
        compose.onNodeWithText("Log a connection").performClick()
        frames(2)
        compose.mainClock.advanceTimeBy(3 * SwipeHintTiming.EVERY_MS)
        assertEquals(0, asked)
    }

    @Test
    fun `CARD-09 - no hints while the list menu is open, and the wait starts again once it closes`() {
        // The prototype's rule: the hints wait while a menu covers the card.
        // The menu's button is in the app bar, outside the card's touch
        // watcher, and its popup takes the touches after it, so only the
        // menu's own open state can pause them.
        var asked = 0
        compose.mainClock.autoAdvance = false
        setCard(moveHints = { asked++; hints })
        frames(2)
        compose.mainClock.advanceTimeBy(SwipeHintTiming.IDLE_MS - 1_000)

        compose.onNodeWithContentDescription("More actions for Inner orbit").performClick()
        frames(5)
        compose.onNodeWithText("Browse people").assertExists()
        compose.mainClock.advanceTimeBy(3 * SwipeHintTiming.EVERY_MS)
        assertEquals(0, asked, "nothing while the menu is open")

        // Closing it (here by choosing an item) starts the four seconds again.
        compose.onNodeWithText("List settings").performClick()
        frames(5)
        compose.mainClock.advanceTimeBy(SwipeHintTiming.IDLE_MS - 200)
        assertEquals(0, asked, "a fresh four seconds after the menu")
        compose.mainClock.advanceTimeBy(400)
        assertEquals(1, asked)
    }

    // CARD-10 ------------------------------------------------------------

    @Test
    fun `CARD-10 - Log a connection opens the shared sheet and logs the person it was opened over`() {
        var shown by mutableStateOf<CardViewUiState>(ready(1L, "Avery Quinn"))
        val logged = mutableListOf<List<Any>>()
        setCard(
            state = { shown },
            onLogConnection = { id, whenChoice, note, attempt -> logged += listOf(id, whenChoice, note, attempt) },
        )

        compose.onNodeWithText("Log a connection").performClick()
        compose.onNodeWithText("We connected").assertExists()
        // The deck moves on while the sheet is open (a call-log sync):
        // the sheet still belongs to Avery.
        shown = ready(2L, "Kai Reyes")
        compose.waitForIdle()
        // Robolectric gives a ModalBottomSheet no window for touches
        // (PauseDurationSheetTest), so the button is pressed through its
        // click action, which is what a tap runs.
        compose.onNodeWithText("Log connection").performSemanticsAction(SemanticsActions.OnClick)

        compose.runOnIdle {
            assertEquals(listOf<List<Any>>(listOf(1L, LogConnectionWhen.Today, "", false)), logged)
        }
    }

    private val loggedAvery = CardMessage.Logged(
        text = UiText.res(R.string.card_logged_named_when, "Avery", UiText.res(R.string.card_due_tomorrow)),
        curtainText = UiText.res(R.string.card_logged_unnamed_when, UiText.res(R.string.card_due_tomorrow)),
    )

    /** Shows [message] and holds the clock, so the snackbar is still up when read. */
    private fun show(message: CardMessage) {
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        messages.tryEmit(message)
        frames(20)
    }

    @Test
    fun `CARD-10 - the snackbar after a log has no name under the curtain`() {
        setCard(curtain = true)
        show(loggedAvery)
        compose.onNodeWithText("Logged. They come up again tomorrow.").assertExists()
        compose.onAllNodesWithText("Avery", substring = true).assertCountEquals(0)
    }

    @Test
    fun `CARD-10 - without the curtain the snackbar names the person`() {
        setCard()
        show(loggedAvery)
        compose.onNodeWithText("Logged. Avery comes up again tomorrow.").assertExists()
    }

    // CARD-11 ------------------------------------------------------------

    @Test
    fun `CARD-11 - a call worth a note opens its page with no snackbar`() {
        // The page withdraws the person's notification after a call as it
        // opens, whichever way it is reached (PostCallNoteScreenTest), so the
        // card only has to open it.
        val opened = mutableListOf<Pair<Long, Long?>>()
        setCard(onAddNote = { id, call -> opened += id to call })

        show(CardMessage.OpenNote(contactId = 1L, callEventId = 41L))

        compose.runOnIdle { assertEquals(listOf<Pair<Long, Long?>>(1L to 41L), opened) }
        compose.onAllNodesWithText("Called", substring = true).assertCountEquals(0)
    }

    @Test
    fun `CARD-03 - Add a note on Called opens the page with no call named`() {
        val opened = mutableListOf<Pair<Long, Long?>>()
        setCard(onAddNote = { id, call -> opened += id to call })
        show(CardMessage.Called(UiText.res(R.string.card_called_named, "Avery"), contactId = 1L))

        compose.onNodeWithText("Add a note").performClick()
        frames(5)

        compose.runOnIdle { assertEquals(listOf<Pair<Long, Long?>>(1L to null), opened) }
    }
}
