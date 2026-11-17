package me.ash.reader.domain.model.article

import androidx.room.*
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.query.TextNormalizer
import java.util.*

/**
 * v8 article (S2.1). New columns: publisher guid/provenance, snapshot and
 * updated-queue state, read timestamp, extraction timestamp, the
 * S12a `*Lower` shadow columns and the S18a `stateVersion` choke point.
 *
 * `fullContent` is redefined (no longer @Deprecated): locally extracted
 * sanitized HTML, NULL when not extracted — never populated from feed data.
 *
 * Shadow-column invariant (S11a write path): every construction recomputes
 * `*Lower` from its source field via [TextNormalizer] (the single canonical
 * normalization, S12a); Room reads pass stored values explicitly.
 */
@Entity(
    tableName = "article",
    foreignKeys = [ForeignKey(
        entity = Feed::class,
        parentColumns = ["id"],
        childColumns = ["feedId"],
        onDelete = ForeignKey.CASCADE,
        onUpdate = ForeignKey.CASCADE
    )]
)
data class Article(
    @PrimaryKey
    var id: String,
    @ColumnInfo
    var date: Date,
    @ColumnInfo
    var title: String,
    @ColumnInfo
    var author: String? = null,
    @ColumnInfo
    var rawDescription: String,
    @ColumnInfo
    var shortDescription: String,
    @ColumnInfo
    var fullContent: String? = null,
    @ColumnInfo
    var img: String? = null,
    @ColumnInfo
    var link: String,
    @ColumnInfo(index = true)
    var feedId: String,
    @ColumnInfo(index = true)
    var accountId: Int,
    @ColumnInfo
    var isUnread: Boolean = true,
    @ColumnInfo
    var isStarred: Boolean = false,
    @ColumnInfo
    var isReadLater: Boolean = false,
    @ColumnInfo
    var updateAt: Date? = null,
    @ColumnInfo(defaultValue = "")
    var guid: String = "",
    @ColumnInfo
    var originalContent: String? = null,
    @ColumnInfo
    var contentChangedAt: Date? = null,
    @ColumnInfo(defaultValue = "0")
    var isUpdated: Boolean = false,
    @ColumnInfo
    var readAt: Date? = null,
    @ColumnInfo
    var extractedAt: Date? = null,
    @ColumnInfo
    var publishedAt: Date? = null,
    @ColumnInfo(defaultValue = "0")
    var stateVersion: Long = 0,
    @ColumnInfo(defaultValue = "")
    var titleLower: String = TextNormalizer.normalize(title),
    @ColumnInfo(defaultValue = "")
    var shortLower: String = TextNormalizer.normalize(shortDescription),
    @ColumnInfo(defaultValue = "")
    var rawLower: String = TextNormalizer.normalize(rawDescription),
    @ColumnInfo(defaultValue = "")
    var fullLower: String = TextNormalizer.normalize(fullContent),
) {

    @Ignore
    var dateString: String? = null
}
