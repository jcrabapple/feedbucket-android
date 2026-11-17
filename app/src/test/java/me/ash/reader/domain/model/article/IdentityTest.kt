package me.ash.reader.domain.model.article

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S6/S9: deterministic article ids — stable across calls, `$` spacer,
 * guid→link→hash fallback chain (web store_entries, db.py:496).
 */
class IdentityTest {

    @Test
    fun articleIdIsStableAcrossComputations() {
        val first = ArticleIdentity.articleId(ACCOUNT_ID, FEED_URL, "guid-abc")
        val second = ArticleIdentity.articleId(ACCOUNT_ID, FEED_URL, "guid-abc")
        assertEquals(first, second)
    }

    @Test
    fun articleIdUsesDollarSpacerPrefix() {
        val id = ArticleIdentity.articleId(ACCOUNT_ID, FEED_URL, "guid-abc")
        assertTrue("id must start with '<accountId>$': $id", id.startsWith("$ACCOUNT_ID$"))
    }

    @Test
    fun articleIdSeparatesFeedAndExternalId() {
        val base = ArticleIdentity.articleId(ACCOUNT_ID, FEED_URL, "guid-abc")
        assertNotEquals(base, ArticleIdentity.articleId(ACCOUNT_ID, "https://other.example/feed.xml", "guid-abc"))
        assertNotEquals(base, ArticleIdentity.articleId(ACCOUNT_ID, FEED_URL, "guid-abd"))
        assertNotEquals(base, ArticleIdentity.articleId(ACCOUNT_ID + 1, FEED_URL, "guid-abc"))
    }

    @Test
    fun externalIdPrefersGuid() {
        assertEquals(
            "guid-1",
            ArticleIdentity.externalId("guid-1", "https://x.example/1", "t", "u", "a", 1L, "s", "c")
        )
    }

    @Test
    fun externalIdFallsBackToLink() {
        assertEquals(
            "https://x.example/1",
            ArticleIdentity.externalId("", "https://x.example/1", "t", "u", "a", 1L, "s", "c")
        )
        assertEquals(
            "https://x.example/1",
            ArticleIdentity.externalId("   ", "https://x.example/1", "t", "u", "a", 1L, "s", "c")
        )
        assertEquals(
            "https://x.example/1",
            ArticleIdentity.externalId(null, "https://x.example/1", "t", "u", "a", 1L, "s", "c")
        )
    }

    @Test
    fun externalIdHashesJoinedFieldsWhenNoGuidOrLink() {
        val first = ArticleIdentity.externalId("", "", "Title", "https://x.example/1", "auth", 42L, "sum", "body")
        val second = ArticleIdentity.externalId(null, "", "Title", "https://x.example/1", "auth", 42L, "sum", "body")
        assertEquals(first, second)
        // Pinned against Python hashlib.sha256("Title|https://x.example/1|auth|42|sum|body".encode()).
        assertEquals("8fa5e7ef57bfc6047334e4ad4423789005bccf85bd58ab6229c2fdbb3266fd04", first)
        assertNotEquals(first, ArticleIdentity.externalId("", "", "Title", "https://x.example/1", "auth", 43L, "sum", "body"))
    }

    companion object {
        private const val ACCOUNT_ID = 1
        private const val FEED_URL = "https://example.com/feed.xml"
    }
}
