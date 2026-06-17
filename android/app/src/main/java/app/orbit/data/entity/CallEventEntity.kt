package app.orbit.data.entity

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

@Immutable
@Entity(
    tableName = "call_events",
    foreignKeys = [
        ForeignKey(
            entity = ContactEntity::class,
            parentColumns = ["id"],
            childColumns = ["contactId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["contactId", "occurredAt"]),
        // Eliminates filesort on every Call Log open
        // (CallEventDao.observeForLog ORDER BY occurredAt DESC).
        Index(value = ["occurredAt"]),
    ],
)
data class CallEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val contactId: Long,
    val occurredAt: Instant,
    val direction: CallDirection,
    val durationSeconds: Int,
    val source: CallSource,
)
