package me.ash.reader.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import me.ash.reader.infrastructure.preference.FlowArticleListDescPreference
import me.ash.reader.infrastructure.preference.FlowArticleListFeedIconPreference
import me.ash.reader.infrastructure.preference.FlowArticleListFeedNamePreference
import me.ash.reader.infrastructure.preference.FlowArticleListImagePreference
import me.ash.reader.infrastructure.preference.FlowArticleListTimePreference
import me.ash.reader.infrastructure.preference.FlowArticleReadIndicatorPreference
import me.ash.reader.infrastructure.preference.LocalFlowArticleListDesc
import me.ash.reader.infrastructure.preference.LocalFlowArticleListFeedIcon
import me.ash.reader.infrastructure.preference.LocalFlowArticleListFeedName
import me.ash.reader.infrastructure.preference.LocalFlowArticleListImage
import me.ash.reader.infrastructure.preference.LocalFlowArticleListReadIndicator
import me.ash.reader.infrastructure.preference.LocalFlowArticleListTime
import me.ash.reader.ui.page.home.flow.ARTICLE_ROW_DOT_TAG
import me.ash.reader.ui.page.home.flow.ARTICLE_ROW_GUTTER_TAG
import me.ash.reader.ui.page.home.flow.ARTICLE_ROW_ICON_TAG
import me.ash.reader.ui.page.home.flow.ArticleItem
import me.ash.reader.ui.page.home.flow.StickyHeader
import me.ash.reader.ui.theme.palette.FeedBucketColorSchemes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

/**
 * Structural gutter alignment gates for the flow row redesign: feedName,
 * title and snippet share the exact same left edge across icon-on/off,
 * dot-reserved/omitted, thumbnail-on/off, starred and read/unread
 * combinations; the unread dot renders below the favicon inside the left
 * gutter; the sticky date header starts on the row text column edge; and
 * read/unread toggling never shifts text or icon geometry.
 *
 * Geometry notes (420dpi, density 2.625): the row test tag sits on the
 * merged clickable node, whose bounds start after the 12dp outer row
 * margin, so text-column offsets measured from the tag bounds are
 * 12dp inner padding + gutter + 12dp gap. Fractional-dp padding segments
 * round independently per node, so cross-component comparisons (sticky
 * header vs row text) carry a 3px tolerance; same-column text lines are
 * structurally identical and held to 1px.
 */
class ArticleItemAlignmentInstrumentedTest {

    @get:Rule val composeTestRule = createComposeRule()

    private val primaryArgb = FeedBucketColorSchemes.Light.primary.toArgb()
    private val surfaceArgb = FeedBucketColorSchemes.Light.surface.toArgb()
    private val opaqueRed = 0xFFFF0000.toInt()

    @Composable
    private fun FlowPrefs(
        feedIcon: Boolean,
        readIndicator: FlowArticleReadIndicatorPreference,
        content: @Composable () -> Unit,
    ) {
        MaterialTheme(colorScheme = FeedBucketColorSchemes.Light) {
            Surface(color = MaterialTheme.colorScheme.surface) {
                CompositionLocalProvider(
                    LocalFlowArticleListFeedIcon provides
                        if (feedIcon) FlowArticleListFeedIconPreference.ON
                        else FlowArticleListFeedIconPreference.OFF,
                    LocalFlowArticleListFeedName provides FlowArticleListFeedNamePreference.ON,
                    LocalFlowArticleListImage provides FlowArticleListImagePreference.ON,
                    LocalFlowArticleListDesc provides FlowArticleListDescPreference.SHORT,
                    LocalFlowArticleListTime provides FlowArticleListTimePreference.ON,
                    LocalFlowArticleListReadIndicator provides readIndicator,
                ) {
                    content()
                }
            }
        }
    }

    private fun leftOf(text: String): Float = boundsOf(text).left

    private fun boundsOf(text: String): Rect =
        composeTestRule
            .onNodeWithText(text, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot

    private fun assertSameLeft(label: String, tolerance: Float = 1f, vararg lefts: Float) {
        for (left in lefts) {
            assertTrue(
                "$label: lefts ${lefts.toList()} differ by more than ${tolerance}px",
                abs(left - lefts[0]) <= tolerance,
            )
        }
    }

    private fun pixelAtCenter(bounds: Rect): Int {
        val image = composeTestRule.onRoot().captureToImage().asAndroidBitmap()
        val x = ((bounds.left + bounds.right) / 2f).toInt().coerceIn(0, image.width - 1)
        val y = ((bounds.top + bounds.bottom) / 2f).toInt().coerceIn(0, image.height - 1)
        return image.getPixel(x, y)
    }

    private fun assertColorClose(actual: Int, expected: Int, message: String, tolerance: Int = 4) {
        for (shift in intArrayOf(16, 8, 0)) {
            val a = (actual shr shift) and 0xFF
            val e = (expected shr shift) and 0xFF
            assertTrue(
                "$message: actual=0x${Integer.toHexString(actual)} " +
                    "expected=0x${Integer.toHexString(expected)}",
                abs(a - e) <= tolerance,
            )
        }
    }

    @Composable
    private fun row(
        tag: String,
        feed: String,
        title: String,
        snippet: String,
        isUnread: Boolean,
        isStarred: Boolean = false,
        thumbnail: Bitmap? = null,
    ) {
        ArticleItem(
            modifier = Modifier.fillMaxWidth().testTag(tag),
            feedName = feed,
            feedIconUrl = null,
            title = title,
            shortDescription = snippet,
            timeString = "10:00",
            imgData = thumbnail,
            isStarred = isStarred,
            isUnread = isUnread,
        )
    }

    @Test
    fun textEdgesShareExactLeftAcrossIconThumbnailAndReadStates() {
        val red =
            Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(opaqueRed) }
        composeTestRule.setContent {
            Column(Modifier.fillMaxWidth()) {
                FlowPrefs(feedIcon = true, FlowArticleReadIndicatorPreference.ExcludingStarred) {
                    Column(Modifier.fillMaxWidth()) {
                        row("rowA", "Feed Alpha", "Alpha Unread Starred Thumb", "snippet alpha", true, true, red)
                        row("rowB", "Feed Beta", "Beta Read Plain", "snippet beta", false)
                    }
                }
                FlowPrefs(feedIcon = false, FlowArticleReadIndicatorPreference.AllRead) {
                    Column(Modifier.fillMaxWidth()) {
                        row("rowC", "Feed Gamma", "Gamma Unread Thumb", "snippet gamma", true, false, red)
                        row("rowD", "Feed Delta", "Delta Read Starred", "snippet delta", false, true)
                    }
                }
            }
        }
        composeTestRule.waitForIdle()

        // feedName.left == title.left == shortDescription.left, per row.
        assertSameLeft(
            "rowA",
            lefts = floatArrayOf(
                leftOf("Feed Alpha"),
                leftOf("Alpha Unread Starred Thumb"),
                leftOf("snippet alpha"),
            ),
        )
        assertSameLeft(
            "rowB",
            lefts = floatArrayOf(
                leftOf("Feed Beta"),
                leftOf("Beta Read Plain"),
                leftOf("snippet beta"),
            ),
        )
        assertSameLeft(
            "rowC",
            lefts = floatArrayOf(
                leftOf("Feed Gamma"),
                leftOf("Gamma Unread Thumb"),
                leftOf("snippet gamma"),
            ),
        )
        assertSameLeft(
            "rowD",
            lefts = floatArrayOf(
                leftOf("Feed Delta"),
                leftOf("Delta Read Starred"),
                leftOf("snippet delta"),
            ),
        )

        // Structural offsets from the row tag bounds (which start after the
        // 12dp outer margin): 12dp inner padding + gutter + 12dp gap.
        val d = composeTestRule.density.density
        for ((tag, title, gutterTotalDp) in
            listOf(
                Triple("rowA", "Alpha Unread Starred Thumb", 36f), // 24dp icon + 12dp gap
                Triple("rowB", "Beta Read Plain", 36f),
                Triple("rowC", "Gamma Unread Thumb", 24f), // 12dp dot lane + 12dp gap
                Triple("rowD", "Delta Read Starred", 24f),
            )
        ) {
            val rowLeft =
                composeTestRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.left
            assertEquals(
                "$tag text column offset",
                12f + gutterTotalDp,
                (leftOf(title) - rowLeft) / d,
                1f,
            )
        }
    }

    @Test
    fun dotSitsBelowIconInsideLeftGutter() {
        val red =
            Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(opaqueRed) }
        composeTestRule.setContent {
            Column(Modifier.fillMaxWidth()) {
                FlowPrefs(feedIcon = true, FlowArticleReadIndicatorPreference.ExcludingStarred) {
                    Column(Modifier.fillMaxWidth()) {
                        row("rowU", "Feed Unread", "Unread Geometry", "snippet u", true, false, red)
                        row("rowR", "Feed Read", "Read Geometry", "snippet r", false)
                    }
                }
                FlowPrefs(feedIcon = false, FlowArticleReadIndicatorPreference.AllRead) {
                    row("rowN", "Feed NoIcon", "NoIcon Geometry", "snippet n", true)
                }
            }
        }
        composeTestRule.waitForIdle()

        val d = composeTestRule.density.density
        val gutters =
            composeTestRule.onAllNodesWithTag(ARTICLE_ROW_GUTTER_TAG, useUnmergedTree = true)
        val icons =
            composeTestRule.onAllNodesWithTag(ARTICLE_ROW_ICON_TAG, useUnmergedTree = true)
        val dots =
            composeTestRule.onAllNodesWithTag(ARTICLE_ROW_DOT_TAG, useUnmergedTree = true)
        gutters.assertCountEquals(3)
        icons.assertCountEquals(2)
        dots.assertCountEquals(3)

        // Rows 0/1: icon shown. The dot lives below the icon, inside the
        // gutter, entirely left of the text column.
        for (i in 0..1) {
            val gutter = gutters[i].fetchSemanticsNode().boundsInRoot
            val icon = icons[i].fetchSemanticsNode().boundsInRoot
            val dot = dots[i].fetchSemanticsNode().boundsInRoot
            assertTrue("icon at gutter top", abs(icon.top - gutter.top) <= 1f)
            assertTrue(
                "dot below icon: dot.top=${dot.top} icon.bottom=${icon.bottom}",
                dot.top >= icon.bottom,
            )
            assertTrue("dot inside gutter", dot.bottom <= gutter.bottom + 1f)
            assertTrue("dot 8dp wide", abs(dot.width / d - 8f) <= 0.5f)
        }
        val unreadTitle = boundsOf("Unread Geometry")
        val readTitle = boundsOf("Read Geometry")
        val unreadDot = dots[0].fetchSemanticsNode().boundsInRoot
        val readDot = dots[1].fetchSemanticsNode().boundsInRoot
        assertTrue("unread dot left of title", unreadDot.right <= unreadTitle.left)
        assertTrue("read dot left of title", readDot.right <= readTitle.left)
        // Read/unread share the identical reserved slot geometry (rows stack
        // vertically, so compare the row-relative dot top).
        val rowUBounds = composeTestRule.onNodeWithTag("rowU").fetchSemanticsNode().boundsInRoot
        val rowRBounds = composeTestRule.onNodeWithTag("rowR").fetchSemanticsNode().boundsInRoot
        assertEquals(readDot.left, unreadDot.left, 1f)
        assertEquals(readDot.top - rowRBounds.top, unreadDot.top - rowUBounds.top, 1.5f)

        // Unread dot painted primary; the reserved read slot stays empty.
        assertColorClose(pixelAtCenter(unreadDot), primaryArgb, "unread dot center")
        assertColorClose(pixelAtCenter(readDot), surfaceArgb, "read dot slot empty")

        // Row 2: icon hidden, dot lane only (12dp lane + 12dp gap = 24dp).
        val noIconGutter = gutters[2].fetchSemanticsNode().boundsInRoot
        val noIconDot = dots[2].fetchSemanticsNode().boundsInRoot
        val noIconTitle = boundsOf("NoIcon Geometry")
        assertEquals("dot-lane gutter width", 24f, noIconGutter.width / d, 1f)
        assertTrue("lane dot left of title", noIconDot.right <= noIconTitle.left)
        assertTrue(
            "lane dot inside gutter",
            noIconDot.left >= noIconGutter.left - 1f && noIconDot.right <= noIconGutter.right + 1f,
        )
        assertColorClose(pixelAtCenter(noIconDot), primaryArgb, "no-icon unread dot center")
    }

    @Test
    fun readUnreadToggleNeverShiftsTextOrIcon() {
        composeTestRule.setContent {
            Column(Modifier.fillMaxWidth()) {
                FlowPrefs(feedIcon = true, FlowArticleReadIndicatorPreference.ExcludingStarred) {
                    Column(Modifier.fillMaxWidth()) {
                        row("rowT1", "Feed Toggle", "Toggle Unread", "snippet t1", true)
                        row("rowT2", "Feed Toggle", "Toggle Read", "snippet t2", false)
                    }
                }
            }
        }
        composeTestRule.waitForIdle()

        val icons =
            composeTestRule.onAllNodesWithTag(ARTICLE_ROW_ICON_TAG, useUnmergedTree = true)
        val dots =
            composeTestRule.onAllNodesWithTag(ARTICLE_ROW_DOT_TAG, useUnmergedTree = true)
        val rowT1 = composeTestRule.onNodeWithTag("rowT1").fetchSemanticsNode().boundsInRoot
        val rowT2 = composeTestRule.onNodeWithTag("rowT2").fetchSemanticsNode().boundsInRoot
        val iconA = icons[0].fetchSemanticsNode().boundsInRoot
        val iconB = icons[1].fetchSemanticsNode().boundsInRoot
        val dotA = dots[0].fetchSemanticsNode().boundsInRoot
        val dotB = dots[1].fetchSemanticsNode().boundsInRoot

        // Identical geometry for icon and reserved dot slot across states
        // (rows stack vertically, so compare row-relative tops).
        assertEquals(iconA.left, iconB.left, 1f)
        assertEquals(iconA.top - rowT1.top, iconB.top - rowT2.top, 1.5f)
        assertEquals(dotA.left, dotB.left, 1f)
        assertEquals(dotA.top - rowT1.top, dotB.top - rowT2.top, 1.5f)

        // Text column does not move: titles share the exact left edge and
        // each feedName sits on its own row's title edge.
        assertEquals(leftOf("Toggle Unread"), leftOf("Toggle Read"), 1f)
        val feeds = composeTestRule.onAllNodesWithText("Feed Toggle", useUnmergedTree = true)
        feeds.assertCountEquals(2)
        assertEquals(feeds[0].fetchSemanticsNode().boundsInRoot.left, leftOf("Toggle Unread"), 1f)
        assertEquals(feeds[1].fetchSemanticsNode().boundsInRoot.left, leftOf("Toggle Read"), 1f)
    }

    @Test
    fun stickyHeaderStartsOnRowTextColumnEdge() {
        composeTestRule.setContent {
            Column(Modifier.fillMaxWidth()) {
                FlowPrefs(feedIcon = true, FlowArticleReadIndicatorPreference.ExcludingStarred) {
                    Column(Modifier.fillMaxWidth()) {
                        StickyHeader("Header Icon On", isShowFeedIcon = true, articleListTonalElevation = 0)
                        row("rowH1", "Feed Header", "Header Row Title", "snippet h1", true)
                    }
                }
                FlowPrefs(feedIcon = false, FlowArticleReadIndicatorPreference.AllRead) {
                    Column(Modifier.fillMaxWidth()) {
                        StickyHeader("Header Icon Off", isShowFeedIcon = false, articleListTonalElevation = 0)
                        row("rowH2", "Feed Header2", "Header Row Title2", "snippet h2", true)
                    }
                }
                FlowPrefs(feedIcon = false, FlowArticleReadIndicatorPreference.None) {
                    Column(Modifier.fillMaxWidth()) {
                        StickyHeader("Header No Gutter", isShowFeedIcon = false, articleListTonalElevation = 0)
                        row("rowH3", "Feed Header3", "Header Row Title3", "snippet h3", true)
                    }
                }
            }
        }
        composeTestRule.waitForIdle()

        val d = composeTestRule.density.density
        // Icon on: header text aligns with the row text column (60dp edge).
        // Cross-component tolerance: fractional-dp segments round per node.
        assertSameLeft(
            "icon-on header",
            tolerance = 3f,
            lefts = floatArrayOf(
                leftOf("Header Icon On"),
                leftOf("Feed Header"),
                leftOf("Header Row Title"),
                leftOf("snippet h1"),
            ),
        )
        // Icon off, dot lane reserved: header aligns with the 48dp text edge.
        assertSameLeft(
            "icon-off header",
            tolerance = 3f,
            lefts = floatArrayOf(
                leftOf("Header Icon Off"),
                leftOf("Feed Header2"),
                leftOf("Header Row Title2"),
                leftOf("snippet h2"),
            ),
        )
        // No gutter at all: header aligns with the card content edge (24dp).
        val rowH3Left =
            composeTestRule.onNodeWithTag("rowH3").fetchSemanticsNode().boundsInRoot.left
        assertSameLeft(
            "no-gutter header",
            tolerance = 3f,
            lefts = floatArrayOf(
                leftOf("Header No Gutter"),
                leftOf("Feed Header3"),
                leftOf("Header Row Title3"),
                leftOf("snippet h3"),
            ),
        )
        assertEquals(
            "no-gutter text offset",
            12f,
            (leftOf("Header Row Title3") - rowH3Left) / d,
            1f,
        )
    }
}
