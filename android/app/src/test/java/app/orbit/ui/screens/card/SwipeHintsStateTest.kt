package app.orbit.ui.screens.card

import androidx.compose.animation.core.TweenSpec
import app.orbit.R
import app.orbit.ui.util.UiText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * CARD-09's idle clock ([SwipeHintsState]) on virtual time: the hints come
 * after four untouched seconds, hold, go, come back every twelve seconds, at
 * most three times for one person; a touch hides them at once and the clock
 * starts again from zero; once the swipe is learnt they never come. And the
 * fade ([hintFade]): none at all with animations off, none for a hide a touch
 * caused.
 *
 * Plain JUnit: the state is coroutines and snapshot state, no frame clock.
 * The clock on a real card (a real touch restarting it, the Log a connection
 * sheet and the list menu holding it) and the hints' absence from what
 * TalkBack is given are CardViewMovesTest's.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SwipeHintsStateTest {

    private val hints = CardMoveHints(
        later = UiText.res(R.string.card_hint_later_when, UiText.res(R.string.card_hint_tomorrow)),
        sooner = UiText.res(R.string.card_hint_sooner_when, UiText.res(R.string.card_hint_today)),
    )

    private val fadeIn = SwipeHintTiming.FADE_MS.toLong()

    @Test
    fun `the hints come after four untouched seconds, hold, and go`() = runTest {
        val state = SwipeHintsState()
        var asked = 0
        val clock = launch { state.runIdleClock { asked++; hints } }

        advanceTimeBy(SwipeHintTiming.IDLE_MS - 1)
        runCurrent()
        assertFalse(state.visible, "not before four seconds")
        assertEquals(0, asked, "what to say is asked only when they show")

        advanceTimeBy(1)
        runCurrent()
        assertTrue(state.visible)
        assertEquals(hints, state.hints)
        assertEquals(1, asked)

        advanceTimeBy(fadeIn + SwipeHintTiming.HOLD_MS - 1)
        runCurrent()
        assertTrue(state.visible, "held about two and a half seconds")
        advanceTimeBy(1)
        runCurrent()
        assertFalse(state.visible)
        assertFalse(state.hiddenAtOnce, "an idle hide fades")
        clock.cancel()
    }

    @Test
    fun `they come back every twelve seconds, three times at most for one person`() = runTest {
        val state = SwipeHintsState()
        var asked = 0
        val clock = launch { state.runIdleClock { asked++; hints } }

        advanceTimeBy(SwipeHintTiming.IDLE_MS)
        runCurrent()
        assertEquals(1, asked)
        advanceTimeBy(SwipeHintTiming.EVERY_MS)
        runCurrent()
        assertTrue(state.visible, "back twelve seconds later")
        assertEquals(2, asked)
        advanceTimeBy(SwipeHintTiming.EVERY_MS)
        runCurrent()
        assertEquals(3, asked)

        advanceTimeBy(10 * SwipeHintTiming.EVERY_MS)
        runCurrent()
        assertEquals(3, asked, "never a fourth time")
        assertEquals(SwipeHintTiming.MAX_PER_PERSON, state.timesShown)
        assertFalse(state.visible)
        assertTrue(clock.isCompleted, "the clock stops")
    }

    @Test
    fun `a touch hides them at once and the clock starts again from zero`() = runTest {
        val state = SwipeHintsState()
        var asked = 0
        var clock = launch { state.runIdleClock { asked++; hints } }
        advanceTimeBy(SwipeHintTiming.IDLE_MS + 500)
        runCurrent()
        assertTrue(state.visible)

        // What rememberSwipeHints does on a press, then on lifting the finger.
        state.hideAtOnce()
        clock.cancel()
        assertFalse(state.visible)
        assertTrue(state.hiddenAtOnce, "gone in the same frame, no fade")
        clock = launch { state.runIdleClock { asked++; hints } }

        advanceTimeBy(SwipeHintTiming.IDLE_MS - 1)
        runCurrent()
        assertFalse(state.visible, "the idle clock restarted")
        advanceTimeBy(1)
        runCurrent()
        assertTrue(state.visible)
        assertFalse(state.hiddenAtOnce)
        assertEquals(2, asked, "the touch did not spend an appearance")
        clock.cancel()
    }

    @Test
    fun `touches never buy more than three appearances`() = runTest {
        val state = SwipeHintsState()
        var asked = 0
        repeat(6) {
            val clock = launch { state.runIdleClock { asked++; hints } }
            advanceTimeBy(SwipeHintTiming.IDLE_MS + 100)
            runCurrent()
            state.hideAtOnce()
            clock.cancel()
        }
        assertEquals(3, asked)
    }

    @Test
    fun `once the swipe is learnt they never show, and are not asked for again`() = runTest {
        val state = SwipeHintsState()
        var asked = 0
        launch { state.runIdleClock { asked++; null } }
        advanceTimeBy(SwipeHintTiming.IDLE_MS)
        runCurrent()
        assertFalse(state.visible)
        assertNull(state.hints)

        val again = launch { state.runIdleClock { asked++; null } }
        advanceTimeBy(SwipeHintTiming.IDLE_MS * 4)
        runCurrent()
        assertEquals(1, asked, "a later touch does not ask again for this person")
        assertTrue(again.isCompleted)
    }

    @Test
    fun `the count survives a rotation`() {
        val state = SwipeHintsState(timesShown = 2)
        val saved = with(SwipeHintsState.Saver) { androidx.compose.runtime.saveable.SaverScope { true }.save(state) }
        assertEquals(2, SwipeHintsState.Saver.restore(assertIs<Int>(saved))?.timesShown)
    }

    @Test
    fun `with animations off nothing fades`() {
        assertNull(hintFade(show = true, hiddenAtOnce = false, reducedMotion = true))
        assertNull(hintFade(show = false, hiddenAtOnce = false, reducedMotion = true))
    }

    @Test
    fun `a touch hides at once, the idle clock fades in and out`() {
        assertNull(hintFade(show = false, hiddenAtOnce = true, reducedMotion = false))
        val fadeIn = assertIs<TweenSpec<Float>>(hintFade(show = true, hiddenAtOnce = false, reducedMotion = false))
        assertEquals(SwipeHintTiming.FADE_MS, fadeIn.durationMillis)
        val fadeOut = assertIs<TweenSpec<Float>>(hintFade(show = false, hiddenAtOnce = false, reducedMotion = false))
        assertEquals(SwipeHintTiming.FADE_MS, fadeOut.durationMillis)
    }
}
