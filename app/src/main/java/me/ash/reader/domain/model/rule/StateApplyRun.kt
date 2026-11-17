package me.ash.reader.domain.model.rule

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * S2.3/S18a — durable undo-ledger head for apply-to-existing runs.
 * Retention: on each new apply, prune all but the 10 most recent runs.
 */
@Entity(tableName = "state_apply_run")
data class StateApplyRun(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "ruleId")
    val ruleId: Long? = null,
    @ColumnInfo(name = "kind", defaultValue = "apply_to_existing")
    val kind: String = "apply_to_existing",
    @ColumnInfo(name = "startedAt")
    val startedAt: Long,
    @ColumnInfo(name = "finishedAt")
    val finishedAt: Long? = null,
    @ColumnInfo(name = "appliedCount", defaultValue = "0")
    val appliedCount: Int = 0,
    @ColumnInfo(name = "undoneAt")
    val undoneAt: Long? = null,
    @ColumnInfo(name = "undoRestoredCount", defaultValue = "0")
    val undoRestoredCount: Int = 0,
    @ColumnInfo(name = "undoSkippedCount", defaultValue = "0")
    val undoSkippedCount: Int = 0,
)
