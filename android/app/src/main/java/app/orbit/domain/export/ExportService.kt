package app.orbit.domain.export

import android.content.Context
import android.net.Uri
import app.orbit.data.entity.CallEventEntity
import app.orbit.data.entity.ContactEntity
import app.orbit.data.entity.ContactPhoneEntity
import app.orbit.data.entity.ListEntity
import app.orbit.data.entity.ListMembershipEntity
import app.orbit.data.entity.NoteEntity
import app.orbit.data.entity.RuleTemplateEntity
import app.orbit.data.repository.CallEventRepository
import app.orbit.data.repository.ContactRepository
import app.orbit.data.repository.ListRepository
import app.orbit.data.repository.NoteRepository
import app.orbit.data.repository.RuleTemplateRepository
import app.orbit.domain.JsonProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * EXPORT-01 — coordinates the encrypted-JSON export pipeline.
 *
 * 1. Snapshot every repository:
 *    - List + Membership + Contact: one-shot `.first()` reads on the existing
 *      `observe*()` Flows (these surfaces are stable and bounded).
 *    - CallEvent + Note + RuleTemplate: dedicated `snapshotAll()` reads.
 * 2. Build [ExportEnvelope] (EXPORT-02 — excludes SQLCipher passphrase
 *    and Keystore wrapper key by construction).
 * 3. Serialize to JSON via the existing [JsonProvider.json].
 * 4. Encrypt via [PassphraseEncryptor] (EXPORT-03).
 * 5. Write the resulting `ByteArray` to the SAF-provided [Uri] via
 *    `Context.contentResolver.openOutputStream(uri)`.
 *
 * Failure surface — every step throws on error; the caller (ExportViewModel)
 * wraps the call in `runCatching` and surfaces a snackbar per UI-SPEC
 * §"Empty / Loading / Error States" — "Couldn't save the file. Try again?".
 */
@Singleton
class ExportService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val listRepo: ListRepository,
    private val contactRepo: ContactRepository,
    private val callEventRepo: CallEventRepository,
    private val noteRepo: NoteRepository,
    private val ruleTemplateRepo: RuleTemplateRepository,
) {

    /**
     * @param uri SAF-provided output destination (from
     *            [android.content.Intent.ACTION_CREATE_DOCUMENT]).
     * @param passphrase user-supplied; this method does NOT cache it.
     *                   Caller MUST zero the array after this returns.
     * @return [ExportSummary] capturing how many lists / contacts shipped.
     */
    suspend fun export(uri: Uri, passphrase: CharArray): ExportSummary = withContext(Dispatchers.IO) {
        val envelope = buildEnvelope()
        val plaintext = JsonProvider.json
            .encodeToString(ExportEnvelope.serializer(), envelope)
            .encodeToByteArray()
        val ciphertext = PassphraseEncryptor.encrypt(plaintext, passphrase)
        context.contentResolver.openOutputStream(uri).use { os ->
            requireNotNull(os) { "openOutputStream returned null for $uri" }
            os.write(ciphertext)
            os.flush()
        }
        ExportSummary(
            listCount = envelope.lists.size,
            contactCount = envelope.contacts.size,
            callEventCount = envelope.callEvents.size,
            noteCount = envelope.notes.size,
        )
    }

    private suspend fun buildEnvelope(): ExportEnvelope {
        // List + Membership + Contact: one-shot Flow reads (these are stable
        // bounded surfaces; calling .first() once at export time is fine).
        val listEntities: List<ListEntity> = listRepo.observeAll().first()
        val contactEntities: List<ContactEntity> = contactRepo.observeAll().first()

        // For memberships, iterate per-list (the existing repo surface is
        // observeMembersOfList(listId)). Acceptable here — list count is
        // bounded; this is one-shot at export time.
        val membershipEntities: List<ListMembershipEntity> = listEntities.flatMap { l ->
            listRepo.observeMembersOfList(l.id).first()
        }

        // CallEvent + Note + RuleTemplate: dedicated snapshotAll() surfaces.
        // No `.first()` on a Flow — direct suspend reads.
        val callEventEntities: List<CallEventEntity> = callEventRepo.snapshotAll()
        val noteEntities: List<NoteEntity> = noteRepo.snapshotAll()
        val ruleTemplateEntities: List<RuleTemplateEntity> = ruleTemplateRepo.snapshotAll()

        // Envelope v2 — contact_phones rows (migration 9→10) ride along so
        // multi-number reconciliation survives a restore. The snapshot surface
        // already existed for CallLogReconciler.
        val contactPhoneEntities: List<ContactPhoneEntity> = contactRepo.snapshotAllPhones()

        return ExportEnvelope(
            version = ExportEnvelope.CURRENT_VERSION,
            createdAt = System.currentTimeMillis(),
            lists = listEntities.map { it.toExport() },
            contacts = contactEntities.map { it.toExport() },
            memberships = membershipEntities.map { it.toExport() },
            callEvents = callEventEntities.map { it.toExport() },
            notes = noteEntities.map { it.toExport() },
            ruleTemplates = ruleTemplateEntities.map { it.toExport() },
            contactPhones = contactPhoneEntities.map { it.toExport() },
        )
    }
}

data class ExportSummary(
    val listCount: Int,
    val contactCount: Int,
    val callEventCount: Int,
    val noteCount: Int,
)

// ─── Entity-to-export adapters ────────────────────────────────────────────────
//
// Field shapes verified against entity definitions at HEAD; if an entity has
// drifted, the executor MUST follow reality. See ExportEnvelope.kt for the
// per-entity field map documented in the KDoc.

private fun ListEntity.toExport(): ListExport = ListExport(
    id = id,
    name = name,
    sortOrder = sortOrder,
    isArchived = isArchived,
    type = type.name,
    smartRuleJson = smartRuleJson,
    ruleTemplateId = ruleTemplateId,
    activeHoursStartSecondOfDay = activeHoursStart?.toSecondOfDay(),
    activeHoursEndSecondOfDay = activeHoursEnd?.toSecondOfDay(),
    notificationsEnabled = notificationsEnabled,
    ruleParamsOverrideJson = ruleParamsOverrideJson,
)

private fun ContactEntity.toExport(): ContactExport = ContactExport(
    id = id,
    phoneNumber = phoneNumber,
    normalizedPhone = normalizedPhone,
    displayName = displayName,
    photoUri = photoUri,
    isIgnored = isIgnored,
    ignoredAt = ignoredAt?.toEpochMilli(),
    isArchived = isArchived,
    pausedUntil = pausedUntil?.toEpochMilli(),
    ruleOverrideJson = ruleOverrideJson,
    preIgnoreListMembershipsJson = preIgnoreListMembershipsJson,
)

private fun ListMembershipEntity.toExport(): MembershipExport = MembershipExport(
    contactId = contactId,
    listId = listId,
    addedAt = addedAt.toEpochMilli(),
    nextDueAt = nextDueAt?.toEpochMilli(),
    skipCount = skipCount,
)

private fun CallEventEntity.toExport(): CallEventExport = CallEventExport(
    id = id,
    contactId = contactId,
    occurredAt = occurredAt.toEpochMilli(),
    direction = direction.name,
    durationSeconds = durationSeconds,
    source = source.name,
)

private fun NoteEntity.toExport(): NoteExport = NoteExport(
    id = id,
    contactId = contactId,
    createdAt = createdAt.toEpochMilli(),
    body = body,
)

private fun ContactPhoneEntity.toExport(): ContactPhoneExport = ContactPhoneExport(
    contactId = contactId,
    phoneNumber = phoneNumber,
    normalizedPhone = normalizedPhone,
    isPrimary = isPrimary,
)

private fun RuleTemplateEntity.toExport(): RuleTemplateExport = RuleTemplateExport(
    id = id,
    name = name,
    kind = kind.name,
    paramsJson = paramsJson,
)
