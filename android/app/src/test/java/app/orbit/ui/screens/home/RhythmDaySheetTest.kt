package app.orbit.ui.screens.home

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.orbit.data.entity.CallDirection
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * HOME-8 — the rhythm day sheet's summary line.
 *
 * The assertions here are voice rules as much as formatting rules: the two
 * directions must read symmetrically (same verb shape, same weight, no
 * "only"), a zero side must be omitted rather than printed, and no ratio or
 * target may appear. See `vision/00-home/00-home.md` §HOME-8.
 *
 * The copy lives in string resources (strings_home.xml), so the summary is a
 * UiText resolved here against real resources under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class RhythmDaySheetTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun call(id: Long, direction: CallDirection) = RhythmCall(
        callEventId = id,
        contactId = id,
        contactName = "Kai",
        photoUri = null,
        durationSeconds = 600,
        direction = direction,
        durationLabel = "10 min",
        timeLabel = "4:30pm",
    )

    private fun summary(calls: List<RhythmCall>): String = directionSummary(calls).asString(context)

    @Test
    fun `both directions render symmetrically`() {
        val summary = summary(
            listOf(
                call(1L, CallDirection.OUTGOING),
                call(2L, CallDirection.OUTGOING),
                call(3L, CallDirection.INCOMING),
            ),
        )
        assertEquals("You called 2 · They called 1", summary)
    }

    @Test
    fun `all-outgoing day omits the empty half instead of printing a zero`() {
        val summary = summary(listOf(call(1L, CallDirection.OUTGOING)))
        assertEquals("You called 1", summary)
    }

    @Test
    fun `all-incoming day omits the empty half instead of printing a zero`() {
        val summary = summary(listOf(call(1L, CallDirection.INCOMING)))
        assertEquals("They called 1", summary)
    }

    /**
     * The sheet never opens on a quiet day, but the summary is also the a11y
     * label for a day column, so it must degrade to something sayable.
     */
    @Test
    fun `empty day reads as a fact, not a shortfall`() {
        assertEquals("No calls", summary(emptyList()))
    }

    @Test
    fun `direction words are the same shape for both sides`() {
        assertEquals("You called", context.getString(directionWord(CallDirection.OUTGOING)))
        assertEquals("They called", context.getString(directionWord(CallDirection.INCOMING)))
    }
}
