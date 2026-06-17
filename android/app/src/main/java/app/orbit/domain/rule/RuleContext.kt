package app.orbit.domain.rule

import app.orbit.data.entity.CallDirection
import app.orbit.data.entity.CallSource
import java.time.Instant
import java.time.LocalTime

/**
 * Per-contact context bundle passed to every [RuleEngine.nextDue] call. Bundled
 * (rather than split across parameters) because every engine needs every field;
 * adding a new field is a single-site edit here and at the use-case builder in
 * `SurfaceNextUseCase`.
 *
 * `lastCall*` fields are null when the contact has zero call events — engines
 * MUST treat null `lastCallAt` as "cold start, surface immediately".
 */
data class RuleContext(
    val lastCallAt: Instant?,
    val lastCallDurationSec: Int,
    val lastCallDirection: CallDirection?,
    val lastCallSource: CallSource?,
    val skipCount: Int,
    val params: RuleParams,
    val activeHoursStart: LocalTime? = null,
    val activeHoursEnd: LocalTime? = null,
)
