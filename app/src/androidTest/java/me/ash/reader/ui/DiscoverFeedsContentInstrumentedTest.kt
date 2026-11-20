package me.ash.reader.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import com.rometools.rome.feed.synd.SyndFeedImpl
import java.util.Date
import me.ash.reader.domain.model.discovery.DiscoveredFeed
import me.ash.reader.domain.model.discovery.DiscoverySearchScope
import me.ash.reader.domain.model.discovery.DiscoverySource
import me.ash.reader.domain.model.discovery.FeedFormat
import me.ash.reader.domain.model.discovery.FeedPreview
import me.ash.reader.domain.model.discovery.PreviewEntry
import me.ash.reader.domain.model.group.Group
import me.ash.reader.ui.page.home.feeds.discover.DiscoverFeedCard
import me.ash.reader.ui.page.home.feeds.discover.DiscoverFeedsContent
import me.ash.reader.ui.page.home.feeds.discover.DiscoverPreviewState
import me.ash.reader.ui.page.home.feeds.discover.DiscoverSubscribeSheet
import me.ash.reader.ui.page.home.feeds.discover.DiscoverTestTags
import me.ash.reader.ui.page.home.feeds.discover.DiscoverUiState
import me.ash.reader.ui.page.home.feeds.subscribe.SubscribeCandidateList
import me.ash.reader.ui.theme.palette.FeedBucketColorSchemes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * FD2 — instrumented Compose coverage of the discovery UI rendering halves:
 * the stateless [DiscoverFeedsContent] (browse chips, search scope chips,
 * result cards with Subscribed state, error/retry, preview sheet, subscribe
 * sheet) and the multi-feed [SubscribeCandidateList] picker rows. Both are
 * driven with fixed state and recording callbacks — no Hilt, no network —
 * so the assertions pin the FD2 UI contract on a real Android runtime.
 */
class DiscoverFeedsContentInstrumentedTest {

    @get:Rule val composeTestRule = createComposeRule()

    private val arsTechnica =
        DiscoveredFeed(
            url = "https://arstechnica.com/feed/",
            title = "Ars Technica",
            description = "Technology news and in-depth analysis",
            siteUrl = "https://arstechnica.com",
            format = FeedFormat.RSS,
            subscribers = 12345,
            source = DiscoverySource.STARTER_CATALOG,
        )
    private val lwn =
        DiscoveredFeed(
            url = "https://lwn.net/headlines/rss",
            title = "LWN.net",
            description = "Kernel, distributions and open-source policy",
            format = FeedFormat.RSS,
            source = DiscoverySource.STARTER_CATALOG,
        )
    private val group = Group(id = "g1", name = "Default", accountId = 1)

    @Composable
    private fun SampleSurface(content: @Composable () -> Unit) {
        MaterialTheme(colorScheme = FeedBucketColorSchemes.Light) {
            Surface(color = MaterialTheme.colorScheme.surface) { content() }
        }
    }

    private fun setContent(
        state: DiscoverUiState,
        inputQuery: String = "",
        onQueryChange: (String) -> Unit = {},
        onSearch: (String) -> Unit = {},
        onScopeSelect: (DiscoverySearchScope) -> Unit = {},
        onCategorySelect: (String) -> Unit = {},
        onFeedClick: (DiscoveredFeed) -> Unit = {},
        onFeedSubscribeClick: (DiscoveredFeed) -> Unit = {},
        onRetry: () -> Unit = {},
        onPreviewClose: () -> Unit = {},
        onPreviewRetry: (DiscoveredFeed) -> Unit = {},
        onPreviewSubscribe: (DiscoverPreviewState.Loaded) -> Unit = {},
        onSubscribeSheetClose: () -> Unit = {},
        onSubscribeGroupSelect: (String) -> Unit = {},
        onSubscribeNotificationToggle: () -> Unit = {},
        onSubscribeFullContentToggle: () -> Unit = {},
        onSubscribeBrowserToggle: () -> Unit = {},
        onSubscribeAddGroup: (String) -> Unit = {},
        onSubscribeConfirm: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            SampleSurface {
                DiscoverFeedsContent(
                    state = state,
                    inputQuery = inputQuery,
                    onBack = {},
                    onQueryChange = onQueryChange,
                    onSearch = onSearch,
                    onScopeSelect = onScopeSelect,
                    onCategorySelect = onCategorySelect,
                    onFeedClick = onFeedClick,
                    onFeedSubscribeClick = onFeedSubscribeClick,
                    onRetry = onRetry,
                    onPreviewClose = onPreviewClose,
                    onPreviewRetry = onPreviewRetry,
                    onPreviewSubscribe = onPreviewSubscribe,
                    onSubscribeSheetClose = onSubscribeSheetClose,
                    onSubscribeGroupSelect = onSubscribeGroupSelect,
                    onSubscribeNotificationToggle = onSubscribeNotificationToggle,
                    onSubscribeFullContentToggle = onSubscribeFullContentToggle,
                    onSubscribeBrowserToggle = onSubscribeBrowserToggle,
                    onSubscribeAddGroup = onSubscribeAddGroup,
                    onSubscribeConfirm = onSubscribeConfirm,
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun browseModeRendersCategoryChipsAndResultCards() {
        val categories = mutableListOf<String>()
        val feedClicks = mutableListOf<DiscoveredFeed>()
        setContent(
            state = DiscoverUiState(results = listOf(arsTechnica, lwn)),
            onCategorySelect = { categories += it },
            onFeedClick = { feedClicks += it },
        )

        composeTestRule
            .onNodeWithTag(DiscoverTestTags.CATEGORY_CHIP_PREFIX + "tech")
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithTag(DiscoverTestTags.CATEGORY_CHIP_PREFIX + "security")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Ars Technica").assertIsDisplayed()
        composeTestRule.onNodeWithText("12,345 subscribers").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("RSS").assertCountEquals(2)

        // Category chip click routes the category id.
        composeTestRule
            .onNodeWithTag(DiscoverTestTags.CATEGORY_CHIP_PREFIX + "security")
            .performClick()
        composeTestRule.waitForIdle()
        assertEquals(listOf("security"), categories)

        // Card tap opens the preview for that feed.
        composeTestRule
            .onNodeWithTag(
                DiscoverTestTags.FEED_CARD_PREFIX + DiscoveredFeed.normalizeUrl(lwn.url)
            )
            .performClick()
        composeTestRule.waitForIdle()
        assertEquals(listOf(lwn), feedClicks)
    }

    @Test
    fun subscribedFeedRendersSubscribedChipWithoutSubscribeButton() {
        setContent(
            state =
                DiscoverUiState(
                    results = listOf(arsTechnica, lwn),
                    subscribedUrls = setOf(DiscoveredFeed.normalizeUrl(arsTechnica.url)),
                )
        )

        // The card Surface(onClick) merges descendant semantics, so the
        // non-merging chip node is queried on the unmerged tree.
        composeTestRule
            .onNodeWithTag(
                DiscoverTestTags.SUBSCRIBED_CHIP_PREFIX + DiscoveredFeed.normalizeUrl(arsTechnica.url),
                useUnmergedTree = true,
            )
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithTag(
                DiscoverTestTags.SUBSCRIBE_BUTTON_PREFIX + DiscoveredFeed.normalizeUrl(arsTechnica.url)
            )
            .assertDoesNotExist()
        // The not-subscribed card keeps its Subscribe action.
        composeTestRule
            .onNodeWithTag(
                DiscoverTestTags.SUBSCRIBE_BUTTON_PREFIX + DiscoveredFeed.normalizeUrl(lwn.url)
            )
            .assertIsDisplayed()
    }

    @Test
    fun searchModeShowsScopeChipsAndRoutesSelection() {
        val scopes = mutableListOf<DiscoverySearchScope>()
        setContent(
            state =
                DiscoverUiState(
                    activeQuery = "linux",
                    results = listOf(lwn),
                ),
            inputQuery = "linux",
            onScopeSelect = { scopes += it },
        )

        // Browse chips are replaced by scope chips while searching.
        composeTestRule
            .onNodeWithTag(DiscoverTestTags.CATEGORY_CHIP_PREFIX + "tech")
            .assertDoesNotExist()
        composeTestRule
            .onNodeWithTag(DiscoverTestTags.SCOPE_CHIP_PREFIX + "PODCASTS")
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithTag(DiscoverTestTags.SCOPE_CHIP_PREFIX + "PODCASTS")
            .performClick()
        composeTestRule.waitForIdle()
        assertEquals(listOf(DiscoverySearchScope.PODCASTS), scopes)
    }

    @Test
    fun errorStateShowsMessageAndRetryAction() {
        var retried = false
        setContent(
            state =
                DiscoverUiState(
                    activeQuery = "linux",
                    errorMessage = "network down",
                ),
            inputQuery = "linux",
            onRetry = { retried = true },
        )

        composeTestRule.onNodeWithText("network down").assertIsDisplayed()
        composeTestRule.onNodeWithTag(DiscoverTestTags.RETRY_BUTTON).performClick()
        composeTestRule.waitForIdle()
        assertTrue(retried)
    }

    @Test
    fun previewSheetListsLatestEntriesAndSubscribeAction() {
        val syndFeed =
            SyndFeedImpl().apply {
                title = "Ars Technica"
                description = "Technology news"
                feedType = "rss_2.0"
            }
        val preview =
            FeedPreview(
                feed = syndFeed,
                feedUrl = "https://arstechnica.com/feed/",
                recentEntries =
                    (1..5).map {
                        PreviewEntry(
                            title = "Article number $it",
                            link = "https://arstechnica.com/$it",
                            publishedAt = Date().time,
                        )
                    },
            )
        val subscribedFromPreview = mutableListOf<DiscoverPreviewState.Loaded>()
        setContent(
            state =
                DiscoverUiState(
                    results = listOf(arsTechnica),
                    preview = DiscoverPreviewState.Loaded(arsTechnica, preview),
                ),
            onPreviewSubscribe = { subscribedFromPreview += it },
        )
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Latest articles").assertIsDisplayed()
        composeTestRule
            .onNodeWithTag(DiscoverTestTags.PREVIEW_ENTRY_PREFIX + "0")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Article number 1").assertIsDisplayed()
        // Sheet title + the card behind it both read "Ars Technica".
        composeTestRule.onAllNodesWithText("Ars Technica").assertCountEquals(2)

        composeTestRule.onNodeWithTag(DiscoverTestTags.PREVIEW_SUBSCRIBE).performClick()
        composeTestRule.waitForIdle()
        assertEquals(1, subscribedFromPreview.size)
        assertEquals(preview, subscribedFromPreview.first().preview)
    }

    @Test
    fun subscribeSheetRendersOptionsAndConfirmAction() {
        var confirmed = false
        setContent(
            state =
                DiscoverUiState(
                    results = listOf(arsTechnica),
                    groups = listOf(group),
                    subscribeSheet =
                        DiscoverSubscribeSheet(
                            feed = arsTechnica,
                            resolvedFeedUrl = arsTechnica.url,
                            preview = null,
                            groups = listOf(group),
                            selectedGroupId = "g1",
                        ),
                ),
            onSubscribeConfirm = { confirmed = true },
        )
        composeTestRule.waitForIdle()

        // FeedOptionView rows render inside the sheet.
        composeTestRule.onNodeWithText("Parse full content").assertIsDisplayed()
        composeTestRule.onNodeWithText("Default").assertIsDisplayed()
        composeTestRule.onNodeWithTag(DiscoverTestTags.SUBSCRIBE_CONFIRM).performClick()
        composeTestRule.waitForIdle()
        assertTrue(confirmed)
    }

    @Test
    fun candidateListRendersEveryMultiFeedCandidate() {
        val picked = mutableListOf<DiscoveredFeed>()
        val releases =
            DiscoveredFeed(
                url = "https://github.com/octo/repo/releases.atom",
                title = "octo/repo releases",
                format = FeedFormat.ATOM,
                source = DiscoverySource.PLATFORM_SHORTCUT,
            )
        val commits =
            DiscoveredFeed(
                url = "https://github.com/octo/repo/commits.atom",
                title = "octo/repo commits",
                format = FeedFormat.ATOM,
                source = DiscoverySource.PLATFORM_SHORTCUT,
            )
        composeTestRule.setContent {
            SampleSurface {
                SubscribeCandidateList(
                    candidates = listOf(releases, commits),
                    onCandidateClick = { picked += it },
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("octo/repo releases").assertIsDisplayed()
        composeTestRule.onNodeWithText("octo/repo commits").assertIsDisplayed()
        composeTestRule.onNodeWithTag("subscribe_candidate_1").performClick()
        composeTestRule.waitForIdle()
        assertEquals(listOf(commits), picked)
    }

    @Test
    fun feedCardRendersFormatBadgeAndDescription() {
        composeTestRule.setContent {
            SampleSurface {
                DiscoverFeedCard(
                    feed =
                        arsTechnica.copy(
                            format = FeedFormat.JSON_FEED,
                            description = "Short description line",
                        ),
                    subscribed = false,
                    onClick = {},
                    onSubscribeClick = {},
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("JSON").assertIsDisplayed()
        composeTestRule.onNodeWithText("Short description line").assertIsDisplayed()
        composeTestRule.onNodeWithText("arstechnica.com").assertIsDisplayed()
    }
}
