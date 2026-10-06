package app.orbit.ui.screens.lists

import app.orbit.domain.JsonProvider
import app.orbit.domain.rule.RuleParams
import kotlin.test.assertEquals
import org.junit.Test

/**
 * The resolver the Lists row and List settings share: the override wins,
 * nothing configured is [RuleParamsResolution.None], and a blob that does
 * not decode is [RuleParamsResolution.Unreadable], never None and never a
 * quiet fall-through to the template (rules.md Code 3).
 */
class RuleParamsResolutionTest {

    private val json = JsonProvider.json
    private val fortnightly = json.encodeToString(
        RuleParams.serializer(),
        RuleParams.KeepInTouch().withIntervalHours(14 * 24),
    )
    private val lateNight = json.encodeToString(RuleParams.serializer(), RuleParams.LateNight())

    @Test
    fun nothing_configured_is_None() {
        assertEquals(RuleParamsResolution.None, resolveRuleParams(null, null, json))
    }

    @Test
    fun the_override_wins_over_the_template() {
        assertEquals(
            RuleParamsResolution.Decoded(RuleParams.KeepInTouch().withIntervalHours(14 * 24)),
            resolveRuleParams(fortnightly, lateNight, json),
        )
    }

    @Test
    fun the_template_answers_when_there_is_no_override() {
        assertEquals(
            RuleParamsResolution.Decoded(RuleParams.LateNight()),
            resolveRuleParams(null, lateNight, json),
        )
    }

    @Test
    fun a_blob_that_does_not_decode_is_Unreadable_not_None() {
        // Malformed text, from either source.
        assertEquals(RuleParamsResolution.Unreadable, resolveRuleParams("{not json", null, json))
        assertEquals(RuleParamsResolution.Unreadable, resolveRuleParams(null, "{not json", json))
        // Well-formed JSON that is not a RuleParams (no "type" discriminator).
        assertEquals(
            RuleParamsResolution.Unreadable,
            resolveRuleParams("""{"cooldownMinHours":48}""", null, json),
        )
    }

    @Test
    fun an_unreadable_override_does_not_fall_through_to_the_template() {
        // The template would decode, but showing it would describe a rhythm
        // the engine is not running: the domain resolver throws on the same
        // override.
        val resolved = resolveRuleParams("{not json", lateNight, json)
        assertEquals(RuleParamsResolution.Unreadable, resolved)
    }
}
