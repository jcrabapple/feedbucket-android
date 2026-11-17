package me.ash.reader.domain.model.position

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * S2.3/S24a — durable list position per canonical scope key
 * (`scopeKey = "v1/" + pctEncoded(view/feedId/groupId/savedSearchId/query/sort)`,
 * injective; the encoding is owned by Wave C's PositionStore).
 */
@Entity(tableName = "list_position", indices = [Index("updatedAt")])
data class ListPosition(
    @PrimaryKey
    @ColumnInfo(name = "scopeKey")
    val scopeKey: String,
    @ColumnInfo(name = "anchorArticleId")
    val anchorArticleId: String? = null,
    @ColumnInfo(name = "anchorItemIndex", defaultValue = "0")
    val anchorItemIndex: Int = 0,
    @ColumnInfo(name = "anchorOffsetPx", defaultValue = "0")
    val anchorOffsetPx: Int = 0,
    @ColumnInfo(name = "updatedAt")
    val updatedAt: Long,
)
