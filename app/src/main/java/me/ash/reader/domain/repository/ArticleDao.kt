package me.ash.reader.domain.repository

import androidx.paging.PagingSource
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.MapColumn
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RewriteQueriesToDropUnusedColumns
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.ArticleMeta
import me.ash.reader.domain.model.article.ArticleWithFeed
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.feed.ImportantNum
import java.util.Date

@Dao
interface ArticleDao {

    @Query(
        """
        UPDATE article SET isStarred = :isStarred, stateVersion = stateVersion + 1
        WHERE accountId = :accountId
        AND id in (:ids)
        """
    )
    fun markAsStarredByIdSet(
        accountId: Int,
        ids: Set<String>,
        isStarred: Boolean,
    ): Int

    /**
     * S21a/S23 batch read-state write with the same single-transaction semantics
     * as [markAsReadByArticleId]: read sets readAt + clears isUpdated, unread
     * clears readAt; stateVersion bumps on every touched row.
     */
    @Query(
        """
        UPDATE article SET
            isUnread = :isUnread,
            readAt = CASE WHEN :isUnread = 1 THEN NULL ELSE :readAt END,
            isUpdated = CASE WHEN :isUnread = 1 THEN isUpdated ELSE 0 END,
            stateVersion = stateVersion + 1
        WHERE accountId = :accountId
        AND id in (:ids)
        """
    )
    fun markAsReadByIdSet(
        accountId: Int,
        ids: Set<String>,
        isUnread: Boolean,
        readAt: Date? = if (isUnread) null else Date(),
    ): Int

    @Transaction
    @RewriteQueriesToDropUnusedColumns
    @Query(
        """
        SELECT count(1)
        FROM article
        WHERE feedId = :feedId
        AND isStarred = :isStarred
        AND accountId = :accountId
        """
    )
    fun countByFeedIdWhenIsStarred(
        accountId: Int,
        feedId: String,
        isStarred: Boolean,
    ): Int

    @Transaction
    @RewriteQueriesToDropUnusedColumns
    @Query(
        """
        SELECT count(1)
        FROM article AS a
        LEFT JOIN feed AS b ON b.id = a.feedId
        LEFT JOIN `group` AS c ON c.id = b.groupId
        WHERE c.id = :groupId
        AND a.isStarred = :isStarred
        AND a.accountId = :accountId
        """
    )
    fun countByGroupIdWhenIsStarred(
        accountId: Int,
        groupId: String,
        isStarred: Boolean,
    ): Int


    @Transaction
    @Query(
        """
        SELECT * FROM article
        WHERE accountId = :accountId 
        AND feedId IN (
            SELECT id FROM feed WHERE groupId = :groupId
        )
        AND isUnread = :isUnread
        AND (
            title LIKE '%' || :text || '%'
            OR shortDescription LIKE '%' || :text || '%'
            OR fullContent LIKE '%' || :text || '%'
        )
        ORDER BY
            CASE WHEN :sortAscending = 1 THEN date END ASC,
            CASE WHEN :sortAscending = 0 THEN date END DESC
        """
    )
    fun searchArticleByGroupIdWhenIsUnread(
        accountId: Int,
        text: String,
        groupId: String,
        isUnread: Boolean,
        sortAscending: Boolean = false
    ): PagingSource<Int, ArticleWithFeed>

    @Transaction
    @Query(
        """
        SELECT * FROM article
        WHERE accountId = :accountId 
        AND feedId IN (
            SELECT id FROM feed WHERE groupId = :groupId
        )
        AND isStarred = :isStarred
        AND (
            title LIKE '%' || :text || '%'
            OR shortDescription LIKE '%' || :text || '%'
            OR fullContent LIKE '%' || :text || '%'
        )
        ORDER BY
            CASE WHEN :sortAscending = 1 THEN date END ASC,
            CASE WHEN :sortAscending = 0 THEN date END DESC
        """
    )
    fun searchArticleByGroupIdWhenIsStarred(
        accountId: Int,
        text: String,
        groupId: String,
        isStarred: Boolean,
        sortAscending: Boolean = false
    ): PagingSource<Int, ArticleWithFeed>

    @Transaction
    @Query(
        """
        SELECT * FROM article
        WHERE accountId = :accountId 
        AND feedId IN (
            SELECT id FROM feed WHERE groupId = :groupId
        )
        AND (
            title LIKE '%' || :text || '%'
            OR shortDescription LIKE '%' || :text || '%'
            OR fullContent LIKE '%' || :text || '%'
        )
        ORDER BY
            CASE WHEN :sortAscending = 1 THEN date END ASC,
            CASE WHEN :sortAscending = 0 THEN date END DESC
        """
    )
    fun searchArticleByGroupIdWhenAll(
        accountId: Int, text: String, groupId: String, sortAscending: Boolean = false
    ): PagingSource<Int, ArticleWithFeed>

    @Transaction
    @Query(
        """
        SELECT * FROM article
        WHERE accountId = :accountId 
        AND feedId = :feedId
        AND isUnread = :isUnread
        AND (
            title LIKE '%' || :text || '%'
            OR shortDescription LIKE '%' || :text || '%'
            OR fullContent LIKE '%' || :text || '%'
        )
        ORDER BY
            CASE WHEN :sortAscending = 1 THEN date END ASC,
            CASE WHEN :sortAscending = 0 THEN date END DESC
        """
    )
    fun searchArticleByFeedIdWhenIsUnread(
        accountId: Int,
        text: String,
        feedId: String,
        isUnread: Boolean,
        sortAscending: Boolean = false
    ): PagingSource<Int, ArticleWithFeed>

    @Transaction
    @Query(
        """
        SELECT * FROM article
        WHERE accountId = :accountId 
        AND feedId = :feedId
        AND isStarred = :isStarred
        AND (
            title LIKE '%' || :text || '%'
            OR shortDescription LIKE '%' || :text || '%'
            OR fullContent LIKE '%' || :text || '%'
        )
        ORDER BY
            CASE WHEN :sortAscending = 1 THEN date END ASC,
            CASE WHEN :sortAscending = 0 THEN date END DESC
        """
    )
    fun searchArticleByFeedIdWhenIsStarred(
        accountId: Int,
        text: String,
        feedId: String,
        isStarred: Boolean,
        sortAscending: Boolean = false
    ): PagingSource<Int, ArticleWithFeed>

    @Transaction
    @Query(
        """
        SELECT * FROM article
        WHERE accountId = :accountId 
        AND feedId = :feedId 
        AND (
            title LIKE '%' || :text || '%'
            OR shortDescription LIKE '%' || :text || '%'
            OR fullContent LIKE '%' || :text || '%'
        )
        ORDER BY
            CASE WHEN :sortAscending = 1 THEN date END ASC,
            CASE WHEN :sortAscending = 0 THEN date END DESC
        """
    )
    fun searchArticleByFeedIdWhenAll(
        accountId: Int, text: String, feedId: String, sortAscending: Boolean = false
    ): PagingSource<Int, ArticleWithFeed>

    @Transaction
    @Query(
        """
        SELECT * FROM article
        WHERE accountId = :accountId 
        AND isUnread = :isUnread
        AND (
            title LIKE '%' || :text || '%'
            OR shortDescription LIKE '%' || :text || '%'
            OR fullContent LIKE '%' || :text || '%'
        )
        ORDER BY
            CASE WHEN :sortAscending = 1 THEN date END ASC,
            CASE WHEN :sortAscending = 0 THEN date END DESC
        """
    )
    fun searchArticleWhenIsUnread(
        accountId: Int, text: String, isUnread: Boolean, sortAscending: Boolean = false
    ): PagingSource<Int, ArticleWithFeed>

    @Transaction
    @Query(
        """
        SELECT * FROM article
        WHERE accountId = :accountId 
        AND isStarred = :isStarred
        AND (
            title LIKE '%' || :text || '%'
            OR shortDescription LIKE '%' || :text || '%'
            OR fullContent LIKE '%' || :text || '%'
        )
        ORDER BY
            CASE WHEN :sortAscending = 1 THEN date END ASC,
            CASE WHEN :sortAscending = 0 THEN date END DESC
        """
    )
    fun searchArticleWhenIsStarred(
        accountId: Int, text: String, isStarred: Boolean, sortAscending: Boolean = false
    ): PagingSource<Int, ArticleWithFeed>

    @Transaction
    @Query(
        """
        SELECT * FROM article
        WHERE accountId = :accountId 
        AND (
            title LIKE '%' || :text || '%'
            OR shortDescription LIKE '%' || :text || '%'
            OR fullContent LIKE '%' || :text || '%'
        )
        ORDER BY
            CASE WHEN :sortAscending = 1 THEN date END ASC,
            CASE WHEN :sortAscending = 0 THEN date END DESC
        """
    )
    fun searchArticleWhenAll(
        accountId: Int, text: String, sortAscending: Boolean = false
    ): PagingSource<Int, ArticleWithFeed>


    @Query(
        """
        DELETE FROM article
        WHERE accountId = :accountId
        AND updateAt < :before
        AND isUnread = 0
        AND isStarred = 0
        """
    )
    suspend fun deleteAllArchivedBeforeThan(
        accountId: Int,
        before: Date,
    )

    @Query(
        """
        select * FROM article
        WHERE accountId = :accountId
        AND updateAt < :before
        AND isUnread = 0
        AND isStarred = 0
        """
    )
    suspend fun queryArchivedArticleBefore(
        accountId: Int,
        before: Date,
    ): List<Article>

    /**
     * S21a/S23 scoped mark-all-read (whole account): sets readAt, clears isUpdated,
     * bumps stateVersion on every flipped row; returns the exact affected count
     * (S25a confirmation parity with web mark_all_read).
     */
    @Transaction
    @Query(
        """
        UPDATE article SET
            isUnread = :isUnread,
            readAt = CASE WHEN :isUnread = 1 THEN NULL ELSE :readAt END,
            isUpdated = CASE WHEN :isUnread = 1 THEN isUpdated ELSE 0 END,
            stateVersion = stateVersion + 1
        WHERE accountId = :accountId
        AND date < :before
        AND isUnread != :isUnread
        """
    )
    suspend fun markAllAsRead(
        accountId: Int,
        isUnread: Boolean,
        before: Date,
        readAt: Date? = if (isUnread) null else Date(),
    ): Int

    /**
     * S21a/S23 scoped mark-all-read (group). Same write semantics and count
     * contract as [markAllAsRead].
     */
    @Transaction
    @Query(
        """
        UPDATE article SET
            isUnread = :isUnread,
            readAt = CASE WHEN :isUnread = 1 THEN NULL ELSE :readAt END,
            isUpdated = CASE WHEN :isUnread = 1 THEN isUpdated ELSE 0 END,
            stateVersion = stateVersion + 1
        WHERE feedId IN (
            SELECT id FROM feed
            WHERE groupId = :groupId
        )
        AND accountId = :accountId
        AND isUnread != :isUnread
        AND date < :before
        """
    )
    suspend fun markAllAsReadByGroupId(
        accountId: Int,
        groupId: String,
        isUnread: Boolean,
        before: Date,
        readAt: Date? = if (isUnread) null else Date(),
    ): Int

    /**
     * S21a/S23 scoped mark-all-read (feed). Same write semantics and count
     * contract as [markAllAsRead].
     */
    @Transaction
    @Query(
        """
        UPDATE article SET
            isUnread = :isUnread,
            readAt = CASE WHEN :isUnread = 1 THEN NULL ELSE :readAt END,
            isUpdated = CASE WHEN :isUnread = 1 THEN isUpdated ELSE 0 END,
            stateVersion = stateVersion + 1
        WHERE feedId = :feedId
        AND accountId = :accountId
        AND isUnread != :isUnread
        AND date < :before
        """
    )
    suspend fun markAllAsReadByFeedId(
        accountId: Int,
        feedId: String,
        isUnread: Boolean,
        before: Date,
        readAt: Date? = if (isUnread) null else Date(),
    ): Int

    /**
     * S21a/S23 single-article read-state write, one transaction: mark-read sets
     * isUnread=0, readAt=now, isUpdated=0 and bumps stateVersion; mark-unread sets
     * isUnread=1 and clears readAt (web set_read parity), bumping stateVersion.
     */
    @Query(
        """
        UPDATE article SET
            isUnread = :isUnread,
            readAt = CASE WHEN :isUnread = 1 THEN NULL ELSE :readAt END,
            isUpdated = CASE WHEN :isUnread = 1 THEN isUpdated ELSE 0 END,
            stateVersion = stateVersion + 1
        WHERE id = :articleId
        AND accountId = :accountId
        """
    )
    suspend fun markAsReadByArticleId(
        accountId: Int,
        articleId: String,
        isUnread: Boolean,
        readAt: Date? = if (isUnread) null else Date(),
    ): Int

    @Query(
        """
        UPDATE article SET isStarred = :isStarred, stateVersion = stateVersion + 1
        WHERE id = :articleId
        AND accountId = :accountId
        """
    )
    suspend fun markAsStarredByArticleId(
        accountId: Int,
        articleId: String,
        isStarred: Boolean,
    )

    @Query(
        """
        DELETE FROM article
        WHERE accountId = :accountId
        AND feedId = :feedId
        AND (isStarred = :includeStarred OR :includeStarred = 1)
        """
    )
    suspend fun deleteByFeedId(accountId: Int, feedId: String, includeStarred: Boolean = false)

    @Query(
        """
        DELETE FROM article
        WHERE id IN (
            SELECT a.id FROM article AS a, feed AS b, `group` AS c
            WHERE a.accountId = :accountId
            AND a.feedId = b.id
            AND b.groupId = c.id
            AND c.id = :groupId
            AND (a.isStarred = :includeStarred OR :includeStarred = 1)
        )
        """
    )
    suspend fun deleteByGroupId(accountId: Int, groupId: String, includeStarred: Boolean = false)

    @Query(
        """
        DELETE FROM article
        WHERE accountId = :accountId
        """
    )
    suspend fun deleteByAccountId(accountId: Int)


    @Transaction
    @Query(
        """
        SELECT feedId, COUNT(*) AS important
        FROM article
        WHERE isUnread = :isUnread
        AND accountId = :accountId
        GROUP BY feedId
        """
    )
    fun queryImportantCountWhenIsUnread(
        accountId: Int,
        isUnread: Boolean,
    ): Flow<Map<@MapColumn("feedId") String, @MapColumn("important") Int>>

    @Transaction
    @Query(
        """
        SELECT feedId, COUNT(*) AS important
        FROM article
        WHERE isStarred = :isStarred
        AND accountId = :accountId
        GROUP BY feedId
        """
    )
    fun queryImportantCountWhenIsStarred(
        accountId: Int,
        isStarred: Boolean,
    ): Flow<Map<@MapColumn("feedId") String, @MapColumn("important") Int>>

    @Transaction
    @Query(
        """
        SELECT feedId, COUNT(*) AS important
        FROM article
        WHERE accountId = :accountId
        GROUP BY feedId
        """
    )
    fun queryImportantCountWhenIsAll(accountId: Int):
            Flow<Map<@MapColumn("feedId") String, @MapColumn("important") Int>>


    @Transaction
    @Query(
        """
        SELECT * FROM article 
        WHERE accountId = :accountId
        ORDER BY
            CASE WHEN :sortAscending = 1 THEN date END ASC,
            CASE WHEN :sortAscending = 0 THEN date END DESC
        """
    )
    fun queryArticleWithFeedWhenIsAll(
        accountId: Int, sortAscending: Boolean = false
    ): PagingSource<Int, ArticleWithFeed>

    @Transaction
    @Query(
        """
        SELECT * FROM article
        WHERE isStarred = :isStarred 
        AND accountId = :accountId
        ORDER BY
            CASE WHEN :sortAscending = 1 THEN date END ASC,
            CASE WHEN :sortAscending = 0 THEN date END DESC
        """
    )
    fun queryArticleWithFeedWhenIsStarred(
        accountId: Int, isStarred: Boolean, sortAscending: Boolean = false
    ): PagingSource<Int, ArticleWithFeed>

    @Transaction
    @Query(
        """
        SELECT * FROM article 
        WHERE isUnread = :isUnread 
        AND accountId = :accountId
        ORDER BY
            CASE WHEN :sortAscending = 1 THEN date END ASC,
            CASE WHEN :sortAscending = 0 THEN date END DESC
        """
    )
    fun queryArticleWithFeedWhenIsUnread(
        accountId: Int, isUnread: Boolean, sortAscending: Boolean = false
    ): PagingSource<Int, ArticleWithFeed>

    @Transaction
    @RewriteQueriesToDropUnusedColumns
    @Query(
        """
        SELECT a.id, a.date, a.title, a.author, a.rawDescription, 
        a.shortDescription, a.fullContent, a.img, a.link, a.feedId, 
        a.accountId, a.isUnread, a.isStarred, a.isReadLater, a.updateAt,
        a.guid, a.originalContent, a.contentChangedAt, a.isUpdated, a.readAt,
        a.extractedAt, a.publishedAt, a.stateVersion, a.titleLower, a.shortLower,
        a.rawLower, a.fullLower
        FROM article AS a
        LEFT JOIN feed AS b ON b.id = a.feedId
        LEFT JOIN `group` AS c ON c.id = b.groupId
        WHERE c.id = :groupId
        AND a.accountId = :accountId
        ORDER BY
            CASE WHEN :sortAscending = 1 THEN a.date END ASC,
            CASE WHEN :sortAscending = 0 THEN a.date END DESC
        """
    )
    fun queryArticleWithFeedByGroupIdWhenIsAll(
        accountId: Int, groupId: String, sortAscending: Boolean = false
    ): PagingSource<Int, ArticleWithFeed>

    @Transaction
    @RewriteQueriesToDropUnusedColumns
    @Query(
        """
        SELECT a.id, a.date, a.title, a.author, a.rawDescription, 
        a.shortDescription, a.fullContent, a.img, a.link, a.feedId, 
        a.accountId, a.isUnread, a.isStarred, a.isReadLater, a.updateAt,
        a.guid, a.originalContent, a.contentChangedAt, a.isUpdated, a.readAt,
        a.extractedAt, a.publishedAt, a.stateVersion, a.titleLower, a.shortLower,
        a.rawLower, a.fullLower
        FROM article AS a
        LEFT JOIN feed AS b ON b.id = a.feedId
        LEFT JOIN `group` AS c ON c.id = b.groupId
        WHERE c.id = :groupId
        AND a.isStarred = :isStarred
        AND a.accountId = :accountId
        ORDER BY
            CASE WHEN :sortAscending = 1 THEN a.date END ASC,
            CASE WHEN :sortAscending = 0 THEN a.date END DESC
        """
    )
    fun queryArticleWithFeedByGroupIdWhenIsStarred(
        accountId: Int, groupId: String, isStarred: Boolean, sortAscending: Boolean = false
    ): PagingSource<Int, ArticleWithFeed>

    @Transaction
    @RewriteQueriesToDropUnusedColumns
    @Query(
        """
        SELECT a.id, a.date, a.title, a.author, a.rawDescription, 
        a.shortDescription, a.fullContent, a.img, a.link, a.feedId, 
        a.accountId, a.isUnread, a.isStarred, a.isReadLater, a.updateAt,
        a.guid, a.originalContent, a.contentChangedAt, a.isUpdated, a.readAt,
        a.extractedAt, a.publishedAt, a.stateVersion, a.titleLower, a.shortLower,
        a.rawLower, a.fullLower
        FROM article AS a
        LEFT JOIN feed AS b ON b.id = a.feedId
        LEFT JOIN `group` AS c ON c.id = b.groupId
        WHERE c.id = :groupId
        AND a.isUnread = :isUnread
        AND a.accountId = :accountId
        ORDER BY
            CASE WHEN :sortAscending = 1 THEN a.date END ASC,
            CASE WHEN :sortAscending = 0 THEN a.date END DESC
        """
    )
    fun queryArticleWithFeedByGroupIdWhenIsUnread(
        accountId: Int, groupId: String, isUnread: Boolean, sortAscending: Boolean = false
    ): PagingSource<Int, ArticleWithFeed>

    @Transaction
    @Query(
        """
        SELECT * FROM article
        WHERE feedId = :feedId
        AND accountId = :accountId
        ORDER BY
            CASE WHEN :sortAscending = 1 THEN date END ASC,
            CASE WHEN :sortAscending = 0 THEN date END DESC
        """
    )
    fun queryArticleWithFeedByFeedIdWhenIsAll(
        accountId: Int, feedId: String, sortAscending: Boolean = false
    ): PagingSource<Int, ArticleWithFeed>

    @Transaction
    @Query(
        """
        SELECT * from article 
        WHERE feedId = :feedId 
        AND isStarred = :isStarred
        AND accountId = :accountId
        ORDER BY
            CASE WHEN :sortAscending = 1 THEN date END ASC,
            CASE WHEN :sortAscending = 0 THEN date END DESC
        """
    )
    fun queryArticleWithFeedByFeedIdWhenIsStarred(
        accountId: Int, feedId: String, isStarred: Boolean, sortAscending: Boolean = false
    ): PagingSource<Int, ArticleWithFeed>

    @Transaction
    @Query(
        """
        SELECT * FROM article 
        WHERE feedId = :feedId 
        AND isUnread = :isUnread
        AND accountId = :accountId
        ORDER BY
            CASE WHEN :sortAscending = 1 THEN date END ASC,
            CASE WHEN :sortAscending = 0 THEN date END DESC
        """
    )
    fun queryArticleWithFeedByFeedIdWhenIsUnread(
        accountId: Int, feedId: String, isUnread: Boolean, sortAscending: Boolean = false
    ): PagingSource<Int, ArticleWithFeed>


    @RewriteQueriesToDropUnusedColumns
    @Query(
        """
        SELECT a.id, a.date, a.title, a.author, a.rawDescription, 
        a.shortDescription, a.fullContent, a.img, a.link, a.feedId, 
        a.accountId, a.isUnread, a.isStarred, a.isReadLater, a.updateAt,
        a.guid, a.originalContent, a.contentChangedAt, a.isUpdated, a.readAt,
        a.extractedAt, a.publishedAt, a.stateVersion, a.titleLower, a.shortLower,
        a.rawLower, a.fullLower
        FROM article AS a LEFT JOIN feed AS b 
        ON a.feedId = b.id
        WHERE a.feedId = :feedId 
        AND a.accountId = :accountId
        ORDER BY date DESC LIMIT 1
        """
    )
    suspend fun queryLatestByFeedId(accountId: Int, feedId: String): Article?


    @Query(
        """
        SELECT * from article 
        WHERE link in (:linkList)
        AND feedId = :feedId
        AND accountId = :accountId
        """
    )
    suspend fun queryArticlesByLinks(
        linkList: List<String>,
        feedId: String,
        accountId: Int,
    ): List<Article>

    @Query(
        """
        SELECT a.*
        FROM article AS a
        LEFT JOIN feed AS f ON a.feedId = f.id
        WHERE f.accountId = :accountId
        AND f.isFullContent = 1
        AND a.isUnread = 1
        """
    )
    suspend fun queryUnreadFullContentArticles(
        accountId: Int,
    ): List<Article>

    @Transaction
    @Query(
        """
        SELECT * FROM article
        WHERE id = :id
        """
    )
    suspend fun queryById(id: String): ArticleWithFeed?


    @Transaction
    @Query(
        """
        SELECT id, isUnread, isStarred FROM article
        WHERE accountId = :accountId
        ORDER BY
            CASE WHEN :sortAscending = 1 THEN date END ASC,
            CASE WHEN :sortAscending = 0 THEN date END DESC
        """
    )
    fun queryMetadataAll(
        accountId: Int, sortAscending: Boolean = false
    ): List<ArticleMeta>

    @Transaction
    @Query(
        """
        SELECT id, isUnread, isStarred FROM article
        WHERE accountId = :accountId
        AND isUnread = :isUnread
        ORDER BY
            CASE WHEN :sortAscending = 1 THEN date END ASC,
            CASE WHEN :sortAscending = 0 THEN date END DESC
        """
    )
    fun queryMetadataAll(
        accountId: Int, isUnread: Boolean, sortAscending: Boolean = false
    ): List<ArticleMeta>

    @Transaction
    @Query(
        """
        SELECT id, isUnread, isStarred FROM article
        WHERE accountId = :accountId
        AND date < :before
        ORDER BY
            CASE WHEN :sortAscending = 1 THEN date END ASC,
            CASE WHEN :sortAscending = 0 THEN date END DESC
        """
    )
    fun queryMetadataAll(
        accountId: Int, before: Date, sortAscending: Boolean = false
    ): List<ArticleMeta>

    @Transaction
    @Query(
        """
        SELECT id, isUnread, isStarred FROM article
        WHERE accountId = :accountId
        AND isUnread = :isUnread
        AND date < :before
        ORDER BY
            CASE WHEN :sortAscending = 1 THEN date END ASC,
            CASE WHEN :sortAscending = 0 THEN date END DESC
        """
    )
    fun queryMetadataAll(
        accountId: Int, isUnread: Boolean, before: Date, sortAscending: Boolean = false
    ): List<ArticleMeta>

    @Transaction
    @Query(
        """
        SELECT id, isUnread, isStarred FROM article
        WHERE accountId = :accountId
        AND feedId = :feedId
        AND isUnread = :isUnread
        ORDER BY
            CASE WHEN :sortAscending = 1 THEN date END ASC,
            CASE WHEN :sortAscending = 0 THEN date END DESC
        """
    )
    fun queryMetadataByFeedId(
        accountId: Int, feedId: String, isUnread: Boolean, sortAscending: Boolean = false
    ): List<ArticleMeta>

    @Transaction
    @Query(
        """
        SELECT id, isUnread, isStarred FROM article
        WHERE accountId = :accountId
        AND feedId = :feedId
        AND isUnread = :isUnread
        AND date < :before
        ORDER BY
            CASE WHEN :sortAscending = 1 THEN date END ASC,
            CASE WHEN :sortAscending = 0 THEN date END DESC
        """
    )
    fun queryMetadataByFeedId(
        accountId: Int,
        feedId: String,
        isUnread: Boolean,
        before: Date,
        sortAscending: Boolean = false
    ): List<ArticleMeta>

    @Transaction
    @Query(
        """
        SELECT a.id, a.isUnread, a.isStarred 
        FROM article AS a
        LEFT JOIN feed AS b ON b.id = a.feedId
        LEFT JOIN `group` AS c ON c.id = b.groupId
        WHERE c.id = :groupId
        AND a.accountId = :accountId
        AND a.isUnread = :isUnread
        ORDER BY
            CASE WHEN :sortAscending = 1 THEN a.date END ASC,
            CASE WHEN :sortAscending = 0 THEN a.date END DESC
        """
    )
    fun queryMetadataByGroupIdWhenIsUnread(
        accountId: Int, groupId: String, isUnread: Boolean, sortAscending: Boolean = false
    ): List<ArticleMeta>

    @Transaction
    @Query(
        """
        SELECT a.id, a.isUnread, a.isStarred 
        FROM article AS a
        LEFT JOIN feed AS b ON b.id = a.feedId
        LEFT JOIN `group` AS c ON c.id = b.groupId
        WHERE c.id = :groupId
        AND a.accountId = :accountId
        AND a.isUnread = :isUnread
        AND a.date < :before
        ORDER BY
            CASE WHEN :sortAscending = 1 THEN a.date END ASC,
            CASE WHEN :sortAscending = 0 THEN a.date END DESC
        """
    )
    fun queryMetadataByGroupIdWhenIsUnread(
        accountId: Int,
        groupId: String,
        isUnread: Boolean,
        before: Date,
        sortAscending: Boolean = false
    ): List<ArticleMeta>


    /**
     * query the latest unread articles from account with id, limit count
     */
    @Transaction
    @Query(
        """
        SELECT * FROM article
        WHERE accountId = :accountId
        AND isUnread = 1
        ORDER BY date desc
        LIMIT :limit
        """
    )
    fun queryLatestUnreadArticles(accountId: Int, limit: Int = 15): Flow<List<ArticleWithFeed>>


    /**
     * query the latest unread articles from feed with id, limit count
     */
    @Transaction
    @Query(
        """
        SELECT * FROM article
        WHERE feedId = :feedId
        AND isUnread = 1
        ORDER BY date desc
        LIMIT :limit
        """
    )
    fun queryLatestUnreadArticlesFromFeed(feedId: String, limit: Int = 15): Flow<List<ArticleWithFeed>>


    /**
     * query the latest unread articles from group with id, limit count
     */
    @Transaction
    @Query(
        """
        SELECT a.* FROM article AS a
        LEFT JOIN feed AS f ON a.feedId = f.id
        WHERE f.groupId = :groupId
        AND a.isUnread = 1
        ORDER BY a.date DESC
        LIMIT :limit
        """
    )
    fun queryLatestUnreadArticlesFromGroup(groupId: String, limit: Int = 15): Flow<List<ArticleWithFeed>>


    /**
     * query the latest unread articles from account with id, limit count
     */
    @Transaction
    @Query(
        """
        SELECT * FROM article
        WHERE accountId = :accountId
        AND isUnread = 1
        ORDER BY date desc
        LIMIT :limit
        """
    )
    fun queryLatestUnreadArticleFlow(accountId: Int, limit: Int): Flow<List<ArticleWithFeed>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInternal(vararg article: Article)

    /** S11a: source-text writers refresh the shadow columns in the same write. */
    @Transaction
    suspend fun insert(vararg article: Article) {
        article.forEach { it.refreshShadows() }
        insertInternal(*article)
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOnConflictIgnoreInternal(vararg article: Article)

    @Transaction
    suspend fun insertOnConflictIgnore(vararg article: Article) {
        article.forEach { it.refreshShadows() }
        insertOnConflictIgnoreInternal(*article)
    }

    @Insert
    suspend fun insertListInternal(articles: List<Article>)

    @Transaction
    suspend fun insertList(articles: List<Article>) {
        articles.forEach { it.refreshShadows() }
        insertListInternal(articles)
    }

    @Delete
    suspend fun delete(vararg article: Article)

    @Update
    suspend fun updateInternal(vararg article: Article)

    @Transaction
    suspend fun update(vararg article: Article) {
        article.forEach { it.refreshShadows() }
        updateInternal(*article)
    }

    @Transaction
    suspend fun insertListIfNotExist(articles: List<Article>, feed: Feed): List<Article> {
        if (articles.isEmpty()) return articles

        val existingArticles = queryArticlesByLinks(
            linkList = articles.map { it.link },
            feedId = feed.id,
            accountId = feed.accountId
        ).associateBy { it.link }

        return articles.filterNot { existingArticles.containsKey(it.link) }.also { insertList(it) }
    }

    @Query(
        """
        SELECT * FROM article
        WHERE feedId = :feedId
        AND accountId = :accountId
        AND (
            (guid != '' AND guid IN (:guids))
            OR (link != '' AND link IN (:links))
        )
        """
    )
    suspend fun queryByFeedIdGuidsOrLinks(
        feedId: String,
        accountId: Int,
        guids: List<String>,
        links: List<String>,
    ): List<Article>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertListIgnoreConflictInternal(articles: List<Article>): List<Long>

    @Transaction
    suspend fun insertListIgnoreConflict(articles: List<Article>): List<Long> {
        articles.forEach { it.refreshShadows() }
        return insertListIgnoreConflictInternal(articles)
    }

    /**
     * S7 dedupe on ingest: an incoming article matches an existing row when
     * feedId matches AND (guid non-empty AND equals existing guid) OR (link
     * non-empty AND equals existing link). Matches take the update path in
     * later waves (S20a); here they are skipped exactly like the upstream
     * link-only path, keeping this wave's sync semantics byte-identical.
     * INSERT uses IGNORE on the PK as the last-resort race guard for two
     * workers computing the same deterministic S6 id.
     */
    @Transaction
    suspend fun insertListIfNotExistByIdentity(articles: List<Article>, feed: Feed): List<Article> {
        if (articles.isEmpty()) return articles

        val existingArticles = queryByFeedIdGuidsOrLinks(
            feedId = feed.id,
            accountId = feed.accountId,
            guids = articles.map { it.guid },
            links = articles.map { it.link },
        )

        val fresh = articles.filterNot { incoming ->
            existingArticles.any { existing ->
                (incoming.guid.isNotEmpty() && existing.guid.isNotEmpty() && existing.guid == incoming.guid) ||
                    (incoming.link.isNotEmpty() && existing.link.isNotEmpty() && existing.link == incoming.link)
            }
        }
        insertListIgnoreConflict(fresh)
        return fresh
    }

    /**
     * S11a single-term search over the S12a `*Lower` shadow columns with
     * ESCAPE — the reference LIKE path for the canonical predicate (FTS
     * cannot do mid-word substrings; body-only terms must work). Callers
     * prep `:text` with `SearchSql.likeTerm(TextNormalizer.normalize(term))`.
     * Ordering per S15 default: date DESC, rowid DESC.
     */
    @Transaction
    @RewriteQueriesToDropUnusedColumns
    @Query(
        """
        SELECT * FROM article
        WHERE accountId = :accountId
        AND (
            titleLower LIKE '%' || :text || '%' ESCAPE '\'
            OR shortLower LIKE '%' || :text || '%' ESCAPE '\'
            OR rawLower LIKE '%' || :text || '%' ESCAPE '\'
            OR fullLower LIKE '%' || :text || '%' ESCAPE '\'
        )
        ORDER BY date DESC, rowid DESC
        """
    )
    fun searchArticleByShadowText(
        accountId: Int,
        text: String,
    ): PagingSource<Int, ArticleWithFeed>

    /**
     * `tag:` CSV containment (S11a): matches when the normalized tag is a
     * whole CSV element of feed.tags — `tech` must NOT match `biotech`.
     */
    @Query(
        """
        SELECT * FROM article
        WHERE accountId = :accountId
        AND EXISTS (
            SELECT 1 FROM feed AS f2
            WHERE f2.id = article.feedId
            AND (',' || f2.tags || ',' LIKE '%,' || :tag || ',%')
        )
        """
    )
    suspend fun queryArticlesByTag(accountId: Int, tag: String): List<Article>

    /**
     * S15 — recentlyRead view: `readAt IS NOT NULL` ordered `readAt DESC, rowid
     * DESC`; mutes NOT applied (exact web matrix). Scoped variants below follow
     * the same ordering.
     */
    @Transaction
    @Query(
        """
        SELECT * FROM article
        WHERE accountId = :accountId
        AND readAt IS NOT NULL
        ORDER BY readAt DESC, rowid DESC
        """
    )
    fun queryArticleWithFeedWhenIsRecentlyRead(
        accountId: Int,
    ): PagingSource<Int, ArticleWithFeed>

    @Transaction
    @Query(
        """
        SELECT * FROM article
        WHERE accountId = :accountId
        AND feedId = :feedId
        AND readAt IS NOT NULL
        ORDER BY readAt DESC, rowid DESC
        """
    )
    fun queryArticleWithFeedByFeedIdWhenIsRecentlyRead(
        accountId: Int,
        feedId: String,
    ): PagingSource<Int, ArticleWithFeed>

    @Transaction
    @Query(
        """
        SELECT * FROM article AS a
        LEFT JOIN feed AS b ON b.id = a.feedId
        LEFT JOIN `group` AS c ON c.id = b.groupId
        WHERE c.id = :groupId
        AND a.accountId = :accountId
        AND a.readAt IS NOT NULL
        ORDER BY a.readAt DESC, a.rowid DESC
        """
    )
    fun queryArticleWithFeedByGroupIdWhenIsRecentlyRead(
        accountId: Int,
        groupId: String,
    ): PagingSource<Int, ArticleWithFeed>

    /**
     * S25a — exact unread count for the scoped mark-all-read confirmation
     * (web mark_all_read parity: the dialog reports the count before applying).
     * [feedId]/[groupId] are nullable; null means unscoped at that dimension.
     */
    @Query(
        """
        SELECT count(1) FROM article
        WHERE accountId = :accountId
        AND isUnread = 1
        AND date < :before
        AND (:feedId IS NULL OR feedId = :feedId)
        AND (:groupId IS NULL OR feedId IN (SELECT id FROM feed WHERE groupId = :groupId))
        """
    )
    suspend fun countUnreadInScope(
        accountId: Int,
        feedId: String?,
        groupId: String?,
        before: Date,
    ): Int

    /**
     * S25a — list GENERATION snapshot: the ordered (id, sortKey, rowid) rows of a
     * scope at generation roll. `sortKey` is `date` for every view except
     * `recentlyRead`, which orders by `readAt` (S15). Ordering: default
     * `date DESC, rowid DESC`; `sortAscending` flips both keys; recentlyRead is
     * always `readAt DESC, rowid DESC` (web parity: sort:asc does not affect the
     * recently_read view). Search-scoped generations are Wave D (canonical
     * grammar); Wave C rolls generations for the plain view/feed/group scopes.
     */
    @Query(
        """
        SELECT id, date AS sortKey, rowid AS rowId FROM article
        WHERE accountId = :accountId
        AND (:feedId IS NULL OR feedId = :feedId)
        AND (:groupId IS NULL OR feedId IN (SELECT id FROM feed WHERE groupId = :groupId))
        AND (
            :view = 'all'
            OR (:view = 'unread' AND isUnread = 1)
            OR (:view = 'starred' AND isStarred = 1)
            OR (:view = 'recentlyRead' AND readAt IS NOT NULL)
        )
        ORDER BY
            CASE WHEN :view = 'recentlyRead' THEN readAt END DESC,
            CASE WHEN :view = 'recentlyRead' THEN rowid END DESC,
            CASE WHEN :view != 'recentlyRead' AND :sortAscending = 1 THEN date END ASC,
            CASE WHEN :view != 'recentlyRead' AND :sortAscending = 0 THEN date END DESC,
            CASE WHEN :view != 'recentlyRead' AND :sortAscending = 1 THEN rowid END ASC,
            CASE WHEN :view != 'recentlyRead' AND :sortAscending = 0 THEN rowid END DESC
        """
    )
    suspend fun queryScopeRows(
        accountId: Int,
        view: String,
        feedId: String?,
        groupId: String?,
        sortAscending: Boolean,
    ): List<ScopeRow>

    /** S25a — the (sortKey, rowid) position of one article, for the neighbor fallback. */
    @Query(
        """
        SELECT id, date AS sortKey, rowid AS rowId FROM article
        WHERE id = :articleId
        """
    )
    suspend fun querySortPosition(articleId: String): ScopeRow?

    /**
     * S25a — previous row in scope order when the current article has left the
     * predicate (port of web db.py:678 row-value fallback). Default DESC order:
     * the closest row with `(date, rowid) > (:sortKey, :rowId)`; ASC order: the
     * closest row with `<`.
     */
    @Query(
        """
        SELECT id FROM article
        WHERE accountId = :accountId
        AND (:feedId IS NULL OR feedId = :feedId)
        AND (:groupId IS NULL OR feedId IN (SELECT id FROM feed WHERE groupId = :groupId))
        AND (
            :view = 'all'
            OR (:view = 'unread' AND isUnread = 1)
            OR (:view = 'starred' AND isStarred = 1)
        )
        AND (
            (:sortAscending = 0 AND (date, rowid) > (:sortKey, :rowId))
            OR (:sortAscending = 1 AND (date, rowid) < (:sortKey, :rowId))
        )
        ORDER BY
            CASE WHEN :sortAscending = 1 THEN date END DESC,
            CASE WHEN :sortAscending = 0 THEN date END ASC,
            CASE WHEN :sortAscending = 1 THEN rowid END DESC,
            CASE WHEN :sortAscending = 0 THEN rowid END ASC
        LIMIT 1
        """
    )
    suspend fun queryScopeNeighborPrev(
        accountId: Int,
        view: String,
        feedId: String?,
        groupId: String?,
        sortAscending: Boolean,
        sortKey: Long,
        rowId: Long,
    ): String?

    /** S25a — next row in scope order; complement of [queryScopeNeighborPrev]. */
    @Query(
        """
        SELECT id FROM article
        WHERE accountId = :accountId
        AND (:feedId IS NULL OR feedId = :feedId)
        AND (:groupId IS NULL OR feedId IN (SELECT id FROM feed WHERE groupId = :groupId))
        AND (
            :view = 'all'
            OR (:view = 'unread' AND isUnread = 1)
            OR (:view = 'starred' AND isStarred = 1)
        )
        AND (
            (:sortAscending = 0 AND (date, rowid) < (:sortKey, :rowId))
            OR (:sortAscending = 1 AND (date, rowid) > (:sortKey, :rowId))
        )
        ORDER BY
            CASE WHEN :sortAscending = 1 THEN date END ASC,
            CASE WHEN :sortAscending = 0 THEN date END DESC,
            CASE WHEN :sortAscending = 1 THEN rowid END ASC,
            CASE WHEN :sortAscending = 0 THEN rowid END DESC
        LIMIT 1
        """
    )
    suspend fun queryScopeNeighborNext(
        accountId: Int,
        view: String,
        feedId: String?,
        groupId: String?,
        sortAscending: Boolean,
        sortKey: Long,
        rowId: Long,
    ): String?

    /**
     * S25a generation paging — loads one page of a generation by id list; the
     * caller restores generation order (DAO row order is undefined for IN).
     */
    @Transaction
    @Query(
        """
        SELECT * FROM article
        WHERE id IN (:ids)
        """
    )
    suspend fun queryArticleWithFeedByIds(ids: List<String>): List<ArticleWithFeed>
}

/** S25a — one ordered row of a scope snapshot (see [ArticleDao.queryScopeRows]). */
data class ScopeRow(
    @ColumnInfo(name = "id") val articleId: String,
    @ColumnInfo(name = "sortKey") val sortKey: Long,
    @ColumnInfo(name = "rowId") val rowId: Long,
)