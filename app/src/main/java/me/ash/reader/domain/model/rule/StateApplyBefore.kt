package me.ash.reader.domain.model.rule

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import me.ash.reader.domain.model.article.Article

/**
 * S2.3/S18a — before-state ledger rows for one apply run. Undo restores a
 * row ONLY when its current `stateVersion` still equals
 * `stateVersionAtApply` (the POST-apply bumped version recorded in the same
 * apply transaction); changed rows are skipped and counted.
 */
@Entity(
    tableName = "state_apply_before",
    primaryKeys = ["runId", "articleId"],
    foreignKeys = [
        ForeignKey(
            entity = StateApplyRun::class,
            parentColumns = ["id"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = Article::class,
            parentColumns = ["id"],
            childColumns = ["articleId"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("articleId")],
)
data class StateApplyBefore(
    @ColumnInfo(name = "runId")
    val runId: Long,
    @ColumnInfo(name = "articleId")
    val articleId: String,
    @ColumnInfo(name = "wasUnread")
    val wasUnread: Boolean,
    @ColumnInfo(name = "wasStarred")
    val wasStarred: Boolean,
    @ColumnInfo(name = "wasUpdated")
    val wasUpdated: Boolean,
    @ColumnInfo(name = "readAtBefore")
    val readAtBefore: Long? = null,
    @ColumnInfo(name = "stateVersionAtApply")
    val stateVersionAtApply: Long,
)
