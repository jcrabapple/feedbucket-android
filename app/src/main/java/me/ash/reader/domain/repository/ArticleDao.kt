package me.ash.reader.domain.repository

import androidx.paging.PagingSource
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.MapColumn
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.RewriteQueriesToDropUnusedColumns
import androidx.room.Transaction
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery
import java.util.Date
import kotlinx.coroutines.flow.Flow
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.ArticleMeta
import me.ash.reader.domain.model.article.ArticleWithFeed
import me.ash.reader.domain.model.article.UpdateDetection
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.feed.ImportantNum
import me.ash.reader.domain.model.query.QueryMatcher
import me.ash.reader.domain.model.query.SearchSql
import me.ash.reader.domain.model.query.TextNormalizer
import me.ash.reader.domain.model.rule.ActionRule
import me.ash.reader.domain.model.rule.ArticleApplyState
import me.ash.reader.domain.service.RuleEngine

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
     * (S25a confirmation parity with web mark_all_read). The predicate also
     * drains ALREADY-READ updated rows (isUnread=0, isUpdated=1): web
     * mark_all_read deletes from BOTH the unread and updated tables for
     * every entry in the listing scope (db.py:1002-1026) — review round 1
     * finding 2. The mark-UNREAD direction is unchanged (the isUpdated
     * clause requires :isUnread = 0).
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
        AND (isUnread != :isUnread OR (isUpdated != 0 AND :isUnread = 0))
        """
    )
    suspend fun markAllAsRead(
        accountId: Int,
        isUnread: Boolean,
        before: Date,
        readAt: Date? = if (isUnread) null else Date(),
    ): Int

    /**
     * S21a/S23 scoped mark-all-read (group). Same write semantics, drain,
     * and count contract as [markAllAsRead].
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
        AND (isUnread != :isUnread OR (isUpdated != 0 AND :isUnread = 0))
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
     * S21a/S23 scoped mark-all-read (feed). Same write semantics, drain,
     * and count contract as [markAllAsRead].
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
        AND (isUnread != :isUnread OR (isUpdated != 0 AND :isUnread = 0))
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

    /** S29 — ids matching deleteByFeedId, for download file cascade. */
    @Query(
        """
        SELECT id FROM article
        WHERE accountId = :accountId
        AND feedId = :feedId
        AND (isStarred = :includeStarred OR :includeStarred = 1)
        """
    )
    suspend fun queryIdsByFeedId(accountId: Int, feedId: String, includeStarred: Boolean): List<String>

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

    /** S29 — ids matching deleteByGroupId, for download file cascade. */
    @Query(
        """
        SELECT id FROM article
        WHERE accountId = :accountId
        AND feedId IN (SELECT id FROM feed WHERE groupId = :groupId)
        AND (isStarred = :includeStarred OR :includeStarred = 1)
        """
    )
    suspend fun queryIdsByGroupId(accountId: Int, groupId: String, includeStarred: Boolean): List<String>

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
        AND a.fullContent IS NULL
        """
    )
    suspend fun queryUnreadFullContentArticles(
        accountId: Int,
    ): List<Article>

    /**
     * E4 (SPEC-EXTRACT-v1) — the ONE extraction write path: a single UPDATE
     * setting EXACTLY `fullContent` + its S12a shadow `fullLower` (computed
     * by the CALLER via `TextNormalizer.normalize(fullContent)`, S11a
     * write-path invariant) + `extractedAt`. Never touches
     * rawDescription/shortDescription/originalContent/contentChangedAt/
     * isUpdated/isUnread/isStarred/readAt/img/publishedAt/date (S19) and
     * does NOT bump `stateVersion` (the S18a choke point covers read/star/
     * update state writers only; extraction is a content write). Room's
     * `room_fts_content_sync_*` triggers carry the change into `article_fts`
     * (S16 — extracted text becomes searchable). Returns rows updated.
     */
    @Query(
        """
        UPDATE article SET
            fullContent = :fullContent, fullLower = :fullLower, extractedAt = :extractedAt
        WHERE id = :id
        """
    )
    suspend fun applyExtraction(
        id: String,
        fullContent: String,
        fullLower: String,
        extractedAt: Date,
    ): Int

    /**
     * E7 (SPEC-EXTRACT-v1) — median recompute samples: the feed's most
     * recent articles' `rawDescription`, non-empty rows only, `ORDER BY
     * rowid DESC LIMIT :limit` (E7's recompute cap is 100 via the feedId
     * index; the cap bounds cost inside a mobile sync transaction). The
     * caller (`MedianBodyLength.recompute`, X3) computes
     * `plainTextLength` per row, drops zeros and takes the upper median.
     */
    @Query(
        """
        SELECT rawDescription FROM article
        WHERE feedId = :feedId
        AND rawDescription IS NOT NULL
        AND rawDescription != ''
        ORDER BY rowid DESC
        LIMIT :limit
        """
    )
    suspend fun queryRecentRawDescriptionsForMedian(
        feedId: String,
        limit: Int,
    ): List<String>

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

    /** S32a step 7 — provenance removal deletes the article row by id. */
    @Query("DELETE FROM article WHERE id = :id")
    suspend fun deleteById(id: String)

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

    /** S32a step 7 — cascade guard: article ids of one feed (removal walk). */
    @Query("SELECT id FROM article WHERE feedId = :feedId")
    suspend fun queryIdsByFeed(feedId: String): List<String>

    @Transaction
    suspend fun insertListIgnoreConflict(articles: List<Article>): List<Long> {
        articles.forEach { it.refreshShadows() }
        return insertListIgnoreConflictInternal(articles)
    }

    /**
     * S20a/S17a — the full web `store_entries` state machine
     * (db.py:476-581), replacing skip-on-identity ingest. NEW articles
     * insert with S17a standard-rule effects (mark_read suppresses unread;
     * star budget 100/run). MATCHED rows run [UpdateDetection] (S20a):
     * compare keys on `publishedAt` (NEVER `date`, R7), the first-version
     * snapshot gate (showUpdates × !extracted × recency × significance),
     * extracted rows update ONLY title/link/author/publishedAt(+date),
     * web rows also content fields + `*Lower` shadows,
     * `isUpdated`/`contentChangedAt` only when the feed shows updates,
     * `updateAt` on every changed row — then S17a update effects
     * (mark_read clears isUnread AND isUpdated; star is skipped). One
     * transaction per feed (web store_entries parity).
     *
     * Returns the newly inserted articles (notification parity with the
     * former insertListIfNotExist* contract).
     */
    @Transaction
    suspend fun storeEntries(
        articles: List<Article>,
        feed: Feed,
        feedTags: List<String>,
        groupName: String?,
        rules: List<ActionRule>,
        now: Date,
    ): List<Article> {
        if (articles.isEmpty()) return articles

        val existingArticles = queryByFeedIdGuidsOrLinks(
            feedId = feed.id,
            accountId = feed.accountId,
            guids = articles.map { it.guid },
            links = articles.map { it.link },
        )

        // S7 partition: identity match → S20a update path; no match → insert.
        val matched = mutableListOf<Pair<Article, Article>>()
        val fresh = mutableListOf<Article>()
        for (incoming in articles) {
            val existing = existingArticles.firstOrNull { identityMatches(it, incoming) }
            if (existing != null) matched += incoming to existing else fresh += incoming
        }

        // S17a new-article path (unchanged behavior; star budget per run).
        val withEffects = RuleEngine.applyToNewArticles(fresh, feedTags, groupName, rules)
        insertListIgnoreConflict(withEffects.articles)

        val standardRules = rules.filter { it.type == ActionRule.TYPE_STANDARD }
        for ((incoming, existing) in matched) {
            when (
                val decision =
                    UpdateDetection.decide(existing, incoming, feed.showUpdates, now)
            ) {
                UpdateDetection.Decision.Unchanged -> Unit
                is UpdateDetection.Decision.Update -> {
                    val plan = decision.plan
                    // Snapshot FIRST (web order): captures the OLD content.
                    if (plan.snapshotWrite) {
                        writeUpdateSnapshot(existing.id, existing.rawDescription)
                    }
                    val extracted = existing.fullContent != null
                    val titleLower = TextNormalizer.normalize(plan.title)
                    if (extracted) {
                        applyExtractedUpdate(
                            id = existing.id,
                            title = plan.title,
                            titleLower = titleLower,
                            link = plan.link,
                            author = plan.author,
                            publishedAt = plan.publishedAt,
                            date = plan.date,
                            updateAt = plan.updateAt,
                            markUpdated = plan.markUpdated,
                            contentChangedAt = plan.contentChangedAt,
                        )
                    } else {
                        applyWebUpdate(
                            id = existing.id,
                            title = plan.title,
                            titleLower = titleLower,
                            link = plan.link,
                            author = plan.author,
                            rawDescription = plan.rawDescription,
                            rawLower = TextNormalizer.normalize(plan.rawDescription),
                            shortDescription = plan.shortDescription,
                            shortLower = TextNormalizer.normalize(plan.shortDescription),
                            img = plan.img,
                            publishedAt = plan.publishedAt,
                            date = plan.date,
                            updateAt = plan.updateAt,
                            markUpdated = plan.markUpdated,
                            contentChangedAt = plan.contentChangedAt,
                        )
                    }
                    // S17a update effects: matching mark_read clears
                    // isUnread AND isUpdated (web set_read on update);
                    // star is NEVER applied on updates. Web matches over
                    // OLD content for extracted rows, NEW otherwise.
                    if (standardRules.isNotEmpty() &&
                        RuleEngine.shouldMarkReadOnUpdate(
                            QueryMatcher.Fields(
                                title = plan.title,
                                shortDescription =
                                    if (extracted) existing.shortDescription
                                    else plan.shortDescription,
                                rawDescription =
                                    if (extracted) existing.rawDescription
                                    else plan.rawDescription,
                                fullContent = existing.fullContent,
                            ),
                            feedTags,
                            groupName,
                            standardRules,
                        )
                    ) {
                        applyUpdateMarkRead(existing.id, now)
                    }
                }
            }
        }
        return withEffects.articles
    }

    /** S20a snapshot write: first version only (gate evaluated by caller). */
    @Query("UPDATE article SET originalContent = :originalContent WHERE id = :id")
    suspend fun writeUpdateSnapshot(id: String, originalContent: String): Int

    /**
     * S20a extracted-row update: ONLY title/link/author/publishedAt(+date)
     * change — feed-provided content never clobbers the extraction.
     * `isUpdated`/`contentChangedAt`/`stateVersion` move only when the
     * feed shows updates (S18a choke point).
     */
    @Query(
        """
        UPDATE article SET
            title = :title, titleLower = :titleLower, link = :link, author = :author,
            publishedAt = :publishedAt, date = :date, updateAt = :updateAt,
            isUpdated = CASE WHEN :markUpdated THEN 1 ELSE isUpdated END,
            contentChangedAt = CASE WHEN :markUpdated THEN :contentChangedAt ELSE contentChangedAt END,
            stateVersion = CASE WHEN :markUpdated THEN stateVersion + 1 ELSE stateVersion END
        WHERE id = :id
        """
    )
    suspend fun applyExtractedUpdate(
        id: String,
        title: String,
        titleLower: String,
        link: String,
        author: String?,
        publishedAt: Date?,
        date: Date,
        updateAt: Date,
        markUpdated: Boolean,
        contentChangedAt: Date?,
    ): Int

    /** S20a web-row update: content fields + S12a shadow refresh in one write. */
    @Query(
        """
        UPDATE article SET
            title = :title, titleLower = :titleLower, link = :link, author = :author,
            rawDescription = :rawDescription, rawLower = :rawLower,
            shortDescription = :shortDescription, shortLower = :shortLower,
            img = :img,
            publishedAt = :publishedAt, date = :date, updateAt = :updateAt,
            isUpdated = CASE WHEN :markUpdated THEN 1 ELSE isUpdated END,
            contentChangedAt = CASE WHEN :markUpdated THEN :contentChangedAt ELSE contentChangedAt END,
            stateVersion = CASE WHEN :markUpdated THEN stateVersion + 1 ELSE stateVersion END
        WHERE id = :id
        """
    )
    suspend fun applyWebUpdate(
        id: String,
        title: String,
        titleLower: String,
        link: String,
        author: String?,
        rawDescription: String,
        rawLower: String,
        shortDescription: String,
        shortLower: String,
        img: String?,
        publishedAt: Date?,
        date: Date,
        updateAt: Date,
        markUpdated: Boolean,
        contentChangedAt: Date?,
    ): Int

    /**
     * S17a update-path mark_read effect: clears isUnread AND isUpdated and
     * stamps readAt (web set_read on update; star skipped by the caller
     * never applying it). stateVersion bump per the S18a choke point.
     */
    @Query(
        """
        UPDATE article SET
            isUnread = 0, isUpdated = 0, readAt = :readAt,
            stateVersion = stateVersion + 1
        WHERE id = :id
        """
    )
    suspend fun applyUpdateMarkRead(id: String, readAt: Date): Int

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
     *
     * Review lane 3 defect 4: the internal query declares `ESCAPE '\'` and
     * this wrapper LIKE-escapes the tag, so `_`/`%` inside tag names match
     * literally instead of acting as wildcards (SearchSql.renderTag parity).
     */
    suspend fun queryArticlesByTag(accountId: Int, tag: String): List<Article> =
        queryArticlesByTagInternal(accountId, SearchSql.escapeLike(tag))

    @Query(
        """
        SELECT * FROM article
        WHERE accountId = :accountId
        AND EXISTS (
            SELECT 1 FROM feed AS f2
            WHERE f2.id = article.feedId
            AND (',' || f2.tags || ',' LIKE '%,' || :tag || ',%' ESCAPE '\')
        )
        """
    )
    suspend fun queryArticlesByTagInternal(accountId: Int, tag: String): List<Article>

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
     * recently_read view). The production path is [queryScopeRowsRaw] (Wave D
     * dynamic scope SQL over ScopeSql); the legacy static query is gone.
     */
    /** S25a — the (sortKey, rowid) position of one article, for the neighbor fallback. */
    @Query(
        """
        SELECT id, date AS sortKey, rowid AS rowId FROM article
        WHERE id = :articleId
        """
    )
    suspend fun querySortPosition(articleId: String): ScopeRow?

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

    /**
     * S15/S25a — raw scope snapshot over ScopeSql (Wave D): one code path
     * for every view, adding canonical S10 query terms and mute NOT-clauses
     * (unread/updated only) that static `@Query` cannot express. For empty
     * query/mute inputs the builder reproduces the legacy static SQL.
     */
    @RawQuery(observedEntities = [Article::class, Feed::class])
    suspend fun queryScopeRowsRaw(query: SupportSQLiteQuery): List<ScopeRow>

    /** S25a — raw neighbor fallback (see [queryScopeRowsRaw]). */
    @RawQuery(observedEntities = [Article::class, Feed::class])
    suspend fun queryScopeNeighborPrevRaw(query: SupportSQLiteQuery): String?

    /** S25a — raw neighbor fallback (see [queryScopeRowsRaw]). */
    @RawQuery(observedEntities = [Article::class, Feed::class])
    suspend fun queryScopeNeighborNextRaw(query: SupportSQLiteQuery): String?

    /** S18a rule preview samples: `(articleId, title, feed name, date)`. */
    @RawQuery(observedEntities = [Article::class, Feed::class])
    suspend fun queryRuleMatchPreviewRaw(query: SupportSQLiteQuery): List<RulePreviewRow>

    /** S18a rule preview total match count. */
    @RawQuery(observedEntities = [Article::class])
    suspend fun queryRuleMatchCountRaw(query: SupportSQLiteQuery): Int

    /** S18a apply-to-existing: matched before-states for the ledger. */
    @RawQuery(observedEntities = [Article::class])
    suspend fun queryArticleApplyStatesRaw(query: SupportSQLiteQuery): List<ArticleApplyState>

    /**
     * S18a apply-to-existing effect writer: ONE conditional UPDATE per
     * touched article. [expectedVersion] MUST be the article's CURRENT
     * (pre-write) stateVersion as read by the caller — the WHERE clause
     * matches it and the SET bumps by one, so a concurrent change turns
     * the write into a no-op (returns 0) instead of clobbering. On success
     * the row lands exactly on the ledger's `stateVersionAtApply`
     * (pre+1), which the undo restore then matches. `mark_read` clears
     * isUnread+isUpdated and sets readAt; `star` sets isStarred.
     */
    @Query(
        """
        UPDATE article SET
            isUnread = CASE WHEN :markRead THEN 0 ELSE isUnread END,
            isUpdated = CASE WHEN :markRead THEN 0 ELSE isUpdated END,
            readAt = CASE WHEN :markRead THEN :readAt ELSE readAt END,
            isStarred = CASE WHEN :star THEN 1 ELSE isStarred END,
            stateVersion = stateVersion + 1
        WHERE id = :id AND stateVersion = :expectedVersion
        """
    )
    suspend fun applyRuleEffectsById(
        id: String,
        markRead: Boolean,
        star: Boolean,
        readAt: Date,
        expectedVersion: Long,
    ): Int

    /**
     * S18a undo restore: writes the exact before-state ONLY when the
     * current version still equals `stateVersionAtApply`; returns rows
     * written (0 = changed since apply → skipped+counted). NEVER clears
     * pre-existing stars (`wasStarred=1` restores to starred).
     */
    @Query(
        """
        UPDATE article SET isUnread = :isUnread, isStarred = :isStarred,
            isUpdated = :isUpdated, readAt = :readAt, stateVersion = stateVersion + 1
        WHERE id = :id AND stateVersion = :expectedVersion
        """
    )
    suspend fun restoreStateByIdIfVersion(
        id: String,
        isUnread: Boolean,
        isStarred: Boolean,
        isUpdated: Boolean,
        readAt: Date?,
        expectedVersion: Long,
    ): Int

    // --- S16/S28 — FTS coverage (account-scoped) & bulk download source -----

    /** S16 — M = COUNT(*) FROM article WHERE accountId = :id. */
    @Query("SELECT COUNT(*) FROM article WHERE accountId = :accountId")
    fun countAccountArticlesFlow(accountId: Int): Flow<Int>

    /**
     * S16 — N = COUNT(article_fts ⋈ article ON article.rowid = article_fts.docid
     * WHERE accountId = :id). The FTS docid space spans all accounts, so the
     * join is required for an honest per-account coverage number (R4).
     */
    @Query(
        """
        SELECT COUNT(*) FROM article
        INNER JOIN article_fts ON article.rowid = article_fts.docid
        WHERE article.accountId = :accountId
        """
    )
    fun countFtsCoveredArticlesFlow(accountId: Int): Flow<Int>

    /** S28 — bulk "download all in scope" source (capped by DownloadPolicy). */
    @Query("SELECT id FROM article WHERE accountId = :accountId AND isUnread = 1")
    suspend fun queryUnreadIds(accountId: Int): List<String>

    /** S29 — ids matching deleteByAccountId, for download file cascade. */
    @Query("SELECT id FROM article WHERE accountId = :accountId")
    suspend fun queryIdsByAccountId(accountId: Int): List<String>
}

/** S7 — identity match: guid equality (both non-empty) OR link equality. */
private fun identityMatches(existing: Article, incoming: Article): Boolean =
    (incoming.guid.isNotEmpty() && existing.guid.isNotEmpty() && existing.guid == incoming.guid) ||
        (incoming.link.isNotEmpty() && existing.link.isNotEmpty() && existing.link == incoming.link)

/** S25a — one ordered row of a scope snapshot (see [ArticleDao.queryScopeRows]). */
data class ScopeRow(
    @ColumnInfo(name = "id") val articleId: String,
    @ColumnInfo(name = "sortKey") val sortKey: Long,
    @ColumnInfo(name = "rowId") val rowId: Long,
)

/** S18a — one rule preview sample row `(articleId, title, feed name, date)`. */
data class RulePreviewRow(
    @ColumnInfo(name = "id") val articleId: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "feedName") val feedName: String?,
    @ColumnInfo(name = "date") val date: Date?,
)