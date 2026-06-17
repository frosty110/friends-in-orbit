package app.orbit.data.dao

/**
 * Pre-ignore snapshot row used by [UnignoreContactUseCase] to read just the
 * serialized membership snapshot back without hydrating the whole contact row.
 * Room maps `id` and `preIgnoreListMembershipsJson` columns by name.
 */
data class PreIgnoreSnapshot(
    val id: Long,
    val preIgnoreListMembershipsJson: String?,
)
