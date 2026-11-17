package me.ash.reader.domain.model.download

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import me.ash.reader.domain.model.article.Article

/**
 * S2.3/S27a — per-article durable download state.
 *
 * `ready` ALWAYS means text durably committed with a valid manifest; images
 * may still be in flight (`assetsComplete=0`). `errorCause` is a stable
 * enum string (network/http_4xx/http_5xx/challenge/oversize/storage/
 * cancelled/unknown).
 */
@Entity(
    tableName = "article_download",
    foreignKeys = [ForeignKey(
        entity = Article::class,
        parentColumns = ["id"],
        childColumns = ["articleId"],
        onDelete = ForeignKey.CASCADE,
        onUpdate = ForeignKey.CASCADE,
    )],
    indices = [Index("state")],
)
data class ArticleDownload(
    @PrimaryKey
    @ColumnInfo(name = "articleId")
    val articleId: String,
    @ColumnInfo(name = "state", defaultValue = "pending")
    val state: String = "pending",
    @ColumnInfo(name = "textBytes", defaultValue = "0")
    val textBytes: Long = 0,
    @ColumnInfo(name = "imageBytes", defaultValue = "0")
    val imageBytes: Long = 0,
    @ColumnInfo(name = "imageCount", defaultValue = "0")
    val imageCount: Int = 0,
    @ColumnInfo(name = "expectedImageCount", defaultValue = "0")
    val expectedImageCount: Int = 0,
    @ColumnInfo(name = "assetsComplete", defaultValue = "0")
    val assetsComplete: Boolean = false,
    @ColumnInfo(name = "errorCause")
    val errorCause: String? = null,
    @ColumnInfo(name = "attempts", defaultValue = "0")
    val attempts: Int = 0,
    @ColumnInfo(name = "requestedAt")
    val requestedAt: Long,
    @ColumnInfo(name = "lastAttemptAt")
    val lastAttemptAt: Long? = null,
    @ColumnInfo(name = "completedAt")
    val completedAt: Long? = null,
) {

    companion object {
        const val STATE_PENDING = "pending"
        const val STATE_DOWNLOADING = "downloading"
        const val STATE_READY = "ready"
        const val STATE_FAILED = "failed"
    }
}
