package me.ash.reader.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.Group
import me.ash.reader.ui.page.home.feeds.detail.FeedDetailContent
import me.ash.reader.ui.page.home.feeds.detail.GroupDetailContent
import me.ash.reader.ui.theme.palette.FeedBucketColorSchemes
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Post-v1 Wave H gap (UI-CONTRACT §2.2/§3.3/§8.1): FeedDetail/GroupDetail
 * destinations expose >=48dp actionable targets, icon-only controls carry
 * TalkBack action names, toggles expose toggleableState + state description,
 * and each page exposes exactly one heading with the visible page title.
 * Target bounds are measured in dp, so the assertions hold on any window
 * width; the measured config is logged as witness.
 */
class FeedGroupDetailInstrumentedTest {

    @get:Rule val composeTestRule = createComposeRule()

    private fun dpWidth(tag: String): Float {
        val bounds = composeTestRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        return bounds.width / composeTestRule.density.density
    }

    private fun dpHeight(tag: String): Float {
        val bounds = composeTestRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        return bounds.height / composeTestRule.density.density
    }

    private fun assertHeightAtLeast48dp(tag: String) {
        val h = dpHeight(tag)
        assertTrue("$tag height ${h}dp < 48", h >= 48f)
    }

    @Composable
    private fun SampleSurface(content: @Composable () -> Unit) {
        MaterialTheme(colorScheme = FeedBucketColorSchemes.Light) {
            Surface(color = MaterialTheme.colorScheme.surface) { content() }
        }
    }

    private val feed =
        Feed(
            id = "feed-1",
            name = "Example Feed",
            icon = null,
            url = "https://example.com/feed.xml",
            groupId = "group-1",
            accountId = 1,
            isNotification = true,
            isFullContent = false,
            isBrowser = true,
        )

    private val groups =
        listOf(
            Group(id = "group-1", name = "News", accountId = 1),
            Group(id = "group-2", name = "Tech", accountId = 1),
        )

    @Test
    fun feedDetailTargetsAreAtLeast48dp() {
        val config =
            InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration
        println(
            "[feed-detail] smallestScreenWidthDp=${config.smallestScreenWidthDp} " +
                "densityDpi=${config.densityDpi} fontScale=${config.fontScale}"
        )

        composeTestRule.setContent {
            SampleSurface {
                FeedDetailContent(
                    feed = feed,
                    groups = groups,
                    selectedGroupId = "group-1",
                    canEdit = true,
                    canMove = true,
                    canDelete = true,
                    onBack = {},
                    onOpenOriginal = {},
                    onChangeUrl = {},
                    onRename = {},
                    onGroupClick = {},
                    onAddNewGroup = {},
                    onNotificationChange = {},
                    onFullContentChange = {},
                    onBrowserChange = {},
                    onClear = {},
                    onUnsubscribe = {},
                    onIconClick = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        // Action rows (§3.3 settings/feed rows: 64dp two-line / >=48dp).
        assertHeightAtLeast48dp("feed_detail_notifications_row")
        assertHeightAtLeast48dp("feed_detail_full_content_row")
        assertHeightAtLeast48dp("feed_detail_browser_row")
        assertHeightAtLeast48dp("feed_detail_row_clear")
        assertHeightAtLeast48dp("feed_detail_row_unsubscribe")

        // URL block and its 48dp buttons.
        assertHeightAtLeast48dp("feed_detail_url")
        assertHeightAtLeast48dp("feed_detail_open_original")
        assertHeightAtLeast48dp("feed_detail_change_url")

        // Folder chips and the new-group target (§3.3 scope-chip floor).
        assertHeightAtLeast48dp("feed_detail_group_chip_group-1")
        assertHeightAtLeast48dp("feed_detail_group_chip_group-2")
        assertHeightAtLeast48dp("feed_detail_new_group")
    }

    @Test
    fun feedDetailExposesTalkBackSemantics() {
        composeTestRule.setContent {
            SampleSurface {
                FeedDetailContent(
                    feed = feed,
                    groups = groups,
                    selectedGroupId = "group-1",
                    canEdit = true,
                    canMove = true,
                    canDelete = true,
                    onBack = {},
                    onOpenOriginal = {},
                    onChangeUrl = {},
                    onRename = {},
                    onGroupClick = {},
                    onAddNewGroup = {},
                    onNotificationChange = {},
                    onFullContentChange = {},
                    onBrowserChange = {},
                    onClear = {},
                    onUnsubscribe = {},
                    onIconClick = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        // Icon-only Back control exposes an action name, not an icon name
        // (§8.1), and keeps the 48dp app-bar floor.
        val backBounds =
            composeTestRule
                .onNodeWithContentDescription("Back to Feeds")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        val backW = backBounds.width / composeTestRule.density.density
        val backH = backBounds.height / composeTestRule.density.density
        assertTrue("back action ${backW}dp < 48", backW >= 48f)
        assertTrue("back action ${backH}dp < 48", backH >= 48f)

        // §8.1: exactly one heading with the visible page title.
        composeTestRule
            .onNode(isHeading())
            .assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text))

        // §8.1: the raw feed URL is available to semantics even though the
        // display copy carries zero-width break opportunities.
        composeTestRule
            .onNodeWithContentDescription("https://example.com/feed.xml")
            .assertIsDisplayed()

        // Toggle controls expose toggleableState + state description (§8.1).
        composeTestRule
            .onNodeWithTag("feed_detail_notifications")
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ToggleableState,
                    ToggleableState.On,
                )
            )
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription))
        composeTestRule
            .onNodeWithTag("feed_detail_full_content")
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ToggleableState,
                    ToggleableState.Off,
                )
            )
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription))
        composeTestRule
            .onNodeWithTag("feed_detail_browser")
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ToggleableState,
                    ToggleableState.On,
                )
            )
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription))
    }

    @Test
    fun groupDetailTargetsAreAtLeast48dp() {
        val config =
            InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration
        println(
            "[group-detail] smallestScreenWidthDp=${config.smallestScreenWidthDp} " +
                "densityDpi=${config.densityDpi} fontScale=${config.fontScale}"
        )

        composeTestRule.setContent {
            SampleSurface {
                GroupDetailContent(
                    group = groups[0],
                    groups = groups,
                    canMove = true,
                    canDelete = true,
                    onBack = {},
                    onRename = {},
                    onMoveToGroup = {},
                    onNotificationClick = {},
                    onFullContentClick = {},
                    onBrowserClick = {},
                    onClear = {},
                    onDelete = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        assertHeightAtLeast48dp("group_detail_row_notifications")
        assertHeightAtLeast48dp("group_detail_row_full_content")
        assertHeightAtLeast48dp("group_detail_row_browser")
        assertHeightAtLeast48dp("group_detail_row_clear")
        assertHeightAtLeast48dp("group_detail_row_delete")
        assertHeightAtLeast48dp("group_detail_group_chip_group-2")

        val backBounds =
            composeTestRule
                .onNodeWithContentDescription("Back to Feeds")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        val backW = backBounds.width / composeTestRule.density.density
        val backH = backBounds.height / composeTestRule.density.density
        assertTrue("back action ${backW}dp < 48", backW >= 48f)
        assertTrue("back action ${backH}dp < 48", backH >= 48f)

        composeTestRule.onNode(isHeading()).assertIsDisplayed()
    }
}
