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

    @Query("SELECT * FROM article_download WHERE articleId IN (:articleIds)")
    suspend fun queryByArticleIds(articleIds: List<String>): List<ArticleDownload>

    @Query("SELECT * FROM article_download WHERE state = :state")
    suspend fun queryByState(state: String): List<ArticleDownload>

    @Query("SELECT * FROM article_download")
    suspend fun queryAll(): List<ArticleDownload>

    /** S28a — manual retry: failed -> pending and RESET the attempt counter. */
    @Query(
        """
        UPDATE article_download SET state = 'pending', errorCause = NULL,
        attempts = 0, lastAttemptAt = NULL, completedAt = NULL
        WHERE articleId = :articleId
        """
    )
    suspend fun resetForRetry(articleId: String)

    /** S27a — pending/downloading -> downloading, counting this attempt. */
    @Query(
        """
        UPDATE article_download SET state = 'downloading', errorCause = NULL,
        lastAttemptAt = :now, attempts = attempts + 1
        WHERE articleId = :articleId
        """
    )
    suspend fun markDownloading(articleId: String, now: Long)

    /**
     * S27a — text commit: row flips to `ready` ONLY after the store's atomic
     * rename succeeded; expectedImageCount is recorded at text-commit.
     */
    @Query(
        """
        UPDATE article_download SET state = 'ready', errorCause = NULL,
        textBytes = :textBytes, expectedImageCount = :expectedImageCount,
        imageCount = 0, imageBytes = 0, assetsComplete = 0,
        completedAt = :now
        WHERE articleId = :articleId
        """
    )
    suspend fun markReady(articleId: String, textBytes: Long, expectedImageCount: Int, now: Long)

    /** S27a — each asset landing bumps imageCount/imageBytes (+assetsComplete). */
    @Query(
        """
        UPDATE article_download SET imageCount = imageCount + 1,
        imageBytes = imageBytes + :bytes, assetsComplete = :assetsComplete
        WHERE articleId = :articleId
        """
    )
    suspend fun bumpImageProgress(articleId: String, bytes: Long, assetsComplete: Boolean)

    @Query("UPDATE article_download SET assetsComplete = :assetsComplete WHERE articleId = :articleId")
    suspend fun setAssetsComplete(articleId: String, assetsComplete: Boolean)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(download: ArticleDownload)

    @Query(
        """
        UPDATE article_download SET state = :state, errorCause = :errorCause,
        lastAttemptAt = :lastAttemptAt, completedAt = :completedAt
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

    /** S29 — removing a download deletes its manifest asset rows too
     *  (download_asset FKs article, not article_download). */
    @Query("DELETE FROM download_asset WHERE articleId = :articleId")
    suspend fun deleteAssetsByArticleId(articleId: String)

    @Query("DELETE FROM download_asset")
    suspend fun deleteAllAssets()

    @Query("DELETE FROM article_download")
    suspend fun deleteAllDownloads()

    @Query("DELETE FROM article_download WHERE articleId = :articleId")
    suspend fun deleteByArticleId(articleId: String)
}
