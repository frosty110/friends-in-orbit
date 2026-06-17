package app.orbit.domain.usecase

import app.orbit.data.dao.ListDao
import app.orbit.data.dao.ListMembershipDao
import app.orbit.data.db.TransactionRunner
import app.orbit.data.entity.ListMembershipEntity
import app.orbit.data.repository.ContactRepository
import app.orbit.data.repository.ListRepository
import app.orbit.data.serialization.PreIgnoreMembershipsSnapshot
import app.orbit.domain.JsonProvider
import app.orbit.domain.WidgetRefreshTrigger
import app.orbit.domain.clock.Clock
import javax.inject.Inject
import kotlinx.serialization.decodeFromString

/**
 * IGNORE-07 — un-ignore with drift detection. Decodes the snapshot saved by
 * [IgnoreContactUseCase], queries [ListRepository.observeAll] for the current
 * active list set, and restores any snapshot membership whose list still
 * exists AND isn't already a current membership.
 *
 * Lists that were archived/deleted during the ignore window are skipped — the
 * contact will simply not appear there. Lists that were re-added during the
 * ignore window (somehow gaining the same id) are skipped — the user can
 * re-add manually.
 *
 * Snapshot-null path (legacy contact ignored before the snapshot column
 * existed): clears the four ignore columns and skips the restore loop. No crash.
 *
 * Dispatcher-free withTransaction body — only suspending repo / DAO calls inside.
 */
class UnignoreContactUseCase @Inject constructor(
    private val txRunner: TransactionRunner,
    private val contactRepo: ContactRepository,
    private val listDao: ListDao,
    private val listMembershipDao: ListMembershipDao,
    private val listRepo: ListRepository,
    private val clock: Clock,
    private val widgetRefreshTrigger: WidgetRefreshTrigger = WidgetRefreshTrigger { },
) {
    suspend operator fun invoke(contactId: Long) {
        val snapshot = contactRepo.getPreIgnoreSnapshot(contactId)
        val snapshotIds: List<Long> = snapshot?.preIgnoreListMembershipsJson
            ?.let {
                JsonProvider.json
                    .decodeFromString<PreIgnoreMembershipsSnapshot>(it)
                    .listIds
            }
            ?: emptyList()

        txRunner.withTransaction {
            val activeListIds = listDao.getActive().map { it.id }.toSet()
            val current = listMembershipDao.getMembershipsForContact(contactId)
                .map { it.listId }.toSet()
            val toRestore = snapshotIds.filter { it in activeListIds && it !in current }
            val now = clock.now()
            toRestore.forEach { lid ->
                listMembershipDao.insert(
                    ListMembershipEntity(contactId = contactId, listId = lid, addedAt = now),
                )
            }
            contactRepo.markIgnored(
                id = contactId,
                isIgnored = false,
                ignoredAt = null,
                preIgnoreListMembershipsJson = null,
            )
            // Every list whose membership this unignore touches
            // (newly restored OR pre-existing memberships of the now-unignored
            // contact) needs dueCount recomputed because the surface filter
            // semantics changed (isIgnored flip).
            val affectedListIds = (toRestore + current).distinct()
            affectedListIds.forEach { lid ->
                listRepo.recomputeDueCountForList(lid, now)
            }
        }
        // WIDGET-06: unignore re-adds this contact to who-is-due; surface changes.
        // Resolved open question (UI-SPEC §Update Cadence): fire trigger for
        // unignore so the widget reflects the change within ~30s (not up to 60min).
        widgetRefreshTrigger.scheduleRefresh()
    }
}
