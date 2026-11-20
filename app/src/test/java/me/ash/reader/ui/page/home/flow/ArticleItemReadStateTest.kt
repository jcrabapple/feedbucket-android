package me.ash.reader.ui.page.home.flow

import androidx.compose.ui.unit.dp
import me.ash.reader.infrastructure.preference.FlowArticleReadIndicatorPreference.AllRead
import me.ash.reader.infrastructure.preference.FlowArticleReadIndicatorPreference.ExcludingStarred
import me.ash.reader.infrastructure.preference.FlowArticleReadIndicatorPreference.None
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REFRESH-v0.2 sections 1.1-1.3: the flow row read-state truth table
 * (emphasis, dot visibility, reserved slot, feedName start alignment)
 * pinned against the extracted pure helper.
 */
class ArticleItemReadStateTest {

    @Test
    fun noneAlwaysEmphasizesAndNeverShowsOrReservesDot() {
        for (unread in listOf(true, false)) {
            for (starred in listOf(true, false)) {
                assertTrue(ArticleItemReadState.emphasized(None, unread, starred))
                assertFalse(ArticleItemReadState.showDot(None, unread))
            }
        }
        assertFalse(ArticleItemReadState.reservesDotSlot(None))
    }

    @Test
    fun allReadEmphasizesOnlyUnreadAndDotsOnlyUnread() {
        assertTrue(ArticleItemReadState.emphasized(AllRead, isUnread = true, isStarred = false))
        assertTrue(ArticleItemReadState.emphasized(AllRead, isUnread = true, isStarred = true))
        assertFalse(ArticleItemReadState.emphasized(AllRead, isUnread = false, isStarred = true))
        assertFalse(ArticleItemReadState.emphasized(AllRead, isUnread = false, isStarred = false))
        assertTrue(ArticleItemReadState.showDot(AllRead, isUnread = true))
        assertFalse(ArticleItemReadState.showDot(AllRead, isUnread = false))
        assertTrue(ArticleItemReadState.reservesDotSlot(AllRead))
    }

    @Test
    fun excludingStarredEmphasizesStarredReadWithoutDot() {
        // Unread: emphasized + dot.
        assertTrue(ArticleItemReadState.emphasized(ExcludingStarred, isUnread = true, isStarred = false))
        assertTrue(ArticleItemReadState.showDot(ExcludingStarred, isUnread = true))
        // Read + starred: unread-level typography, NO dot (filled star carries it).
        assertTrue(ArticleItemReadState.emphasized(ExcludingStarred, isUnread = false, isStarred = true))
        assertFalse(ArticleItemReadState.showDot(ExcludingStarred, isUnread = false))
        // Read, not starred: de-emphasized, no dot.
        assertFalse(ArticleItemReadState.emphasized(ExcludingStarred, isUnread = false, isStarred = false))
        assertTrue(ArticleItemReadState.reservesDotSlot(ExcludingStarred))
    }

    @Test
    fun dotIsStrictlyUnreadAcrossEveryPreference() {
        for (pref in listOf(None, AllRead, ExcludingStarred)) {
            for (unread in listOf(true, false)) {
                assertEquals(
                    "$pref unread=$unread",
                    pref != None && unread,
                    ArticleItemReadState.showDot(pref, unread),
                )
            }
        }
    }

    @Test
    fun feedNameStartPaddingKeepsTitleEdgeAlignment() {
        // 24dp favicon frame + 10dp gap, plus the 16dp reserved dot slot
        // (8dp dot + 8dp spacer) whenever the indicator is not None.
        assertEquals(50.dp, ArticleItemReadState.feedNameStartPadding(true, ExcludingStarred))
        assertEquals(50.dp, ArticleItemReadState.feedNameStartPadding(true, AllRead))
        assertEquals(34.dp, ArticleItemReadState.feedNameStartPadding(true, None))
        assertEquals(16.dp, ArticleItemReadState.feedNameStartPadding(false, ExcludingStarred))
        assertEquals(16.dp, ArticleItemReadState.feedNameStartPadding(false, AllRead))
        assertEquals(0.dp, ArticleItemReadState.feedNameStartPadding(false, None))
    }
}
