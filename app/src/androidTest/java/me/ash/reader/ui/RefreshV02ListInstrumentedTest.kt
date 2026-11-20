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
        content: @Composable () -> Unit,
    ) {
        MaterialTheme(colorScheme = FeedBucketColorSchemes.Light) {
            Surface(color = MaterialTheme.colorScheme.surface) {
                CompositionLocalProvider(
                    LocalFlowArticleListFeedIcon provides FlowArticleListFeedIconPreference.ON,
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

        val d = composeTestRule.density.density
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

        // feedName start padding compensates: same vertical edge as title
        // (first "Example Feed" node belongs to the unread row).
        val unreadFeed =
            composeTestRule
                .onAllNodesWithText("Example Feed", useUnmergedTree = true)[0]
                .fetchSemanticsNode()
                .boundsInRoot
        assertEquals(unreadFeed.left, unreadTitle.left, 0.5f)

        // Diagnostic scan: locate every primary-colored pixel in the unread
        // row (dot position witness in the test log).
        val image = composeTestRule.onRoot().captureToImage().asAndroidBitmap()
        val row = composeTestRule.onNodeWithTag("row_unread").fetchSemanticsNode().boundsInRoot
        println(
            "[probe] title dp rect L=${unreadTitle.left / d} T=${unreadTitle.top / d} " +
                "R=${unreadTitle.right / d} B=${unreadTitle.bottom / d}; " +
                "row dp rect L=${row.left / d} T=${row.top / d} R=${row.right / d} B=${row.bottom / d}"
        )
        var hits = 0
        var y = row.top.toInt().coerceAtLeast(0)
        while (y < row.bottom.toInt().coerceAtMost(image.height)) {
            var x = row.left.toInt().coerceAtLeast(0)
            while (x < row.right.toInt().coerceAtMost(image.width)) {
                if (image.getPixel(x, y) == primaryArgb) {
                    if (hits < 24) {
                        println("[probe] primary px ($x,$y) dp (${x / d},${y / d})")
                    }
                    hits++
                }
                x += 2
            }
            y += 2
        }
        println("[probe] primary pixel hits in unread row: $hits")

        // Dot pixel: primary center for the unread row, transparent (surface)
        // at the identical slot of the read row.
        val unreadDotX = unreadTitle.left / d - 12f
        val unreadDotY = unreadTitle.top / d + 4f
        println(
            "[probe] unread dot sample dp ($unreadDotX,$unreadDotY) " +
                "px=0x${Integer.toHexString(pixelIn(image, unreadDotX, unreadDotY))}"
        )
        assertColorClose(pixelIn(image, unreadDotX, unreadDotY), primaryArgb, "unread dot center")
        val readDotX = readTitle.left / d - 12f
        val readDotY = readTitle.top / d + 4f
        assertColorClose(pixelIn(image, readDotX, readDotY), surfaceArgb, "read dot slot is empty")
    }

    @Test
    fun noDotSlotUnderNonePreferenceAndAlignmentHolds() {
        composeTestRule.setContent {
            Column(Modifier.fillMaxWidth()) {
                FlowPrefs(FlowArticleReadIndicatorPreference.None) {
                    ArticleItem(
                        feedName = "Example Feed",
                        feedIconUrl = null,
                        title = "None Pref Title",
                        timeString = "10:00",
                        isUnread = false,
                    )
                }
                FlowPrefs(FlowArticleReadIndicatorPreference.AllRead) {
                    ArticleItem(
                        feedName = "Example Feed",
                        feedIconUrl = null,
                        title = "AllRead Pref Title",
                        timeString = "10:00",
                        isUnread = false,
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        val noneTitleLeft =
            composeTestRule
                .onNodeWithText("None Pref Title", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
                .left
        val allReadTitleLeft =
            composeTestRule
                .onNodeWithText("AllRead Pref Title", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
                .left
        // None: no slot, no padding compensation; AllRead: exactly 16dp in.
        val shiftDp = (allReadTitleLeft - noneTitleLeft) / composeTestRule.density.density
        assertEquals(16f, shiftDp, 0.5f)

        // feedName aligns with the title edge in both configurations.
        val feeds = composeTestRule.onAllNodesWithText("Example Feed", useUnmergedTree = true)
        assertEquals(noneTitleLeft, feeds[0].fetchSemanticsNode().boundsInRoot.left, 0.5f)
        assertEquals(allReadTitleLeft, feeds[1].fetchSemanticsNode().boundsInRoot.left, 0.5f)
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
        val title =
            composeTestRule
                .onNodeWithText("Starred Read Title", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        val d = composeTestRule.density.density
        assertColorClose(
            pixelAtDp(title.left / d - 12f, title.top / d + 4f),
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
