package me.ash.reader.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Date
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.Group
import me.ash.reader.domain.model.query.ScopeSql
import me.ash.reader.domain.model.rule.ActionRule
import me.ash.reader.infrastructure.db.AndroidDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Gate E instrumented test (acceptance #4/#5 companion, S21a + S20a
 * DB-visible transitions), running on the hosted CI emulator gate
 * (feedbucket-instrumented.yml, API35+API36) at the exact head:
 *
 *  - updated view ordered by the DEFAULT scope order `date DESC, rowid
 *    DESC` (web parity: list_entries(view='updated') only adds the join,
 *    db.py:603-605/627) and `contentChangedAt` is NEVER a sort key (D8)
 *  - drain-on-read (S21a): mark-read clears isUpdated in the same write
 *  - S20a snapshot/update transitions: first-version-only originalContent,
 *    isUpdated/contentChangedAt only when showUpdates, updateAt on every
 *    changed row, dateless rows never fake-update across refreshes (R7),
 *    extracted rows never clobbered, S17a update-path mark_read clears
 *    isUnread AND isUpdated
 */
@RunWith(AndroidJUnit4::class)
class ArticleDaoUpdatedViewTest {

    private lateinit var db: AndroidDatabase
    private lateinit var feed: Feed

    private val t0 = Date(1_800_000_000_000L)
    private val t1 = Date(1_800_000_100_000L)
    private val t2 = Date(1_800_000_200_000L)

    @Before
    fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AndroidDatabase::class.java).build()
        db.groupDao().insert(Group(id = "1\$g1", name = "Tech", accountId = 1))
        feed = Feed(
            id = "1\$f1", name = "Tech Feed", url = "https://example.com/t.xml",
            groupId = "1\$g1", accountId = 1, tags = "tech",
        )
        db.feedDao().insert(feed)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun article(
        id: String,
        title: String = "Title $id",
        raw: String = "<p>body $id</p>",
        short: String = "body $id",
        link: String = "https://example.com/$id",
        guid: String = "guid-$id",
        publishedAt: Date? = t0,
        date: Date = publishedAt ?: t0,
        isUpdated: Boolean = false,
        contentChangedAt: Date? = null,
        originalContent: String? = null,
        updateAt: Date = t0,
        fullContent: String? = null,
    ) = Article(
        id = "1\$$id",
        date = date,
        title = title,
        rawDescription = raw,
        shortDescription = short,
        fullContent = fullContent,
        link = link,
        feedId = feed.id,
        accountId = 1,
        updateAt = updateAt,
        guid = guid,
        publishedAt = publishedAt,
        isUpdated = isUpdated,
        contentChangedAt = contentChangedAt,
        originalContent = originalContent,
    )

    private suspend fun store(
        articles: List<Article>,
        now: Date,
        rules: List<ActionRule> = emptyList(),
        useFeed: Feed = feed,
    ) = db.articleDao().storeEntries(
        articles = articles,
        feed = useFeed,
        feedTags = listOf("tech"),
        groupName = "Tech",
        rules = rules,
        now = now,
    )

    private suspend fun row(id: String) =
        db.articleDao().queryById("1\$$id")!!.article

    private fun updatedViewIds(): List<String> {
        val built =
            ScopeSql.rows(
                accountId = 1,
                view = "updated",
                feedId = null,
                groupId = null,
                sortAscending = false,
                search = null,
                mutes = emptyList(),
            )
        return runBlocking {
            db.articleDao()
                .queryScopeRowsRaw(SimpleSQLiteQuery(built.sql, built.args.toTypedArray()))
                .map { it.articleId }
        }
    }

    @Test
    fun updatedViewOrdersByDateDescRowidDesc() = runBlocking {
        // Insert in rowid order a(old), b(new), c(oldest); make b and c
        // updated members, a not. Expected: b (newest) then c (oldest).
        store(listOf(article("a", publishedAt = Date(1000))), now = t0)
        store(listOf(article("b", publishedAt = Date(3000))), now = t0)
        store(listOf(article("c", publishedAt = Date(2000))), now = t0)
        db.articleDao().update(article("b", publishedAt = Date(3000), isUpdated = true))
        db.articleDao().update(article("c", publishedAt = Date(2000), isUpdated = true))

        assertEquals(listOf("1\$b", "1\$c"), updatedViewIds())
    }

    @Test
    fun contentChangedAtIsNeverASortKey() {
        val built =
            ScopeSql.rows(
                accountId = 1, view = "updated", feedId = null, groupId = null,
                sortAscending = false, search = null, mutes = emptyList(),
            )
        assertFalse(
            "contentChangedAt must never appear in scope SQL (D8)",
            built.sql.contains("contentChangedAt", ignoreCase = true),
        )
        assertTrue(built.sql.contains("date DESC, rowid DESC"))
    }

    @Test
    fun drainOnReadClearsIsUpdated() = runBlocking {
        db.articleDao().update(
            article("a", publishedAt = Date(1000), isUpdated = true, contentChangedAt = t1),
        )
        db.articleDao().markAsReadByArticleId(1, "1\$a", isUnread = false, readAt = t2)

        val after = row("a")
        assertFalse(after.isUpdated)
        assertEquals(t2, after.readAt)
    }

    @Test
    fun s20aSnapshotFirstVersionOnlyAndUpdatedQueue() = runBlocking {
        val oldBody = "<p>${"o".repeat(200)}</p>"
        val newBody = "<p>${"o".repeat(60)}${"n".repeat(80)}</p>"
        store(listOf(article("a", title = "V1", raw = oldBody, short = "old")), now = t0)

        // Second refresh: significant content change → snapshot + updated queue.
        store(
            listOf(article("a", title = "V2", raw = newBody, short = "new")),
            now = t1,
        )
        val afterSecond = row("a")
        assertEquals("V2", afterSecond.title)
        assertEquals("first-version snapshot keeps OLD content", oldBody, afterSecond.originalContent)
        assertTrue(afterSecond.isUpdated)
        assertEquals(t1, afterSecond.contentChangedAt)
        assertEquals(t1, afterSecond.updateAt)

        // Third refresh: another change → snapshot NEVER overwritten.
        val thirdBody = "<p>${"x".repeat(140)}</p>"
        store(listOf(article("a", title = "V3", raw = thirdBody, short = "new2")), now = t2)
        val afterThird = row("a")
        assertEquals(oldBody, afterThird.originalContent)
        assertEquals(t2, afterThird.contentChangedAt)
    }

    @Test
    fun s20aShowUpdatesOffSkipsUpdatedQueueAndSnapshot() = runBlocking {
        val oldBody = "<p>${"o".repeat(200)}</p>"
        val newBody = "<p>${"o".repeat(60)}${"n".repeat(80)}</p>"
        val quietFeed = feed.copy(showUpdates = false)
        store(listOf(article("a", raw = oldBody, short = "old")), now = t0, useFeed = quietFeed)
        store(listOf(article("a", raw = newBody, short = "new")), now = t1, useFeed = quietFeed)

        val after = row("a")
        assertFalse(after.isUpdated)
        assertNull(after.contentChangedAt)
        assertNull(after.originalContent)
        // The row still updates: content + updateAt move.
        assertEquals(newBody, after.rawDescription)
        assertEquals(t1, after.updateAt)
    }

    @Test
    fun datelessRowNeverFakeUpdatesAcrossRefreshes() = runBlocking {
        // Insert with no publisher date (RssHelper mints date=preDate).
        store(
            listOf(article("a", publishedAt = null, date = t0, raw = "<p>same</p>", short = "same")),
            now = t0,
        )
        // Identical second refresh at a later sync time: untouched.
        store(
            listOf(article("a", publishedAt = null, date = t1, raw = "<p>same</p>", short = "same")),
            now = t1,
        )
        val after = row("a")
        assertEquals("no updateAt bump on an unchanged dateless row", t0, after.updateAt)
        assertFalse(after.isUpdated)
        assertNull(after.originalContent)
        // Stored display date is NOT re-minted either (frozen, R7).
        assertEquals(t0, after.date)
    }

    @Test
    fun extractedRowContentNeverClobbered() = runBlocking {
        val extracted = "<p>locally extracted full text</p>"
        store(listOf(article("a", raw = "<p>feed v1</p>", short = "feed v1")), now = t0)
        // Simulate completed extraction.
        db.articleDao().update(row("a").copy(fullContent = extracted))

        // Publisher changes title AND feed content. Extracted rows update
        // ONLY title/link/author/publishedAt(+date) — rawDescription keeps
        // its stored value and the snapshot gate never fires.
        store(
            listOf(article("a", title = "Edited", raw = "<p>feed v2</p>", short = "feed v2")),
            now = t1,
        )
        val after = row("a")
        assertEquals("Edited", after.title)
        assertEquals(extracted, after.fullContent)
        assertNull("extracted rows never snapshot", after.originalContent)
        // Compare keys for extracted rows ignore content; the content
        // fields must NOT be rewritten from the feed.
        assertEquals("<p>feed v1</p>", after.rawDescription)
    }

    @Test
    fun updatePathMarkReadRuleClearsUnreadAndUpdated() = runBlocking {
        val oldBody = "<p>${"o".repeat(200)}</p>"
        val newBody = "<p>${"o".repeat(60)}${"n".repeat(80)}</p>"
        store(listOf(article("a", title = "match me", raw = oldBody, short = "old")), now = t0)
        val rule = ActionRule(
            id = 1L,
            title = "auto-read",
            query = "match me",
            effects = ActionRule.EFFECT_MARK_READ,
            type = ActionRule.TYPE_STANDARD,
            createdAt = t0.time,
        )
        store(listOf(article("a", title = "match me", raw = newBody, short = "new")), now = t1, rules = listOf(rule))

        val after = row("a")
        assertFalse("S17a update-path mark_read clears isUnread", after.isUnread)
        assertFalse("S17a update-path mark_read clears isUpdated", after.isUpdated)
        assertEquals(t1, after.readAt)
    }
}
