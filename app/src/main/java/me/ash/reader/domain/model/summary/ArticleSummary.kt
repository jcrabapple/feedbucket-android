package me.ash.reader.domain.model.summary

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import me.ash.reader.domain.model.article.Article

/**
 * AI-summary cache row (schema v11): the last generated summary for one
 * article, keyed by the article id. Deleting the parent article cascades
 * (the summary is derived data with no meaning without its article), and
 * the child column is indexed for the FK plus the per-article lookup.
 *
 * [sourceIsFullContent] records whether the summarized input was the
 * extracted full content (true) or the feed description (false), so the UI
 * can explain what the model actually saw.
 */
@Entity(
    tableName = "article_summary",
    foreignKeys = [
        ForeignKey(
            entity = Article::class,
            parentColumns = ["id"],
            childColumns = ["articleId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("articleId")],
)
data class ArticleSummary(
    @PrimaryKey
    val articleId: String,
    @ColumnInfo
    val summary: String,
    @ColumnInfo
    val model: String,
    @ColumnInfo
    val sourceIsFullContent: Boolean,
    @ColumnInfo
    val createdAt: Long,
)
