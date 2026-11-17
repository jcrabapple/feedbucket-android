package me.ash.reader.domain.model.query

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S18a — exact-string pins for the rule preview/apply match SQL (raw
 * Room queries; same rationale as ScopeSqlTest).
 */
class RuleMatchSqlTest {

    private val TERM = "(titleLower LIKE '%' || ? || '%' ESCAPE '\\' OR " +
        "shortLower LIKE '%' || ? || '%' ESCAPE '\\' OR " +
        "rawLower LIKE '%' || ? || '%' ESCAPE '\\' OR " +
        "fullLower LIKE '%' || ? || '%' ESCAPE '\\')"

    private val TAG = "EXISTS (SELECT 1 FROM feed f2 WHERE f2.id = a.feedId " +
        "AND (',' || f2.tags || ',' LIKE '%,' || ? || ',%'))"

    @Test
    fun `preview selects id title feed name date with limit 20`() {
        val built = RuleMatchSql.preview(1, listOf("f1", "f2"), SearchQuery.parse("foo"))
        assertEquals(
            "SELECT a.id, a.title, f.name AS feedName, a.date FROM article AS a " +
                "LEFT JOIN feed AS f ON f.id = a.feedId WHERE a.accountId = ? " +
                "AND a.feedId IN (?, ?) AND $TERM ORDER BY a.date DESC, a.rowid DESC LIMIT ?",
            built.sql,
        )
        assertEquals(listOf<Any?>(1, "f1", "f2", "%foo%", "%foo%", "%foo%", "%foo%", 20), built.args)
    }

    @Test
    fun `preview aliases the tag clause to the article alias`() {
        val built = RuleMatchSql.preview(1, listOf("f1"), SearchQuery.parse("tag:tech foo"))
        assertTrue(built.sql.contains("AND $TERM AND $TAG "))
        assertEquals(listOf<Any?>(1, "f1", "%foo%", "%foo%", "%foo%", "%foo%", "tech", 20), built.args)
    }

    @Test
    fun `preview relevance orders by the S14 score`() {
        val built = RuleMatchSql.preview(1, listOf("f1"), SearchQuery.parse("sort:relevance foo"))
        val score = "( CASE WHEN a.titleLower LIKE '%' || ? || '%' ESCAPE '\\' THEN 5 ELSE 0 END + " +
            "CASE WHEN a.shortLower LIKE '%' || ? || '%' ESCAPE '\\' THEN 2 ELSE 0 END + " +
            "CASE WHEN a.rawLower LIKE '%' || ? || '%' ESCAPE '\\' OR " +
            "a.fullLower LIKE '%' || ? || '%' ESCAPE '\\' THEN 1 ELSE 0 END )"
        assertTrue(built.sql.contains("ORDER BY ($score) DESC, a.date DESC, a.rowid DESC LIMIT ?"))
    }

    @Test
    fun `states selects the ledger columns over the full match set`() {
        val built = RuleMatchSql.states(1, listOf("f1", "f2"), SearchQuery.parse("foo"))
        assertEquals(
            "SELECT id, isUnread, isStarred, isUpdated, readAt, stateVersion FROM article " +
                "WHERE accountId = ? AND feedId IN (?, ?) AND $TERM ORDER BY date DESC, rowid DESC",
            built.sql,
        )
        assertEquals(listOf<Any?>(1, "f1", "f2", "%foo%", "%foo%", "%foo%", "%foo%"), built.args)
    }

    @Test
    fun `count sums the full match set for the confirmation dialog`() {
        val built = RuleMatchSql.count(1, listOf("f1", "f2"), SearchQuery.parse("foo"))
        assertEquals(
            "SELECT count(1) FROM article WHERE accountId = ? AND feedId IN (?, ?) AND $TERM",
            built.sql,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty scope matches nothing and refuses to build`() {
        RuleMatchSql.states(1, emptyList(), SearchQuery.parse("foo"))
    }
}
