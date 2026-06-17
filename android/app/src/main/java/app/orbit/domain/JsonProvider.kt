package app.orbit.domain

import kotlinx.serialization.json.Json

/**
 * Shared kotlinx-serialization configuration for domain-layer JSON
 * (RuleParams, SmartListRule). Consumed by `engineFor`, `resolveParamsFor`, and
 * `SmartListEngine`.
 *
 * - `encodeDefaults = false`: default field values are omitted from stored JSON,
 *   keeping `RuleTemplateEntity.paramsJson` compact.
 * - `ignoreUnknownKeys = true`: forward-compatible decoding; future versions may
 *   add fields without breaking stored rows.
 * - `classDiscriminator = "type"`: the default, pinned for clarity — changing it
 *   later would break stored JSON.
 */
object JsonProvider {
    val json: Json = Json {
        encodeDefaults = false
        ignoreUnknownKeys = true
        classDiscriminator = "type"
    }
}
