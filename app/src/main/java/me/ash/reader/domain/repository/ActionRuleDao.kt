package me.ash.reader.domain.repository

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.ash.reader.domain.model.rule.ActionRule

@Dao
interface ActionRuleDao {

    @Query("SELECT * FROM action_rule ORDER BY createdAt DESC, id DESC")
    fun queryAllFlow(): Flow<List<ActionRule>>

    @Query("SELECT * FROM action_rule WHERE id = :id")
    suspend fun queryById(id: Long): ActionRule?

    @Query("SELECT * FROM action_rule WHERE type = :type")
    suspend fun queryByType(type: String): List<ActionRule>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rule: ActionRule): Long

    @Update
    suspend fun update(rule: ActionRule)

    @Query("DELETE FROM action_rule WHERE id = :id")
    suspend fun deleteById(id: Long)
}
