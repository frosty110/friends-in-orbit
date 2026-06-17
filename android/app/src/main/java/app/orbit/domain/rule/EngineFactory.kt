package app.orbit.domain.rule

import app.orbit.data.entity.RuleTemplateEntity
import kotlinx.serialization.json.Json

/**
 * Resolves a [RuleEngine] for a given [RuleTemplateEntity]. Dispatches on the
 * decoded [RuleParams] sealed subtype — not on `template.kind` — so a mismatch
 * between `kind` and the stored `paramsJson` surfaces as a `SerializationException`
 * at decode rather than a wrong engine silently running. The `when` is exhaustive
 * because `RuleParams` is sealed; adding a new subtype later forces a compile
 * error here, which is the intent.
 *
 * Consumers: `resolveParamsFor` (sibling) and `SurfaceNextUseCase` /
 * `MarkCalledUseCase`. Top-level function (not a method on a class) —
 * keeps the dispatcher greppable and DI-simple.
 */
fun engineFor(template: RuleTemplateEntity, json: Json): RuleEngine =
    engineFor(json.decodeFromString<RuleParams>(template.paramsJson))

/**
 * Builds an engine directly from decoded params. Useful when the caller has
 * already resolved params via [resolveParamsFor] (per-contact override path —
 * DOM-04). Overload resolution picks this by param type unambiguously.
 * The template-taking overload above delegates here, so adding a 4th engine
 * is a single-site edit.
 */
fun engineFor(params: RuleParams): RuleEngine = when (params) {
    is RuleParams.KeepInTouch -> KeepInTouchEngine(params)
    is RuleParams.LateNight   -> LateNightEngine(params)
    is RuleParams.Energize    -> EnergizeEngine(params)
}
