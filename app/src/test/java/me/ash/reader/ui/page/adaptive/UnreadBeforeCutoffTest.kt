package me.ash.reader.ui.page.adaptive

import java.util.Date
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.general.MarkAsReadConditions
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnreadBeforeCutoffTest {

    private fun article(date: Date, isUnread: Boolean = true) =
        Article(
            id = "a-${date.time}",
            date = date,
            title = "t",
            rawDescription = "body",
            shortDescription = "short",
            link = "https://example.com/x",
            feedId = "feed",
            accountId = 1,
            isUnread = isUnread,
        )

    @Test
    fun `null cutoff from All matches every unread article without throwing`() {
        // Regression: mark-all-read uses MarkAsReadConditions.All, whose toDate() is null.
        assertNull(MarkAsReadConditions.All.toDate())
        assertTrue(isUnreadBeforeCutoff(article(Date(0L)), null))
        assertTrue(isUnreadBeforeCutoff(article(Date(9_000_000_000_000L)), null))
    }

    @Test
    fun `null cutoff still skips already read articles`() {
        assertFalse(isUnreadBeforeCutoff(article(Date(0L), isUnread = false), null))
    }

    @Test
    fun `dated cutoff only matches unread articles older than the cutoff`() {
        val cutoff = Date(1_000_000L)
        assertTrue(isUnreadBeforeCutoff(article(Date(999_999L)), cutoff))
        assertFalse(isUnreadBeforeCutoff(article(Date(1_000_001L)), cutoff))
        assertFalse(isUnreadBeforeCutoff(article(Date(1L), isUnread = false), cutoff))
    }
}
