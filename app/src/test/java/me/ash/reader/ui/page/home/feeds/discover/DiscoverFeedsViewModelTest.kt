package me.ash.reader.ui.page.home.feeds.discover

import com.rometools.rome.feed.synd.SyndEntryImpl
import com.rometools.rome.feed.synd.SyndFeedImpl
import java.io.IOException
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import me.ash.reader.R
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.model.discovery.DiscoveredFeed
import me.ash.reader.domain.model.discovery.DiscoveryResult
import me.ash.reader.domain.model.discovery.DiscoverySearchScope
import me.ash.reader.domain.model.discovery.DiscoverySource
import me.ash.reader.domain.model.discovery.FeedFormat
import me.ash.reader.domain.model.discovery.FeedPreview
import me.ash.reader.domain.model.discovery.PreviewEntry
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.Group
import me.ash.reader.domain.model.group.GroupWithFeed
import me.ash.reader.domain.service.AbstractRssRepository
import me.ash.reader.domain.service.AccountService
import me.ash.reader.domain.service.FeedDiscoveryService
import me.ash.reader.domain.service.RssService
import me.ash.reader.domain.service.StarterFeedCatalog
import me.ash.reader.infrastructure.android.AndroidStringsHelper
import me.ash.reader.infrastructure.rss.RssHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * FD2 — the Discover feeds ViewModel state machine, driven over the REAL
 * [DiscoverFeedsViewModel] with Mockito fakes for the discovery engine, the
 * account/repository layer and the strings helper (no HTTP is performed).
 *
 * Pinned behaviors:
 *  - browse mode seeds the curated starter catalog and follows category
 *    selection;
 *  - search replaces results with the discovery outcome and propagates the
 *    non-fatal notice;
 *  - an unexpected search failure KEEPS the instantly-seeded local catalog
 *    matches next to the error message (offline resilience);
 *  - subscribed feed URLs are observed reactively from `pullFeeds()` and
 *    flip cards to the Subscribed state (normalized-URL comparison);
 *  - preview load/failure state transitions;
 *  - confirmSubscribe reuses the loaded preview wire model, routes through
 *    `rssService.get().subscribe(...)` with the sheet options, refuses
 *    duplicates via `isFeedExist`, and emits toast feedback.
 *
 * Dispatching: `Dispatchers.Main` is the inline [me.ash.reader.ui.page
 * .adaptive.TestMainDispatcherFactory] (ServiceLoader seam — no
 * coroutines-test artifact on the classpath), so `viewModelScope` work runs
 * synchronously on the test thread; [applicationScope] is Unconfined for the
 * same reason.
 */
class DiscoverFeedsViewModelTest {

    companion object {
        init {
            // Must be set before the first Dispatchers.Main access so the
            // coroutines ServiceLoader path (not FastServiceLoader) resolves
            // the inline test Main factory.
            System.setProperty("kotlinx.coroutines.fast.service.loader", "false")
        }
    }

    private val account = Account(id = 1, name = "Local", type = AccountType.Local)
    private val group = Group(id = "g1", name = "Default", accountId = 1)

    private val discoveryService = mock<FeedDiscoveryService>()
    private val rssService = mock<RssService>()
    private val rssHelper = mock<RssHelper>()
    private val stringsHelper = mock<AndroidStringsHelper>()
    private val accountService = mock<AccountService>()
    private val repository = mock<AbstractRssRepository>()

    private val subscribedFeedsFlow = MutableStateFlow(mutableListOf<GroupWithFeed>())
    private val groupsFlow = MutableStateFlow(mutableListOf(group))
    private val applicationScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    private lateinit var viewModel: DiscoverFeedsViewModel

    @Before
    fun setUp() {
        whenever(accountService.currentAccountFlow).thenReturn(MutableStateFlow<Account?>(account))
        whenever(rssService.get()).thenReturn(repository)
        whenever(repository.pullFeeds()).thenReturn(subscribedFeedsFlow)
        whenever(repository.pullGroups()).thenReturn(groupsFlow)
        viewModel =
            DiscoverFeedsViewModel(
                discoveryService = discoveryService,
                rssService = rssService,
                rssHelper = rssHelper,
                androidStringsHelper = stringsHelper,
                applicationScope = applicationScope,
                accountService = accountService,
            )
    }

    private fun seedSubscribedFeed(url: String) {
        subscribedFeedsFlow.value =
            mutableListOf(
                GroupWithFeed(
                    group = group,
                    feeds =
                        mutableListOf(
                            Feed(
                                id = "1-feed-1",
                                name = "Subscribed One",
                                icon = null,
                                url = url,
                                groupId = group.id,
                                accountId = 1,
                                isNotification = false,
                                isFullContent = false,
                                isBrowser = false,
                            )
                        ),
                )
            )
    }

    private fun feed(
        url: String,
        title: String = "Feed $url",
        format: FeedFormat = FeedFormat.RSS,
        subscribers: Int? = null,
    ) =
        DiscoveredFeed(
            url = url,
            title = title,
            description = "Description of $title",
            siteUrl = null,
            iconUrl = null,
            format = format,
            subscribers = subscribers,
            source = DiscoverySource.FEEDLY_SEARCH,
        )

    // --- browse mode ----------------------------------------------------------

    @Test
    fun browseModeSeedsFirstCuratedCategory() {
        val state = viewModel.uiState.value
        assertTrue(state.browsing)
        assertEquals("tech", state.selectedCategoryId)
        val tech = StarterFeedCatalog.categories.first { it.id == "tech" }
        assertEquals(tech.feeds.map { it.url }, state.results.map { it.url })
    }

    @Test
    fun selectCategoryShowsThatCategoriesFeeds() {
        viewModel.selectCategory("security")
        val state = viewModel.uiState.value
        assertEquals("security", state.selectedCategoryId)
        val security = StarterFeedCatalog.categories.first { it.id == "security" }
        assertEquals(security.feeds.map { it.url }, state.results.map { it.url })
    }

    // --- search ---------------------------------------------------------------

    @Test
    fun searchReplacesResultsWithDiscoveryOutcome() = runBlocking<Unit> {
        val remote = listOf(feed("https://remote.example/feed.xml", "Remote Feed"))
        whenever(discoveryService.discover("linux", DiscoverySearchScope.ALL))
            .thenReturn(DiscoveryResult(remote, notice = "Some sources were unavailable."))

        viewModel.search("linux")

        val state = viewModel.uiState.value
        assertFalse(state.searching)
        assertEquals("linux", state.activeQuery)
        assertEquals(remote, state.results)
        assertEquals("Some sources were unavailable.", state.notice)
        assertNull(state.errorMessage)
        verify(discoveryService).discover("linux", DiscoverySearchScope.ALL)
    }

    @Test
    fun searchFailureKeepsInstantStarterCatalogMatches() = runBlocking<Unit> {
        whenever(discoveryService.discover(any(), any())).thenAnswer { throw IOException("network down") }

        viewModel.search("linux")

        val state = viewModel.uiState.value
        assertFalse(state.searching)
        assertEquals("network down", state.errorMessage)
        // The offline fallback: local catalog matches stay on screen.
        val expectedLocal = StarterFeedCatalog.search("linux")
        assertTrue(expectedLocal.isNotEmpty())
        assertEquals(expectedLocal.map { it.url }, state.results.map { it.url })
    }

    @Test
    fun scopeChangeRerunsActiveSearchWithNewScope() = runBlocking<Unit> {
        whenever(discoveryService.discover(any(), any()))
            .thenReturn(DiscoveryResult(emptyList()))

        viewModel.search("security")
        viewModel.selectScope(DiscoverySearchScope.PODCASTS)

        assertEquals(DiscoverySearchScope.PODCASTS, viewModel.uiState.value.scope)
        verify(discoveryService).discover("security", DiscoverySearchScope.PODCASTS)
    }

    @Test
    fun blankSearchReturnsToBrowseMode() = runBlocking<Unit> {
        whenever(discoveryService.discover(any(), any()))
            .thenReturn(DiscoveryResult(emptyList()))
        viewModel.search("linux")
        viewModel.search("   ")
        val state = viewModel.uiState.value
        assertTrue(state.browsing)
        assertFalse(state.searching)
    }

    // --- subscribed observation -----------------------------------------------

    @Test
    fun subscribedFeedUrlsFlipCardsReactively() {
        seedSubscribedFeed("https://lwn.net/headlines/rss/")
        viewModel.selectCategory("linux-open-source")
        val lwn =
            viewModel.uiState.value.results.first { it.url == "https://lwn.net/headlines/rss" }
        // Trailing-slash normalization: DB row has "/rss/", catalog has "/rss".
        assertTrue(viewModel.uiState.value.isSubscribed(lwn))

        subscribedFeedsFlow.value = mutableListOf()
        assertFalse(viewModel.uiState.value.isSubscribed(lwn))
    }

    // --- preview ----------------------------------------------------------------

    @Test
    fun previewSuccessLoadsSheetState() = runBlocking<Unit> {
        val syndFeed = SyndFeedImpl().apply { title = "Previewed" }
        val preview =
            FeedPreview(
                feed = syndFeed,
                feedUrl = "https://example.com/feed",
                recentEntries = listOf(PreviewEntry("Entry one", "https://example.com/1", 0L)),
            )
        whenever(discoveryService.previewFeed("https://example.com/feed")).thenReturn(preview)

        viewModel.openPreview(feed("https://example.com/feed"))

        val loaded = viewModel.uiState.value.preview as? DiscoverPreviewState.Loaded
        assertSame(preview, loaded?.preview)
        viewModel.closePreview()
        assertEquals(DiscoverPreviewState.Hidden, viewModel.uiState.value.preview)
    }

    @Test
    fun previewFailureSurfacesMessage() = runBlocking<Unit> {
        whenever(discoveryService.previewFeed(any()))
            .thenAnswer { throw IOException("Blocked by security policy") }

        viewModel.openPreview(feed("https://example.com/feed"))

        val failed = viewModel.uiState.value.preview as? DiscoverPreviewState.Failed
        assertEquals("Blocked by security policy", failed?.message)
    }

    // --- subscribe ---------------------------------------------------------------

    @Test
    fun confirmSubscribeRoutesThroughRepositoryWithSheetOptions() = runBlocking<Unit> {
        val syndFeed = SyndFeedImpl().apply { title = "Ars Technica" }
        val preview =
            FeedPreview(
                feed = syndFeed,
                feedUrl = "https://arstechnica.com/feed/",
                recentEntries = emptyList(),
            )
        whenever(stringsHelper.getString(R.string.discover_subscribe_success, "Ars Technica"))
            .thenReturn("Subscribed to Ars Technica")

        val target = feed("https://arstechnica.com/feed/", "Ars Technica")
        viewModel.openSubscribeSheet(target, preloaded = preview)
        viewModel.toggleSubscribeFullContent()
        viewModel.toggleSubscribeNotification()
        viewModel.confirmSubscribe()

        verify(repository)
            .subscribe(
                eq("https://arstechnica.com/feed/"),
                argThat { this === syndFeed },
                eq("g1"),
                eq(true),
                eq(true),
                eq(false),
            )
        val state = viewModel.uiState.value
        assertNull(state.subscribeSheet)
        assertEquals("Subscribed to Ars Technica", state.toast)
        assertTrue(state.isSubscribed(target))
        assertEquals(DiscoverPreviewState.Hidden, state.preview)
        // The preloaded preview must be reused, never refetched.
        verify(rssHelper, never()).searchFeed(any())
    }

    @Test
    fun confirmSubscribeWithoutPreviewResolvesThroughRssHelper() = runBlocking<Unit> {
        val syndFeed = SyndFeedImpl().apply { title = "Resolved" }
        whenever(rssHelper.searchFeed("https://resolved.example/feed"))
            .thenReturn(RssHelper.SearchFeedResult(feed = syndFeed, feedLink = "https://resolved.example/feed"))
        whenever(repository.isFeedExist("https://resolved.example/feed")).thenReturn(false)
        whenever(stringsHelper.getString(R.string.discover_subscribe_success, "Resolved Feed"))
            .thenReturn("Subscribed to Resolved Feed")

        viewModel.openSubscribeSheet(feed("https://resolved.example/feed", "Resolved Feed"))
        viewModel.confirmSubscribe()

        verify(repository)
            .subscribe(
                eq("https://resolved.example/feed"),
                any(),
                eq("g1"),
                eq(false),
                eq(false),
                eq(false),
            )
        assertEquals("Subscribed to Resolved Feed", viewModel.uiState.value.toast)
    }

    @Test
    fun confirmSubscribeRefusesAlreadySubscribedUrl() = runBlocking<Unit> {
        val syndFeed = SyndFeedImpl().apply { title = "Dupe" }
        val preview =
            FeedPreview(
                feed = syndFeed,
                feedUrl = "https://dupe.example/feed",
                recentEntries = emptyList(),
            )
        whenever(repository.isFeedExist("https://dupe.example/feed")).thenReturn(true)
        whenever(stringsHelper.getString(R.string.already_subscribed))
            .thenReturn("Already subscribed")

        viewModel.openSubscribeSheet(feed("https://dupe.example/feed"), preloaded = preview)
        viewModel.confirmSubscribe()

        verify(repository, never()).subscribe(any(), any(), any(), any(), any(), any())
        assertEquals("Already subscribed", viewModel.uiState.value.toast)
        assertNull(viewModel.uiState.value.subscribeSheet)
        assertTrue(
            DiscoveredFeed.normalizeUrl("https://dupe.example/feed") in
                viewModel.uiState.value.subscribedUrls
        )
    }

    @Test
    fun subscribeFailureKeepsSheetOpenWithToast() = runBlocking<Unit> {
        val syndFeed = SyndFeedImpl().apply { title = "Boom" }
        val preview =
            FeedPreview(
                feed = syndFeed,
                feedUrl = "https://boom.example/feed",
                recentEntries =
                    listOf(
                        PreviewEntry("Entry", "https://boom.example/1", Date().time)
                    ),
            )
        whenever(repository.isFeedExist("https://boom.example/feed")).thenReturn(false)
        whenever(repository.subscribe(any(), any(), any(), any(), any(), any()))
            .thenAnswer { throw IOException("sync backend down") }

        viewModel.openSubscribeSheet(feed("https://boom.example/feed"), preloaded = preview)
        viewModel.confirmSubscribe()

        val sheet = viewModel.uiState.value.subscribeSheet
        assertTrue(sheet != null && !sheet.subscribing)
        assertEquals("sync backend down", viewModel.uiState.value.toast)
    }

    @Test
    fun sheetOptionTogglesAreMutuallyExclusiveForFullContentAndBrowser() {
        viewModel.openSubscribeSheet(feed("https://example.com/feed"))
        viewModel.toggleSubscribeFullContent()
        assertTrue(viewModel.uiState.value.subscribeSheet?.fullContent == true)
        viewModel.toggleSubscribeBrowser()
        val sheet = viewModel.uiState.value.subscribeSheet
        assertTrue(sheet?.browser == true)
        assertFalse(sheet?.fullContent == true)
    }
}
