package app.orbit.calllog

import androidx.compose.runtime.Immutable

/**
 * Immutable summary of a single [CallLogReconciler.reconcile] pass. All counts are
 * non-PII integers — safe to log via Timber (CALL-07 PII gate) and surface in the
 * Settings "last sync" UI.
 *
 * Field semantics:
 *  - `scanned`: total rows the reconciler considered in this pass (every input row
 *    increments scanned, regardless of whether it was filtered, deduped, or inserted).
 *  - `inserted`: rows that resulted in a new `CallEventEntity` write (either via
 *    [app.orbit.domain.usecase.MarkCalledUseCase] propagation for non-ignored
 *    contacts, or via direct [app.orbit.data.dao.CallEventDao.insert] for ignored
 *    contacts per IGNORE-09).
 *  - `skipped`: rows the reconciler intentionally did not insert — pre-`sinceMs`,
 *    not-ingestable type, duration<1s, normalization-empty, unmatched contact, or
 *    deduped via [app.orbit.data.dao.CallEventDao.existsAt].
 *  - `contactsPropagated`: count of unique non-ignored contacts whose
 *    [app.orbit.domain.usecase.MarkCalledUseCase] was invoked (drives the
 *    cross-list `nextDueAt` recomputation surface). Ignored contacts contribute
 *    to `inserted` but never to `contactsPropagated` — IGNORE-09.
 *
 * Invariant (when `sinceMs == 0` and no read errors): `inserted + skipped == scanned`.
 */
@Immutable
data class ReconcileSummary(
    val scanned: Int,
    val inserted: Int,
    val skipped: Int,
    val contactsPropagated: Int,
) {
    companion object {
        val EMPTY = ReconcileSummary(
            scanned = 0,
            inserted = 0,
            skipped = 0,
            contactsPropagated = 0,
        )
    }
}
