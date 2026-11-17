package me.ash.reader.domain.service

import java.util.Date
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.query.QueryMatcher
import me.ash.reader.domain.model.query.ScopeSql
import me.ash.reader.domain.model.rule.ActionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S17a (amended, R1) — ingest rule application, acceptance #3:
 *
 *  - `budgetExhaustedAt101`: 101 matching articles, star rule -> exactly 100
 *    starred (web star_budget, decremented only on actual flips)
 *  - `muteHasNoIngestEffect`: a MUTE rule with default mark_read effects and
 *    a matching new article -> the article is inserted UNREAD (deliberate
 *    divergence from web actions.py:105–123, pinned by R1)
 *  - update path: star skipped entirely; mark_read decision exposed for
 *    Wave E wiring
 *  - effects OR across multiple matching rules; mutes filter ONLY
 *    unread/updated views (starred/all carry no mute NOT-clause)
 */
class RuleEngineTest {

    private fun article(
        index: Int,
        title: String = "Daily tech digest #$index",
        feedId: String = FEED,
        isUnread: Boolean = true,
        isStarred: Boolean = false,
    ) = Article(
        id = "$feedId-article-$index",
        date = Date(index * 1000L),
        title = title,
        rawDescription = "body $index",
        shortDescription = "short $index",
        link = "https://example.com/$index",
        feedId = feedId,
        accountId = 1,
        isUnread = isUnread,
        isStarred = isStarred,
    )

    private fun starRule(query: String = "tech") = ActionRule(
        type = ActionRule.TYPE_STANDARD,
        title = "star tech",
        query = query,
        effects = "${ActionRule.EFFECT_STAR}",
        createdAt = 1L,
    )

    @Test
    fun `budgetExhaustedAt101 - 101 matching articles star rule stars exactly 100`() {
        val articles = (1..101).map { article(it) }
        val result = RuleEngine.applyToNewArticles(articles, emptyList(), null, listOf(starRule()))
        assertEquals(101, result.articles.size)
        assertEquals(100, result.articles.count { it.isStarred })
        assertEquals(100, result.starsApplied)
        // the 101st matched article of the run stays unstarred
        assertFalse(result.articles.last().isStarred)
        assertTrue(result.articles.first().isStarred)
        // unread state untouched by a pure star rule
        assertEquals(101, result.articles.count { it.isUnread })
    }

    @Test
    fun `budget is shared across rules within the run`() {
        val articles = (1..101).map { article(it) }
        // two rules both matching every article: still exactly 100 stars
        val result = RuleEngine.applyToNewArticles(
            articles, emptyList(), null,
            listOf(starRule("tech"), starRule("digest")),
        )
        assertEquals(100, result.starsApplied)
        assertEquals(100, result.articles.count { it.isStarred })
    }

    @Test
    fun `non-matching articles never consume budget`() {
        val articles = (1..150).map { article(it, title = if (it <= 50) "tech news" else "cooking news") }
        val result = RuleEngine.applyToNewArticles(articles, emptyList(), null, listOf(starRule("tech")))
        assertEquals(50, result.starsApplied)
        assertEquals(50, result.articles.count { it.isStarred })
    }

    @Test
    fun `muteHasNoIngestEffect - mute with default mark_read effects leaves new article unread`() {
        val mute = ActionRule(
            type = ActionRule.TYPE_MUTE,
            title = "mute noise",
            query = "tech",
            effects = ActionRule.EFFECT_MARK_READ, // web default effect
            createdAt = 1L,
        )
        val result = RuleEngine.applyToNewArticles(
            listOf(article(1)), emptyList(), null, listOf(mute),
        )
        // R1 divergence from web actions.py:105-123: mutes NEVER apply at ingest
        assertTrue(result.articles.single().isUnread)
        assertFalse(result.articles.single().isStarred)
        assertEquals(0, result.starsApplied)
    }

    @Test
    fun `mark_read effect inserts article as read`() {
        val markRead = ActionRule(
            type = ActionRule.TYPE_STANDARD,
            title = "read noise",
            query = "tech",
            effects = ActionRule.EFFECT_MARK_READ,
            createdAt = 1L,
        )
        val result = RuleEngine.applyToNewArticles(listOf(article(1)), emptyList(), null, listOf(markRead))
        assertFalse(result.articles.single().isUnread)
    }

    @Test
    fun `effects OR across multiple matching rules`() {
        val markRead = ActionRule(
            type = ActionRule.TYPE_STANDARD, title = "a", query = "tech",
            effects = ActionRule.EFFECT_MARK_READ, createdAt = 1L,
        )
        val star = starRule()
        val result = RuleEngine.applyToNewArticles(listOf(article(1)), emptyList(), null, listOf(markRead, star))
        val applied = result.articles.single()
        assertFalse(applied.isUnread)
        assertTrue(applied.isStarred)
    }

    @Test
    fun `update path star is skipped entirely`() {
        // S17a: `star = not is_update and ...` — there is no star decision on
        // the update path at all; only mark_read may fire. The Wave E update
        // path must never call a star effect (pinned here via the API shape:
        // only shouldMarkReadOnUpdate is exposed).
        val fields = QueryMatcher.Fields("tech news", "short", "body", null)
        val rules = listOf(
            ActionRule(
                type = ActionRule.TYPE_STANDARD, title = "star", query = "tech",
                effects = ActionRule.EFFECT_STAR, createdAt = 1L,
            ),
        )
        assertFalse(RuleEngine.shouldMarkReadOnUpdate(fields, emptyList(), null, rules))
    }

    @Test
    fun `update path mark_read fires on match and respects mutes`() {
        val markRead = ActionRule(
            type = ActionRule.TYPE_STANDARD, title = "read", query = "tech",
            effects = ActionRule.EFFECT_MARK_READ, createdAt = 1L,
        )
        val fields = QueryMatcher.Fields("tech news", "short", "body", null)
        assertTrue(RuleEngine.shouldMarkReadOnUpdate(fields, emptyList(), null, listOf(markRead)))
        val noMatch = QueryMatcher.Fields("cooking", "short", "body", null)
        assertFalse(RuleEngine.shouldMarkReadOnUpdate(noMatch, emptyList(), null, listOf(markRead)))
        // mutes never participate in the update path either
        val mute = markRead.copy(type = ActionRule.TYPE_MUTE)
        assertFalse(RuleEngine.shouldMarkReadOnUpdate(fields, emptyList(), null, listOf(mute)))
    }

    @Test
    fun `muteAppliesOnlyToUnreadAndUpdated - starred and all views carry no mute clause`() {
        // View matrix (S15): mutes render as NOT-clauses ONLY on the
        // unread/updated views; starred/all/recentlyRead keep muted matches
        // visible. Pinned at the SQL-builder level (the DAO consumes these
        // builders exclusively — no second mute renderer exists).
        val mute = ActionRule(
            type = ActionRule.TYPE_MUTE, title = "m", query = "tech",
            effects = ActionRule.EFFECT_MARK_READ, createdAt = 1L,
        )
        val scope = setOf(FEED)
        val unread = ScopeSql.rows(1, "unread", null, null, false, null, listOf(mute to scope))
        val updated = ScopeSql.rows(1, "updated", null, null, false, null, listOf(mute to scope))
        val starred = ScopeSql.rows(1, "starred", null, null, false, null, listOf(mute to scope))
        val all = ScopeSql.rows(1, "all", null, null, false, null, listOf(mute to scope))
        val recentlyRead = ScopeSql.rows(1, "recentlyRead", null, null, false, null, listOf(mute to scope))
        assertTrue(unread.sql.contains("NOT ("))
        assertTrue(updated.sql.contains("NOT ("))
        assertFalse(starred.sql.contains("NOT ("))
        assertFalse(all.sql.contains("NOT ("))
        assertFalse(recentlyRead.sql.contains("NOT ("))
    }

    companion object {
        private const val FEED = "1\$feed-1"
    }
}
