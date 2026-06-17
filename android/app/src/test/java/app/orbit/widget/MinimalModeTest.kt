package app.orbit.widget

import app.orbit.domain.contactFixture
import app.orbit.domain.usecase.WidgetSurfaceData
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Widget minimal-mode masking assertions — WIDGET-04.
 *
 * MinimalModeTest is the dedicated WIDGET-04 test. A 4×2 variant method
 * (`minimalModeOn_4x2_masksAllThreeNames`) covers the 4×2 widget surface.
 *
 * Tests use the [selectWidget2x2State] pure helper as the JVM-only seam
 * (same pattern as [OrbitWidget2x2Test]). The seam asserts that name masking
 * and avatar masking flags are set correctly by the selection logic — Glance
 * rendering is verified on-device during UAT.
 */
class MinimalModeTest {

    private val contact = contactFixture(
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

    private val dataWithContact = WidgetSurfaceData(
        primary = contact,
        alternatives = emptyList(),
    )
    private val dataWithThreeContacts = WidgetSurfaceData(
        primary = contact,
        alternatives = listOf(contactB, contactC),
    )

    // ─── WIDGET-04 minimal-mode rendering ────────────────────────────────────

    /** When minimal mode is on, the 2x2 widget shows "Contact" instead of the real name. */
    @Test
    fun minimalModeOn_2x2_replacesNameWithContact() {
        val state = selectWidget2x2State(data = dataWithContact, minimalMode = true)

        assertIs<Widget2x2State.Contact>(state)
        assertEquals("Contact", state.displayedName)
    }

    /** When minimal mode is off, the 2x2 widget shows the real contact name. */
    @Test
    fun minimalModeOff_2x2_showsRealName() {
        val state = selectWidget2x2State(data = dataWithContact, minimalMode = false)

        assertIs<Widget2x2State.Contact>(state)
        assertEquals("Alice", state.displayedName)
    }

    /**
     * When minimal mode is on, the avatar silhouette flag is set.
     * [Widget2x2State.Contact.avatarIsMinimal] drives the [ContactAvatar] composable
     * to render the system silhouette instead of the contact initial (T-11-04 / WIDGET-04).
     */
    @Test
    fun minimalModeOn_masksAvatarToSilhouette() {
        val state = selectWidget2x2State(data = dataWithContact, minimalMode = true)

        assertIs<Widget2x2State.Contact>(state)
        assertTrue(state.avatarIsMinimal, "avatarIsMinimal should be true when minimalMode=true")
    }

    // ─── WIDGET-04 minimal-mode rendering (4×2 surface) ──────────────────────

    /**
     * With minimal mode on, ALL three names (primary + both alternatives) must
     * be masked to "Contact" — no real displayName from any of the three contacts
     * may appear. Avatars for all three use the silhouette branch (WIDGET-04).
     *
     * Uses [selectWidget4x2State] JVM-only seam identical in shape to the 2×2 seam.
     */
    @Test
    fun minimalModeOn_4x2_masksAllThreeNames() {
        val state = selectWidget4x2State(data = dataWithThreeContacts, minimalMode = true)

        assertIs<Widget4x2State.Populated>(state)
        assertEquals(
            "Contact",
            state.primaryDisplayedName,
            "primary name must be masked to 'Contact' in minimal mode",
        )
        assertEquals(2, state.alternatives.size)
        state.alternatives.forEach { alt ->
            assertEquals(
                "Contact",
                alt.displayedName,
                "alternative name must be masked to 'Contact' in minimal mode",
            )
            assertTrue(alt.avatarIsMinimal, "alternative avatarIsMinimal must be true in minimal mode")
        }
        assertTrue(
            state.primaryAvatarIsMinimal,
            "primary avatarIsMinimal must be true in minimal mode",
        )
        // Verify none of the real names leak through
        assertFalse(
            state.primaryDisplayedName == "Alice",
            "Alice must not appear in minimal mode",
        )
        assertFalse(
            state.alternatives.any { it.displayedName == "Bob" || it.displayedName == "Carol" },
            "Bob/Carol must not appear in minimal mode",
        )
    }
}
