package me.ash.reader.domain.repository

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import me.ash.reader.domain.model.rule.StateApplyBefore
import me.ash.reader.domain.model.rule.StateApplyRun

@Dao
interface StateApplyDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRun(run: StateApplyRun): Long

    @Query("SELECT * FROM state_apply_run WHERE id = :id")
    suspend fun queryRun(id: Long): StateApplyRun?

    /**
     * Most recent completed, not-yet-undone apply run (S18a undo is
     * available only for this one).
     */
    @Query(
        """
        SELECT * FROM state_apply_run
        WHERE finishedAt IS NOT NULL AND undoneAt IS NULL
        ORDER BY id DESC LIMIT 1
        """
    )
    suspend fun queryLatestUndoableRun(): StateApplyRun?

    @Query("UPDATE state_apply_run SET undoneAt = :undoneAt, undoRestoredCount = :restored, undoSkippedCount = :skipped WHERE id = :id")
    suspend fun markUndone(id: Long, undoneAt: Long, restored: Int, skipped: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBeforeRows(rows: List<StateApplyBefore>)

    @Query("SELECT * FROM state_apply_before WHERE runId = :runId")
    suspend fun queryBeforeRows(runId: Long): List<StateApplyBefore>

    /**
     * S18a retention: keep the 10 most recent runs, delete older ones
     * (their ledger rows cascade).
     */
    @Query(
        """
        DELETE FROM state_apply_run WHERE id NOT IN (
            SELECT id FROM state_apply_run ORDER BY id DESC LIMIT 10
        )
        """
    )
    suspend fun pruneToLastTenRuns()
}
