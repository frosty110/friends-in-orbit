package app.orbit.data.entity

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalTime

@Immutable
@Entity(
    tableName = "lists",
    foreignKeys = [
        ForeignKey(
            entity = RuleTemplateEntity::class,
            parentColumns = ["id"],
            childColumns = ["ruleTemplateId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("ruleTemplateId"),
        // Non-unique index on `sortOrder` so ORDER BY sortOrder is index-scan
        // backed. The "two active lists cannot share a sortOrder" invariant is
        // enforced in `ListRepositoryImpl.reorder` (range-only renumber inside
        // `db.withTransaction`) — Room's @Index cannot express partial-unique,
        // and Room's strict TableInfo validation rejects any extra raw-SQL
        // index on this table that the entity doesn't declare here.
        Index("sortOrder"),
    ],
)
data class ListEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val sortOrder: Int,
    val isArchived: Boolean = false,
    val type: ListType = ListType.STATIC,
    val smartRuleJson: String? = null,
    val ruleTemplateId: Long? = null,
    val activeHoursStart: LocalTime? = null,
    val activeHoursEnd: LocalTime? = null,
    val notificationsEnabled: Boolean = true,
    // Per-list rule-param override (LIST-04 end-to-end).
    // Null = use the template's shared paramsJson; non-null = decoded by
    // OverrideResolver and wins over the template default. Per-contact override
    // (ContactEntity.ruleOverrideJson) still wins over this (DOM-04 invariant).
    val ruleParamsOverrideJson: String? = null,
    // ADR 0006 Rule 2 — denormalized count of memberships
    // whose nextDueAt is null OR <= clock.now(). Kept fresh by the seven
    // mutator use cases via ListRepository.recomputeDueCountForList(...).
    // Stale by up to 5 minutes for time-based transitions; HomeFeed
    // refreshes on app foreground.
    val dueCount: Int = 0,
    // NOTIF-10/11 — per-list nudge schedule. Serialized NudgeSchedule
    // (kotlinx-serialization JSON). Null = never existed before
    // migration (impossible post-MIGRATION_11_12 backfill). DEFAULT is seeded
    // by the migration for all pre-existing rows.
    val nudgeScheduleJson: String? = null,
)
