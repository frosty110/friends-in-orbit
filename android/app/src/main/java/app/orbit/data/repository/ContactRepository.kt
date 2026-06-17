package app.orbit.data.repository

import app.orbit.data.dao.PreIgnoreSnapshot
import app.orbit.data.entity.ContactEntity
import app.orbit.data.entity.ContactPhoneEntity
import java.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * Bridges DAO calls to domain consumers for the `contacts` table.
 *
 * Method set derived from `SurfaceNextUseCase` + `PauseContactUseCase`
 * consumption. The Room-backed implementation provides this surface; use cases
 * bind against this interface.
 *
 * Extended with the IGNORE-02/06 surface (`observeIgnored`, `markIgnored`,
 * `getPreIgnoreSnapshot`) and the CONTACT-03 per-contact rule override setter.
 */
interface ContactRepository {

    /** Observes every contact row, ordered by displayName ASC (DAO-enforced). */
    fun observeAll(): Flow<List<ContactEntity>>

    /**
     * List-scoped contact observer. Joins through `list_memberships`
     * so the Flow only emits when a contact ON the focused list changes. Replaces
     * the [observeAll] call in [app.orbit.domain.usecase.SurfaceNextUseCase]'s
     * Card View pipeline.
     */
    fun observeForListMembers(listId: Long): Flow<List<ContactEntity>>

    /**
     * Observes contacts with zero call events. Pushes the
     * SmartListEngine NeverCalled predicate into SQL via
     * [app.orbit.data.dao.ContactDao.observeNeverCalled]. Architecture-pure:
     * domain-layer code (SmartListEngine) does NOT inject DAOs; the
     * passthrough lives here so the abstraction boundary stays clean.
     */
    fun observeNeverCalled(): Flow<List<ContactEntity>>

    /** Suspend snapshot variant of [observeNeverCalled] for one-shot reads. */
    suspend fun snapshotNeverCalled(): List<ContactEntity>

    /**
     * One-shot snapshot of every `contact_phones` row (all
     * numbers per contact, not just the primary). Consumed by
     * [app.orbit.calllog.CallLogReconciler] to build its match index so calls
     * to a contact's second number reconcile.
     */
    suspend fun snapshotAllPhones(): List<ContactPhoneEntity>

    /** Observes a single contact by primary key; emits null if absent. */
    fun observeById(id: Long): Flow<ContactEntity?>

    /** One-shot read by primary key; returns null if absent. */
    suspend fun getById(id: Long): ContactEntity?

    /** Sets or clears the pause-until timestamp for a contact (PauseContactUseCase). */
    suspend fun setPausedUntil(id: Long, until: Instant?)

    /** IGNORE-06 — ignored contacts sorted by ignoredAt DESC. */
    fun observeIgnored(): Flow<List<ContactEntity>>

    /** IGNORE-02 — atomic three-column write; pass nulls + `isIgnored = false` for the clear path. */
    suspend fun markIgnored(
        id: Long,
        isIgnored: Boolean,
        ignoredAt: Instant?,
        preIgnoreListMembershipsJson: String?,
    )

    /** Projection for un-ignore restore — read snapshot without hydrating full row. */
    suspend fun getPreIgnoreSnapshot(id: Long): PreIgnoreSnapshot?

    /**
     * CONTACT-03 per-contact rule override setter. Pass null to clear.
     * The RuleOverrideSection editor binds against this surface
     * (NOT a full-row update path).
     */
    suspend fun setRuleOverrideJson(id: Long, json: String?)

    /**
     * CONTACT-06 archive flag setter. Pass true to archive, false to
     * unarchive. Writes ONLY `isArchived`; does NOT delete
     * ListMembership rows; does NOT touch `isIgnored`.
     */
    suspend fun setArchived(id: Long, archived: Boolean)
}
