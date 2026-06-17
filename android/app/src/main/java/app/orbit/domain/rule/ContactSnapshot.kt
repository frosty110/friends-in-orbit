package app.orbit.domain.rule

import java.time.Instant

/**
 * Minimal read-only contact projection for engine consumption. Decouples engines
 * from `ContactEntity` so unit tests can supply hand-rolled fixtures without
 * constructing a Room row. Use cases build `ContactSnapshot` instances
 * from `ContactEntity` at the Flow boundary.
 *
 * Fields are restricted to what engines actually read. `displayName` was removed
 * after review (no engine references it; if a future engine needs it — e.g.,
 * deterministic tiebreak on name for visual stability — add it back at that time).
 */
data class ContactSnapshot(
    val id: Long,
    val isIgnored: Boolean,
    val pausedUntil: Instant?,
)
