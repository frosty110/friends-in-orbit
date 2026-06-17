package app.orbit.domain.export

import kotlinx.serialization.Serializable

/**
 * EXPORT-02 — versioned, portable export contract.
 *
 * Excludes the SQLCipher passphrase and the Keystore wrapper key. The exported
 * JSON contains application data only — a future device can re-encrypt under
 * its own Keystore at import time.
 *
 * `version = 1` is the v0.x baseline. `version = 2` adds the
 * `contactPhones` block mirroring the `contact_phones` table (migration
 * 9→10) so multi-number reconciliation survives a restore. The field
 * defaults to empty, so v1 envelopes decode unchanged; [ImportService]
 * re-derives primary phone rows when the block is absent and the
 * reconciler self-heals secondary numbers on the next ingest. Bump
 * [CURRENT_VERSION] on any future field change; additive defaulted fields
 * preserve back-compat (paired with
 * `JsonProvider.json { ignoreUnknownKeys = true }`).
 *
 * Each entity-mirror record holds only the fields useful to a downstream
 * import (no derived/computed columns — e.g. `dueCount`, `isOrphaned`,
 * `phoneContactId`, `firstSeenByAppAt` are recomputed at import time, or
 * are intentionally not portable across devices).
 *
 * Field shapes verified against entity definitions at HEAD:
 *   - ListEntity:        id, name, sortOrder, isArchived, type,
 *                        smartRuleJson, ruleTemplateId, activeHoursStart,
 *                        activeHoursEnd, notificationsEnabled,
 *                        ruleParamsOverrideJson, dueCount.
 *   - ContactEntity:     id, phoneContactId, phoneNumber, normalizedPhone,
 *                        displayName, photoUri, firstSeenByAppAt, isIgnored,
 *                        isOrphaned, pausedUntil, ruleOverrideJson,
 *                        ignoredAt, preIgnoreListMembershipsJson, isArchived,
 *                        isStarred (device mirror, intentionally
 *                        NOT exported; re-derived by the next contacts ingest
 *                        like phoneContactId / isOrphaned).
 *   - ListMembershipEntity: contactId, listId, addedAt, nextDueAt, skipCount.
 *   - CallEventEntity:   id, contactId, occurredAt, direction, durationSeconds, source.
 *   - NoteEntity:        id, contactId, createdAt, body.
 *   - RuleTemplateEntity: id, name, kind, paramsJson.
 */
@Serializable
data class ExportEnvelope(
    val version: Int = CURRENT_VERSION,
    val createdAt: Long,                          // epoch millis
    val lists: List<ListExport>,
    val contacts: List<ContactExport>,
    val memberships: List<MembershipExport>,
    val callEvents: List<CallEventExport>,
    val notes: List<NoteExport>,
    val ruleTemplates: List<RuleTemplateExport>,
    // Envelope v2 — every contact_phones row. Defaulted so v1
    // envelopes (which predate the table's inclusion) still decode.
    val contactPhones: List<ContactPhoneExport> = emptyList(),
) {
    companion object {
        /**
         * Highest envelope version this build can WRITE and READ.
         * [app.orbit.domain.export.ImportService] refuses anything newer.
         */
        const val CURRENT_VERSION: Int = 2
    }
}

@Serializable
data class ListExport(
    val id: Long,
    val name: String,
    val sortOrder: Int,
    val isArchived: Boolean,
    val type: String,                             // ListType.name (STATIC | SMART)
    val smartRuleJson: String? = null,
    val ruleTemplateId: Long? = null,
    val activeHoursStartSecondOfDay: Int? = null, // LocalTime.toSecondOfDay()
    val activeHoursEndSecondOfDay: Int? = null,   // LocalTime.toSecondOfDay()
    val notificationsEnabled: Boolean,
    val ruleParamsOverrideJson: String? = null,
)

@Serializable
data class ContactExport(
    val id: Long,
    val phoneNumber: String,                      // raw; importer renormalizes
    val normalizedPhone: String,
    val displayName: String,
    val photoUri: String? = null,
    val isIgnored: Boolean,
    val ignoredAt: Long? = null,
    val isArchived: Boolean,
    val pausedUntil: Long? = null,
    val ruleOverrideJson: String? = null,
    val preIgnoreListMembershipsJson: String? = null,
)

@Serializable
data class MembershipExport(
    val contactId: Long,
    val listId: Long,
    val addedAt: Long,
    val nextDueAt: Long? = null,
    val skipCount: Int,
)

@Serializable
data class CallEventExport(
    val id: Long,
    val contactId: Long,
    val occurredAt: Long,
    val direction: String,                        // CallDirection.name
    val durationSeconds: Int,
    val source: String,                           // CallSource.name
)

@Serializable
data class NoteExport(
    val id: Long,
    val contactId: Long,
    val createdAt: Long,
    val body: String,
)

/**
 * Envelope v2 — mirror of [app.orbit.data.entity.ContactPhoneEntity].
 * `id` is intentionally dropped: nothing references `contact_phones.id`, and
 * letting Room re-generate it on import avoids autoincrement collisions.
 */
@Serializable
data class ContactPhoneExport(
    val contactId: Long,
    val phoneNumber: String,
    val normalizedPhone: String,
    val isPrimary: Boolean = false,
)

@Serializable
data class RuleTemplateExport(
    val id: Long,
    val name: String,
    val kind: String,                             // RuleKind.name
    val paramsJson: String,
)
