package app.orbit.domain.export

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import app.orbit.data.db.OrbitDatabase
import app.orbit.data.entity.CallDirection
import app.orbit.data.entity.CallEventEntity
import app.orbit.data.entity.CallSource
import app.orbit.data.entity.ContactEntity
import app.orbit.data.entity.ContactPhoneEntity
import app.orbit.data.entity.ListEntity
import app.orbit.data.entity.ListMembershipEntity
import app.orbit.data.entity.ListType
import app.orbit.data.entity.NoteEntity
import app.orbit.data.entity.RuleKind
import app.orbit.data.entity.RuleTemplateEntity
import app.orbit.domain.JsonProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The other half of [ExportService]. Reads an encrypted `.bin` produced by
 * export, validates it, and restores it with
 * REPLACE semantics (wipe-and-restore) — the simplest honest contract for
 * v1: after a successful import the device holds exactly the backup,
 * nothing merged, nothing half-applied.
 *
 * Two-stage API so the UI can validate BEFORE showing the destructive
 * confirmation dialog (a wrong passphrase should never get as far as
 * "replace everything?"):
 *
 *  1. [read] — SAF Uri + passphrase → decrypt ([PassphraseEncryptor.decrypt],
 *     same PBKDF2/AES-GCM format the export writes) → JSON-decode
 *     [ExportEnvelope] → map to Room entities. Throws
 *     [ImportVersionTooNewException] when the envelope was written by a
 *     newer app, [ImportFormatException] for everything else (wrong
 *     passphrase, truncated file, not an Orbit backup, malformed JSON).
 *  2. [apply] — one Room transaction: DELETE every table child-first, then
 *     re-insert the payload parent-first with the exported primary keys, so
 *     FK relationships survive verbatim. Room Flows invalidate when the
 *     transaction commits — every screen refreshes by itself.
 *
 * v1 envelopes (no `contactPhones` block) restore with primary phone rows
 * re-derived from each contact (mirrors MIGRATION_9_10's backfill);
 * secondary numbers self-heal on the next contacts ingest.
 *
 * Layering note: this service injects [OrbitDatabase] directly (same
 * precedent as [app.orbit.data.repository.ResetService]) because the
 * repository interfaces deliberately expose no bulk wipe/insert surface —
 * widening them for a once-a-lifetime restore would put footguns on the
 * everyday path.
 */
@Singleton
open class ImportService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: OrbitDatabase,
) {

    /**
     * Stage 1 — decrypt + validate + map. Does NOT touch the database.
     *
     * @param passphrase caller wipes the array after this returns; this
     *                    method does not cache it.
     */
    open suspend fun read(uri: Uri, passphrase: CharArray): ImportPayload =
        withContext(Dispatchers.IO) {
            val ciphertext = context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "openInputStream returned null for $uri" }
                input.readBytes()
            }
            decode(ciphertext, passphrase)
        }

    /**
     * Stage 2 — REPLACE-apply the validated payload. One transaction:
     * either the whole backup lands or nothing changes.
     */
    open suspend fun apply(payload: ImportPayload): ImportSummary {
        database.withTransaction {
            // Wipe child tables before parents so FK constraints never trip.
            // Raw DELETEs on the transaction connection — the repositories
            // expose no wipe surface (by design), and clearAllTables() cannot
            // join a transaction with the inserts below.
            val db = database.openHelper.writableDatabase
            db.execSQL("DELETE FROM notes")
            db.execSQL("DELETE FROM call_events")
            db.execSQL("DELETE FROM list_memberships")
            db.execSQL("DELETE FROM contact_phones")
            db.execSQL("DELETE FROM lists")
            db.execSQL("DELETE FROM contacts")
            db.execSQL("DELETE FROM rule_templates")

            // Re-insert parent-first with exported primary keys intact.
            payload.ruleTemplates.forEach { database.ruleTemplateDao().insert(it) }
            database.contactDao().insertAll(payload.contacts)
            payload.lists.forEach { database.listDao().insert(it) }
            database.contactDao().insertPhones(payload.contactPhones)
            database.listMembershipDao().insertAll(payload.memberships)
            payload.callEvents.forEach { database.callEventDao().insert(it) }
            payload.notes.forEach { database.noteDao().insert(it) }
        }
        return ImportSummary(
            listCount = payload.lists.size,
            contactCount = payload.contacts.size,
            callEventCount = payload.callEvents.size,
            noteCount = payload.notes.size,
        )
    }

    companion object {

        /**
         * Pure decode pipeline, extracted for JVM round-trip tests.
         * Ciphertext + passphrase → validated [ImportPayload].
         */
        internal suspend fun decode(ciphertext: ByteArray, passphrase: CharArray): ImportPayload {
            val plaintext = try {
                PassphraseEncryptor.decrypt(ciphertext, passphrase)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Wrong passphrase (AEADBadTagException), truncated stream,
                // magic/format mismatch — all collapse to "not readable".
                throw ImportFormatException("backup could not be decrypted", e)
            }
            val envelope = try {
                JsonProvider.json.decodeFromString(
                    ExportEnvelope.serializer(),
                    plaintext.decodeToString(),
                )
            } catch (e: Exception) {
                throw ImportFormatException("decrypted payload is not an Orbit envelope", e)
            }
            if (envelope.version > ExportEnvelope.CURRENT_VERSION) {
                throw ImportVersionTooNewException(
                    found = envelope.version,
                    supported = ExportEnvelope.CURRENT_VERSION,
                )
            }
            return try {
                envelope.toPayload(now = Instant.now())
            } catch (e: ImportVersionTooNewException) {
                throw e
            } catch (e: Exception) {
                // Unknown enum name, malformed time value, etc. — within a
                // supported version this means a corrupted file.
                throw ImportFormatException("envelope payload failed validation", e)
            }
        }
    }
}

/** Validated, entity-mapped contents of a backup — ready for [ImportService.apply]. */
data class ImportPayload(
    val envelopeVersion: Int,
    val ruleTemplates: List<RuleTemplateEntity>,
    val contacts: List<ContactEntity>,
    val lists: List<ListEntity>,
    val contactPhones: List<ContactPhoneEntity>,
    val memberships: List<ListMembershipEntity>,
    val callEvents: List<CallEventEntity>,
    val notes: List<NoteEntity>,
)

data class ImportSummary(
    val listCount: Int,
    val contactCount: Int,
    val callEventCount: Int,
    val noteCount: Int,
)

/**
 * The file is not a readable Orbit backup: wrong passphrase, truncated or
 * corrupted bytes, or not an Orbit export at all. UI copy:
 * "That file couldn't be read. Check it's an Orbit backup."
 */
class ImportFormatException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/** The envelope was written by a newer Orbit than this build understands. */
class ImportVersionTooNewException(val found: Int, val supported: Int) :
    RuntimeException("envelope version $found > supported $supported")

// ─── Export-to-entity adapters ───────────────────────────────────────────────
//
// Inverse of ExportService's entity-to-export adapters. Fields the export
// intentionally drops are recomputed here:
//   - ContactEntity.phoneContactId / isOrphaned: device-specific; the next
//     contacts ingest re-links (orphan detection included).
//   - ContactEntity.firstSeenByAppAt: not portable; set to import time.
//   - ListEntity.dueCount: denormalized (ADR 0006 Rule 2); recomputed from
//     the envelope's memberships with the same predicate as MIGRATION_8_9.
//   - contact_phones for v1 envelopes: primary rows re-derived from contacts
//     (mirrors MIGRATION_9_10's backfill); skips empty normalized keys which
//     would collide under the unique index.

internal fun ExportEnvelope.toPayload(now: Instant): ImportPayload {
    val dueCounts: Map<Long, Int> = memberships
        .groupBy { it.listId }
        .mapValues { (_, rows) ->
            rows.count { it.nextDueAt == null || it.nextDueAt <= now.toEpochMilli() }
        }
    val phones: List<ContactPhoneEntity> =
        if (contactPhones.isNotEmpty()) {
            contactPhones.map { it.toEntity() }
        } else {
            contacts
                .filter { it.normalizedPhone.isNotEmpty() }
                .map { c ->
                    ContactPhoneEntity(
                        contactId = c.id,
                        phoneNumber = c.phoneNumber,
                        normalizedPhone = c.normalizedPhone,
                        isPrimary = true,
                    )
                }
        }
    return ImportPayload(
        envelopeVersion = version,
        ruleTemplates = ruleTemplates.map { it.toEntity() },
        contacts = contacts.map { it.toEntity(firstSeenAt = now) },
        lists = lists.map { it.toEntity(dueCount = dueCounts[it.id] ?: 0) },
        contactPhones = phones,
        memberships = memberships.map { it.toEntity() },
        callEvents = callEvents.map { it.toEntity() },
        notes = notes.map { it.toEntity() },
    )
}

private fun ListExport.toEntity(dueCount: Int): ListEntity = ListEntity(
    id = id,
    name = name,
    sortOrder = sortOrder,
    isArchived = isArchived,
    type = ListType.valueOf(type),
    smartRuleJson = smartRuleJson,
    ruleTemplateId = ruleTemplateId,
    activeHoursStart = activeHoursStartSecondOfDay?.let { LocalTime.ofSecondOfDay(it.toLong()) },
    activeHoursEnd = activeHoursEndSecondOfDay?.let { LocalTime.ofSecondOfDay(it.toLong()) },
    notificationsEnabled = notificationsEnabled,
    ruleParamsOverrideJson = ruleParamsOverrideJson,
    dueCount = dueCount,
)

private fun ContactExport.toEntity(firstSeenAt: Instant): ContactEntity = ContactEntity(
    id = id,
    phoneContactId = null,                       // re-linked by the next ingest
    phoneNumber = phoneNumber,
    normalizedPhone = normalizedPhone,
    displayName = displayName,
    photoUri = photoUri,
    firstSeenByAppAt = firstSeenAt,
    isIgnored = isIgnored,
    isOrphaned = false,                          // re-derived by the next ingest
    pausedUntil = pausedUntil?.let(Instant::ofEpochMilli),
    ruleOverrideJson = ruleOverrideJson,
    ignoredAt = ignoredAt?.let(Instant::ofEpochMilli),
    preIgnoreListMembershipsJson = preIgnoreListMembershipsJson,
    isArchived = isArchived,
)

private fun ContactPhoneExport.toEntity(): ContactPhoneEntity = ContactPhoneEntity(
    contactId = contactId,
    phoneNumber = phoneNumber,
    normalizedPhone = normalizedPhone,
    isPrimary = isPrimary,
)

private fun MembershipExport.toEntity(): ListMembershipEntity = ListMembershipEntity(
    contactId = contactId,
    listId = listId,
    addedAt = Instant.ofEpochMilli(addedAt),
    nextDueAt = nextDueAt?.let(Instant::ofEpochMilli),
    skipCount = skipCount,
)

private fun CallEventExport.toEntity(): CallEventEntity = CallEventEntity(
    id = id,
    contactId = contactId,
    occurredAt = Instant.ofEpochMilli(occurredAt),
    direction = CallDirection.valueOf(direction),
    durationSeconds = durationSeconds,
    source = CallSource.valueOf(source),
)

private fun NoteExport.toEntity(): NoteEntity = NoteEntity(
    id = id,
    contactId = contactId,
    createdAt = Instant.ofEpochMilli(createdAt),
    body = body,
)

private fun RuleTemplateExport.toEntity(): RuleTemplateEntity = RuleTemplateEntity(
    id = id,
    name = name,
    kind = RuleKind.valueOf(kind),
    paramsJson = paramsJson,
)
