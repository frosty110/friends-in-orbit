package app.orbit.data.entity

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

@Immutable
@Entity(
    tableName = "contacts",
    indices = [
        // Unique on normalizedPhone — single source of truth for "same person" so
        // re-ingesting the same human (different formatting on subsequent device
        // reads) cannot create duplicate rows. The unique constraint is enforced
        // post-dedup in MIGRATION_3_4.
        Index(value = ["normalizedPhone"], unique = true),
        // Non-unique on phoneNumber — display-side queries (e.g. legacy
        // ContactDao.getByPhoneNumber) still hit this column, so keep it indexed.
        Index(value = ["phoneNumber"]),
        // Eliminates full-table scan on Settings → Ignored Contacts
        // (WHERE isIgnored = 1 ORDER BY ignoredAt DESC). Composite, non-unique.
        Index(value = ["isIgnored", "ignoredAt"]),
    ],
)
data class ContactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val phoneContactId: Long? = null,
    val phoneNumber: String,
    // E.164-or-fallback string used as the dedup/match key. Required at construction:
    // every caller already has access to a normalized form (PhoneContact.normalizedPhone
    // from ContactsReader, or PhoneNumberNormalizer.normalize from the call-log path).
    val normalizedPhone: String,
    val displayName: String,
    val photoUri: String? = null,
    // Schema v=11 — mirrors ContactsContract STARRED (Android favorites:
    // hand-curated closest people). Device-owned like displayName /
    // photoUri: written on insert and refreshed by the ingest delta-sync,
    // never edited in-app. Surfaces as the picker's "Starred" filter chip and
    // floats starred contacts first inside the Unsorted triage view.
    val isStarred: Boolean = false,
    val firstSeenByAppAt: Instant,
    val isIgnored: Boolean = false,
    val isOrphaned: Boolean = false,
    val pausedUntil: Instant? = null,
    val ruleOverrideJson: String? = null,
    // IGNORE-06 sort order. Set when IgnoreContactUseCase fires; cleared by
    // UnignoreContactUseCase. Null = never ignored (or already restored).
    val ignoredAt: Instant? = null,
    // Pre-ignore membership snapshot (List<Long> of listIds, kotlinx-serialization
    // JSON envelope `PreIgnoreMembershipsSnapshot`). Restored by UnignoreContactUseCase
    // to recover memberships that drifted away during the ignore window (e.g., list
    // archived/deleted while ignored).
    val preIgnoreListMembershipsJson: String? = null,
    // CONTACT-06 archive flag. Set ONLY by ArchiveContactUseCase. Independent of
    // `isIgnored`: archive is a hide mechanism for orphaned-and-unwanted
    // contacts; ignore is a "do not surface" decision the user can reverse from
    // the Ignored screen. Archive does NOT delete ListMembership rows —
    // SurfaceNextUseCase filters `AND NOT isArchived`. SettingsIgnoredScreen
    // filters `WHERE isArchived = 0 AND isIgnored = 1` so archived contacts do
    // NOT pollute the ignored view (different mental model).
    val isArchived: Boolean = false,
)
