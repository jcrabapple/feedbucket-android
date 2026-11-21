package me.ash.reader.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.flow.flowOf
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.ArticleFlowItem
import me.ash.reader.domain.model.article.ArticleWithFeed
import me.ash.reader.domain.model.feed.Feed
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
import me.ash.reader.ui.page.home.flow.ArticleItem
import me.ash.reader.ui.page.home.flow.ArticleList
import me.ash.reader.ui.page.home.flow.FLOW_DIVIDER_TAG
import me.ash.reader.ui.theme.palette.FeedBucketColorSchemes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Date
import kotlin.math.abs

/**
 * REFRESH-v0.2 sections 1.1-1.4 instrumented gates: hairline dividers only
 * between adjacent articles (both list branches), the reserved 8dp unread
 * dot slot with feedName/title edge alignment across every read-indicator
 * preference, row stateDescription semantics, and thumbnails that are never
 * dimmed by a read-state alpha wash.
 */
class RefreshV02ListInstrumentedTest {

    @get:Rule val composeTestRule = createComposeRule()

    private val primaryArgb = FeedBucketColorSchemes.Light.primary.toArgb()
    private val surfaceArgb = FeedBucketColorSchemes.Light.surface.toArgb()
    private val opaqueRed = 0xFFFF0000.toInt()

    private fun article(
        id: String,
        title: String,
        isUnread: Boolean,
        isStarred: Boolean = false,
    ): ArticleFlowItem.Article =
        ArticleFlowItem.Article(
            ArticleWithFeed(
                article =
                    Article(
                            id = id,
                            date = Date(1654053729L),
                            title = title,
                            rawDescription = "desc $id",
                            shortDescription = "desc $id",
                            link = "https://example.com/$id",
                            feedId = "feed-1",
                            accountId = 1,
                            isUnread = isUnread,
                            isStarred = isStarred,
                        )
                        .apply { dateString = "10:00" },
                feed =
                    Feed(
                        id = "feed-1",
                        name = "Example Feed",
                        icon = null,
                        url = "https://example.com/feed.xml",
                        groupId = "group-1",
                        accountId = 1,
                        isNotification = false,
                        isFullContent = false,
                    ),
            )
        )

    private fun date(label: String) = ArticleFlowItem.Date(label, showSpacer = false)

    @Composable
    private fun FlowPrefs(
        readIndicator: FlowArticleReadIndicatorPreference,
        feedIcon: Boolean = true,
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
                    LocalFlowArticleListDesc provides FlowArticleListDescPreference.NONE,
                    LocalFlowArticleListTime provides FlowArticleListTimePreference.ON,
                    LocalFlowArticleListReadIndicator provides readIndicator,
                ) {
                    content()
                }
            }
        }
    }

    private fun setList(items: List<ArticleFlowItem>, sticky: Boolean) {
        composeTestRule.setContent {
            FlowPrefs(FlowArticleReadIndicatorPreference.ExcludingStarred) {
                val paging = flowOf(PagingData.from(items)).collectAsLazyPagingItems()
                LazyColumn(Modifier.fillMaxSize()) {
                    ArticleList(
                        pagingItems = paging,
                        diffMap = emptyMap(),
                        isShowFeedIcon = true,
                        isShowStickyHeader = sticky,
                        articleListTonalElevation = 0,
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun pixelAtDp(xDp: Float, yDp: Float): Int {
        val image = composeTestRule.onRoot().captureToImage().asAndroidBitmap()
        return pixelIn(image, xDp, yDp)
    }

    private fun pixelIn(image: Bitmap, xDp: Float, yDp: Float): Int {
        val d = composeTestRule.density.density
        val x = (xDp * d).toInt().coerceIn(0, image.width - 1)
        val y = (yDp * d).toInt().coerceIn(0, image.height - 1)
        return image.getPixel(x, y)
    }

    private fun pixelAtCenter(bounds: androidx.compose.ui.geometry.Rect): Int {
        val image = composeTestRule.onRoot().captureToImage().asAndroidBitmap()
        val x = ((bounds.left + bounds.right) / 2f).toInt().coerceIn(0, image.width - 1)
        val y = ((bounds.top + bounds.bottom) / 2f).toInt().coerceIn(0, image.height - 1)
        return image.getPixel(x, y)
    }

    private fun assertColorClose(
        actual: Int,
        expected: Int,
        message: String,
        tolerance: Int = 4,
    ) {
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

    private val mixedItems =
        listOf(
            date("Today"),
            article("a1", "First Article", isUnread = true),
            article("a2", "Second Article", isUnread = false),
            date("Yesterday"),
            article("a3", "Third Article", isUnread = true),
        )

    @Test
    fun dividersOnlyBetweenAdjacentArticles() {
        // Date header, a1, a2 (one divider between), Date header (none
        // before it), a3 (none after the last article).
        setList(mixedItems, sticky = false)
        composeTestRule.onAllNodesWithTag(FLOW_DIVIDER_TAG).assertCountEquals(1)
    }

    @Test
    fun dividersOnlyBetweenAdjacentArticlesStickyHeader() {
        setList(mixedItems, sticky = true)
        composeTestRule.onAllNodesWithTag(FLOW_DIVIDER_TAG).assertCountEquals(1)
    }

    @Test
    fun dividersBetweenEveryAdjacentArticlePair() {
        setList(
            listOf(
                article("a1", "First Article", isUnread = true),
                article("a2", "Second Article", isUnread = true),
                article("a3", "Third Article", isUnread = false),
            ),
            sticky = false,
        )
        composeTestRule.onAllNodesWithTag(FLOW_DIVIDER_TAG).assertCountEquals(2)
    }

    @Test
    fun dividersBetweenEveryAdjacentArticlePairStickyHeader() {
        setList(
            listOf(
                article("a1", "First Article", isUnread = true),
                article("a2", "Second Article", isUnread = true),
                article("a3", "Third Article", isUnread = false),
            ),
            sticky = true,
        )
        composeTestRule.onAllNodesWithTag(FLOW_DIVIDER_TAG).assertCountEquals(2)
    }

    @Test
    fun singleArticleHasNoDivider() {
        setList(listOf(article("a1", "Only Article", isUnread = true)), sticky = false)
        composeTestRule.onAllNodesWithTag(FLOW_DIVIDER_TAG).assertCountEquals(0)
    }

    @Test
    fun dotSlotIsReservedAndAlignedAcrossStates() {
        composeTestRule.setContent {
            FlowPrefs(FlowArticleReadIndicatorPreference.AllRead) {
                Column(Modifier.fillMaxWidth()) {
                    ArticleItem(
                        modifier = Modifier.testTag("row_unread"),
                        feedName = "Example Feed",
                        feedIconUrl = null,
                        title = "Unread Title",
                        timeString = "10:00",
                        isUnread = true,
                    )
                    ArticleItem(
                        modifier = Modifier.testTag("row_read"),
                        feedName = "Example Feed",
                        feedIconUrl = null,
                        title = "Read Title",
                        timeString = "10:00",
                        isUnread = false,
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        val unreadTitle =
            composeTestRule
                .onNodeWithText("Unread Title", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        val readTitle =
            composeTestRule
                .onNodeWithText("Read Title", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        // Reserved slot: read/unread never shift the title edge.
        assertEquals(unreadTitle.left, readTitle.left, 0.5f)

        // Structural gutter: feedName shares the title's left edge
        // (first "Example Feed" node belongs to the unread row).
        val unreadFeed =
            composeTestRule
                .onAllNodesWithText("Example Feed", useUnmergedTree = true)[0]
                .fetchSemanticsNode()
                .boundsInRoot
        assertEquals(unreadFeed.left, unreadTitle.left, 0.5f)

        // Reserved slot below the icon: both rows carry a dot node at
        // identical geometry; only the paint differs.
        val dots =
            composeTestRule.onAllNodesWithTag(ARTICLE_ROW_DOT_TAG, useUnmergedTree = true)
        dots.assertCountEquals(2)
        val unreadDot = dots[0].fetchSemanticsNode().boundsInRoot
        val readDot = dots[1].fetchSemanticsNode().boundsInRoot
        val unreadRow =
            composeTestRule.onNodeWithTag("row_unread").fetchSemanticsNode().boundsInRoot
        val readRow =
            composeTestRule.onNodeWithTag("row_read").fetchSemanticsNode().boundsInRoot
        assertEquals(unreadDot.left, readDot.left, 0.5f)
        assertEquals(unreadDot.top - unreadRow.top, readDot.top - readRow.top, 1.5f)
        // Dot pixel: primary center for the unread row, transparent (surface)
        // at the identical slot of the read row.
        assertColorClose(pixelAtCenter(unreadDot), primaryArgb, "unread dot center")
        assertColorClose(pixelAtCenter(readDot), surfaceArgb, "read dot slot is empty")
    }

    @Test
    fun noDotSlotUnderNonePreferenceAndAlignmentHolds() {
        composeTestRule.setContent {
            Column(Modifier.fillMaxWidth()) {
                FlowPrefs(FlowArticleReadIndicatorPreference.None) {
                    ArticleItem(
                        modifier = Modifier.testTag("row_none_icon"),
                        feedName = "Example Feed",
                        feedIconUrl = null,
                        title = "None Pref Title",
                        timeString = "10:00",
                        isUnread = false,
                    )
                }
                FlowPrefs(FlowArticleReadIndicatorPreference.AllRead) {
                    ArticleItem(
                        modifier = Modifier.testTag("row_allread_icon"),
                        feedName = "Example Feed",
                        feedIconUrl = null,
                        title = "AllRead Pref Title",
                        timeString = "10:00",
                        isUnread = false,
                    )
                }
                FlowPrefs(FlowArticleReadIndicatorPreference.None, feedIcon = false) {
                    ArticleItem(
                        modifier = Modifier.testTag("row_none_noicon"),
                        feedName = "Example Feed",
                        feedIconUrl = null,
                        title = "None NoIcon Title",
                        timeString = "10:00",
                        isUnread = false,
                    )
                }
                FlowPrefs(FlowArticleReadIndicatorPreference.AllRead, feedIcon = false) {
                    ArticleItem(
                        modifier = Modifier.testTag("row_allread_noicon"),
                        feedName = "Example Feed",
                        feedIconUrl = null,
                        title = "AllRead NoIcon Title",
                        timeString = "10:00",
                        isUnread = false,
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        val d = composeTestRule.density.density
        fun offsetDp(tag: String, title: String): Float {
            val rowLeft =
                composeTestRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.left
            val left =
                composeTestRule
                    .onNodeWithText(title, useUnmergedTree = true)
                    .fetchSemanticsNode()
                    .boundsInRoot
                    .left
            return (left - rowLeft) / d
        }

        // Icon on: the gutter is the constant 24dp favicon frame + 12dp gap
        // regardless of the read indicator, so None never differs from
        // AllRead. Row tag bounds start after the 12dp outer margin, so the
        // text edge sits 12dp inner padding + 36dp gutter total inside.
        assertEquals(48f, offsetDp("row_none_icon", "None Pref Title"), 1f)
        assertEquals(48f, offsetDp("row_allread_icon", "AllRead Pref Title"), 1f)
        // Icon off: None omits the gutter entirely (text at the 12dp inner
        // padding edge); AllRead reserves the 12dp dot lane + 12dp gap.
        assertEquals(12f, offsetDp("row_none_noicon", "None NoIcon Title"), 1f)
        assertEquals(36f, offsetDp("row_allread_noicon", "AllRead NoIcon Title"), 1f)

        // feedName aligns with the title edge in every configuration.
        val feeds = composeTestRule.onAllNodesWithText("Example Feed", useUnmergedTree = true)
        for ((i, title) in
            listOf(
                    "None Pref Title",
                    "AllRead Pref Title",
                    "None NoIcon Title",
                    "AllRead NoIcon Title",
                )
                .withIndex()
        ) {
            val titleLeft =
                composeTestRule
                    .onNodeWithText(title, useUnmergedTree = true)
                    .fetchSemanticsNode()
                    .boundsInRoot
                    .left
            assertEquals(
                "feedName/title edge for $title",
                titleLeft,
                feeds[i].fetchSemanticsNode().boundsInRoot.left,
                0.5f,
            )
        }
    }

    @Test
    fun starredReadShowsNoDotUnderExcludingStarred() {
        composeTestRule.setContent {
            FlowPrefs(FlowArticleReadIndicatorPreference.ExcludingStarred) {
                ArticleItem(
                    feedName = "Example Feed",
                    feedIconUrl = null,
                    title = "Starred Read Title",
                    timeString = "10:00",
                    isStarred = true,
                    isUnread = false,
                )
            }
        }
        composeTestRule.waitForIdle()
        val dots =
            composeTestRule.onAllNodesWithTag(ARTICLE_ROW_DOT_TAG, useUnmergedTree = true)
        dots.assertCountEquals(1)
        assertColorClose(
            pixelAtCenter(dots[0].fetchSemanticsNode().boundsInRoot),
            surfaceArgb,
            "starred-read dot slot is empty",
        )
        // Row still exposes the read state to TalkBack.
        composeTestRule
            .onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Read"))
            .assertIsDisplayed()
    }

    @Test
    fun rowsExposeReadStateDescriptions() {
        composeTestRule.setContent {
            FlowPrefs(FlowArticleReadIndicatorPreference.AllRead) {
                Column(Modifier.fillMaxWidth()) {
                    ArticleItem(
                        feedName = "Example Feed",
                        feedIconUrl = null,
                        title = "Unread Row",
                        timeString = "10:00",
                        isUnread = true,
                    )
                    ArticleItem(
                        feedName = "Example Feed",
                        feedIconUrl = null,
                        title = "Read Row",
                        timeString = "10:00",
                        isUnread = false,
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule
            .onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Unread"))
            .assertIsDisplayed()
        composeTestRule
            .onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Read"))
            .assertIsDisplayed()
    }

    @Test
    fun thumbnailIsNeverDimmedOnReadRows() {
        val redBitmap =
            Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(opaqueRed) }
        composeTestRule.setContent {
            FlowPrefs(FlowArticleReadIndicatorPreference.AllRead) {
                ArticleItem(
                    modifier = Modifier.testTag("thumb_row"),
                    feedName = "Example Feed",
                    feedIconUrl = null,
                    title = "Read With Image",
                    timeString = "10:00",
                    imgData = redBitmap,
                    isUnread = false,
                )
            }
        }
        composeTestRule.waitForIdle()

        val d = composeTestRule.density.density
        val row = composeTestRule.onNodeWithTag("thumb_row").fetchSemanticsNode().boundsInRoot
        val title =
            composeTestRule
                .onNodeWithText("Read With Image", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        // Thumbnail: 80dp box flush with the row content edge (12dp inner
        // padding), top-aligned with the title row.
        val thumbCenterXDp = row.right / d - 12f - 40f
        val thumbCenterYDp = title.top / d + 40f

        // The image pipeline crossfades in; poll until the center pixel
        // settles, then require FULL-opacity red (an alpha wash would blend
        // toward the surface and never match).
        composeTestRule.waitUntil(10_000) { pixelAtDp(thumbCenterXDp, thumbCenterYDp) == opaqueRed }
        assertColorClose(
            pixelAtDp(thumbCenterXDp, thumbCenterYDp),
            opaqueRed,
            "read-row thumbnail center",
        )
    }
}
