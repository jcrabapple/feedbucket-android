package me.ash.reader.domain.model.position

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import me.ash.reader.domain.model.article.Article

/**
 * S2.3/S26 — per-article reading (scroll) position.
 * `scrollRatioPx` is the numerator of scrollY/contentHeight*10000.
 */
@Entity(
    tableName = "reading_position",
    foreignKeys = [ForeignKey(
        entity = Article::class,
        parentColumns = ["id"],
        childColumns = ["articleId"],
        onDelete = ForeignKey.CASCADE,
        onUpdate = ForeignKey.CASCADE,
    )],
    indices = [Index("updatedAt")],
)
data class ReadingPosition(
    @PrimaryKey
    @ColumnInfo(name = "articleId")
    val articleId: String,
    @ColumnInfo(name = "scrollRatioPx", defaultValue = "0")
    val scrollRatioPx: Int = 0,
    @ColumnInfo(name = "contentHeightPx", defaultValue = "0")
    val contentHeightPx: Int = 0,
    @ColumnInfo(name = "updatedAt")
    val updatedAt: Long,
)
