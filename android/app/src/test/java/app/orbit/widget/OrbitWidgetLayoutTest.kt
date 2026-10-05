package app.orbit.widget

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import app.orbit.ui.theme.WidgetSizes
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/**
 * WIDGET-07: every breakpoint a widget declares gets a deliberate arrangement.
 *
 * Inside a Responsive composition LocalSize is one of these breakpoints, so
 * the layout functions are pinned at exactly those sizes. The renders that go
 * with them are produced by PlatformSurfacesGalleryTest (-Pscreenshots).
 */
class OrbitWidgetLayoutTest {

    @Test
    fun nextCall_hasAnArrangementForEveryBreakpoint() {
        val expected = mapOf(
            WidgetBreakpoints.Strip to WidgetLayout.STRIP,
            WidgetBreakpoints.StripWide to WidgetLayout.STRIP,
            WidgetBreakpoints.Compact to WidgetLayout.COMPACT,
            WidgetBreakpoints.Hero to WidgetLayout.HERO,
            WidgetBreakpoints.HeroLarge to WidgetLayout.HERO,
            WidgetBreakpoints.Wide to WidgetLayout.WIDE,
        )
        assertEquals(expected.keys, WidgetBreakpoints.nextCall)
        expected.forEach { (size, layout) -> assertEquals(layout, nextCallLayout(size), "at $size") }
    }

    @Test
    fun suggestions_listOthersOnlyWhereTheyFit() {
        val expected = mapOf(
            WidgetBreakpoints.Strip to (WidgetLayout.STRIP to 0),
            WidgetBreakpoints.StripWide to (WidgetLayout.STRIP to 0),
            WidgetBreakpoints.Compact to (WidgetLayout.COMPACT to 0),
            WidgetBreakpoints.Hero to (WidgetLayout.HERO to 0),
            WidgetBreakpoints.HeroLarge to (WidgetLayout.HERO to 0),
            WidgetBreakpoints.Wide to (WidgetLayout.SPLIT to 1),
            WidgetBreakpoints.SplitTall to (WidgetLayout.SPLIT to 2),
            WidgetBreakpoints.Stack to (WidgetLayout.STACK to 2),
        )
        assertEquals(expected.keys, WidgetBreakpoints.suggestions)
        expected.forEach { (size, want) ->
            val slots = alternativeSlots(size)
            assertEquals(want.second, slots, "others listed at $size")
            assertEquals(want.first, suggestionsLayout(size, alternatives = 2), "arrangement at $size")
        }
    }

    @Test
    fun suggestions_withNobodyElse_giveTheLeadTheWholeWidth() {
        assertEquals(WidgetLayout.WIDE, suggestionsLayout(WidgetBreakpoints.SplitTall, alternatives = 0))
        assertEquals(WidgetLayout.WIDE, suggestionsLayout(WidgetBreakpoints.Stack, alternatives = 0))
    }

    @Test
    fun strip_writesTheNameOnlyWhereItFits() {
        assertFalse(stripShowsName(WidgetBreakpoints.Strip))
        assertTrue(stripShowsName(WidgetBreakpoints.StripWide))
    }

    @Test
    fun heroAvatar_growsWithTheSquare() {
        assertEquals(WidgetSizes.avatarMedium, heroAvatarDp(WidgetBreakpoints.Hero))
        assertEquals(WidgetSizes.avatarLarge, heroAvatarDp(WidgetBreakpoints.HeroLarge))
    }

    /**
     * Each breakpoint is tall enough for its arrangement with a full 48dp Call
     * button (rules.md Design 3): a strip's 4dp padding, every other
     * arrangement's 12dp.
     */
    @Test
    fun everyBreakpoint_holdsA48dpCallButton() {
        val tap = WidgetSizes.tapMin.dp
        (WidgetBreakpoints.nextCall + WidgetBreakpoints.suggestions).forEach { size: DpSize ->
            val padding = if (nextCallLayout(size) == WidgetLayout.STRIP) 4.dp else 12.dp
            assertTrue(size.height - padding * 2 >= tap, "a 48dp button fits at $size")
        }
    }
}
