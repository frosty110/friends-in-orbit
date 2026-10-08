package app.orbit.ui.screens.week

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.orbit.data.entity.CallDirection
import app.orbit.ui.screens.home.RhythmCall
import app.orbit.ui.util.formatDuration
import java.time.LocalDate
import kotlin.test.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * HOME-13: what the Week screen says, against the real string resources.
 * The day description is what TalkBack hears for a day column, so it is
 * pinned word for word, with the privacy curtain up and down (PRIV-03).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class WeekWordsTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun call(id: Long, name: String?, time: String, minutes: Int, direction: CallDirection) = RhythmCall(
        callEventId = id,
        contactId = id,
        contactName = name,
        photoUri = null,
        durationSeconds = minutes * 60,
        direction = direction,
        durationLabel = formatDuration(minutes * 60),
        timeLabel = time,
        minuteOfDay = 0,
    )

    private val day = listOf(
        call(1L, "Alex Kim", "1:15pm", 9, CallDirection.INCOMING),
        call(2L, "Sarah Chen", "6:40pm", 14, CallDirection.OUTGOING),
    )

    @Test
    fun `a day reads each call, who and which way, when and how long, in order`() {
        assertEquals(
            "Wednesday 30 September, 2 calls: Alex Kim called you at 1:15pm for 9 min. " +
                "You called Sarah Chen at 6:40pm for 14 min.",
            weekDayDescription(context, "Wednesday 30 September", day, curtain = false),
        )
    }

    @Test
    fun `under the curtain nobody is named`() {
        assertEquals(
            "Wednesday 30 September, 2 calls: Someone called you at 1:15pm for 9 min. " +
                "You called someone at 6:40pm for 14 min.",
            weekDayDescription(context, "Wednesday 30 September", day, curtain = true),
        )
    }

    @Test
    fun `someone who has left the list is someone, as on the day sheet`() {
        assertEquals(
            "Today, 1 call: You called someone at 9:05am for 26 min.",
            weekDayDescription(context, "Today", listOf(call(3L, null, "9:05am", 26, CallDirection.OUTGOING)), curtain = false),
        )
    }

    @Test
    fun `a quiet day says so, in the strip's words`() {
        assertEquals("Friday 2 October, No calls", weekDayDescription(context, "Friday 2 October", emptyList(), curtain = false))
    }

    @Test
    fun `an earlier week is headed by its dates, with the year only when it is not this one`() {
        val today = LocalDate.of(2026, 10, 7)
        assertEquals(
            "28 Sep to 4 Oct",
            weekRangeLabel(context.resources, LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 4), today),
        )
        assertEquals(
            "29 Dec 2025 to 4 Jan 2026",
            weekRangeLabel(context.resources, LocalDate.of(2025, 12, 29), LocalDate.of(2026, 1, 4), today),
        )
    }

    @Test
    fun `the axis follows the phone's clock, every three hours from 3am`() {
        assertEquals(
            listOf("3am", "6am", "9am", "12pm", "3pm", "6pm", "9pm"),
            hourLabels(use24Hour = false).map { it.second },
        )
        assertEquals(
            listOf("03:00", "06:00", "09:00", "12:00", "15:00", "18:00", "21:00"),
            hourLabels(use24Hour = true).map { it.second },
        )
        assertEquals(listOf(3, 6, 9, 12, 15, 18, 21), hourLabels(use24Hour = false).map { it.first })
    }
}
