package app.orbit.domain.usecase

import androidx.compose.runtime.Immutable
import app.orbit.data.android.ContactsReader
import app.orbit.data.android.PhoneContact
import app.orbit.data.android.PhoneNumberRow
import app.orbit.data.dao.ContactDao
import app.orbit.data.db.TransactionRunner
import app.orbit.data.entity.ContactEntity
import app.orbit.data.entity.ContactPhoneEntity
import app.orbit.domain.clock.Clock
import javax.inject.Inject

/**
 * Non-PII outcome counts for one ingest pass. Consumed by
 * [app.orbit.calllog.ContactsIngestWorker] for Timber logging (counts only —
 * PiiSanitizer-safe).
 */
@Immutable
data class IngestSummary(
    /** New ContactEntity rows created. */
    val inserted: Int,
    /** Existing rows whose displayName / photoUri / phoneContactId / isStarred were refreshed. */
    val refreshed: Int,
    /** Mirrored rows newly flagged `isOrphaned = true` (device row vanished). */
    val orphaned: Int,
    /** Previously orphaned rows whose device contact reappeared (flag cleared). */
    val restored: Int,
) {
    companion object {
        val EMPTY: IngestSummary = IngestSummary(0, 0, 0, 0)
    }
}

/**
 * Delta-syncs the device address book into Room.
 *
 * This was once insert-only (NEW normalizedPhones only), which made the
 * Room mirror write-once: renames never propagated, photos were never ingested
 * (`photoUri` was hardcoded null), and `isOrphaned` was read by ContactDetail /
 * ContactPicker but never written. One pass now does four things, all inside a
 * single [TransactionRunner.withTransaction]:
 *
 * 1. **Insert** device contacts with no matching Room row (match = ANY of the
 *    contact's normalized numbers hits `contact_phones` or the legacy primary
 *    `contacts.normalizedPhone`).
 * 2. **Refresh** matched rows whose displayName / photoUri / phoneContactId /
 *    isStarred drifted from the device — via
 *    [ContactDao.refreshMirrorFields], which also clears `isOrphaned`. The
 *    identity key (`phoneNumber` / `normalizedPhone`) and every user-owned
 *    flag (ignore/archive/pause/override) are untouched.
 * 3. **Sync the phone set**: `contact_phones` rows are replaced when the
 *    device number set or primary changed, so the call-log reconciler matches
 *    second-SIM / work-line calls.
 * 4. **Flag orphans**: mirrored rows (`phoneContactId != null`) with no device
 *    match get `isOrphaned = true`. Rows are NEVER deleted — call history and
 *    notes must survive an address-book deletion; the flag flips back via the
 *    refresh path if the contact returns.
 *
 * **Permission handling.** [ContactsReader.readAll] silently returns an empty
 * list if `READ_CONTACTS` is not granted (the system content resolver yields a
 * null cursor). An empty read returns [IngestSummary.EMPTY] WITHOUT orphaning
 * anything — "permission revoked" and "address book truly empty" are
 * indistinguishable here, and mass-orphaning on a revoke would be data loss in
 * spirit.
 *
 * **Deduplication.** Match keys are normalized phone strings (the unique
 * column from MIGRATION_3_4 plus the unique `contact_phones.normalizedPhone`
 * from MIGRATION_9_10). Device reads are deduped on the primary normalized
 * form; cross-contact secondary-number collisions resolve first-wins via
 * `OnConflictStrategy.IGNORE`.
 *
 * **Why call DAO directly, not Repository.** [app.orbit.data.repository.ContactRepository]
 * intentionally exposes a read-only surface for the v1 product. Keeping the
 * DAO injection localized here keeps the Repository read-only and the
 * write-on-ingest path explicit.
 *
 * `open` (class + invoke) so the worker's TTL tests can substitute a counting
 * stub — same seam pattern as [MarkCalledUseCase].
 */
open class IngestPhoneContactsUseCase @Inject constructor(
    private val contactsReader: ContactsReader,
    private val contactDao: ContactDao,
    private val txRunner: TransactionRunner,
    private val clock: Clock,
) {

    /**
     * @return per-outcome counts; [IngestSummary.EMPTY] if permission denied
     *         or no phone contacts on device.
     */
    open suspend operator fun invoke(): IngestSummary {
        val phoneContacts: List<PhoneContact> = contactsReader.readAll()
        if (phoneContacts.isEmpty()) return IngestSummary.EMPTY

        return txRunner.withTransaction {
            val existing = contactDao.getAllOnce()
            val existingById: Map<Long, ContactEntity> = existing.associateBy { it.id }
            val storedPhones = contactDao.getAllPhonesOnce()
            val storedPhonesByContact: Map<Long, List<ContactPhoneEntity>> =
                storedPhones.groupBy { it.contactId }

            // normalized → Room contact id. Phone rows first (they cover every
            // number); the contacts table's primary as a fallback for any row
            // that predates the contact_phones backfill.
            val idByNormalized = HashMap<String, Long>(storedPhones.size + existing.size)
            for (p in storedPhones) idByNormalized.putIfAbsent(p.normalizedPhone, p.contactId)
            for (c in existing) {
                if (c.normalizedPhone.isNotEmpty()) {
                    idByNormalized.putIfAbsent(c.normalizedPhone, c.id)
                }
            }

            val now = clock.now()
            var inserted = 0
            var refreshed = 0
            var restored = 0
            val matchedIds = mutableSetOf<Long>()

            val deviceContacts = phoneContacts
                .asSequence()
                // Two device rows that normalize to the same primary collapse
                // to one ContactEntity (pre-existing behavior).
                .distinctBy { it.normalizedPhone }
                .toList()

            for (pc in deviceContacts) {
                val numbers = pc.numbersOrPrimary()
                if (numbers.isEmpty()) continue

                val matchId = numbers.firstNotNullOfOrNull { idByNormalized[it.normalized] }
                if (matchId == null) {
                    // numbers.first() is the device primary unless the primary's
                    // match key was empty and got filtered — keep the pair
                    // coherent by taking BOTH display + key from the same row.
                    val newId = contactDao.insertAll(
                        listOf(
                            ContactEntity(
                                phoneContactId = pc.contactId,
                                phoneNumber = numbers.first().number,
                                normalizedPhone = numbers.first().normalized,
                                displayName = pc.displayName,
                                photoUri = pc.photoUri,
                                isStarred = pc.isStarred,
                                firstSeenByAppAt = now,
                            ),
                        ),
                    ).firstOrNull() ?: -1L
                    // -1 = unique-index race the pre-check missed; IGNORE
                    // dropped the row, nothing to attach phones to.
                    if (newId > 0L) {
                        contactDao.insertPhones(numbers.toPhoneEntities(newId))
                        numbers.forEach { idByNormalized.putIfAbsent(it.normalized, newId) }
                        inserted++
                    }
                } else {
                    matchedIds += matchId
                    val row = existingById[matchId] ?: continue

                    val needsRefresh = row.displayName != pc.displayName ||
                        row.photoUri != pc.photoUri ||
                        row.phoneContactId != pc.contactId ||
                        // Starred is device-owned; a favorite toggled on the
                        // device propagates like a rename.
                        row.isStarred != pc.isStarred ||
                        row.isOrphaned
                    if (needsRefresh) {
                        contactDao.refreshMirrorFields(
                            id = matchId,
                            displayName = pc.displayName,
                            photoUri = pc.photoUri,
                            phoneContactId = pc.contactId,
                            isStarred = pc.isStarred,
                        )
                        if (row.isOrphaned) restored++ else refreshed++
                    }

                    val stored = storedPhonesByContact[matchId].orEmpty()
                    if (phoneSetChanged(stored, numbers)) {
                        contactDao.deletePhonesForContact(matchId)
                        contactDao.insertPhones(numbers.toPhoneEntities(matchId))
                        numbers.forEach { idByNormalized.putIfAbsent(it.normalized, matchId) }
                    }
                }
            }

            // Orphan pass: mirrored contacts (device-sourced, phoneContactId
            // set) that no device contact matched this round. Call-log-only or
            // already-orphaned rows are left alone.
            val toOrphan = existing
                .filter { it.phoneContactId != null && !it.isOrphaned && it.id !in matchedIds }
                .map { it.id }
            if (toOrphan.isNotEmpty()) {
                contactDao.setOrphanedBatch(toOrphan, orphaned = true)
            }

            IngestSummary(
                inserted = inserted,
                refreshed = refreshed,
                orphaned = toOrphan.size,
                restored = restored,
            )
        }
    }

    /**
     * All numbers for a device contact, primary first, deduped on the
     * normalized form, empty match keys dropped. Falls back to the legacy
     * primary pair when [PhoneContact.phones] is empty (hand-built fixtures).
     */
    private fun PhoneContact.numbersOrPrimary(): List<PhoneNumberRow> =
        phones.ifEmpty { listOf(PhoneNumberRow(number = phone, normalized = normalizedPhone)) }
            .asSequence()
            .filter { it.normalized.isNotEmpty() }
            .distinctBy { it.normalized }
            .toList()

    /**
     * True when the stored phone set diverges from the device set — different
     * normalized membership OR a different primary number. Either triggers a
     * replace-all for the contact (phone rows carry no user data).
     */
    private fun phoneSetChanged(
        stored: List<ContactPhoneEntity>,
        device: List<PhoneNumberRow>,
    ): Boolean {
        val storedSet = stored.map { it.normalizedPhone }.toSet()
        val deviceSet = device.map { it.normalized }.toSet()
        if (storedSet != deviceSet) return true
        val storedPrimary = stored.firstOrNull { it.isPrimary }?.normalizedPhone
        return storedPrimary != device.first().normalized
    }

    private fun List<PhoneNumberRow>.toPhoneEntities(contactId: Long): List<ContactPhoneEntity> =
        mapIndexed { i, n ->
            ContactPhoneEntity(
                contactId = contactId,
                phoneNumber = n.number,
                normalizedPhone = n.normalized,
                isPrimary = i == 0,
            )
        }
}
