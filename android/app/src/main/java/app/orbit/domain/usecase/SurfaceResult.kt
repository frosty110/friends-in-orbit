package app.orbit.domain.usecase

import androidx.compose.runtime.Immutable
import app.orbit.data.entity.ContactEntity
import java.time.Instant

/**
 * Outcome of one [SurfaceNextUseCase] emission.
 *
 * The use case used to return `Flow<ContactEntity?>`, where `null` collapsed
 * three different conditions into a single terminal "All caught up" screen.
 * That conflation read as a completion mechanic ("you're done") and worked
 * against the queue's actual semantics (rotation per the list rule, infinite
 * by design). [SurfaceResult] separates the three so the UI can render an
 * honest tide-marker on a continuously-flowing card and reserve true empty
 * states for the rare cases where there is genuinely nobody to surface.
 */
@Immutable
sealed interface SurfaceResult {

    /**
     * The next-best contact for the list, with the engine-computed
     * [nextDueAt] carried alongside so the UI can decide whether the
     * contact is past or ahead of today's waterline. The use case no longer
     * drops candidates whose `nextDueAt > clock.now()` — they are surfaced
     * as the "ahead of today" tail of the queue.
     */
    @Immutable
    data class Found(
        val contact: ContactEntity,
        val nextDueAt: Instant,
    ) : SurfaceResult

    /**
     * The list has zero non-archived non-ignored memberships — the user has
     * not put anyone in this list (or has archived everyone). UI copy:
     * "Add people to this list."
     */
    @Immutable
    data object NoMembers : SurfaceResult

    /**
     * The list has visible members, but none survives filtering right now —
     * paused, outside active hours, no rule template, or engine returned
     * `nextDue == null`. UI copy: "No one is up next on this list."
     */
    @Immutable
    data object NothingEligible : SurfaceResult
}
