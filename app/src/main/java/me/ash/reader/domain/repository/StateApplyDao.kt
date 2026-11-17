package me.ash.reader.domain.repository

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import me.ash.reader.domain.model.rule.ArticleApplyState
import me.ash.reader.domain.model.rule.StateApplyBefore
import me.ash.reader.domain.model.rule.StateApplyPlanner
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

    /** S18a undo outcome reported to the UI ("Restored N · skipped M"). */
    data class UndoCounts(val restored: Int, val skipped: Int)

    /**
     * S18a apply-to-existing, ONE transaction (R2): writes the ledger head
     * + before rows, applies the effects via [writeEffect], prunes
     * retention. [writeEffect] performs the per-article effect UPDATE(s)
     * (mark_read/star with the stateVersion bump) and MUST be conditional
     * on the article's CURRENT version — a concurrent change turns the
     * write into a no-op (returns 0) rather than clobbering it. NO star
     * budget on apply-to-existing (web parity). `appliedCount`/the return
     * value is the exact number of articles whose effects write landed
     * (0 for an effects-empty rule); matched-but-not-written rows stay
     * ledger-recorded so undo can still evaluate them.
     */
    @Transaction
    suspend fun applyRun(
        matched: List<ArticleApplyState>,
        ruleId: Long?,
        markRead: Boolean,
        star: Boolean,
        now: Long,
        insertRun: suspend (StateApplyRun) -> Long,
        insertBeforeRows: suspend (List<StateApplyBefore>) -> Unit,
        writeEffect: suspend (ArticleApplyState) -> Int,
        prune: suspend () -> Unit,
    ): Int {
        if (matched.isEmpty()) return 0
        val applied = if (markRead || star) matched.sumOf { writeEffect(it) } else 0
        val runId =
            insertRun(
                StateApplyRun(
                    ruleId = ruleId,
                    startedAt = now,
                    finishedAt = now,
                    appliedCount = applied,
                )
            )
        insertBeforeRows(StateApplyPlanner.beforeRows(runId, matched))
        prune()
        return applied
    }

    /**
     * S18a undo of one run, ONE transaction: restores each ledgered
     * article's before-state ONLY when its current `stateVersion` still
     * equals `stateVersionAtApply` — [restore] is the conditional UPDATE
     * (returns rows written; 0 = changed since apply → skipped+counted).
     * `wasStarred=1` rows restore to starred: undo NEVER clears
     * pre-existing stars. Returns null when the run is missing, unfinished
     * or already undone (Undo is available only for the most recent
     * completed, not-yet-undone run).
     */
    @Transaction
    suspend fun undoRun(
        runId: Long,
        now: Long,
        restore: suspend (StateApplyBefore) -> Int,
    ): UndoCounts? {
        val run = queryRun(runId) ?: return null
        if (run.finishedAt == null || run.undoneAt != null) return null
        var restored = 0
        var skipped = 0
        for (row in queryBeforeRows(runId)) {
            val changed = restore(row)
            if (changed > 0) restored += 1 else skipped += 1
        }
        markUndone(runId, now, restored, skipped)
        return UndoCounts(restored = restored, skipped = skipped)
    }

    /** S18a — undo the most recent completed, not-yet-undone run, if any. */
    @Transaction
    suspend fun undoLatestRun(
        now: Long,
        restore: suspend (StateApplyBefore) -> Int,
    ): UndoCounts? {
        val run = queryLatestUndoableRun() ?: return null
        return undoRun(run.id, now, restore)
    }
}
