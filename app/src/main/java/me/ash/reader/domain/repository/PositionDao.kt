package me.ash.reader.domain.repository

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import me.ash.reader.domain.model.position.ListPosition
import me.ash.reader.domain.model.position.ReadingPosition

@Dao
interface PositionDao {

    @Query("SELECT * FROM list_position WHERE scopeKey = :scopeKey")
    suspend fun queryListPosition(scopeKey: String): ListPosition?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertListPosition(position: ListPosition)

    @Query("DELETE FROM list_position WHERE updatedAt < :before")
    suspend fun pruneListPositions(before: Long)

    @Query("SELECT * FROM reading_position WHERE articleId = :articleId")
    suspend fun queryReadingPosition(articleId: String): ReadingPosition?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReadingPosition(position: ReadingPosition)
}
