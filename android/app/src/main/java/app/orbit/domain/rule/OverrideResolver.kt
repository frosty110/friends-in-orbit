package app.orbit.domain.rule

import app.orbit.data.entity.ContactEntity
import app.orbit.data.entity.ListEntity
import app.orbit.data.entity.RuleTemplateEntity
import kotlinx.serialization.json.Json

/**
 * Resolves the [RuleParams] for a contact/list pair (DOM-04 + LIST-04).
 *
 * Resolution order (per-contact beats per-list beats template default):
 *   1. If `contact.ruleOverrideJson != null`, decode that — per-contact override.
 *   2. Otherwise if `list.ruleParamsOverrideJson != null`, decode that — per-list
 *      override (LIST-04).
 *   3. Otherwise decode `listTemplate.paramsJson` — template default.
 *
 * Per-contact wins over per-list (DOM-04 invariant preserved). The per-list
 * branch lets List Configuration writes to `ListEntity.ruleParamsOverrideJson`
 * propagate to `SurfaceNextUseCase` end-to-end.
 *
 * Does NOT import from `app.orbit.data.dao.*` or `app.orbit.data.repository.*` —
 * works on fully-hydrated entities supplied by the caller (use cases fetch the
 * entities and pass them in).
 */
fun resolveParamsFor(
    contact: ContactEntity,
    list: ListEntity,
    listTemplate: RuleTemplateEntity,
    json: Json,
): RuleParams {
    contact.ruleOverrideJson?.let { override ->
        return json.decodeFromString<RuleParams>(override)
    }
    list.ruleParamsOverrideJson?.let { override ->
        return json.decodeFromString<RuleParams>(override)
    }
    return json.decodeFromString<RuleParams>(listTemplate.paramsJson)
}
