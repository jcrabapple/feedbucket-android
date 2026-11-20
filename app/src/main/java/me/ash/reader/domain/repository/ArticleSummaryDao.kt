package me.ash.reader.domain.repository

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import me.ash.reader.domain.model.summary.ArticleSummary

/**
 * AI-summary cache access (schema v11). One row per article; upsert
 * replaces the cached summary on regenerate.
 */
@Dao
interface ArticleSummaryDao {

    @Query("SELECT * FROM article_summary WHERE articleId = :articleId")
    suspend fun getByArticleId(articleId: String): ArticleSummary?

    @Query("SELECT * FROM article_summary WHERE articleId = :articleId")
    fun observeByArticleId(articleId: String): Flow<ArticleSummary?>

    @Upsert
    suspend fun upsert(summary: ArticleSummary)

    @Query("DELETE FROM article_summary WHERE articleId = :articleId")
    suspend fun deleteByArticleId(articleId: String)
}
