package app.orbit.ui.screens.home

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * HOME-7: how the rhythm strip lays out a day's bars in its 48dp column
 * ([rhythmBars]), on the JVM.
 *
 * Until 2026-10-08 the floor was added on top of a long bar's own height and
 * a Column stacked the bars, giving each only what the ones above it left. On
 * a list's busiest day a 5-minute call after a 40-minute one asked for 14dp,
 * got 10.9dp and kept 1.9dp of the person's colour inside a full rim and
 * ring; with 30, 3 and 3 minutes the third call got no height at all. These
 * tests pin the layout that replaced it: every call keeps a bar, the bars
 * fill the column and no more, a day of up to three calls keeps the whole
 * mark, and a longer call is never drawn shorter than a shorter one.
 */
class RhythmBarsTest {

    private val area = 48f
    private val eps = 1e-3f

    private fun minutes(vararg m: Int): List<Int> = m.map { it * 60 }

    private fun quiet(): List<Int> = emptyList()

    /** The person's colour left inside the rim and ring, top to bottom. */
    private fun fill(bar: RhythmBar): Float = bar.height - 2 * (bar.rim + bar.ring)

    /** The height a call has on a day with room, against the strip's scale. */
    private fun natural(minutes: Int, busiestMinutes: Int): Float = minutes / (busiestMinutes * 1.25f) * area

    /** Every bar inside the column, none over another, the last on its foot. */
    private fun assertFitsColumn(day: List<RhythmBar>) {
        assertTrue(day.first().top >= -eps, "the first bar starts inside the column: $day")
        day.zipWithNext { above, below ->
            assertTrue(below.top >= above.top + above.height - eps, "no bar overlaps the one above it: $day")
        }
        val last = day.last()
        assertEquals(area, last.top + last.height, eps, "the stack ends on the column's foot: $day")
    }

    private fun assertWholeMark(bar: RhythmBar) {
        assertEquals(DIRECTION_RIM.value, bar.rim, eps, "the whole 3dp rim: $bar")
        assertEquals(DIRECTION_SEPARATOR.value, bar.ring, eps, "the whole 1.5dp ring: $bar")
    }

    @Test
    fun `on the busiest day a short call after a long one keeps its floor, its whole mark and its colour`() {
        val week = rhythmBars(
            listOf(minutes(40, 5), quiet(), minutes(14), quiet(), quiet(), quiet(), minutes(9)),
        )
        val (long, short) = week[0]

        assertFitsColumn(week[0])
        // The old layout drew this bar 10.9dp tall with a 9dp mark: 1.9dp of colour.
        assertEquals(14f, short.height, eps)
        assertWholeMark(short)
        assertEquals(5f, fill(short), eps)
        assertWholeMark(long)
        assertTrue(long.height > short.height, "the 40-minute call stands taller: ${week[0]}")
    }

    @Test
    fun `one long call and two short ones each keep a bar with the whole mark, and the long one stands taller`() {
        val day = rhythmBars(listOf(minutes(30, 3, 3))).single()

        assertEquals(3, day.size)
        assertFitsColumn(day)
        // The old layout gave the third bar no height at all.
        day.forEach { bar ->
            assertWholeMark(bar)
            assertTrue(fill(bar) >= 2f - eps, "some of the person's colour shows: $bar")
        }
        assertTrue(day[0].height > day[1].height && day[0].height > day[2].height, "the 30-minute call stands taller: $day")
    }

    @Test
    fun `a long call after two short ones is not squeezed down to their height`() {
        val day = rhythmBars(listOf(minutes(3, 3, 30))).single()

        assertFitsColumn(day)
        day.forEach(::assertWholeMark)
        // The old Column measured the last bar against what was left: 14dp, the same as the short calls.
        assertTrue(day[2].height > day[0].height && day[2].height > day[1].height, "the 30-minute call stands taller: $day")
    }

    @Test
    fun `five calls on one day each keep a bar with colour in it, inside the column, longer never shorter`() {
        val lengths = intArrayOf(4, 12, 3, 6, 5)
        val day = rhythmBars(listOf(minutes(*lengths))).single()

        assertEquals(5, day.size)
        assertFitsColumn(day)
        day.forEach { bar ->
            assertTrue(bar.height > 0f, "no call loses its bar: $day")
            assertTrue(fill(bar) > 0f, "every bar keeps some of the person's colour: $bar")
            assertTrue(bar.rim > 0f && bar.ring > 0f, "every bar keeps its direction rim and ring: $bar")
        }
        for (i in lengths.indices) {
            for (j in lengths.indices) {
                if (lengths[i] > lengths[j]) {
                    assertTrue(day[i].height >= day[j].height - eps, "${lengths[i]} min is not drawn shorter than ${lengths[j]} min: $day")
                }
            }
        }
        assertEquals(day[1], day.maxBy { it.height }, "the 12-minute call is the tallest")
    }

    @Test
    fun `fitting the busiest day leaves the rest of the week at its own heights`() {
        val week = rhythmBars(
            listOf(minutes(40, 5), minutes(20), quiet(), minutes(14), quiet(), minutes(3), quiet()),
        )

        assertFitsColumn(week[0])
        assertEquals(natural(20, busiestMinutes = 45), week[1].single().height, eps)
        assertEquals(14f, week[3].single().height, eps)
        assertEquals(14f, week[5].single().height, eps)
        assertTrue(week[2].isEmpty() && week[4].isEmpty() && week[6].isEmpty(), "a quiet day has no bars")
        week.filter { it.isNotEmpty() }.forEach { day -> day.forEach(::assertWholeMark) }
    }

    @Test
    fun `a week whose bars fit is drawn at its natural heights above the 14dp floor`() {
        val week = rhythmBars(
            listOf(minutes(14), quiet(), minutes(26, 6), quiet(), minutes(41), minutes(9), minutes(4)),
        )

        // The busiest day's lone call is its total under 25% headroom: 38.4dp.
        assertEquals(38.4f, week[4].single().height, eps)
        assertEquals(natural(26, busiestMinutes = 41), week[2][0].height, eps)
        assertEquals(14f, week[2][1].height, eps)
        assertEquals(14f, week[0].single().height, eps)
        assertEquals(14f, week[6].single().height, eps)
        // Two bars and a 3dp gap, sitting on the column's foot.
        assertEquals(week[2][0].top + week[2][0].height + 3f, week[2][1].top, eps)
        week.filter { it.isNotEmpty() }.forEach { day ->
            assertFitsColumn(day)
            day.forEach(::assertWholeMark)
        }
    }

    @Test
    fun `however many calls a day has, none loses its bar`() {
        val day = rhythmBars(listOf(List(30) { 3 * 60 })).single()

        assertEquals(30, day.size)
        assertFitsColumn(day)
        day.forEach { bar -> assertTrue(bar.height > 0f && fill(bar) > 0f, "no call loses its bar: $bar") }
    }

    @Test
    fun `a week with no calls has no bars`() {
        val week = rhythmBars(List(7) { quiet() })

        assertEquals(7, week.size)
        assertTrue(week.all { it.isEmpty() })
    }
}
