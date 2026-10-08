package app.orbit.ui.screens.onboarding

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.orbit.data.entity.ListType
import app.orbit.data.entity.RuleKind
import app.orbit.domain.rule.RuleParams
import app.orbit.ui.screens.lists.ListConfigUiState
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #15 (2026-06-09) — pure-function tests for the onboarding first-list
 * activation gate and its helper copy ([firstListCanFinish] /
 * [firstListHelperText], OnboardingFirstListScreen.kt).
 *
 * The bug: OnboardingPermContactsScreen's denied note promises "You can
 * still create lists", but the first-list step (onBack = null, no skip)
 * gated Done on >= 3 members — unmeetable with an empty picker when
 * READ_CONTACTS is denied. The gate must relax to name-only in that state.
 *
 * The helper copy lives in strings_onboarding.xml; [helper] resolves it against
 * real resources under Robolectric so the wording stays pinned.
 *
 * Also pins [firstListFallback]: the step's Error and NotFound states each
 * carry a visible action (Try again, Start again), so the screen can never
 * again show "loading" for ever under two disabled CTAs (state-2, G4).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class OnboardingFirstListGateTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun helper(name: String, memberCount: Int, hasContactsPermission: Boolean): String? =
        firstListHelperText(name, memberCount, hasContactsPermission)?.let { context.getString(it) }

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
            helper(name = "In touch", memberCount = 2, hasContactsPermission = true),
        )
        assertEquals(
            "Add a name and pick at least 3 people to finish.",
            helper(name = "", memberCount = 3, hasContactsPermission = true),
        )
        assertNull(helper(name = "In touch", memberCount = 3, hasContactsPermission = true))
    }

    @Test
    fun `denied - helper sets the empty-picker expectation, never the threshold nudge`() {
        assertEquals(
            "You can add people once Orbit can see your contacts. Grant access any time in Settings.",
            helper(name = "In touch", memberCount = 0, hasContactsPermission = false),
        )
    }

    @Test
    fun `denied - blank name helper still asks for a name`() {
        assertEquals(
            "Give your list a name to finish. You can add people once Orbit can see your contacts.",
            helper(name = "", memberCount = 0, hasContactsPermission = false),
        )
    }

    // ── Fallback states: every non-Ready branch offers a way forward ─────────

    private fun label(fallback: FirstListFallback): String = context.getString(fallback.actionLabelRes)

    @Test
    fun `Error offers Try again`() {
        val fallback = assertNotNull(firstListFallback(ListConfigUiState.Error))
        assertEquals(FirstListFallbackAction.Retry, fallback.action)
        assertEquals("Try again", label(fallback))
        assertEquals("Orbit couldn't load this list", context.getString(fallback.titleRes))
    }

    @Test
    fun `NotFound offers Start again`() {
        val fallback = assertNotNull(firstListFallback(ListConfigUiState.NotFound))
        assertEquals(FirstListFallbackAction.StartAgain, fallback.action)
        assertEquals("Start again", label(fallback))
    }

    @Test
    fun `every fallback has a visible action label`() {
        listOf(ListConfigUiState.Error, ListConfigUiState.NotFound).forEach { state ->
            val fallback = assertNotNull(firstListFallback(state), "$state must not render as loading")
            assertTrue(label(fallback).isNotBlank(), "$state must offer a visible action")
        }
    }

    @Test
    fun `Loading and Ready have no fallback`() {
        assertNull(firstListFallback(ListConfigUiState.Loading))
        assertNull(
            firstListFallback(
                ListConfigUiState.Ready(
                    id = 1L,
                    name = "In touch",
                    type = ListType.STATIC,
                    ruleKind = RuleKind.KEEP_IN_TOUCH,
                    ruleParams = RuleParams.KeepInTouch(cooldownMinHours = 168),
                    smartRule = null,
                    notificationsEnabled = true,
                    nudgeSchedule = null,
                    members = emptyList()
                )
            )
        )
    }
}
