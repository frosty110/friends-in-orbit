package app.orbit.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.orbit.data.entity.RuleKind
import app.orbit.data.entity.RuleTemplateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RuleTemplateDao {

    @Query("SELECT * FROM rule_templates ORDER BY id ASC")
    fun observeAll(): Flow<List<RuleTemplateEntity>>

    @Query("SELECT * FROM rule_templates WHERE id = :id")
    suspend fun get(id: Long): RuleTemplateEntity?

    @Query("SELECT * FROM rule_templates WHERE kind = :kind LIMIT 1")
    suspend fun getByKind(kind: RuleKind): RuleTemplateEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(template: RuleTemplateEntity): Long

    @Update
    suspend fun update(template: RuleTemplateEntity): Int

    @Delete
    suspend fun delete(template: RuleTemplateEntity): Int
}
