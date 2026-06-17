package app.orbit.domain.usecase

import app.orbit.data.dao.ListMembershipDao
import app.orbit.data.db.TransactionRunner
import app.orbit.data.repository.ContactRepository
import app.orbit.data.repository.ListRepository
import app.orbit.data.serialization.PreIgnoreMembershipsSnapshot
import app.orbit.domain.JsonProvider
import app.orbit.domain.WidgetRefreshTrigger
import app.orbit.domain.clock.Clock
import javax.inject.Inject
import kotlinx.serialization.encodeToString

/**
 * IGNORE-02 single-contact ignore. Writes the four-column atomic update via
 * [ContactRepository.markIgnored] inside a [TransactionRunner.withTransaction]
 * block. Snapshots the contact's current ListMembership.listId set into
 * `Contact.preIgnoreListMembershipsJson` so [UnignoreContactUseCase] can detect
 * drift (e.g., a list archived during the ignore window).
 *
 * Pitfall 1: MUST NOT delete any ListMembership rows. `isIgnored = true` is the
 * ONLY membership-affecting state change. Surfacing exclusion lives in
 * SurfaceNextUseCase.
 *
 * Pitfall 3: the `withTransaction` block calls only suspending DAO/Repo methods;
 * no dispatcher switch inside.
 *
 * Result.inverse intentionally does NOT run the drift restore — it only flips
 * back to the immediate prior state. The user undid in <5 seconds; no list
 * churn is possible. The drift-restore is reserved for [UnignoreContactUseCase]
 * (Settings → Ignored un-ignore path, where minutes/days have passed).
 */
class IgnoreContactUseCase @Inject constructor(
    private val txRunner: TransactionRunner,
    private val contactRepo: ContactRepository,
    private val listMembershipDao: ListMembershipDao,
    private val listRepo: ListRepository,
    private val clock: Clock,
    private val widgetRefreshTrigger: WidgetRefreshTrigger = WidgetRefreshTrigger { },
) {
    /**
     * @property inverse Suspending closure that flips the four ignore columns
     *                   back to (false, null, null) — undo within the snackbar
     *                   window; no drift restore needed.
     * @property label Snackbar copy: "Ignored {contactName}".
     */
    data class Result(val inverse: suspend () -> Unit, val label: String)

    suspend operator fun invoke(contactId: Long, contactName: String): Result {
        val now = clock.now()
        // H2 fix — the membership snapshot read MUST happen inside the same
        // transaction as `markIgnored` so a concurrent membership write between
        // the snapshot read and the column update cannot corrupt the persisted
        // `preIgnoreListMembershipsJson`. Mirrors `ListRepositoryImpl.reorder`,
        // which calls `observeActive().first()` inside `db.withTransaction`.
        // Pitfall 3: still no `withContext` switch inside the block.
        //
        // R2.B — `priorMemberships` is lifted out via the transaction's
        // return value so the inverse closure can recompute the same listIds
        // without re-reading the DAO (the membership rows are unchanged across
        // the ignore flip — only the contact's `isIgnored` column moves).
        val priorListIds: List<Long> = txRunner.withTransaction {
            val priorMemberships = listMembershipDao.getMembershipsForContact(contactId)
            val snapshotJson = JsonProvider.json.encodeToString(
                PreIgnoreMembershipsSnapshot(listIds = priorMemberships.map { it.listId }),
            )
            contactRepo.markIgnored(
                id = contactId,
                isIgnored = true,
                ignoredAt = now,
                preIgnoreListMembershipsJson = snapshotJson,
            )
            // R2.B — every list this contact was a member of needs
            // its dueCount recomputed because the surface filter looks at
            // isIgnored. The recompute makes the column truth.
            priorMemberships.forEach { membership ->
                listRepo.recomputeDueCountForList(membership.listId, now)
            }
            priorMemberships.map { it.listId }
        }
        // WIDGET-06: ignore removes this contact from who-is-due; surface changes.
        // Resolved open question (UI-SPEC §Update Cadence): fire trigger for
        // ignore so the widget reflects the change within ~30s (not up to 60min).
        widgetRefreshTrigger.scheduleRefresh()
        return Result(
            inverse = {
                txRunner.withTransaction {
                    contactRepo.markIgnored(
                        id = contactId,
                        isIgnored = false,
                        ignoredAt = null,
                        preIgnoreListMembershipsJson = null,
                    )
                    // R2.B — undo flips isIgnored back, so the same
                    // lists need their dueCount recomputed (the surface filter
                    // semantics flipped again).
                    val undoNow = clock.now()
                    priorListIds.forEach { listId ->
                        listRepo.recomputeDueCountForList(listId, undoNow)
                    }
                }
                // WIDGET-06 (review WR-02): undo flips who-is-due back — the
                // widget must reflect the un-ignored state, not the ignored one.
                // The 30s KEEP debounce coalesces the forward+undo pair.
                widgetRefreshTrigger.scheduleRefresh()
            },
            label = "Ignored $contactName",
        )
    }
}
