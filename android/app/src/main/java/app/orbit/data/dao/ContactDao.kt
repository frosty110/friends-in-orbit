package app.orbit.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import app.orbit.data.entity.CallEventEntity
import app.orbit.data.entity.ContactEntity
import app.orbit.data.entity.ContactPhoneEntity
import app.orbit.data.entity.ListMembershipEntity
import java.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * Asymmetry with other DAOs: ContactDao is an `abstract class`, not an `interface`.
 *
 * Rationale: Room 2.7.2 cannot express multi-table writes (e.g. "insert a CallEventEntity AND
 * update the related ContactEntity row") via a single annotated method. The supported pattern
 * is a `@Transaction`-annotated default-body method on an `abstract class` @Dao that composes
 * two `abstract` helper methods — each annotated individually with @Insert / @Update. Default-
 * body methods are only available on `abstract class`, not on `interface`, in Room 2.x KSP.
 *
 * Since two of the three locked multi-table writes touch `contacts` + one other table, they
 * live here. The other five DAOs stay plain interfaces (see ListMembershipDao, CallEventDao,
 * NoteDao, RuleTemplateDao, ListDao — ListDao uses `@Transaction @Query` which IS interface-
 * compatible, so ListDao stays an interface).
 */
@Dao
abstract class ContactDao {

    @Query("SELECT * FROM contacts ORDER BY displayName COLLATE NOCASE ASC")
    abstract fun observeAll(): Flow<List<ContactEntity>>

    /**
     * List-scoped contact observer used by [SurfaceNextUseCase] to replace
     * `contactRepo.observeAll()` in the Card View pipeline. Joins through
     * `list_memberships` so the Flow only emits when a contact ON the focused
     * list changes, eliminating app-wide-write invalidation of the combine.
     *
     * INNER JOIN does not duplicate rows — `list_memberships` has a composite PK
     * on `(contactId, listId)`, so each contact appears at most once per list.
     * Rows are unordered here (the use case applies its own `nextDueAt ASC`,
     * `lastCalledAt ASC`, `id ASC` sort downstream).
     */
    @Query(
        "SELECT c.* FROM contacts c " +
            "INNER JOIN list_memberships lm ON lm.contactId = c.id " +
            "WHERE lm.listId = :listId",
    )
    abstract fun observeForListMembers(listId: Long): Flow<List<ContactEntity>>

    /**
     * Push the SmartListEngine NeverCalled predicate into SQL. LEFT JOIN
     * call_events grouped by contact, HAVING zero matched rows. The
     * GROUP BY + HAVING leverages the existing `(contactId, occurredAt)` index
     * on `call_events`.
     *
     * Note: this query does NOT pre-filter `isIgnored` or `isArchived` —
     * SmartListEngine applies the `!isIgnored` filter post-DAO (SMART-05) so
     * the SQL output matches the legacy in-memory predicate exactly. If a
     * future change promotes `isIgnored` to a DAO-level filter, update both
     * paths together.
     */
    @Query(
        "SELECT c.* FROM contacts c " +
            "LEFT JOIN call_events ce ON ce.contactId = c.id " +
            "GROUP BY c.id " +
            "HAVING COUNT(ce.id) = 0",
    )
    abstract fun observeNeverCalled(): Flow<List<ContactEntity>>

    /** Suspend snapshot variant of [observeNeverCalled] for one-shot reads. */
    @Query(
        "SELECT c.* FROM contacts c " +
            "LEFT JOIN call_events ce ON ce.contactId = c.id " +
            "GROUP BY c.id " +
            "HAVING COUNT(ce.id) = 0",
    )
    abstract suspend fun snapshotNeverCalled(): List<ContactEntity>

    /**
     * Suspend snapshot variant of [observeAll]. Used by callers that need a
     * one-shot read inside a `withTransaction` block where collecting a Flow
     * via `.first()` works in practice but relies on Room's internal query-
     * executor scheduling. The documented Room idiom is a suspend snapshot
     * method; this is its declaration.
     */
    @Query("SELECT * FROM contacts ORDER BY displayName COLLATE NOCASE ASC")
    abstract suspend fun getAllOnce(): List<ContactEntity>

    @Query("SELECT * FROM contacts WHERE id = :id")
    abstract fun observeById(id: Long): Flow<ContactEntity?>

    @Query("SELECT * FROM contacts WHERE id = :id")
    abstract suspend fun get(id: Long): ContactEntity?

    @Query("SELECT * FROM contacts WHERE phoneNumber = :phoneNumber LIMIT 1")
    abstract suspend fun getByPhoneNumber(phoneNumber: String): ContactEntity?

    @Query("SELECT * FROM contacts WHERE normalizedPhone = :normalizedPhone LIMIT 1")
    abstract suspend fun getByNormalizedPhone(normalizedPhone: String): ContactEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insert(contact: ContactEntity): Long

    /**
     * Bulk insert path used by ingest. IGNORE on conflict so a race that
     * sneaks past the in-tx dedup pre-check (the unique index on
     * `normalizedPhone` would otherwise abort the whole transaction)
     * silently drops the duplicate row instead.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertAll(contacts: List<ContactEntity>): List<Long>

    @Update
    abstract suspend fun update(contact: ContactEntity): Int

    /**
     * Single-column atomic update for `pausedUntil`. Prefer this over the
     * full-row `update(contact)` path in repositories that only need to flip
     * one column — avoids clobbering concurrent writes to other columns
     * (e.g., a future CallLogSyncWorker touch to `isOrphaned`).
     */
    @Query("UPDATE contacts SET pausedUntil = :until WHERE id = :id")
    abstract suspend fun setPausedUntil(id: Long, until: Instant?): Int

    /**
     * Batch helpers — Move/Copy/Ignore/Pause use cases (MOVE-07, BulkIgnore, BulkPause).
     *
     * `getIgnoredFlags` / `getPausedUntilSnapshot` return projections (NOT full ContactEntity rows)
     * because the bulk use cases only need the pre-state of one column to build their inverse undo
     * lambdas. The snapshot data classes ([IgnoredSnapshot], [PausedUntilSnapshot]) live in
     * `data/dao/` and Room maps columns to their `val` properties by name.
     *
     * `setIgnoredBatch` / `setPausedUntilBatch` return the row count for caller-side parity
     * checks — caller can verify `rowsAffected == ids.size` to detect a contact deleted between
     * snapshot read and batch update.
     */
    @Query("SELECT id, isIgnored FROM contacts WHERE id IN (:ids)")
    abstract suspend fun getIgnoredFlags(ids: List<Long>): List<IgnoredSnapshot>

    @Query("UPDATE contacts SET isIgnored = :ignored WHERE id IN (:ids)")
    abstract suspend fun setIgnoredBatch(ids: List<Long>, ignored: Boolean): Int

    @Query("UPDATE contacts SET pausedUntil = :until WHERE id IN (:ids)")
    abstract suspend fun setPausedUntilBatch(ids: List<Long>, until: Instant?): Int

    @Query("SELECT id, pausedUntil FROM contacts WHERE id IN (:ids)")
    abstract suspend fun getPausedUntilSnapshot(ids: List<Long>): List<PausedUntilSnapshot>

    @Delete
    abstract suspend fun delete(contact: ContactEntity): Int

    /**
     * Call-log ingest path. Transactional get-by-normalizedPhone → update | insert.
     *
     * Status: currently has no production call sites — the contacts delta-sync
     * uses [refreshMirrorFields] (in-memory match, no per-row DAO
     * round-trip) and the call-log reconciler skips unmatched numbers. Kept as
     * the canonical single-row upsert for a future "add unknown caller" path;
     * the REPLACE rationale below stays load-bearing for any such caller.
     *
     * Deliberately NOT `OnConflictStrategy.REPLACE`: REPLACE on a row whose unique
     * `normalizedPhone` index fires would CASCADE-delete every related
     * `list_memberships`, `call_events`, and `notes` row (their FKs to
     * `contacts.id` are `CASCADE`). The get→update path keeps the existing
     * primary key — and therefore every FK pointing at it — stable.
     */
    @Transaction
    open suspend fun upsertByPhoneHash(contact: ContactEntity): Long {
        val existing = getByNormalizedPhone(contact.normalizedPhone)
        return if (existing == null) {
            insert(contact)
        } else {
            // Refresh display-side fields only. Do NOT overwrite the PK or the
            // ignore/archive/pause flags — those are owned by other use cases
            // and clobbering them would silently un-ignore / un-archive a contact
            // every time call-log sync hit a row with the same phone.
            update(
                existing.copy(
                    phoneNumber = contact.phoneNumber,
                    displayName = contact.displayName,
                    photoUri = contact.photoUri,
                    phoneContactId = contact.phoneContactId ?: existing.phoneContactId,
                ),
            )
            existing.id
        }
    }

    /**
     * Multi-table write — inserts a ListMembershipEntity row keyed to an existing contact.
     * @Transaction ensures observers see both writes atomically and the subsequent
     * invalidation emits exactly once per observe()-subscribed Flow.
     *
     * Room generates a single-table INSERT for this method but the @Transaction annotation
     * bundles the @Dao's internal consistency check (FK validation against `contacts`) inside
     * one SQLite transaction boundary.
     *
     * Strategy: IGNORE on conflict — aligned with [ListMembershipDao.insertAll] so single-add
     * and bulk-add share the same idempotent semantics (H1 fix). Re-adding a contact already
     * on the list is a no-op rather than a thrown SQLiteConstraintException.
     */
    @Transaction
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun addContactToList(membership: ListMembershipEntity): Long

    /**
     * Helper used by `insertCallEventAndTouchContact`. Not intended as a public API —
     * callers should prefer the transactional method above. Kept `abstract` because
     * Room needs the annotation binding; cannot be made `protected` on an @Dao class.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertCallEvent(event: CallEventEntity): Long

    /**
     * Multi-table write — inserts a CallEventEntity and updates the given ContactEntity row
     * (call-log ingest sets `firstSeenByAppAt` on first call-event observation). The
     * caller performs the read-modify-resolve of the contact row; this method wraps the two
     * writes in a single SQLite transaction via the @Transaction default-impl pattern.
     *
     * Shape: @Transaction + open default body on `abstract class`. Room's KSP generates the
     * transaction wiring around the compiled method body; the body composes `insertCallEvent`
     * + `update` (both abstract above).
     *
     * Lives on ContactDao (not CallEventDao) because the second write targets ContactEntity —
     * which is ContactDao's table. Keeps table-to-DAO ownership clean.
     */
    @Transaction
    open suspend fun insertCallEventAndTouchContact(
        event: CallEventEntity,
        contact: ContactEntity,
    ): Long {
        val rowid = insertCallEvent(event)
        update(contact)
        return rowid
    }

    // ─── Notes / Ignore / Override surface ──────────────────────────────────

    /** IGNORE-06 — ignored contacts sorted by ignoredAt DESC for SettingsIgnoredScreen. */
    @Query("SELECT * FROM contacts WHERE isIgnored = 1 ORDER BY ignoredAt DESC")
    abstract fun observeIgnored(): Flow<List<ContactEntity>>

    /**
     * IGNORE-02 single-contact ignore. Atomic three-column write so the snapshot,
     * timestamp, and flag flip land in one statement — no read-modify-write race
     * with concurrent writers to other columns (matches the rationale on
     * [setPausedUntil]). Pass nulls + `isIgnored = false` to clear (un-ignore path).
     */
    @Query(
        "UPDATE contacts " +
            "SET isIgnored = :isIgnored, " +
            "    ignoredAt = :ignoredAt, " +
            "    preIgnoreListMembershipsJson = :preIgnoreListMembershipsJson " +
            "WHERE id = :id",
    )
    abstract suspend fun markIgnored(
        id: Long,
        isIgnored: Boolean,
        ignoredAt: Instant?,
        preIgnoreListMembershipsJson: String?,
    ): Int

    /** Projection for un-ignore restore — read snapshot without loading the full row. */
    @Query("SELECT id, preIgnoreListMembershipsJson FROM contacts WHERE id = :id")
    abstract suspend fun getPreIgnoreSnapshot(id: Long): PreIgnoreSnapshot?

    /**
     * CONTACT-03 per-contact rule override setter. Atomic single-column write
     * to `ruleOverrideJson` — mirrors the [setPausedUntil] pattern. Pass null
     * to clear the override (resets contact to inherit primary list's template).
     *
     * Stores RAW `RuleParams` JSON (no envelope) —
     * [app.orbit.domain.rule.OverrideResolver] decodes the column directly via
     * `decodeFromString<RuleParams>(json)` using sealed-class
     * `classDiscriminator = "type"`. Forward-compat via
     * [app.orbit.domain.JsonProvider.json] `ignoreUnknownKeys = true`.
     */
    @Query("UPDATE contacts SET ruleOverrideJson = :json WHERE id = :id")
    abstract suspend fun setRuleOverrideJson(id: Long, json: String?): Int

    /**
     * CONTACT-06 archive flag setter. Atomic single-column write to
     * `isArchived`. Mirrors the [setPausedUntil] pattern. This method touches
     * ONLY the archive flag — it does NOT delete ListMembership rows (those are
     * preserved so unarchive in v1.1 can restore visibility cleanly) and does
     * NOT modify `isIgnored` (different mental model — the archive flag and the
     * ignore flag are deliberately kept separate).
     */
    @Query("UPDATE contacts SET isArchived = :archived WHERE id = :id")
    abstract suspend fun setArchived(id: Long, archived: Boolean): Int

    // ─── Delta-sync ingest + multi-number surface ───────────────────────────

    /**
     * Atomic mirror-field refresh used by the ingest delta-sync when a device
     * contact's display data changed (rename, new photo, starred flip, re-keyed
     * device id) or the contact returned after being orphaned. Clears
     * `isOrphaned` in the same statement: a matched device row is by definition
     * not orphaned. Does NOT touch `phoneNumber` / `normalizedPhone` (the
     * identity key) or any user-owned flag (ignore/archive/pause). `isStarred`
     * is device-owned like displayName.
     */
    @Query(
        "UPDATE contacts " +
            "SET displayName = :displayName, " +
            "    photoUri = :photoUri, " +
            "    phoneContactId = :phoneContactId, " +
            "    isStarred = :isStarred, " +
            "    isOrphaned = 0 " +
            "WHERE id = :id",
    )
    abstract suspend fun refreshMirrorFields(
        id: Long,
        displayName: String,
        photoUri: String?,
        phoneContactId: Long,
        isStarred: Boolean,
    ): Int

    /**
     * Batch orphan-flag write. Ingest sets `orphaned = true` for
     * mirrored contacts whose device row vanished; the return path (device row
     * reappears) is handled by [refreshMirrorFields]. Rows are never deleted —
     * call history and notes must survive an address-book deletion.
     */
    @Query("UPDATE contacts SET isOrphaned = :orphaned WHERE id IN (:ids)")
    abstract suspend fun setOrphanedBatch(ids: List<Long>, orphaned: Boolean): Int

    /**
     * One-shot snapshot of every `contact_phones` row. Consumed
     * by ingest (delta-sync set comparison) and by [app.orbit.calllog.CallLogReconciler]
     * (builds the `normalized → contact` match index covering ALL numbers, not
     * just the primary).
     */
    @Query("SELECT * FROM contact_phones")
    abstract suspend fun getAllPhonesOnce(): List<ContactPhoneEntity>

    /**
     * IGNORE on conflict: the unique `normalizedPhone` index resolves
     * cross-contact duplicate numbers first-wins, matching the reconciler's
     * collision semantics. Returns rowids (-1 for ignored rows).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertPhones(phones: List<ContactPhoneEntity>): List<Long>

    /**
     * Clears a contact's phone set ahead of a re-insert when the device set
     * diverged from the stored set. Phone rows carry no user data, so the
     * delete-and-reinsert churn is safe (nothing references `contact_phones.id`).
     */
    @Query("DELETE FROM contact_phones WHERE contactId = :contactId")
    abstract suspend fun deletePhonesForContact(contactId: Long): Int
}
