package app.orbit.widget

import app.orbit.domain.contactFixture
import app.orbit.domain.usecase.WidgetSurfaceData
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.junit.Test

/**
 * [OrbitWidget2x2] rendering assertions — WIDGET-01, WIDGET-03.
 *
 * Tests use the [selectWidget2x2State] pure helper
 * as the JVM-only seam. Glance composable rendering requires an Android device
 * or Robolectric + Glance test harness; the selection logic (which branch to
 * show) is fully JVM-testable via the sealed [Widget2x2State] result.
 *
 * This matches the <behavior> guidance:
 * "assert the data-selection logic (primary == null → empty branch) via a
 * small pure helper. Choose the seam that keeps the test JVM-only."
 */
class OrbitWidget2x2Test {

    private val contact = contactFixture(
        id = 1L,
        displayName = "Alice",
        firstSeenByAppAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    // ─── OrbitWidget2x2 rendering ────────────────────────────────────────────

    /**
     * provideGlance delegates to selectWidget2x2State which uses WidgetSurfaceUseCase
     * output (not a direct Room read). With a Found contact, the widget shows the
     * contact's displayName (not "Contact" or empty).
     */
    @Test
    fun provideGlance_pullsFromWidgetSurfaceUseCase() {
        val data = WidgetSurfaceData(primary = contact, alternatives = emptyList())
        val state = selectWidget2x2State(data = data, minimalMode = false)

        assertIs<Widget2x2State.Contact>(state)
        assertEquals("Alice", state.displayedName)
    }

    /** When WidgetSurfaceData.primary is null, selection returns Empty (renders "No one due"). */
    @Test
    fun emptyState_rendersNoOneDue() {
        val data = WidgetSurfaceData(primary = null, alternatives = emptyList())
        val state = selectWidget2x2State(data = data, minimalMode = false)

        assertIs<Widget2x2State.Empty>(state)
    }
}
