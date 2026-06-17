package app.orbit.data.entity

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per phone number a contact carries on the device.
 *
 * Why a separate table: `ContactEntity.phoneNumber` stores only the primary
 * (display) number, so calls placed to a contact's second number (work line,
 * second SIM) never matched in [app.orbit.calllog.CallLogReconciler] and the
 * engine kept surfacing someone the user had just called. This table holds
 * EVERY normalized number per contact; the reconciler builds its
 * `normalized → contact` lookup from it (still O(1) per row via the in-memory
 * map; the unique index keeps the table itself collision-free).
 *
 * Invariants:
 * - `normalizedPhone` is globally unique — a number maps to at most one
 *   contact, mirroring the unique `contacts.normalizedPhone` semantics.
 *   Cross-contact duplicates resolve first-wins at ingest (IGNORE on insert).
 * - Exactly one row per contact carries `isPrimary = true`; it mirrors
 *   `ContactEntity.phoneNumber` / `normalizedPhone` (the display number).
 * - Rows are owned by ingest ([app.orbit.domain.usecase.IngestPhoneContactsUseCase])
 *   and CASCADE-delete with their contact. Nothing else writes here.
 */
@Immutable
@Entity(
    tableName = "contact_phones",
    foreignKeys = [
        ForeignKey(
            entity = ContactEntity::class,
            parentColumns = ["id"],
            childColumns = ["contactId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        // Unique — the reconciler match key. One number, one contact.
        Index(value = ["normalizedPhone"], unique = true),
        // Per-contact phone-set reads during ingest delta-sync.
        Index(value = ["contactId"]),
    ],
)
data class ContactPhoneEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val contactId: Long,
    // Raw display formatting as read from the device row.
    val phoneNumber: String,
    // E.164-or-fallback match key (same normalization contract as
    // ContactEntity.normalizedPhone).
    val normalizedPhone: String,
    val isPrimary: Boolean = false,
)
