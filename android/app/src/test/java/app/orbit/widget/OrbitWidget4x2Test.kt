package app.orbit.widget

import app.orbit.domain.contactFixture
import app.orbit.domain.usecase.WidgetSurfaceData
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * [OrbitWidget4x2] rendering assertions — WIDGET-02, WIDGET-03.
 *
 * Plain JVM tests using the [selectWidget4x2State] pure helper as the testable
 * seam (same pattern as [OrbitWidget2x2Test]). The 4×2 widget ships as a static
 * three-column fallback: Glance 1.1.1 has no LazyRow/horizontal scroll, so the
 * layout is primary card on the left + two alternatives stacked on the right.
 * Swipe-between is deferred to v1.1.
 */
class OrbitWidget4x2Test {

    private val contactA = contactFixture(
        id = 1L,
        displayName = "Alice",
        firstSeenByAppAt = Instant.parse("2026-01-01T00:00:00Z"),
    )
    private val contactB = contactFixture(
        id = 2L,
        displayName = "Bob",
        firstSeenByAppAt = Instant.parse("2026-01-02T00:00:00Z"),
    )
    private val contactC = contactFixture(
        id = 3L,
        displayName = "Carol",
        firstSeenByAppAt = Instant.parse("2026-01-03T00:00:00Z"),
    )

    // ─── OrbitWidget4x2 rendering ────────────────────────────────

    /**
     * With primary + 2 alternatives, the 4×2 widget renders three contact cards —
     * A as primary, B and C as alternatives (WIDGET-02 three-column layout).
     */
    @Test
    fun content_rendersPrimaryPlusUpToTwoAlternatives() {
        val data = WidgetSurfaceData(
            primary = contactA,
            alternatives = listOf(contactB, contactC),
        )
        val state = selectWidget4x2State(data = data, minimalMode = false)

        assertIs<Widget4x2State.Populated>(state)
        assertEquals("Alice", state.primaryDisplayedName)
        assertEquals(2, state.alternatives.size)
        assertEquals("Bob", state.alternatives[0].displayedName)
        assertEquals("Carol", state.alternatives[1].displayedName)
        assertFalse(
            state.isFullWidth,
            "with alternatives present the layout must keep the divider + right column",
        )
    }

    /**
     * When alternatives list is empty, the primary card takes the full widget
     * width — no divider, no alternatives column (WIDGET-02 edge case,
     * review WR-03). Asserted via the [Widget4x2State.isFullWidth] layout
     * seam, which mirrors the `alternatives.isEmpty()` branch in
     * [OrbitWidget4x2Content].
     */
    @Test
    fun zeroAlternatives_primaryTakesFullWidth() {
        val data = WidgetSurfaceData(
            primary = contactA,
            alternatives = emptyList(),
        )
        val state = selectWidget4x2State(data = data, minimalMode = false)

        assertIs<Widget4x2State.Populated>(state)
        assertEquals("Alice", state.primaryDisplayedName)
        assertTrue(state.alternatives.isEmpty(), "alternatives should be empty")
        assertTrue(state.isFullWidth, "primary must render full-width with zero alternatives")
    }

    /**
     * The empty state ("No one due") also renders full-width — not squeezed
     * into the left column beside a dangling divider (review WR-03).
     */
    @Test
    fun emptyState_rendersFullWidth() {
        val data = WidgetSurfaceData(primary = null, alternatives = emptyList())
        val state = selectWidget4x2State(data = data, minimalMode = false)

        assertIs<Widget4x2State.Empty>(state)
        assertTrue(state.isFullWidth, "empty state must render full-width")
    }
}
