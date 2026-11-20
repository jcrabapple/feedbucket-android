package me.ash.reader.ui.page.home.feeds.subscribe

import androidx.compose.foundation.text.input.TextFieldState
import com.rometools.rome.feed.synd.SyndFeedImpl
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.model.discovery.DiscoveredFeed
import me.ash.reader.domain.model.discovery.DiscoveryResult
import me.ash.reader.domain.model.discovery.DiscoverySearchScope
import me.ash.reader.domain.model.discovery.DiscoverySource
import me.ash.reader.domain.model.discovery.FeedFormat
import me.ash.reader.domain.model.group.Group
import me.ash.reader.domain.service.AbstractRssRepository
import me.ash.reader.domain.service.AccountService
import me.ash.reader.domain.service.FeedDiscoveryService
import me.ash.reader.domain.service.OpmlService
import me.ash.reader.domain.service.RssService
import me.ash.reader.infrastructure.android.AndroidStringsHelper
import me.ash.reader.infrastructure.rss.RssHelper
import me.ash.reader.ui.ext.formatUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * FD2 — the multi-feed candidate picker added to [SubscribeViewModel]:
 * a pasted link that resolves to 2+ advertised feeds must offer
 * [SubscribeState.ChooseFeed] instead of silently taking the first
 * advertisement, a single candidate must continue straight to
 * [SubscribeState.Configure], and an empty/failed discovery must fall back
 * to the legacy direct search (its error message included).
 *
 * The link-state object is an opaque Mockito mock: these tests never touch
 * its text buffer, they only exercise the state machine around it.
 * Dispatching mirrors DiscoverFeedsViewModelTest: inline Main via the
 * ServiceLoader seam, Unconfined application scope.
 */
class SubscribeViewModelChooseFeedTest {

    companion object {
        init {
            System.setProperty("kotlinx.coroutines.fast.service.loader", "false")
        }
    }

    private val account = Account(id = 1, name = "Local", type = AccountType.Local)
    private val group = Group(id = "g1", name = "Default", accountId = 1)

    private val opmlService = mock<OpmlService>()
    private val rssService = mock<RssService>()
    private val rssHelper = mock<RssHelper>()
    private val discoveryService = mock<FeedDiscoveryService>()
    private val stringsHelper = mock<AndroidStringsHelper>()
    private val accountService = mock<AccountService>()
    private val repository = mock<AbstractRssRepository>()

    private val groupsFlow = MutableStateFlow(mutableListOf(group))
    private val applicationScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    private lateinit var viewModel: SubscribeViewModel
    private lateinit var linkState: TextFieldState

    private val releases =
        DiscoveredFeed(
            url = "https://github.com/octo/repo/releases.atom",
            title = "octo/repo releases",
            format = FeedFormat.ATOM,
            source = DiscoverySource.PLATFORM_SHORTCUT,
        )
    private val commits =
        DiscoveredFeed(
            url = "https://github.com/octo/repo/commits.atom",
            title = "octo/repo commits",
            format = FeedFormat.ATOM,
            source = DiscoverySource.PLATFORM_SHORTCUT,
        )

    @Before
    fun setUp() {
        whenever(accountService.currentAccountFlow).thenReturn(MutableStateFlow<Account?>(account))
        whenever(rssService.get()).thenReturn(repository)
        whenever(repository.pullGroups()).thenReturn(groupsFlow)
        linkState = mock()
        viewModel =
            SubscribeViewModel(
                opmlService = opmlService,
                rssService = rssService,
                rssHelper = rssHelper,
                feedDiscoveryService = discoveryService,
                androidStringsHelper = stringsHelper,
                applicationScope = applicationScope,
                accountService = accountService,
            )
    }

    // --- pure routing decision --------------------------------------------------

    @Test
    fun planRoutesByCandidateCount() {
        val entered = "https://example.com"
        assertTrue(planForCandidates(listOf(releases, commits), entered) is CandidatePlan.Choose)
        assertEquals(
            CandidatePlan.Direct(releases.url),
            planForCandidates(listOf(releases), entered),
        )
        assertEquals(CandidatePlan.Direct(entered), planForCandidates(emptyList(), entered))
    }

    // --- state machine ------------------------------------------------------------

    @Test
    fun twoCandidatesEnterChooseFeedAndPickedOneConfigures() = runBlocking<Unit> {
        whenever(repository.isFeedExist(any())).thenReturn(false)
        whenever(discoveryService.discover("https://github.com/octo/repo", DiscoverySearchScope.ALL))
            .thenReturn(DiscoveryResult(listOf(releases, commits)))
        val commitsFeed = SyndFeedImpl().apply { title = "octo/repo commits" }
        whenever(rssHelper.searchFeed(commits.url))
            .thenReturn(RssHelper.SearchFeedResult(feed = commitsFeed, feedLink = commits.url))

        viewModel.beginSearch(linkState, "https://github.com/octo/repo")

        val choose = viewModel.subscribeState.value as? SubscribeState.ChooseFeed
        assertEquals(listOf(releases, commits), choose?.candidates)

        viewModel.chooseFeed(commits)

        val configure = viewModel.subscribeState.value as? SubscribeState.Configure
        assertEquals(commits.url, configure?.feedLink)
        assertEquals("g1", configure?.selectedGroupId)
        assertEquals(commitsFeed, configure?.searchedFeed)
    }

    @Test
    fun singleCandidateSkipsPickerAndConfiguresDirectly() = runBlocking<Unit> {
        whenever(repository.isFeedExist(any())).thenReturn(false)
        whenever(discoveryService.discover("https://arstechnica.com", DiscoverySearchScope.ALL))
            .thenReturn(DiscoveryResult(listOf(releases)))
        val syndFeed = SyndFeedImpl().apply { title = "Main feed" }
        whenever(rssHelper.searchFeed(releases.url))
            .thenReturn(RssHelper.SearchFeedResult(feed = syndFeed, feedLink = releases.url))

        viewModel.beginSearch(linkState, "https://arstechnica.com")

        val configure = viewModel.subscribeState.value as? SubscribeState.Configure
        assertEquals(releases.url, configure?.feedLink)
        assertTrue(viewModel.subscribeState.value !is SubscribeState.ChooseFeed)
    }

    @Test
    fun emptyDiscoveryFallsBackToLegacyDirectSearchError() = runBlocking<Unit> {
        whenever(repository.isFeedExist(any())).thenReturn(false)
        whenever(discoveryService.discover(any(), any())).thenReturn(DiscoveryResult(emptyList()))
        whenever(rssHelper.searchFeed("https://dead.example"))
            .thenAnswer { throw IOException("Unable to detect RSS feed URL") }

        viewModel.beginSearch(linkState, "https://dead.example")

        val idle = viewModel.subscribeState.value as? SubscribeState.Idle
        assertEquals("Unable to detect RSS feed URL", idle?.errorMessage)
    }

    @Test
    fun schemelessLinkIsNormalizedBeforeItReachesTheViewModel() = runBlocking<Unit> {
        // searchFeed() normalizes via formatUrl() before beginSearch sees the
        // link; pin that contract and that discovery receives it verbatim.
        assertEquals("https://github.com/octo/repo", "github.com/octo/repo".formatUrl())

        whenever(repository.isFeedExist(any())).thenReturn(false)
        whenever(discoveryService.discover("https://github.com/octo/repo", DiscoverySearchScope.ALL))
            .thenReturn(DiscoveryResult(listOf(releases, commits)))

        viewModel.beginSearch(linkState, "https://github.com/octo/repo")

        assertTrue(viewModel.subscribeState.value is SubscribeState.ChooseFeed)
        verify(discoveryService).discover("https://github.com/octo/repo", DiscoverySearchScope.ALL)
    }
}
