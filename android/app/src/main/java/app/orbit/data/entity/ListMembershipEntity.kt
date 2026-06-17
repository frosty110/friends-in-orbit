package app.orbit.data.entity

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import java.time.Instant

@Immutable
@Entity(
    tableName = "list_memberships",
    primaryKeys = ["contactId", "listId"],
    foreignKeys = [
        ForeignKey(
            entity = ContactEntity::class,
            parentColumns = ["id"],
            childColumns = ["contactId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ListEntity::class,
            parentColumns = ["id"],
            childColumns = ["listId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("contactId"),
        Index("listId"),
    ],
)
data class ListMembershipEntity(
    val contactId: Long,
    val listId: Long,
    val addedAt: Instant,
    val nextDueAt: Instant? = null,
    val skipCount: Int = 0,
)
