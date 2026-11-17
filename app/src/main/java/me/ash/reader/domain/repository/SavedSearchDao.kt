package me.ash.reader.domain.repository

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.ash.reader.domain.model.search.SavedSearch

@Dao
interface SavedSearchDao {

    @Query("SELECT * FROM saved_search ORDER BY createdAt DESC, id DESC")
    fun queryAllFlow(): Flow<List<SavedSearch>>

    @Query("SELECT * FROM saved_search WHERE id = :id")
    suspend fun queryById(id: Long): SavedSearch?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(savedSearch: SavedSearch): Long

    @Update
    suspend fun update(savedSearch: SavedSearch)

    @Query("DELETE FROM saved_search WHERE id = :id")
    suspend fun deleteById(id: Long)
}
