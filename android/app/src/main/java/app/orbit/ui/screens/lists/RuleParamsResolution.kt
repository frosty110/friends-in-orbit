package app.orbit.ui.screens.lists

import app.orbit.domain.rule.RuleParams
import kotlinx.serialization.json.Json

/**
 * What a list's stored rhythm parameters resolve to, for the two screens
 * that read them without an engine: the Lists row's second line and List
 * settings' How often section.
 *
 * Three answers, because until 2026-10-06 "no subtitle" covered two
 * different facts. [None] is a list with nothing configured: no per-list
 * override and no template (a partially created row). [Unreadable] is a blob
 * Orbit itself wrote (`CreateListUseCase` encodes the override, the seed writes the
 * template) that no longer decodes, which is a bug worth seeing, not a list
 * without a rhythm (rules.md Code 3: a path that cannot happen gets a loud
 * guard, not a shrug). The domain decodes the same JSON with a plain
 * `decodeFromString` in `OverrideResolver.resolveParamsFor` and throws, so
 * the deck and the queue fail on the same blob; the Lists row says so where
 * the user will look.
 */
sealed interface RuleParamsResolution {
    /** Neither an override nor a template: nothing to say. */
    data object None : RuleParamsResolution

    /** The parameters, as the engine reads them. */
    data class Decoded(val params: RuleParams) : RuleParamsResolution

    /** A stored blob that does not decode to [RuleParams]. */
    data object Unreadable : RuleParamsResolution
}

/**
 * Resolves the per-list override, else the template's defaults, in the order
 * `OverrideResolver` uses: the override wins. An override that does not
 * decode is [RuleParamsResolution.Unreadable] even when the template would
 * decode; falling back to the template would hide the broken write behind a
 * rhythm the engine is not running.
 */
fun resolveRuleParams(
    overrideJson: String?,
    templateParamsJson: String?,
    json: Json,
): RuleParamsResolution {
    val source = overrideJson ?: templateParamsJson ?: return RuleParamsResolution.None
    return try {
        RuleParamsResolution.Decoded(json.decodeFromString(RuleParams.serializer(), source))
    } catch (e: IllegalArgumentException) {
        // kotlinx.serialization reports every decode failure (malformed
        // JSON, a missing discriminator, an unknown subtype) as a
        // SerializationException, which is an IllegalArgumentException.
        // Anything else is not a decode failure and propagates to the
        // screen's Error state.
        RuleParamsResolution.Unreadable
    }
}
