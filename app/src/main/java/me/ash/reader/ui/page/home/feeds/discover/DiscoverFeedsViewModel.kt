package me.ash.reader.ui.page.home.feeds.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.ash.reader.R
import me.ash.reader.domain.model.discovery.DiscoveredFeed
import me.ash.reader.domain.model.discovery.DiscoverySearchScope
import me.ash.reader.domain.model.discovery.FeedPreview
import me.ash.reader.domain.model.group.Group
import me.ash.reader.domain.service.AccountService
import me.ash.reader.domain.service.FeedDiscoveryService
import me.ash.reader.domain.service.RssService
import me.ash.reader.domain.service.StarterFeedCatalog
import me.ash.reader.infrastructure.android.AndroidStringsHelper
import me.ash.reader.infrastructure.di.ApplicationScope
import me.ash.reader.infrastructure.rss.RssHelper

/**
 * FD2 — the Discover feeds screen state. When [activeQuery] is blank the page
 * is in browse mode (curated [StarterFeedCatalog] feeds of
 * [selectedCategoryId]); otherwise it shows discovery results for the query.
 * [subscribedUrls] holds NORMALIZED URLs of already-subscribed feeds so cards
 * can render a Subscribed state and duplicate subscriptions are avoided.
 */
data class DiscoverUiState(
    val activeQuery: String = "",
    val scope: DiscoverySearchScope = DiscoverySearchScope.ALL,
    val searching: Boolean = false,
    val results: List<DiscoveredFeed> = emptyList(),
    val subscribedUrls: Set<String> = emptySet(),
    val groups: List<Group> = emptyList(),
    val selectedCategoryId: String = StarterFeedCatalog.categories.first().id,
    val notice: String? = null,
    val errorMessage: String? = null,
    val preview: DiscoverPreviewState = DiscoverPreviewState.Hidden,
    val subscribeSheet: DiscoverSubscribeSheet? = null,
    val toast: String? = null,
) {
    /** True while the page shows curated category feeds (no active search). */
    val browsing: Boolean
        get() = activeQuery.isBlank()

    /** Whether [feed] is already subscribed (normalized-URL comparison). */
    fun isSubscribed(feed: DiscoveredFeed): Boolean =
        DiscoveredFeed.normalizeUrl(feed.url) in subscribedUrls
}

/** FD2 — the preview bottom sheet state for one candidate feed. */
sealed interface DiscoverPreviewState {
    data object Hidden : DiscoverPreviewState

    data class Loading(val url: String) : DiscoverPreviewState

    data class Loaded(val feed: DiscoveredFeed, val preview: FeedPreview) : DiscoverPreviewState

    data class Failed(val url: String, val message: String) : DiscoverPreviewState
}

/** FD2 — the compact group-picker/options sheet shown before subscribing. */
data class DiscoverSubscribeSheet(
    val feed: DiscoveredFeed,
    val resolvedFeedUrl: String,
    val preview: FeedPreview?,
    val groups: List<Group>,
    val selectedGroupId: String,
    val notification: Boolean = false,
    val fullContent: Boolean = false,
    val browser: Boolean = false,
    val subscribing: Boolean = false,
)

/**
 * FD2 — ViewModel of the Discover feeds page: unified search (topic, website
 * URL or platform shortcut) over [FeedDiscoveryService], curated-category
 * browsing, reactive subscribed-URL observation via `pullFeeds()`, feed
 * previewing and one-tap subscribe through `rssService.get().subscribe(...)`.
 *
 * Remote topic-search degradation is inherited from the service: bundled
 * starter-catalog matches survive remote failures (returned with a notice),
 * and unexpected throws still keep the instantly-seeded local matches on
 * screen next to [DiscoverUiState.errorMessage].
 */
@HiltViewModel
class DiscoverFeedsViewModel
@Inject
constructor(
    private val discoveryService: FeedDiscoveryService,
    val rssService: RssService,
    private val rssHelper: RssHelper,
    private val androidStringsHelper: AndroidStringsHelper,
    @ApplicationScope private val applicationScope: CoroutineScope,
    accountService: AccountService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DiscoverUiState())
    val uiState: StateFlow<DiscoverUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var previewJob: Job? = null

    init {
        selectCategory(_uiState.value.selectedCategoryId)
        // Reactively observe subscribed feed URLs (feedDao via pullFeeds) so
        // result cards flip to Subscribed without a manual refresh.
        viewModelScope.launch {
            accountService.currentAccountFlow.collectLatest {
                rssService.get().pullFeeds().collect { groupWithFeeds ->
                    val urls =
                        groupWithFeeds
                            .flatMap { it.feeds }
                            .map { DiscoveredFeed.normalizeUrl(it.url) }
                            .toSet()
                    _uiState.update { it.copy(subscribedUrls = urls) }
                }
            }
        }
        viewModelScope.launch {
            accountService.currentAccountFlow.collectLatest {
                rssService.get().pullGroups().collect { groups ->
                    _uiState.update { state ->
                        state.copy(
                            groups = groups,
                            subscribeSheet =
                                state.subscribeSheet?.copy(
                                    groups = groups,
                                    selectedGroupId =
                                        state.subscribeSheet.selectedGroupId.ifEmpty {
                                            groups.firstOrNull()?.id.orEmpty()
                                        },
                                ),
                        )
                    }
                }
            }
        }
    }

    // --- browse mode ----------------------------------------------------------

    /** Switches to browse mode and shows the curated feeds of [categoryId]. */
    fun selectCategory(categoryId: String) {
        searchJob?.cancel()
        val category =
            StarterFeedCatalog.categories.firstOrNull { it.id == categoryId } ?: return
        _uiState.update { state ->
            state.copy(
                activeQuery = "",
                searching = false,
                notice = null,
                errorMessage = null,
                selectedCategoryId = categoryId,
                results =
                    category.feeds.map {
                        with(StarterFeedCatalog) { it.toDiscoveredFeed() }
                    },
            )
        }
    }

    /** Clears the active search and returns to browse mode. */
    fun clearSearch() {
        selectCategory(_uiState.value.selectedCategoryId)
    }

    // --- search ---------------------------------------------------------------

    /**
     * Runs the unified discovery search for [rawQuery]. Local starter-catalog
     * matches are seeded synchronously first, so results stay on screen even
     * when the remote search fails or the device is offline.
     */
    fun search(rawQuery: String) {
        val query = rawQuery.trim()
        if (query.isEmpty()) {
            clearSearch()
            return
        }
        searchJob?.cancel()
        val scope = _uiState.value.scope
        val localMatches = StarterFeedCatalog.search(query)
        _uiState.update { state ->
            state.copy(
                activeQuery = query,
                searching = true,
                notice = null,
                errorMessage = null,
                results = localMatches,
            )
        }
        searchJob =
            viewModelScope.launch {
                val outcome = runCatching { discoveryService.discover(query, scope) }
                outcome
                    .onSuccess { result ->
                        _uiState.update { state ->
                            if (state.activeQuery != query) {
                                state // a newer search already took over
                            } else {
                                state.copy(
                                    searching = false,
                                    results = result.feeds,
                                    notice = result.notice,
                                )
                            }
                        }
                    }
                    .onFailure { throwable ->
                        if (throwable is CancellationException) throw throwable
                        _uiState.update { state ->
                            if (state.activeQuery != query) {
                                state
                            } else {
                                state.copy(
                                    searching = false,
                                    // Keep the instantly-seeded local matches.
                                    errorMessage = throwable.message ?: SEARCH_FAILED_MESSAGE,
                                )
                            }
                        }
                    }
            }
    }

    /** Changes the search scope and re-runs the active search, if any. */
    fun selectScope(scope: DiscoverySearchScope) {
        if (_uiState.value.scope == scope) return
        _uiState.update { it.copy(scope = scope) }
        val query = _uiState.value.activeQuery
        if (query.isNotBlank()) search(query)
    }

    /** Re-runs the last failed search. */
    fun retrySearch() {
        val query = _uiState.value.activeQuery
        if (query.isNotBlank()) search(query)
    }

    // --- preview --------------------------------------------------------------

    /** Loads and shows the preview sheet for [feed]. */
    fun openPreview(feed: DiscoveredFeed) {
        previewJob?.cancel()
        _uiState.update { it.copy(preview = DiscoverPreviewState.Loading(feed.url)) }
        previewJob =
            viewModelScope.launch {
                val outcome = runCatching { discoveryService.previewFeed(feed.url) }
                outcome
                    .onSuccess { loaded ->
                        _uiState.update { state ->
                            val loading = state.preview as? DiscoverPreviewState.Loading
                            if (loading == null || loading.url != feed.url) {
                                state // sheet was closed or another preview took over
                            } else {
                                state.copy(
                                    preview = DiscoverPreviewState.Loaded(feed, loaded)
                                )
                            }
                        }
                    }
                    .onFailure { throwable ->
                        if (throwable is CancellationException) throw throwable
                        _uiState.update { state ->
                            if (state.preview !is DiscoverPreviewState.Loading) {
                                state
                            } else {
                                state.copy(
                                    preview =
                                        DiscoverPreviewState.Failed(
                                            url = feed.url,
                                            message =
                                                throwable.message ?: PREVIEW_FAILED_MESSAGE,
                                        )
                                )
                            }
                        }
                    }
            }
    }

    /** Hides the preview sheet. */
    fun closePreview() {
        previewJob?.cancel()
        _uiState.update { it.copy(preview = DiscoverPreviewState.Hidden) }
    }

    // --- subscribe ------------------------------------------------------------

    /**
     * Opens the compact group/options confirmation sheet for [feed].
     * [preloaded] reuses an already-fetched preview (sheet inside the preview
     * sheet) so confirming does not refetch the feed.
     */
    fun openSubscribeSheet(feed: DiscoveredFeed, preloaded: FeedPreview? = null) {
        viewModelScope.launch {
            val groups =
                _uiState.value.groups.ifEmpty {
                    runCatching { rssService.get().pullGroups().first() }.getOrDefault(emptyList())
                }
            _uiState.update {
                it.copy(
                    subscribeSheet =
                        DiscoverSubscribeSheet(
                            feed = feed,
                            resolvedFeedUrl = preloaded?.feedUrl ?: feed.url,
                            preview = preloaded,
                            groups = groups,
                            selectedGroupId = groups.firstOrNull()?.id.orEmpty(),
                        )
                )
            }
        }
    }

    fun closeSubscribeSheet() {
        _uiState.update { it.copy(subscribeSheet = null) }
    }

    fun selectSubscribeGroup(groupId: String) {
        _uiState.update { state ->
            state.copy(subscribeSheet = state.subscribeSheet?.copy(selectedGroupId = groupId))
        }
    }

    fun toggleSubscribeNotification() {
        _uiState.update { state ->
            state.copy(
                subscribeSheet =
                    state.subscribeSheet?.copy(notification = !state.subscribeSheet.notification)
            )
        }
    }

    fun toggleSubscribeFullContent() {
        _uiState.update { state ->
            state.copy(
                subscribeSheet =
                    state.subscribeSheet?.copy(
                        fullContent = !state.subscribeSheet.fullContent,
                        browser = false,
                    )
            )
        }
    }

    fun toggleSubscribeBrowser() {
        _uiState.update { state ->
            state.copy(
                subscribeSheet =
                    state.subscribeSheet?.copy(
                        browser = !state.subscribeSheet.browser,
                        fullContent = false,
                    )
            )
        }
    }

    /** Creates a new group from the subscribe sheet and selects it. */
    fun addNewGroup(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        applicationScope.launch {
            val groupId =
                runCatching { rssService.get().addGroup(null, trimmed) }.getOrNull() ?: return@launch
            selectSubscribeGroup(groupId)
        }
    }

    /**
     * Subscribes to the sheet's target through the current account service,
     * reusing the loaded preview wire model when available (otherwise the
     * feed is resolved once through `RssHelper.searchFeed`). Duplicate URLs
     * are rejected via `isFeedExist`; the local subscribed set is updated
     * immediately and the reactive `pullFeeds()` stream keeps it in sync.
     */
    fun confirmSubscribe() {
        val sheet = _uiState.value.subscribeSheet ?: return
        if (sheet.subscribing || sheet.selectedGroupId.isBlank()) return
        _uiState.update { it.copy(subscribeSheet = sheet.copy(subscribing = true)) }
        applicationScope.launch {
            val title = sheet.feed.title
            val resolved =
                runCatching {
                    sheet.preview
                        ?: rssHelper.searchFeed(sheet.feed.url).let {
                            FeedPreview(feed = it.feed, feedUrl = it.feedLink, recentEntries = emptyList())
                        }
                }
            val preview = resolved.getOrNull()
            if (preview == null) {
                val throwable = resolved.exceptionOrNull()
                if (throwable is CancellationException) throw throwable
                finishSubscribe(
                    success = false,
                    message = throwable?.message ?: PREVIEW_FAILED_MESSAGE,
                )
                return@launch
            }
            val exists =
                runCatching { rssService.get().isFeedExist(preview.feedUrl) }.getOrDefault(false)
            if (exists) {
                markSubscribed(preview.feedUrl)
                finishSubscribe(
                    success = true,
                    message = androidStringsHelper.getString(R.string.already_subscribed),
                )
                return@launch
            }
            val outcome =
                runCatching {
                    rssService
                        .get()
                        .subscribe(
                            searchedFeed = preview.feed,
                            feedLink = preview.feedUrl,
                            groupId = sheet.selectedGroupId,
                            isNotification = sheet.notification,
                            isFullContent = sheet.fullContent,
                            isBrowser = sheet.browser,
                        )
                }
            outcome
                .onSuccess {
                    markSubscribed(preview.feedUrl)
                    finishSubscribe(
                        success = true,
                        message =
                            androidStringsHelper.getString(R.string.discover_subscribe_success, title),
                    )
                }
                .onFailure { throwable ->
                    if (throwable is CancellationException) throw throwable
                    finishSubscribe(
                        success = false,
                        message = throwable.message ?: SUBSCRIBE_FAILED_MESSAGE,
                    )
                }
        }
    }

    /** Consumes the one-shot toast message after the UI displayed it. */
    fun consumeToast() {
        _uiState.update { it.copy(toast = null) }
    }

    private fun finishSubscribe(success: Boolean, message: String?) {
        _uiState.update { state ->
            state.copy(
                subscribeSheet = if (success) null else state.subscribeSheet?.copy(subscribing = false),
                preview = if (success) DiscoverPreviewState.Hidden else state.preview,
                toast = message ?: state.toast,
            )
        }
    }

    private fun markSubscribed(feedUrl: String) {
        val normalized = DiscoveredFeed.normalizeUrl(feedUrl)
        _uiState.update { it.copy(subscribedUrls = it.subscribedUrls + normalized) }
    }

    private companion object {
        const val SEARCH_FAILED_MESSAGE = "Search failed. Check your connection."
        const val PREVIEW_FAILED_MESSAGE = "Could not load the feed."
        const val SUBSCRIBE_FAILED_MESSAGE = "Subscribe failed."
    }
}
