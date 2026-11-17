package me.ash.reader.domain.repository

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import me.ash.reader.domain.model.importing.ImportIdentity
import me.ash.reader.domain.model.importing.ImportRun

@Dao
interface ImportDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRun(run: ImportRun): Long

    @Query("SELECT * FROM import_run WHERE id = :id")
    suspend fun queryRun(id: Long): ImportRun?

    @Query("SELECT * FROM import_run WHERE state = 'running' ORDER BY id DESC LIMIT 1")
    suspend fun queryRunningRun(): ImportRun?

    @Query("UPDATE import_run SET state = :state, finishedAt = :finishedAt, countsJson = :countsJson WHERE id = :id")
    suspend fun updateRunState(id: Long, state: String, finishedAt: Long?, countsJson: String)

    @Query(
        """
        SELECT * FROM import_identity
        WHERE source = :source AND sourceId = :sourceId AND targetKind = :targetKind
        """
    )
    suspend fun queryIdentity(source: String, sourceId: String, targetKind: String): ImportIdentity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIdentities(identities: List<ImportIdentity>): List<Long>
}
