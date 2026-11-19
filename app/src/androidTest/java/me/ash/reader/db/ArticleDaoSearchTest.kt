package me.ash.reader.db

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.Group
import me.ash.reader.domain.model.query.SearchQuery
import me.ash.reader.domain.model.query.SearchSql
import me.ash.reader.domain.model.query.TextNormalizer
import me.ash.reader.infrastructure.db.AndroidDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Acceptance #12 — S11a SQL behavior over the `*Lower` shadow columns,
 * exercised through the Room DAO on a real v8 database:
 *
 * ESCAPE fixtures (`50%_off\`), body-only match, mid-word substring
 * (term "ucket" matches "FeedBucket" — FTS word index cannot; the LIKE
 * path must), Unicode case-insensitive fixtures (Turkish/Greek/Cyrillic
 * per S12a), fullContent-change visibility (shadow refresh in the same
 * tx), relevance ordering + rowid tiebreak (S14 via SearchSql rendering),
 * tag: CSV containment with no partial-name false positive (tag `tech`
 * must not match tags `biotech`).
 *
 * Runs on an explicitly selected emulator only (D15).
 */
@RunWith(AndroidJUnit4::class)
class ArticleDaoSearchTest {

    private lateinit var db: AndroidDatabase
    private lateinit var feedTech: Feed
    private lateinit var feedBio: Feed

    @Before
    fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AndroidDatabase::class.java).build()
        db.groupDao().insert(Group(id = "1\$g1", name = "Tech", accountId = 1))
        db.groupDao().insert(Group(id = "1\$g2", name = "Science", accountId = 1))
        feedTech = Feed(
            id = "1\$f1", name = "Tech Feed", url = "https://example.com/t.xml",
            groupId = "1\$g1", accountId = 1, tags = "tech,news",
        )
        feedBio = Feed(
            id = "1\$f2", name = "Biotech Feed", url = "https://example.com/b.xml",
            groupId = "1\$g2", accountId = 1, tags = "biotech",
        )
        db.feedDao().insert(feedTech, feedBio)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun article(
        id: String,
        title: String,
        raw: String = "",
        short: String = "",
        full: String? = null,
        feedId: String = feedTech.id,
        dateMillis: Long = 1700000000000,
    ) = Article(
        id = "1\$$id",
        date = java.util.Date(dateMillis),
        title = title,
        rawDescription = raw,
        shortDescription = short,
        fullContent = full,
        link = "https://example.com/$id",
        feedId = feedId,
        accountId = 1,
    )

    private suspend fun insert(vararg articles: Article) {
        articles.forEach { db.articleDao().insert(it) }
    }

    /** Runs the single-term shadow search and returns matched article ids in result order. */
    private suspend fun shadowSearch(term: String): List<String> {
        val pattern = SearchSql.likeTerm(TextNormalizer.normalize(term))
        val result = db.articleDao().searchArticleByShadowText(1, pattern)
            .load(PagingSource.LoadParams.Refresh(null, 50, false))
        return (result as PagingSource.LoadResult.Page).map { it.article.id }
    }

    @Test
    fun escapeFixtureDoesNotWildcardExplode() = runBlocking {
        insert(
            article("a1", "Sale", raw = "coupon 50%_off\\ today"),
            article("a2", "Other", raw = "nothing here"),
        )
        assertEquals(listOf("1\$a1"), shadowSearch("50%_off\\"))
        // A different escaped token must not match (no wildcard explosion).
        assertEquals(emptyList<String>(), shadowSearch("99%_off\\"))
    }

    @Test
    fun bodyOnlyTermMatchesViaRawOrFull() = runBlocking {
        val body = "x".repeat(300) + "UNIQUEBODYONLY"
        insert(
            article(
                "a1", "Plain Title",
                raw = body, short = body.take(280), // term beyond char 280 of the body
            ),
        )
        assertEquals(listOf("1\$a1"), shadowSearch("uniquebodyonly"))
    }

    @Test
    fun midWordSubstringMatches() = runBlocking {
        insert(article("a1", "All About FeedBucket"))
        // FTS word index cannot find "ucket"; the LIKE path must.
        assertEquals(listOf("1\$a1"), shadowSearch("ucket"))
    }

    @Test
    fun unicodeCaseInsensitiveFixtures() = runBlocking {
        insert(
            article("a1", "İstanbul Havadisleri"),
            article("a2", "ΣΊΣΥΦΟΣ Ανάλυση"),
            article("a3", "ПРИВЕТ МИР"),
            article("a4", "HELLO WORLD"),
        )
        // term "i" (the first codepoint of normalized İ) matches İ-text only
        // here — a1..a4 other titles carry no combining dot above.
        assertEquals(listOf("1\$a1"), shadowSearch("İ"))
        assertEquals(listOf("1\$a2"), shadowSearch("σίσυφο"))
        assertEquals(listOf("1\$a3"), shadowSearch("привет"))
        assertEquals(listOf("1\$a4"), shadowSearch("hello"))
        // German full-fold limitation pin: strasse does NOT match straße.
        insert(article("a5", "Straße Berlin"))
        assertEquals(emptyList<String>(), shadowSearch("strasse"))
        assertEquals(listOf("1\$a5"), shadowSearch("straße"))
    }

    @Test
    fun fullContentChangeIsVisibleInSameTransactionPath() = runBlocking {
        val original = article("a1", "Extraction Target", full = "original extracted text")
        insert(original)

        // Before change: old content findable, new content absent.
        assertEquals(listOf("1\$a1"), shadowSearch("original extracted"))
        assertEquals(emptyList<String>(), shadowSearch("replacement extracted"))

        // Update fullContent through the entity: the S11a write invariant
        // recomputes fullLower in the same DAO write.
        db.articleDao().update(original.copy(fullContent = "replacement extracted text"))
        assertEquals(listOf("1\$a1"), shadowSearch("replacement extracted"))
        assertEquals(emptyList<String>(), shadowSearch("original extracted"))
    }

    @Test
    fun relevanceOrderingAndRowidTiebreak() = runBlocking {
        // S14: title=5, short=2, raw/full=1; ties by date DESC, rowid DESC.
        insert(
            article("a1", "kernel weekly", dateMillis = 1700000003000),
            article("a2", "kernel", raw = "the kernel deep dive", dateMillis = 1700000002000),
            article("a3", "KERNEL roundup", short = "kernel in short", dateMillis = 1700000001000),
        )
        val query = SearchQuery.parse("kernel", defaultSort = SearchQuery.Sort.RELEVANCE)
        val score = SearchSql.renderRelevanceScore(query)
        val terms = SearchSql.renderTerms(query)
        val rows = mutableListOf<Pair<String, Long>>()
        db.openHelper.readableDatabase.query(
            """
            SELECT id, rowid FROM article
            WHERE accountId = 1 AND ${terms.sql}
            ORDER BY ${score.sql} DESC, date DESC, rowid DESC
            """.trimIndent(),
            terms.params.toTypedArray() + score.params.toTypedArray(),
        ).use { c ->
            while (c.moveToNext()) rows.add(c.getString(0) to c.getLong(1))
        }
        // a1: title(5) = 5; a2: title(5) + raw(1) = 6; a3: title(5) + short(2) = 7.
        assertEquals(listOf("1\$a3", "1\$a2", "1\$a1"), rows.map { it.first })

        // Rowid tiebreak: two rows with identical score and date order by rowid DESC.
        insert(
            article("a4", "tie one", dateMillis = 1700000004000),
            article("a5", "tie two", dateMillis = 1700000004000),
        )
        val tied = mutableListOf<String>()
        db.openHelper.readableDatabase.query(
            "SELECT id FROM article WHERE titleLower LIKE '%tie%' ORDER BY date DESC, rowid DESC"
        ).use { c ->
            while (c.moveToNext()) tied.add(c.getString(0))
        }
        assertEquals(listOf("1\$a5", "1\$a4"), tied)
    }

    @Test
    fun tagCsvContainmentNoPartialNameFalsePositive() = runBlocking {
        insert(
            article("a1", "Tech News", feedId = feedTech.id),
            article("a2", "Biotech News", feedId = feedBio.id),
            article("a3", "Untagged", feedId = feedBio.id), // feed tags 'biotech' only
        )
        // S11a: tags are feed-level CSV; a2 AND a3 both live in feedBio (tags 'biotech').
        assertEquals(listOf("1\$a1"), db.articleDao().queryArticlesByTag(1, "tech").map { it.id })
        assertEquals(
            listOf("1\$a2", "1\$a3"),
            db.articleDao().queryArticlesByTag(1, "biotech").map { it.id },
        )
        assertEquals(listOf("1\$a1"), db.articleDao().queryArticlesByTag(1, "news").map { it.id })
    }

    @Test
    fun tagWildcardsMatchLiterallyNotAsLikeWildcards() = runBlocking {
        // Review lane 3 defect 4: `_` and `%` inside tag names bind escaped
        // (ESCAPE '\'), so `foo_bar` must not match `fooxbar` and `100%`
        // must not match `1000`.
        val feedUnderscore = Feed(
            id = "1\$f3", name = "Underscore Feed", url = "https://example.com/u.xml",
            groupId = "1\$g1", accountId = 1, tags = "foo_bar",
        )
        val feedLookalike = Feed(
            id = "1\$f4", name = "Lookalike Feed", url = "https://example.com/l.xml",
            groupId = "1\$g1", accountId = 1, tags = "fooxbar,1000",
        )
        val feedPercent = Feed(
            id = "1\$f5", name = "Percent Feed", url = "https://example.com/p.xml",
            groupId = "1\$g1", accountId = 1, tags = "100%",
        )
        db.feedDao().insert(feedUnderscore, feedLookalike, feedPercent)
        insert(
            article("u1", "Underscore", feedId = feedUnderscore.id),
            article("l1", "Lookalike", feedId = feedLookalike.id),
            article("p1", "Percent", feedId = feedPercent.id),
        )
        assertEquals(
            listOf("1\$u1"),
            db.articleDao().queryArticlesByTag(1, "foo_bar").map { it.id },
        )
        assertEquals(
            listOf("1\$p1"),
            db.articleDao().queryArticlesByTag(1, "100%").map { it.id },
        )
        // The lookalike tag still matches exactly.
        assertEquals(
            listOf("1\$l1"),
            db.articleDao().queryArticlesByTag(1, "fooxbar").map { it.id },
        )
    }

    @Test
    fun shadowColumnsAreWrittenAtInsert() = runBlocking {
        val a = article("a1", "FeedBucket Release Notes", raw = "Body Text Here")
        insert(a)
        db.openHelper.readableDatabase.query(
            "SELECT titleLower, rawLower FROM article WHERE id = '1\$a1'"
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("feedbucket release notes", c.getString(0))
            assertEquals("body text here", c.getString(1))
        }
    }

    @Test
    fun stateWritersBumpStateVersion() = runBlocking {
        val a = article("a1", "State Probe")
        insert(a)
        assertEquals(0L, db.articleDao().queryById("1\$a1")?.article?.stateVersion)
        db.articleDao().markAsStarredByArticleId(1, "1\$a1", true)
        assertEquals(1L, db.articleDao().queryById("1\$a1")?.article?.stateVersion)
        db.articleDao().markAsReadByArticleId(1, "1\$a1", false)
        assertEquals(2L, db.articleDao().queryById("1\$a1")?.article?.stateVersion)
    }
}
