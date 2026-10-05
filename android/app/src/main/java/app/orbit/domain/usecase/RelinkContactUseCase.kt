package app.orbit.domain.usecase

import app.orbit.data.dao.ContactDao
import app.orbit.data.dao.ListMembershipDao
import app.orbit.data.db.TransactionRunner
import app.orbit.data.entity.ContactEntity
import app.orbit.data.entity.ContactPhoneEntity
import app.orbit.data.entity.ListMembershipEntity
import app.orbit.data.repository.ListRepository
import app.orbit.domain.WidgetRefreshTrigger
import app.orbit.domain.clock.Clock
import java.time.Instant
import javax.inject.Inject

/**
 * CONTACT-07: re-link an orphaned contact to a phone contact the user picks.
 *
 * An orphan is a Room contact whose address-book entry vanished (deleted,
 * merged on the device, or re-created under a new number). The phone contact
 * the user picks has almost always been mirrored by ingest already, as its
 * own Room row. So re-linking is a merge of two rows into one person:
 *
 *  - The ORPHAN row survives. It holds the history the user cares about, and
 *    keeping its id keeps Contact detail (the screen the user re-linked from)
 *    and the back stack pointing at a row that still exists.
 *  - The LIVE row's call events, notes, phone numbers and list memberships
 *    move onto the orphan, then the emptied live row is deleted. Every table
 *    that references `contacts.id` is covered (the CONTACT-07 block in
 *    [ContactDao] plus [ListMembershipDao.deleteAllForContact]); a child row
 *    left behind would be cascade-deleted with its parent.
 *  - The orphan takes the live row's device-owned identity (number, name,
 *    photo, starred flag, device id) and stops being orphaned, so the next
 *    ingest matches it by number like any other mirrored contact.
 *  - User-owned state stays the orphan's (pause, rule override, ignore,
 *    archive). The live row's pause or override fills in only where the
 *    orphan has none.
 *
 * A list both rows were on keeps one membership; see [mergeMemberships].
 *
 * The undo is a snapshot restore, the same shape as [IgnoreContactUseCase]:
 * both rows, both phone sets, both membership sets, and the exact call-event
 * and note ids that moved. Edits made to the merged contact inside the
 * snackbar window are rolled back with it.
 *
 * Talks to [ContactDao] directly for the reason [IngestPhoneContactsUseCase]
 * gives: the contact repository is a read surface, and this is a structural
 * write.
 */
class RelinkContactUseCase @Inject constructor(
    private val txRunner: TransactionRunner,
    private val contactDao: ContactDao,
    private val listMembershipDao: ListMembershipDao,
    private val listRepo: ListRepository,
    private val clock: Clock,
    private val widgetRefreshTrigger: WidgetRefreshTrigger = WidgetRefreshTrigger { }
) {
    /**
     * @property inverse Suspending closure that splits the two rows back apart.
     * @property linkedName The phone contact's display name, for the caller's
     *                      snackbar ("Re-linked to Mum", string resources).
     */
    data class Result(val inverse: suspend () -> Unit, val linkedName: String)

    /** Everything the forward merge changes, captured before it runs. */
    private data class Snapshot(
        val orphan: ContactEntity,
        val live: ContactEntity,
        val orphanPhones: List<ContactPhoneEntity>,
        val livePhones: List<ContactPhoneEntity>,
        val orphanMemberships: List<ListMembershipEntity>,
        val liveMemberships: List<ListMembershipEntity>,
        val liveCallEventIds: List<Long>,
        val liveNoteIds: List<Long>
    ) {
        val affectedListIds: Set<Long> =
            (orphanMemberships + liveMemberships).mapTo(mutableSetOf()) { it.listId }
    }

    /**
     * @param orphanId the orphaned contact being re-linked.
     * @param liveId the Room row of the phone contact the user picked.
     * @return null, with nothing written, when the pair cannot be merged:
     *         either row is gone, a sync restored the orphan in the meantime,
     *         or the pick is not a live phone contact ([isRelinkTarget]).
     *         The caller reports that as a failed save.
     */
    suspend operator fun invoke(orphanId: Long, liveId: Long): Result? {
        val snapshot = txRunner.withTransaction {
            val orphan = contactDao.get(orphanId)
            val live = contactDao.get(liveId)
            if (orphan == null || live == null || !orphan.isOrphaned || !isRelinkTarget(live, orphanId)) {
                return@withTransaction null
            }
            val snap = Snapshot(
                orphan = orphan,
                live = live,
                orphanPhones = contactDao.getPhonesForContact(orphanId),
                livePhones = contactDao.getPhonesForContact(liveId),
                orphanMemberships = listMembershipDao.getMembershipsForContact(orphanId),
                liveMemberships = listMembershipDao.getMembershipsForContact(liveId),
                liveCallEventIds = contactDao.getCallEventIdsForContact(liveId),
                liveNoteIds = contactDao.getNoteIdsForContact(liveId)
            )

            snap.liveCallEventIds.inChunks { contactDao.reassignCallEvents(it, orphanId) }
            snap.liveNoteIds.inChunks { contactDao.reassignNotes(it, orphanId) }

            listMembershipDao.deleteAllForContact(orphanId)
            listMembershipDao.deleteAllForContact(liveId)
            listMembershipDao.insertAll(
                mergeMemberships(orphanId, snap.orphanMemberships, snap.liveMemberships)
            )

            // The orphan's own numbers are dead (that is why it orphaned); the
            // next ingest would replace them with the device set anyway.
            contactDao.deletePhonesForContact(orphanId)
            contactDao.reassignPhones(fromContactId = liveId, toContactId = orphanId)

            // Delete before the update: the orphan is about to take the live
            // row's normalizedPhone, which is unique.
            contactDao.delete(live)
            contactDao.update(absorb(orphan, live))

            recompute(snap.affectedListIds)
            snap
        } ?: return null

        // WIDGET-06: who is due can change when two memberships collapse.
        widgetRefreshTrigger.scheduleRefresh()
        return Result(
            inverse = { undo(snapshot) },
            linkedName = snapshot.live.displayName
        )
    }

    private suspend fun undo(snap: Snapshot) {
        val orphanId = snap.orphan.id
        val liveId = snap.live.id
        txRunner.withTransaction {
            // Restore the orphan first: it holds the live row's number, and the
            // live row cannot be re-inserted until that unique key is free.
            contactDao.update(snap.orphan)
            contactDao.deletePhonesForContact(orphanId)
            // Same id as before. Room's autoGenerate keys are AUTOINCREMENT, so
            // a deleted id is never handed to another row in the meantime.
            contactDao.insert(snap.live)
            contactDao.insertPhones(snap.orphanPhones + snap.livePhones)

            snap.liveCallEventIds.inChunks { contactDao.reassignCallEvents(it, liveId) }
            snap.liveNoteIds.inChunks { contactDao.reassignNotes(it, liveId) }

            listMembershipDao.deleteAllForContact(orphanId)
            listMembershipDao.insertAll(snap.orphanMemberships + snap.liveMemberships)

            recompute(snap.affectedListIds)
        }
        widgetRefreshTrigger.scheduleRefresh()
    }

    private suspend fun recompute(listIds: Set<Long>) {
        val now = clock.now()
        listIds.forEach { listRepo.recomputeDueCountForList(it, now) }
    }

    companion object {
        /**
         * Who an orphan can be re-linked to: a different contact that is
         * mirrored from the phone right now and is not hidden. One definition,
         * read by both the picker (which candidates to show) and the use case
         * (which merges to refuse).
         */
        fun isRelinkTarget(candidate: ContactEntity, orphanId: Long): Boolean =
            candidate.id != orphanId &&
                candidate.phoneContactId != null &&
                !candidate.isOrphaned &&
                !candidate.isIgnored &&
                !candidate.isArchived

        // Well under SQLite's 999 bound-parameter floor on older Android builds.
        private const val MAX_IDS_PER_STATEMENT = 500

        private suspend fun List<Long>.inChunks(block: suspend (List<Long>) -> Unit) {
            chunked(MAX_IDS_PER_STATEMENT).forEach { block(it) }
        }
    }
}

/**
 * The orphan after it absorbs [live]: device-owned fields come from the phone
 * contact it now mirrors, user-owned fields stay the orphan's, and the live
 * row's pause and override fill in only where the orphan has none.
 * `firstSeenByAppAt` keeps the earlier instant: this person is not new to
 * Orbit, so they must not land in a "Recently added" smart list.
 */
internal fun absorb(orphan: ContactEntity, live: ContactEntity): ContactEntity = orphan.copy(
    phoneContactId = live.phoneContactId,
    phoneNumber = live.phoneNumber,
    normalizedPhone = live.normalizedPhone,
    displayName = live.displayName,
    photoUri = live.photoUri,
    isStarred = live.isStarred,
    deviceUpdatedAt = live.deviceUpdatedAt ?: orphan.deviceUpdatedAt,
    firstSeenByAppAt = minOf(orphan.firstSeenByAppAt, live.firstSeenByAppAt),
    isOrphaned = false,
    pausedUntil = orphan.pausedUntil ?: live.pausedUntil,
    ruleOverrideJson = orphan.ruleOverrideJson ?: live.ruleOverrideJson
)

/**
 * Folds [absorbed]'s memberships into [survivor]'s, all re-keyed to
 * [survivorId]. On a list both were on, one row remains with:
 *  - the earlier `addedAt`, because the relationship started then; and
 *  - the later `nextDueAt` (with its `skipCount`), because a call logged
 *    against either row pushed it out. Taking the earlier one would surface
 *    someone the user just called. A null due date means "due now", so it
 *    counts as the earliest.
 */
internal fun mergeMemberships(
    survivorId: Long,
    survivor: List<ListMembershipEntity>,
    absorbed: List<ListMembershipEntity>
): List<ListMembershipEntity> {
    val byList = survivor.associateByTo(LinkedHashMap()) { it.listId }
    for (other in absorbed) {
        val mine = byList[other.listId]
        byList[other.listId] = if (mine == null) {
            other
        } else {
            val later = if (other.nextDueAt.orEarliest() > mine.nextDueAt.orEarliest()) other else mine
            mine.copy(
                addedAt = minOf(mine.addedAt, other.addedAt),
                nextDueAt = later.nextDueAt,
                skipCount = later.skipCount
            )
        }
    }
    return byList.values.map { it.copy(contactId = survivorId) }
}

private fun Instant?.orEarliest(): Instant = this ?: Instant.MIN
