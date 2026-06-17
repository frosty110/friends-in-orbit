package app.orbit.ui.screens.onboarding

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * #15 (2026-06-09) — pure-function tests for the onboarding first-list
 * activation gate and its helper copy ([firstListCanFinish] /
 * [firstListHelperText], OnboardingFirstListScreen.kt).
 *
 * The bug: OnboardingPermContactsScreen's denied note promises "You can
 * still create lists", but the first-list step (onBack = null, no skip)
 * gated Done on >= 3 members — unmeetable with an empty picker when
 * READ_CONTACTS is denied. The gate must relax to name-only in that state.
 */
class OnboardingFirstListGateTest {

    // ── Gate: contacts granted (E5 / ONB-24 unchanged) ──────────────────────

    @Test
    fun `granted - requires a name and three members`() {
        assertTrue(firstListCanFinish(name = "In touch", memberCount = 3, hasContactsPermission = true))
        assertFalse(firstListCanFinish(name = "In touch", memberCount = 2, hasContactsPermission = true))
        assertFalse(firstListCanFinish(name = "", memberCount = 3, hasContactsPermission = true))
        assertFalse(firstListCanFinish(name = "   ", memberCount = 5, hasContactsPermission = true))
    }

    // ── Gate: contacts denied (#15 relaxation) ──────────────────────────────

    @Test
    fun `denied - finishes with zero members when the list has a name`() {
        assertTrue(firstListCanFinish(name = "In touch", memberCount = 0, hasContactsPermission = false))
    }

    @Test
    fun `denied - still requires a non-blank name`() {
        assertFalse(firstListCanFinish(name = "", memberCount = 0, hasContactsPermission = false))
        assertFalse(firstListCanFinish(name = "  ", memberCount = 0, hasContactsPermission = false))
    }

    // ── Helper copy ──────────────────────────────────────────────────────────

    @Test
    fun `granted - helper nudges the threshold until met, then goes quiet`() {
        assertEquals(
            "Add a name and pick at least 3 people to finish.",
            firstListHelperText(name = "In touch", memberCount = 2, hasContactsPermission = true),
        )
        assertEquals(
            "Add a name and pick at least 3 people to finish.",
            firstListHelperText(name = "", memberCount = 3, hasContactsPermission = true),
        )
        assertNull(firstListHelperText(name = "In touch", memberCount = 3, hasContactsPermission = true))
    }

    @Test
    fun `denied - helper sets the empty-picker expectation, never the threshold nudge`() {
        assertEquals(
            "You can add people once Orbit can see your contacts — grant access any time in Settings.",
            firstListHelperText(name = "In touch", memberCount = 0, hasContactsPermission = false),
        )
    }

    @Test
    fun `denied - blank name helper still asks for a name`() {
        assertEquals(
            "Give your list a name to finish. You can add people once Orbit can see your contacts.",
            firstListHelperText(name = "", memberCount = 0, hasContactsPermission = false),
        )
    }
}
