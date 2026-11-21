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
 * (emphasis, dot visibility, reserved slot, structural gutter geometry)
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
    fun gutterWidthIsFaviconFrameWhenIconShownRegardlessOfIndicator() {
        // The favicon frame (24dp) is the gutter; the reserved dot lives
        // below it, so the indicator preference never changes the width.
        for (pref in listOf(None, AllRead, ExcludingStarred)) {
            assertEquals(24.dp, ArticleItemReadState.gutterWidth(true, pref))
            assertTrue(ArticleItemReadState.showsGutter(true, pref))
        }
    }

    @Test
    fun gutterWidthIsDotLaneWhenIconHiddenAndIndicatorReserved() {
        assertEquals(12.dp, ArticleItemReadState.gutterWidth(false, ExcludingStarred))
        assertEquals(12.dp, ArticleItemReadState.gutterWidth(false, AllRead))
        assertTrue(ArticleItemReadState.showsGutter(false, ExcludingStarred))
        assertTrue(ArticleItemReadState.showsGutter(false, AllRead))
    }

    @Test
    fun gutterOmittedOnlyWhenIconHiddenAndIndicatorNone() {
        // Text column spans the full padded width; zero visual change for
        // users who opted out of both favicon and read indicator.
        assertEquals(0.dp, ArticleItemReadState.gutterWidth(false, None))
        assertFalse(ArticleItemReadState.showsGutter(false, None))
    }

    @Test
    fun stickyHeaderStartAlignsWithRowTextColumnEdge() {
        // Card content edge (12dp margin + 12dp inner padding) + gutter +
        // 12dp gutter gap when a gutter is shown, else the content edge.
        assertEquals(60.dp, ArticleItemReadState.stickyHeaderStartPadding(true, ExcludingStarred))
        assertEquals(60.dp, ArticleItemReadState.stickyHeaderStartPadding(true, AllRead))
        assertEquals(60.dp, ArticleItemReadState.stickyHeaderStartPadding(true, None))
        assertEquals(48.dp, ArticleItemReadState.stickyHeaderStartPadding(false, ExcludingStarred))
        assertEquals(48.dp, ArticleItemReadState.stickyHeaderStartPadding(false, AllRead))
        assertEquals(24.dp, ArticleItemReadState.stickyHeaderStartPadding(false, None))
    }
}
