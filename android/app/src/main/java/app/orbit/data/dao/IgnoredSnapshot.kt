package app.orbit.data.dao

/**
 * Pre-state row used by [BulkIgnoreUseCase] to compute its inverse undo lambda.
 * Returned by [ContactDao.getIgnoredFlags]. Room maps `id` and `isIgnored` columns by name.
 */
data class IgnoredSnapshot(
    val id: Long,
    val isIgnored: Boolean,
)
