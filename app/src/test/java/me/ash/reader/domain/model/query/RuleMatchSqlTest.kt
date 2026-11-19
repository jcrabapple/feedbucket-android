package me.ash.reader.domain.model.query

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S18a — exact-string pins for the rule preview/apply match SQL (raw
 * Room queries; same rationale as ScopeSqlTest).
 *
 * Review lane 3 defect 3: the scope predicate renders quoted feed-id
 * literals in <= [RuleMatchSql.SCOPE_CHUNK_SIZE]-item OR-ed `IN` chunks —
 * ZERO bind variables — because SQLITE_MAX_VARIABLE_NUMBER (999 on API
 * 26-29) bounds the TOTAL host parameters of one statement, so chunked
 * bind lists would still overflow for >999-feed scopes. `allFeeds` skips
 * the scope predicate entirely (`accountId = ?` bounds it).
 */
class RuleMatchSqlTest {

    private val TERM = "(titleLower LIKE ? ESCAPE '\\' OR " +
        "shortLower LIKE ? ESCAPE '\\' OR " +
        "rawLower LIKE ? ESCAPE '\\' OR " +
        "fullLower LIKE ? ESCAPE '\\')"

    private val TAG = "EXISTS (SELECT 1 FROM feed f2 WHERE f2.id = a.feedId " +
        "AND (',' || f2.tags || ',' LIKE '%,' || ? || ',%' ESCAPE '\\'))"

    @Test
    fun `preview selects id title feed name date with limit 20`() {
        val built = RuleMatchSql.preview(1, listOf("f1", "f2"), SearchQuery.parse("foo"))
        assertEquals(
            "SELECT a.id, a.title, f.name AS feedName, a.date FROM article AS a " +
                "LEFT JOIN feed AS f ON f.id = a.feedId WHERE a.accountId = ? " +
                "AND (a.feedId IN ('f1', 'f2')) AND $TERM ORDER BY a.date DESC, a.rowid DESC LIMIT ?",
            built.sql,
        )
        assertEquals(listOf<Any?>(1, "%foo%", "%foo%", "%foo%", "%foo%", 20), built.args)
    }

    @Test
    fun `preview aliases the tag clause to the article alias`() {
        val built = RuleMatchSql.preview(1, listOf("f1"), SearchQuery.parse("tag:tech foo"))
        assertTrue(built.sql.contains("AND $TERM AND $TAG "))
        assertEquals(listOf<Any?>(1, "%foo%", "%foo%", "%foo%", "%foo%", "tech", 20), built.args)
    }

    @Test
    fun `preview relevance orders by the S14 score`() {
        val built = RuleMatchSql.preview(1, listOf("f1"), SearchQuery.parse("sort:relevance foo"))
        val score = "( CASE WHEN a.titleLower LIKE ? ESCAPE '\\' THEN 5 ELSE 0 END + " +
            "CASE WHEN a.shortLower LIKE ? ESCAPE '\\' THEN 2 ELSE 0 END + " +
            "CASE WHEN a.rawLower LIKE ? ESCAPE '\\' OR " +
            "a.fullLower LIKE ? ESCAPE '\\' THEN 1 ELSE 0 END )"
        assertTrue(built.sql.contains("ORDER BY ($score) DESC, a.date DESC, a.rowid DESC LIMIT ?"))
    }

    @Test
    fun `states selects the ledger columns over the full match set`() {
        val built = RuleMatchSql.states(1, listOf("f1", "f2"), SearchQuery.parse("foo"))
        assertEquals(
            "SELECT id, isUnread, isStarred, isUpdated, readAt, stateVersion FROM article " +
                "WHERE accountId = ? AND (feedId IN ('f1', 'f2')) AND $TERM ORDER BY date DESC, rowid DESC",
            built.sql,
        )
        assertEquals(listOf<Any?>(1, "%foo%", "%foo%", "%foo%", "%foo%"), built.args)
    }

    @Test
    fun `count sums the full match set for the confirmation dialog`() {
        val built = RuleMatchSql.count(1, listOf("f1", "f2"), SearchQuery.parse("foo"))
        assertEquals(
            "SELECT count(1) FROM article WHERE accountId = ? AND (feedId IN ('f1', 'f2')) AND $TERM",
            built.sql,
        )
    }

    @Test
    fun `scope ids are quoted literals with single quotes doubled`() {
        val built = RuleMatchSql.states(1, listOf("f'1", "f\\2"), SearchQuery.parse(""))
        assertTrue(built.sql.contains("(feedId IN ('f''1', 'f\\2'))"))
        assertEquals(listOf<Any?>(1), built.args)
    }

    @Test
    fun `over 1000 feed scope chunks into OR-ed IN literals under the variable cap`() {
        // Review lane 3 defect 3 regression: 1200-feed scope on API 26-29
        // (SQLITE_MAX_VARIABLE_NUMBER = 999) must build ONE statement whose
        // TOTAL bind-parameter count stays tiny — the ids live in the SQL as
        // quoted literals across ceil(1200/500) = 3 OR-ed IN chunks.
        val feedIds = (0 until 1200).map { "1\$feed$it" }
        val built = RuleMatchSql.states(1, feedIds, SearchQuery.parse("foo"))
        val chunks = built.sql.split(" OR feedId IN (")
        assertEquals("1200 ids -> 3 chunks of <= 500", 3, chunks.size)
        assertTrue(built.sql.startsWith("SELECT id, isUnread, isStarred, isUpdated, readAt, stateVersion " +
            "FROM article WHERE accountId = ? AND (feedId IN ('1\$feed0'"))
        assertTrue(built.sql.contains("'1\$feed499') OR feedId IN ('1\$feed500'"))
        assertTrue(built.sql.contains("'1\$feed999') OR feedId IN ('1\$feed1000'"))
        assertTrue(built.sql.contains("'1\$feed1199')) AND $TERM ORDER BY date DESC, rowid DESC"))
        // No scope bind variables: accountId + 4 term params ONLY (<< 999).
        assertEquals(listOf<Any?>(1, "%foo%", "%foo%", "%foo%", "%foo%"), built.args)
        assertTrue(
            "total bind args must stay far under the 999-variable floor",
            built.args.size < 999,
        )
        // Every id present exactly once.
        feedIds.forEach {
            assertEquals(1, Regex.fromLiteral("'$it'").findAll(built.sql).count())
        }
    }

    @Test
    fun `allFeeds scope omits the feed predicate entirely`() {
        // Review lane 3 defect 3: allFeeds never enumerates feed ids.
        val states = RuleMatchSql.states(1, emptyList(), SearchQuery.parse("foo"), allFeeds = true)
        assertFalse(states.sql.contains("feedId"))
        assertEquals(
            "SELECT id, isUnread, isStarred, isUpdated, readAt, stateVersion FROM article " +
                "WHERE accountId = ? AND $TERM ORDER BY date DESC, rowid DESC",
            states.sql,
        )
        val count = RuleMatchSql.count(1, emptyList(), SearchQuery.parse("foo"), allFeeds = true)
        assertEquals(
            "SELECT count(1) FROM article WHERE accountId = ? AND $TERM",
            count.sql,
        )
        val preview = RuleMatchSql.preview(1, emptyList(), SearchQuery.parse("foo"), allFeeds = true)
        assertFalse(preview.sql.contains("a.feedId IN"))
        assertTrue(preview.sql.contains("WHERE a.accountId = ? AND $TERM"))
        assertEquals(listOf<Any?>(1, "%foo%", "%foo%", "%foo%", "%foo%", 20), preview.args)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty scope matches nothing and refuses to build`() {
        RuleMatchSql.states(1, emptyList(), SearchQuery.parse("foo"))
    }
}
