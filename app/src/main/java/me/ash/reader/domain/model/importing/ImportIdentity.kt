package me.ash.reader.domain.model.importing

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * S2.3/S8/S32a — import identity map. Re-import consults this map first:
 * mapped rows are SKIPPED for content and counted as `skipped`.
 *
 * `createdByRunId` is set ONLY when that run CREATED the target row (NULL
 * for identity-map skip hits). `stateStamp`: article → stateVersion at
 * import; feed/saved_search/action_rule → sha256 of user-editable content.
 */
@Entity(tableName = "import_identity", primaryKeys = ["source", "sourceId", "targetKind"])
data class ImportIdentity(
    @ColumnInfo(name = "source")
    val source: String,
    @ColumnInfo(name = "sourceId")
    val sourceId: String,
    @ColumnInfo(name = "targetKind")
    val targetKind: String,
    @ColumnInfo(name = "targetId")
    val targetId: String,
    @ColumnInfo(name = "importRunId")
    val importRunId: Long,
    @ColumnInfo(name = "importedAt")
    val importedAt: Long,
    @ColumnInfo(name = "createdByRunId")
    val createdByRunId: Long? = null,
    @ColumnInfo(name = "stateStamp", defaultValue = "")
    val stateStamp: String = "",
) {

    companion object {
        const val TARGET_ARTICLE = "article"
        const val TARGET_FEED = "feed"
        const val TARGET_SAVED_SEARCH = "saved_search"
        const val TARGET_ACTION_RULE = "action_rule"
    }
}
