package me.ash.reader.domain.model.download

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import me.ash.reader.domain.model.article.Article

/**
 * S2.3/S27a — one landed offline asset; the manifest for offline render.
 * Composite PK (articleId, url).
 */
@Entity(
    tableName = "download_asset",
    primaryKeys = ["articleId", "url"],
    foreignKeys = [ForeignKey(
        entity = Article::class,
        parentColumns = ["id"],
        childColumns = ["articleId"],
        onDelete = ForeignKey.CASCADE,
        onUpdate = ForeignKey.CASCADE,
    )],
    indices = [Index("articleId")],
)
data class DownloadAsset(
    @ColumnInfo(name = "articleId")
    val articleId: String,
    @ColumnInfo(name = "url")
    val url: String,
    @ColumnInfo(name = "localPath")
    val localPath: String,
    @ColumnInfo(name = "bytes")
    val bytes: Long,
    @ColumnInfo(name = "sha256")
    val sha256: String,
)
