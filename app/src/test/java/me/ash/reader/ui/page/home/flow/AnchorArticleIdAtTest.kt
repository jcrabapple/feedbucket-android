package me.ash.reader.ui.page.home.flow

import androidx.paging.ItemSnapshotList
import java.util.Date
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.ArticleFlowItem
import me.ash.reader.domain.model.article.ArticleWithFeed
import me.ash.reader.domain.model.feed.Feed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnchorArticleIdAtTest {

    private fun articleItem(id: String) =
        ArticleFlowItem.Article(
            ArticleWithFeed(
                article =
                    Article(
                        id = id,
                        date = Date(1_000L),
                        title = "t",
                        rawDescription = "body",
                        shortDescription = "short",
                        link = "https://example.com/$id",
                        feedId = "feed",
                        accountId = 1,
                    ),
                feed =
                    Feed(
                        id = "feed",
                        name = "Feed",
                        url = "https://example.com/feed.xml",
                        groupId = "group",
                        accountId = 1,
                    ),
            )
        )

    private fun snapshot(vararg items: ArticleFlowItem) =
        ItemSnapshotList(0, 0, items.toList())

    @Test
    fun `empty snapshot at index 0 returns null instead of throwing`() {
        // Regression: Read -> Feeds fires ON_PAUSE with a stale firstVisibleItemIndex
        // while the paging snapshot is empty; peek(0) threw IndexOutOfBoundsException.
        assertNull(anchorArticleIdAt(snapshot(), 0))
    }

    @Test
    fun `null snapshot returns null`() {
        assertNull(anchorArticleIdAt(null, 0))
    }

    @Test
    fun `negative index returns null`() {
        assertNull(anchorArticleIdAt(snapshot(articleItem("a")), -1))
    }

    @Test
    fun `index beyond size returns null`() {
        assertNull(anchorArticleIdAt(snapshot(articleItem("a")), 1))
        assertNull(anchorArticleIdAt(snapshot(articleItem("a")), 99))
    }

    @Test
    fun `valid article index returns its id`() {
        val items = snapshot(articleItem("a"), articleItem("b"))
        assertEquals("a", anchorArticleIdAt(items, 0))
        assertEquals("b", anchorArticleIdAt(items, 1))
    }

    @Test
    fun `date separator position returns null`() {
        val items = snapshot(ArticleFlowItem.Date("Today", false), articleItem("a"))
        assertNull(anchorArticleIdAt(items, 0))
        assertEquals("a", anchorArticleIdAt(items, 1))
    }

    @Test
    fun `placeholder position returns null`() {
        val items = ItemSnapshotList(1, 0, listOf<ArticleFlowItem>(articleItem("a")))
        assertNull(anchorArticleIdAt(items, 0))
        assertEquals("a", anchorArticleIdAt(items, 1))
    }
}
