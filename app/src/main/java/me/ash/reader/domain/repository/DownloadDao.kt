package me.ash.reader.domain.repository

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import me.ash.reader.domain.model.download.ArticleDownload
import me.ash.reader.domain.model.download.DownloadAsset

@Dao
interface DownloadDao {

    @Query("SELECT * FROM article_download WHERE articleId = :articleId")
    suspend fun queryByArticleId(articleId: String): ArticleDownload?

    @Query("SELECT * FROM article_download WHERE state = :state")
    suspend fun queryByState(state: String): List<ArticleDownload>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(download: ArticleDownload)

    @Query(
        """
        UPDATE article_download SET state = :state, errorCause = :errorCause,
        lastAttemptAt = :lastAttemptAt, completedAt = :completedAt,
        attempts = attempts + 1
        WHERE articleId = :articleId
        """
    )
    suspend fun updateState(
        articleId: String,
        state: String,
        errorCause: String?,
        lastAttemptAt: Long?,
        completedAt: Long?,
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAssets(assets: List<DownloadAsset>)

    @Query("SELECT * FROM download_asset WHERE articleId = :articleId")
    suspend fun queryAssets(articleId: String): List<DownloadAsset>

    @Query("DELETE FROM article_download WHERE articleId = :articleId")
    suspend fun deleteByArticleId(articleId: String)
}
