package app.orbit.data.serialization

import kotlinx.serialization.Serializable

/**
 * Pre-ignore membership snapshot envelope (IGNORE-02, IGNORE-07).
 *
 * Stored in `Contact.preIgnoreListMembershipsJson` by `IgnoreContactUseCase` at
 * ignore time; decoded by `UnignoreContactUseCase` to compare against current
 * memberships and detect drift (e.g., a list archived/deleted during the ignore
 * window). Use [app.orbit.domain.JsonProvider.json] for encode/decode — DO NOT
 * configure a fresh `Json` instance.
 *
 * Forward-compat: `JsonProvider.json` ships with `ignoreUnknownKeys = true`, so
 * future schema additions (e.g., a `version: Int` field) decode cleanly even
 * from older app versions, following the forward-compat rule.
 *
 * Per-contact rule-override JSON (`Contact.ruleOverrideJson`) is NOT wrapped in
 * an envelope — `OverrideResolver` decodes it directly as a raw
 * `RuleParams` (sealed-class `classDiscriminator = "type"` reconstructs the
 * subtype). Adding an envelope would require modifying the resolver and
 * migrating in-place rows — a load-bearing simplicity decision.
 */
@Serializable
data class PreIgnoreMembershipsSnapshot(
    val listIds: List<Long>,
)
