package me.ash.reader.ui.page.home.reading

import java.util.Date
import me.ash.reader.ui.page.adaptive.ReaderState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Lane 4 defect 2 — key-stability contract for the ReadingPage
 * `AnimatedContent` identity ([ReaderBodyState] / [toBodyState]).
 *
 * The animation must fire ONLY when the rendered article body changes.
 * Transient flags (isExtracting, the ≤3 s extractFailed tint), prefetch
 * neighbour updates and listIndex changes previously re-ran the whole
 * slide/fade transition and recreated `rememberScrollState()`, resetting
 * the reader's scroll position mid-article.
 */
class ReaderBodyStateTest {

    private val published = Date(1_700_000_000_000L)

    private fun baseState(
        articleId: String? = "1\$article-a",
        content: ReaderState.ContentState = ReaderState.Description("body text"),
        isExtracting: Boolean = false,
        extractFailed: Boolean = false,
        nextArticle: ReaderState.PrefetchResult? = null,
        previousArticle: ReaderState.PrefetchResult? = null,
        listIndex: Int? = 0,
    ) = ReaderState(
        articleId = articleId,
        feedName = "Feed",
        title = "Title",
        author = "Author",
        link = "https://example.com/a",
        publishedDate = published,
        content = content,
        listIndex = listIndex,
        nextArticle = nextArticle,
        previousArticle = previousArticle,
        isExtracting = isExtracting,
        extractFailed = extractFailed,
    )

    @Test
    fun transientExtractFlagsDoNotChangeBodyIdentity() {
        val idle = baseState().toBodyState()
        assertEquals(idle, baseState(isExtracting = true).toBodyState())
        assertEquals(idle, baseState(extractFailed = true).toBodyState())
        assertEquals(idle, baseState(isExtracting = true, extractFailed = true).toBodyState())
        // hashCode must agree too — AnimatedContent identity is equality-based,
        // but a divergent hashCode would still churn snapshot lookups.
        assertEquals(idle.hashCode(), baseState(isExtracting = true).toBodyState().hashCode())
    }

    @Test
    fun prefetchNeighboursAndListIndexDoNotChangeBodyIdentity() {
        val bare = baseState().toBodyState()
        assertEquals(
            bare,
            baseState(
                nextArticle = ReaderState.PrefetchResult("1\$article-b", 1),
                previousArticle = ReaderState.PrefetchResult("1\$article-z", -1),
                listIndex = 42,
            ).toBodyState(),
        )
    }

    @Test
    fun articleSwitchChangesBodyIdentity() {
        assertNotEquals(baseState().toBodyState(), baseState(articleId = "1\$article-b").toBodyState())
    }

    @Test
    fun contentTextChangeChangesBodyIdentity() {
        assertNotEquals(
            baseState(content = ReaderState.Description("short")).toBodyState(),
            baseState(content = ReaderState.Description("full extracted text")).toBodyState(),
        )
    }

    @Test
    fun contentClassChangeChangesBodyIdentity() {
        // Same text, different render path: Description toggling to
        // FullContent must animate (this drives the same-article direction
        // branch in ReadingPage's transitionSpec).
        assertNotEquals(
            baseState(content = ReaderState.Description("same")).toBodyState(),
            baseState(content = ReaderState.FullContent("same")).toBodyState(),
        )
    }

    @Test
    fun loadingToContentChangesBodyIdentity() {
        assertNotEquals(
            baseState(content = ReaderState.Loading).toBodyState(),
            baseState(content = ReaderState.Description("body text")).toBodyState(),
        )
    }

    @Test
    fun renderedBodyFieldsAreCarriedIntoTheSnapshot() {
        val body = baseState().toBodyState()
        assertEquals("1\$article-a", body.articleId)
        assertEquals("Feed", body.feedName)
        assertEquals("Title", body.title)
        assertEquals("Author", body.author)
        assertEquals("https://example.com/a", body.link)
        assertEquals(published, body.publishedDate)
        assertEquals(ReaderState.Description("body text"), body.content)
    }
}
