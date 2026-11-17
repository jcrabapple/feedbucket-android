package me.ash.reader.domain.model.query

import me.ash.reader.domain.model.group.Group
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S12a/S13 — canonical in-memory match contract and rule scope resolution.
 */
class QueryMatcherTest {

    private fun fields(
        title: String? = null,
        short: String? = null,
        raw: String? = null,
        full: String? = null,
    ) = QueryMatcher.Fields(title, short, raw, full)

    @Test
    fun termMatchesAnyFieldPerFieldOr() {
        val query = SearchQuery.parse("feedbucket")
        assertTrue(QueryMatcher.matches(query, fields(title = "About FeedBucket")))
        assertTrue(QueryMatcher.matches(query, fields(short = "FeedBucket news digest")))
        assertTrue(QueryMatcher.matches(query, fields(raw = "<p>feedbucket release</p>")))
        assertTrue(QueryMatcher.matches(query, fields(full = "<div>FeedBucket offline</div>")))
        assertFalse(QueryMatcher.matches(query, fields(title = "Other", short = "Other")))
    }

    @Test
    fun termsAreAndedAcrossFields() {
        // web concatenated fields with \n so a term could match ACROSS field
        // boundaries; per-field OR forbids that ("feed" in title + "bucket"
        // in body must NOT satisfy term "feedbucket").
        val crossBoundary = SearchQuery.parse("feedbucket")
        assertFalse(
            QueryMatcher.matches(
                crossBoundary,
                fields(title = "feed", raw = "bucket"),
            )
        )
        val anded = SearchQuery.parse("alpha beta")
        assertTrue(QueryMatcher.matches(anded, fields(title = "alpha beta")))
        assertTrue(QueryMatcher.matches(anded, fields(title = "alpha", raw = "the beta release")))
        assertFalse(QueryMatcher.matches(anded, fields(title = "alpha")))
    }

    @Test
    fun bodyAfter280CharsMatchesViaRawOrFull() {
        val body = "x".repeat(300) + "UNIQUEBODYTERM"
        val short = body.take(280) // shortDescription lacks the term
        assertFalse(short.contains("UNIQUEBODYTERM"))
        val query = SearchQuery.parse("uniquebodyterm")
        assertFalse(QueryMatcher.matches(query, fields(title = "t", short = short)))
        assertTrue(QueryMatcher.matches(query, fields(title = "t", short = short, raw = body)))
        assertTrue(QueryMatcher.matches(query, fields(title = "t", short = short, full = body)))
    }

    @Test
    fun unicodeCaseInsensitiveFixtures() {
        // Turkish: term i matches İ-text, not dotless ı
        assertTrue(QueryMatcher.matches(SearchQuery.parse("i"), fields(title = "İstanbul")))
        assertFalse(QueryMatcher.matches(SearchQuery.parse("i"), fields(title = "ıspanak")))
        // Greek: σ matches Σ
        assertTrue(QueryMatcher.matches(SearchQuery.parse("σίσυφο"), fields(title = "ΣΊΣΥΦΟΣ")))
        assertFalse(QueryMatcher.matches(SearchQuery.parse("σίσυφοσ"), fields(title = "ΣΊΣΥΦΟΣ")))
        // Cyrillic
        assertTrue(QueryMatcher.matches(SearchQuery.parse("привет"), fields(title = "ПРИВЕТ")))
        // German: strasse does NOT match straße (simple-lowercase pin)
        assertFalse(QueryMatcher.matches(SearchQuery.parse("strasse"), fields(title = "Straße")))
        assertTrue(QueryMatcher.matches(SearchQuery.parse("straße"), fields(title = "STRAßE")))
        assertTrue(QueryMatcher.matches(SearchQuery.parse("strasse"), fields(title = "Auf der STRASSE")))
    }

    @Test
    fun tagMatchesFeedTagsOrGroupName() {
        val query = SearchQuery.parse("tag:tech")
        assertTrue(QueryMatcher.matches(query, fields(title = "x"), feedTags = listOf("tech")))
        assertTrue(QueryMatcher.matches(query, fields(title = "x"), feedTags = listOf("news", "tech")))
        assertTrue(QueryMatcher.matches(query, fields(title = "x"), groupName = "Tech"))
        assertFalse(QueryMatcher.matches(query, fields(title = "x"), feedTags = listOf("biotech")))
        assertFalse(QueryMatcher.matches(query, fields(title = "x"), feedTags = listOf("science")))
        // tag AND term
        val both = SearchQuery.parse("tag:tech kernel")
        assertTrue(QueryMatcher.matches(both, fields(title = "kernel weekly"), feedTags = listOf("tech")))
        assertFalse(QueryMatcher.matches(both, fields(title = "kernel weekly"), feedTags = listOf("science")))
    }

    private fun feed(id: String, groupId: String = "", tags: String = "") =
        QueryMatcher.ScopedFeed(id = id, groupId = groupId, tags = tags)

    private fun group(id: String, name: String) = Group(id = id, name = name, accountId = 1)

    @Test
    fun scopeAllFeeds() {
        val feeds = listOf(feed("f1"), feed("f2"))
        assertEquals(setOf("f1", "f2"), QueryMatcher.resolveScope(true, emptyList(), emptyList(), feeds, emptyList()))
    }

    @Test
    fun scopeFeedIdsIntersectedWithExisting() {
        val feeds = listOf(feed("f1"), feed("f2"))
        assertEquals(setOf("f1"), QueryMatcher.resolveScope(false, listOf("f1", "ghost"), emptyList(), feeds, emptyList()))
    }

    @Test
    fun scopeGroupIdsMatchGroupIdAndTagsByName() {
        val feeds = listOf(
            feed("f1", groupId = "g1"),
            feed("f2", tags = "news,tech"),
            feed("f3", groupId = "g2", tags = "tech"),
            feed("f4"),
        )
        val groups = listOf(group("g1", "Tech"), group("g2", "Misc"))
        // g1's NAME "tech" matches f1 (groupId), f2 and f3 (tags CSV)
        assertEquals(setOf("f1", "f2", "f3"), QueryMatcher.resolveScope(false, emptyList(), listOf("g1"), feeds, groups))
        // g2 matches f3 by groupId
        assertEquals(setOf("f3"), QueryMatcher.resolveScope(false, emptyList(), listOf("g2"), feeds, groups))
    }

    @Test
    fun scopeUnionOfFeedIdsAndGroups() {
        val feeds = listOf(feed("f1", groupId = "g1"), feed("f2"), feed("f3"))
        val groups = listOf(group("g1", "Tech"))
        assertEquals(setOf("f1", "f2"), QueryMatcher.resolveScope(false, listOf("f2"), listOf("g1"), feeds, groups))
    }

    @Test
    fun emptyScopeMatchesNothing() {
        val feeds = listOf(feed("f1"))
        assertEquals(emptySet<String>(), QueryMatcher.resolveScope(false, emptyList(), emptyList(), feeds, emptyList()))
    }
}
