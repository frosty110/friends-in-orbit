package app.orbit.data.repository

import java.time.Instant

/**
 * Aggregated per-contact call statistics for picker / search / smart-list
 * decoration. The repository's `observeAggregatesForContacts(ids)`
 * folds the DAO's [app.orbit.data.dao.CallAggRow] rows into a
 * `Map<Long, CallAgg>` keyed by contactId; consumers default-coalesce
 * (`map[id]?.count ?: 0`, `map[id]?.lastAt`) for ids with no events.
 *
 * Replaces the in-memory `events.groupBy { it.contactId }` reductions that the
 * deleted `CallEventRepository.observeAll()` sentinel used to feed.
 */
data class CallAgg(val count: Int, val lastAt: Instant?)
