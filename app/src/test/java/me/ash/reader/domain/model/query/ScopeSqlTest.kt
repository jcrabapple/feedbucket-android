package me.ash.reader.domain.model.query

import me.ash.reader.domain.model.rule.ActionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S15/S25a — exact-string pins for the dynamic scope SQL consumed via
 * Room `@RawQuery`. These strings are the ONLY compile-independent
 * verification of the raw path (Room cannot validate dynamic SQL), so any
 * intentional change must update this test in the same commit.
 */
class ScopeSqlTest {

    private val TERM = "(titleLower LIKE '%' || ? || '%' ESCAPE '\\' OR " +
        "shortLower LIKE '%' || ? || '%' ESCAPE '\\' OR " +
        "rawLower LIKE '%' || ? || '%' ESCAPE '\\' OR " +
        "fullLower LIKE '%' || ? || '%' ESCAPE '\\')"

    private val VIEW_UNREAD = "(? = 'all' OR ? = 'unread' AND isUnread = 1 OR " +
        "? = 'updated' AND isUpdated = 1 OR ? = 'starred' AND isStarred = 1 OR " +
        "? = 'recentlyRead' AND readAt IS NOT NULL)"

    @Test
    fun `plain unread rows mirror the legacy static query shape`() {
        val built = ScopeSql.rows(1, "unread", null, null, false, null, emptyList())
        assertEquals(
            "SELECT id, date AS sortKey, rowid AS rowId FROM article WHERE accountId = ? " +
                "AND (? IS NULL OR feedId = ?) " +
                "AND (? IS NULL OR feedId IN (SELECT id FROM feed WHERE groupId = ?)) " +
                "AND $VIEW_UNREAD ORDER BY date DESC, rowid DESC",
            built.sql,
        )
        assertEquals(
            listOf<Any?>(1, null, null, null, null, "unread", "unread", "unread", "unread", "unread"),
            built.args,
        )
    }

    @Test
    fun `feed and group scoping binds null-tolerant args in order`() {
        val built = ScopeSql.rows(7, "all", "1\$f1", "1\$g1", true, null, emptyList())
        assertEquals(
            listOf<Any?>(7, "1\$f1", "1\$f1", "1\$g1", "1\$g1", "all", "all", "all", "all", "all"),
            built.args,
        )
        assertTrue(built.sql.contains("ORDER BY date ASC, rowid ASC"))
    }

    @Test
    fun `search terms render the canonical S11a clause and relevance ordering`() {
        val built = ScopeSql.rows(1, "all", null, null, false, SearchQuery.parse("sort:relevance foo"), emptyList())
        assertTrue(built.sql.contains("AND $TERM "))
        val score = "( CASE WHEN titleLower LIKE '%' || ? || '%' ESCAPE '\\' THEN 5 ELSE 0 END + " +
            "CASE WHEN shortLower LIKE '%' || ? || '%' ESCAPE '\\' THEN 2 ELSE 0 END + " +
            "CASE WHEN rawLower LIKE '%' || ? || '%' ESCAPE '\\' OR " +
            "fullLower LIKE '%' || ? || '%' ESCAPE '\\' THEN 1 ELSE 0 END )"
        assertTrue(built.sql.endsWith("ORDER BY ($score) DESC, date DESC, rowid DESC"))
        // WHERE args precede ORDER BY args
        assertEquals(listOf<Any?>(1, null, null, null, null, "all", "all", "all", "all", "all", "%foo%", "%foo%", "%foo%", "%foo%", "%foo%", "%foo%", "%foo%", "%foo%"), built.args)
    }

    @Test
    fun `mute renders one NOT clause with scope ids before match params`() {
        val mute = ActionRule(type = ActionRule.TYPE_MUTE, title = "m", query = "tech", createdAt = 1L)
        val built = ScopeSql.rows(1, "unread", null, null, false, null, listOf(mute to setOf("f1", "f2")))
        assertTrue(built.sql.contains("AND NOT (feedId IN (?, ?) AND $TERM)"))
        val notAt = built.sql.indexOf("NOT (")
        val orderAt = built.sql.indexOf("ORDER BY")
        assertTrue(notAt < orderAt)
        // args: account, feed(2), group(2), view(5), scope(2), term params(4)
        assertEquals(
            listOf<Any?>(1, null, null, null, null, "unread", "unread", "unread", "unread", "unread", "f1", "f2", "%tech%", "%tech%", "%tech%", "%tech%"),
            built.args,
        )
    }

    @Test
    fun `blank-query and empty-scope mutes are skipped like the web loop`() {
        val blank = ActionRule(type = ActionRule.TYPE_MUTE, title = "b", query = "   ", createdAt = 1L)
        val emptyScope = ActionRule(type = ActionRule.TYPE_MUTE, title = "e", query = "tech", createdAt = 1L)
        val built = ScopeSql.rows(1, "unread", null, null, false, null, listOf(blank to setOf("f1"), emptyScope to emptySet()))
        assertFalse(built.sql.contains("NOT ("))
    }

    @Test
    fun `sort-only mute query is skipped and cannot emit malformed SQL`() {
        val sortOnly = ActionRule(type = ActionRule.TYPE_MUTE, title = "s", query = "sort:relevance", createdAt = 1L)
        // direct renderer contract: no match clause -> no clause at all
        assertNull(MuteFilters.renderOne(sortOnly, setOf("f1")))
        // scope generation (rows + neighbor, unread + updated): no NOT
        // clause, and no dangling `AND )` / `AND  ORDER BY` from an empty
        // match contribution
        val unread = ScopeSql.rows(1, "unread", null, null, false, null, listOf(sortOnly to setOf("f1")))
        assertFalse(unread.sql.contains("NOT ("))
        val updated = ScopeSql.rows(1, "updated", null, null, false, null, listOf(sortOnly to setOf("f1")))
        assertFalse(updated.sql.contains("NOT ("))
        val neighbor = ScopeSql.next(1, "unread", null, null, false, 100L, 7L, null, listOf(sortOnly to setOf("f1")))
        assertFalse(neighbor.sql.contains("NOT ("))
        for (built in listOf(unread, updated, neighbor)) {
            assertFalse(built.sql.contains("AND )"))
            assertFalse(Regex("AND\\s+ORDER BY").containsMatchIn(built.sql))
        }
    }

    @Test
    fun `standard rules never render as mute clauses`() {
        val standard = ActionRule(type = ActionRule.TYPE_STANDARD, title = "s", query = "tech", createdAt = 1L)
        val built = ScopeSql.rows(1, "unread", null, null, false, null, listOf(standard to setOf("f1")))
        assertFalse(built.sql.contains("NOT ("))
    }

    @Test
    fun `neighbor prev mirrors the legacy row-value fallback`() {
        val built = ScopeSql.prev(1, "unread", null, null, false, 100L, 7L)
        assertEquals(
            "SELECT id FROM article WHERE accountId = ? " +
                "AND (? IS NULL OR feedId = ?) " +
                "AND (? IS NULL OR feedId IN (SELECT id FROM feed WHERE groupId = ?)) " +
                "AND $VIEW_UNREAD " +
                "AND ((? = 0 AND (date, rowid) > (?, ?)) OR (? = 1 AND (date, rowid) < (?, ?))) " +
                "ORDER BY CASE WHEN ? = 1 THEN date END DESC, CASE WHEN ? = 0 THEN date END ASC, " +
                "CASE WHEN ? = 1 THEN rowid END DESC, CASE WHEN ? = 0 THEN rowid END ASC LIMIT 1",
            built.sql,
        )
        assertEquals(
            listOf<Any?>(1, null, null, null, null, "unread", "unread", "unread", "unread", "unread", 0, 100L, 7L, 0, 100L, 7L, 0, 0, 0, 0),
            built.args,
        )
    }

    @Test
    fun `neighbor next flips comparison and closest-side ordering`() {
        val built = ScopeSql.next(1, "unread", null, null, false, 100L, 7L)
        assertTrue(built.sql.contains("(date, rowid) < (?, ?)"))
        assertTrue(built.sql.contains("CASE WHEN ? = 1 THEN date END ASC, CASE WHEN ? = 0 THEN date END DESC"))
    }

    @Test
    fun `recentlyRead ordering ignores sort ascending`() {
        val built = ScopeSql.rows(1, "recentlyRead", null, null, true, null, emptyList())
        assertTrue(built.sql.endsWith("ORDER BY readAt DESC, rowid DESC"))
    }
}
