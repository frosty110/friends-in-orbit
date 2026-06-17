package app.orbit.data.dao

import java.time.Instant

/**
 * Pre-state row used by [BulkPauseUseCase] to compute its inverse undo lambda.
 * Returned by [ContactDao.getPausedUntilSnapshot]. Room maps columns by name.
 */
data class PausedUntilSnapshot(
    val id: Long,
    val pausedUntil: Instant?,
)
