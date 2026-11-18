package me.ash.reader.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Date
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.Group
import me.ash.reader.domain.model.query.TextNormalizer
import me.ash.reader.infrastructure.db.AndroidDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Wave X1 (SPEC-EXTRACT-v1 E4/E7/E9b) — extraction DAO paths on a real v9
 * Room database (D15: explicitly selected emulator only):
 *
 * - `applyExtraction` writes EXACTLY fullContent/fullLower/extractedAt;
 *   every other column (incl. the S19-protected content/state columns and
 *   the S18a `stateVersion` choke point) is byte-identical before/after.
 * - S12a shadow: `fullLower` stores `TextNormalizer.normalize(fullContent)`.
 * - S16 FTS: a term present ONLY in the extracted text is findable via
 *   `article_fts` MATCH after the write (Room trigger sync).
 * - E9b: `queryUnreadFullContentArticles` skips already-extracted rows and
 *   still returns unread rows of flagged feeds only.
 * - E7: `queryRecentRawDescriptionsForMedian` honors LIMIT, excludes empty
 *   rawDescription rows, and orders by rowid DESC.
 */
@RunWith(AndroidJUnit4::class)
class ArticleDaoExtractionTest {

    private lateinit var db: AndroidDatabase

    @Before
    fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AndroidDatabase::class.java).build()
        db.groupDao().insert(Group(id = "1\$g1", name = "Tech", accountId = 1))
        db.feedDao().insert(
            Feed(
                id = FEED_FULL, name = "Flagged Feed", url = "https://example.com/f.xml",
                groupId = "1\$g1", accountId = 1, isFullContent = true,
            ),
            Feed(
                id = FEED_PLAIN, name = "Plain Feed", url = "https://example.com/p.xml",
                groupId = "1\$g1", accountId = 1, isFullContent = false,
            ),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun article(
        id: String,
        title: String = "Title $id",
        raw: String = "raw body $id",
        short: String = "short $id",
        feedId: String = FEED_FULL,
        isUnread: Boolean = true,
        isStarred: Boolean = false,
        readAt: Date? = null,
        originalContent: String? = null,
        contentChangedAt: Date? = null,
        isUpdated: Boolean = false,
        stateVersion: Long = 0,
        fullContent: String? = null,
    ) = Article(
        id = "1\$$id",
        date = Date(1700000000000),
        title = title,
        rawDescription = raw,
        shortDescription = short,
        fullContent = fullContent,
        img = "https://example.com/img-$id.png",
        link = "https://example.com/$id",
        feedId = feedId,
        accountId = 1,
        isUnread = isUnread,
        isStarred = isStarred,
        readAt = readAt,
        publishedAt = Date(1699999999000),
        originalContent = originalContent,
        contentChangedAt = contentChangedAt,
        isUpdated = isUpdated,
        stateVersion = stateVersion,
    )

    /** Full raw-SQL snapshot of an article row: column name -> text value. */
    private fun rowSnapshot(articleId: String): Map<String, String?> {
        val snapshot = LinkedHashMap<String, String?>()
        db.openHelper.readableDatabase.query(
            "SELECT * FROM article WHERE id = ?", arrayOf(articleId)
        ).use { c ->
            assertTrue(c.moveToFirst())
            for (i in 0 until c.columnCount) {
                snapshot[c.getColumnName(i)] = if (c.isNull(i)) null else c.getString(i)
            }
        }
        return snapshot
    }

    private fun ftsMatchCount(term: String): Int =
        db.openHelper.readableDatabase.query(
            "SELECT count(*) FROM article_fts WHERE article_fts MATCH ?", arrayOf(term)
        ).use { c -> c.moveToFirst(); c.getInt(0) }

    @Test
    fun applyExtractionSetsExactlyThreeColumnsAndNothingElse() = runBlocking {
        // Seed with DISTINCTIVE values in every S19-protected column so a
        // clobber cannot hide behind a default.
        val seeded = article(
            "a1",
            raw = "<p>teaser</p>",
            short = "preview",
            isUnread = false,
            isStarred = true,
            readAt = Date(1700000005000),
            originalContent = "snapshot-body",
            contentChangedAt = Date(1700000006000),
            isUpdated = true,
            stateVersion = 7,
        )
        db.articleDao().insert(seeded)
        val before = rowSnapshot("1\$a1")
        // Sanity: the fixture really carries the protected values.
        assertEquals("snapshot-body", before["originalContent"])
        assertEquals("7", before["stateVersion"])
        assertEquals("1", before["isStarred"])
        assertEquals("1", before["isUpdated"])
        assertNull(before["fullContent"])

        val extractedAt = Date(1700000009000)
        val full = "<p>the extracted article body</p>"
        val rows = db.articleDao().applyExtraction(
            id = "1\$a1",
            fullContent = full,
            fullLower = TextNormalizer.normalize(full),
            extractedAt = extractedAt,
        )
        assertEquals(1, rows)

        val after = rowSnapshot("1\$a1")
        assertEquals(before.keys, after.keys)
        val written = setOf("fullContent", "fullLower", "extractedAt")
        for (column in before.keys) {
            if (column in written) continue
            assertEquals("column `$column` was modified by applyExtraction", before[column], after[column])
        }
        assertEquals(full, after["fullContent"])
        assertEquals(TextNormalizer.normalize(full), after["fullLower"])
        assertEquals(extractedAt.time.toString(), after["extractedAt"])
        // Explicit pins for the card-listed protected columns (S19/S18a).
        assertEquals(before["rawDescription"], after["rawDescription"])
        assertEquals(before["shortDescription"], after["shortDescription"])
        assertEquals("snapshot-body", after["originalContent"])
        assertEquals(before["contentChangedAt"], after["contentChangedAt"])
        assertEquals("1", after["isUpdated"])
        assertEquals("0", after["isUnread"])
        assertEquals("1", after["isStarred"])
        assertEquals(before["readAt"], after["readAt"])
        assertEquals("7", after["stateVersion"])
    }

    @Test
    fun extractedOnlyTermIsFtsSearchableAndShadowIsNormalized() = runBlocking {
        db.articleDao().insert(article("a2", raw = "alpha omega", short = "alpha"))
        assertEquals(0, ftsMatchCount("zorkmidextracted"))

        val full = "<p>the zorkmidextracted chronicles</p>"
        db.articleDao().applyExtraction(
            id = "1\$a2",
            fullContent = full,
            fullLower = TextNormalizer.normalize(full),
            extractedAt = Date(1700000010000),
        )

        // S16: Room's room_fts_content_sync_* triggers carry fullContent
        // into article_fts — a term present ONLY in the extraction matches.
        assertEquals(1, ftsMatchCount("zorkmidextracted"))
        // S12a shadow (S11a write-path invariant, caller-computed).
        db.openHelper.readableDatabase.query(
            "SELECT fullLower FROM article WHERE id = '1\$a2'"
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(TextNormalizer.normalize(full), c.getString(0))
        }
    }

    @Test
    fun prefetchQuerySkipsExtractedAndUnflaggedRows() = runBlocking {
        // a3: unread in a flagged feed, not extracted -> the ONLY match.
        db.articleDao().insert(article("a3", feedId = FEED_FULL))
        // a4: unread in a flagged feed, already extracted -> skipped (E9b).
        db.articleDao().insert(article("a4", feedId = FEED_FULL))
        db.articleDao().applyExtraction(
            id = "1\$a4",
            fullContent = "<p>stored extraction</p>",
            fullLower = TextNormalizer.normalize("<p>stored extraction</p>"),
            extractedAt = Date(1700000011000),
        )
        // a5: READ in a flagged feed -> skipped.
        db.articleDao().insert(
            article("a5", feedId = FEED_FULL, isUnread = false, readAt = Date(1700000012000))
        )
        // a6: unread in an unflagged feed -> skipped.
        db.articleDao().insert(article("a6", feedId = FEED_PLAIN))

        val result = db.articleDao().queryUnreadFullContentArticles(1)
        assertEquals(listOf("1\$a3"), result.map { it.id })
    }

    @Test
    fun medianSamplesHonorLimitExcludeEmptyAndOrderRowidDesc() = runBlocking {
        // 105 non-empty bodies in ascending rowid order, with empty
        // rawDescription rows interleaved, plus one other-feed row.
        for (i in 0..104) {
            db.articleDao().insert(article("m$i", raw = "body-$i", feedId = FEED_FULL))
            if (i % 35 == 34) {
                db.articleDao().insert(article("e$i", raw = "", feedId = FEED_FULL))
            }
        }
        db.articleDao().insert(article("x1", raw = "other-feed-body", feedId = FEED_PLAIN))

        val samples = db.articleDao().queryRecentRawDescriptionsForMedian(FEED_FULL, 100)
        assertEquals(100, samples.size)
        // rowid DESC: newest first; empties excluded; other feeds excluded.
        assertEquals("body-104", samples.first())
        assertEquals("body-5", samples.last())
        assertTrue(samples.none { it.isEmpty() })
        assertTrue("other-feed-body" !in samples)
        assertEquals(samples, samples.sortedWith(compareByDescending { it.substringAfter('-').toInt() }))

        // LIMIT is honored for small values too.
        val small = db.articleDao().queryRecentRawDescriptionsForMedian(FEED_FULL, 3)
        assertEquals(listOf("body-104", "body-103", "body-102"), small)
    }

    companion object {
        private const val FEED_FULL = "1\$ff"
        private const val FEED_PLAIN = "1\$fp"
    }
}
