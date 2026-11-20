package me.ash.reader.ui.page.home.feeds.subscribe

import androidx.compose.foundation.text.input.TextFieldState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rometools.rome.feed.synd.SyndFeed
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.InputStream
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.ash.reader.R
import me.ash.reader.domain.model.discovery.DiscoveredFeed
import me.ash.reader.domain.model.discovery.DiscoverySearchScope
import me.ash.reader.domain.model.group.Group
import me.ash.reader.domain.service.AccountService
import me.ash.reader.domain.service.FeedDiscoveryService
import me.ash.reader.domain.service.OpmlService
import me.ash.reader.domain.service.RssService
import me.ash.reader.infrastructure.android.AndroidStringsHelper
import me.ash.reader.infrastructure.di.ApplicationScope
import me.ash.reader.infrastructure.rss.RssHelper
import me.ash.reader.ui.ext.formatUrl

@HiltViewModel
class SubscribeViewModel
@Inject
constructor(
    private val opmlService: OpmlService,
    val rssService: RssService,
    private val rssHelper: RssHelper,
    private val feedDiscoveryService: FeedDiscoveryService,
    private val androidStringsHelper: AndroidStringsHelper,
    @ApplicationScope private val applicationScope: CoroutineScope,
    accountService: AccountService,
) : ViewModel() {

    private val _subscribeUiState = MutableStateFlow(SubscribeUiState())
    val subscribeUiState: StateFlow<SubscribeUiState> = _subscribeUiState.asStateFlow()

    private val _subscribeState: MutableStateFlow<SubscribeState> =
        MutableStateFlow(SubscribeState.Hidden)
    val subscribeState = _subscribeState.asStateFlow()

    val groupsFlow = MutableStateFlow<List<Group>>(emptyList())

    init {
        viewModelScope.launch {
            accountService.currentAccountFlow.collectLatest {
                rssService.get().pullGroups().collect { groupsFlow.value = it }
            }
        }
        viewModelScope.launch {
            groupsFlow.collect { groups ->
                _subscribeState.update {
                    when (it) {
                        is SubscribeState.Configure -> it.copy(groups = groups)
                        else -> it
                    }
                }
            }
        }
    }

    fun reset() {
        cancelSearch()
    }

    fun importFromInputStream(inputStream: InputStream) {
        applicationScope.launch {
            opmlService.saveToDatabase(inputStream)
            rssService.get().doSyncOneTime()
        }
    }

    fun selectedGroup(groupId: String) {
        _subscribeState.update {
            when (it) {
                is SubscribeState.Configure -> it.copy(selectedGroupId = groupId)
                else -> it
            }
        }
    }

    fun addNewGroup() {
        if (_subscribeUiState.value.newGroupContent.isNotBlank()) {
            applicationScope.launch {
                // TODO: How to add a single group without no feeds via Google Reader API?
                selectedGroup(
                    rssService.get().addGroup(null, _subscribeUiState.value.newGroupContent)
                )
                hideNewGroupDialog()
                _subscribeUiState.update { it.copy(newGroupContent = "") }
            }
        }
    }

    fun toggleParseFullContentPreset() {
        _subscribeState.update { state ->
            when (state) {
                is SubscribeState.Configure ->
                    state.copy(fullContent = !state.fullContent, browser = false)

                else -> state
            }
        }
    }

    fun toggleOpenInBrowserPreset() {
        _subscribeState.update { state ->
            when (state) {
                is SubscribeState.Configure ->
                    state.copy(browser = !state.browser, fullContent = false)

                else -> state
            }
        }
    }

    fun toggleAllowNotificationPreset() {
        _subscribeState.update { state ->
            when (state) {
                is SubscribeState.Configure -> state.copy(notification = !state.notification)
                else -> state
            }
        }
    }

    fun searchFeed() {
        val currentState = _subscribeState.value
        if (currentState !is SubscribeState.Idle) return
        val feedLink = currentState.linkState.text.trim().toString().formatUrl()
        currentState.linkState.edit { this.replace(0, length, feedLink) }
        beginSearch(currentState.linkState, feedLink, currentState.importFromOpmlEnabled)
    }

    /**
     * FD2 — resolves an ALREADY normalized [feedLink] and moves to the next
     * state. When the link advertises 2+ candidate feeds (for example a site
     * main RSS plus a comments RSS, or GitHub releases plus commits), the
     * user picks one in [SubscribeState.ChooseFeed]; a single candidate
     * continues straight to [SubscribeState.Configure] without extra
     * friction. Extracted from [searchFeed] as an internal seam so the
     * multi-candidate decision can be unit-tested with an opaque (mocked)
     * link state — the text-field write-back stays in [searchFeed] because
     * `TextFieldState.edit` is an inline function no mock can intercept.
     */
    internal fun beginSearch(
        linkState: TextFieldState,
        feedLink: String,
        importFromOpmlEnabled: Boolean = false,
    ) {
        viewModelScope.launch {
            if (rssService.get().isFeedExist(feedLink)) {
                _subscribeState.value =
                    SubscribeState.Idle(
                        linkState = linkState,
                        importFromOpmlEnabled = importFromOpmlEnabled,
                        errorMessage = androidStringsHelper.getString(R.string.already_subscribed)
                    )
                return@launch
            }
            val groups = groupsFlow.value
            val firstGroupId = groups.firstOrNull()?.id ?: return@launch

            val job =
                viewModelScope.launch(start = CoroutineStart.LAZY) {
                    // Discovery is best-effort: on an unexpected failure the
                    // legacy direct search runs so its error message surfaces.
                    // discover() is the unified entry: platform shortcuts
                    // (github.com/owner/repo, r/sub, @user@host, ...) resolve
                    // through their dedicated resolvers, plain URLs run the
                    // multi-feed autodiscovery.
                    val candidates =
                        try {
                            feedDiscoveryService
                                .discover(feedLink, DiscoverySearchScope.ALL)
                                .feeds
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            null
                        }
                    if (candidates == null) {
                        fetchAndConfigure(feedLink, linkState, firstGroupId, importFromOpmlEnabled)
                    } else {
                        when (val plan = planForCandidates(candidates, feedLink)) {
                            is CandidatePlan.Choose ->
                                _subscribeState.value =
                                    SubscribeState.ChooseFeed(
                                        candidates = plan.candidates,
                                        linkState = linkState,
                                    )

                            is CandidatePlan.Direct ->
                                fetchAndConfigure(
                                    plan.url,
                                    linkState,
                                    firstGroupId,
                                    importFromOpmlEnabled,
                                )
                        }
                    }
                }

            _subscribeState.value =
                SubscribeState.Fetching(linkState = linkState, job = job)
            job.start()
        }
    }

    /**
     * FD2 — the user picked one of multiple candidates in
     * [SubscribeState.ChooseFeed]: fetch and configure that feed.
     */
    fun chooseFeed(candidate: DiscoveredFeed) {
        val currentState = _subscribeState.value
        if (currentState !is SubscribeState.ChooseFeed) return
        val firstGroupId = groupsFlow.value.firstOrNull()?.id ?: return
        val job =
            viewModelScope.launch(start = CoroutineStart.LAZY) {
                runCatching { rssHelper.searchFeed(candidate.url) }
                    .onSuccess {
                        enterConfigure(it, currentState.linkState, firstGroupId)
                    }
                    .onFailure {
                        if (it is CancellationException) throw it
                        _subscribeState.value =
                            SubscribeState.Idle(
                                linkState = currentState.linkState,
                                errorMessage = it.message,
                            )
                    }
            }
        _subscribeState.value =
            SubscribeState.Fetching(linkState = currentState.linkState, job = job)
        job.start()
    }

    private suspend fun fetchAndConfigure(
        feedLink: String,
        linkState: TextFieldState,
        firstGroupId: String,
        importFromOpmlEnabled: Boolean = false,
    ) {
        runCatching { rssHelper.searchFeed(feedLink) }
            .onSuccess { enterConfigure(it, linkState, firstGroupId, importFromOpmlEnabled) }
            .onFailure {
                if (it is CancellationException) throw it
                _subscribeState.value =
                    SubscribeState.Idle(
                        linkState = linkState,
                        importFromOpmlEnabled = importFromOpmlEnabled,
                        errorMessage = it.message,
                    )
            }
    }

    private suspend fun enterConfigure(
        result: RssHelper.SearchFeedResult,
        linkState: TextFieldState,
        firstGroupId: String,
        importFromOpmlEnabled: Boolean = false,
    ) {
        if (rssService.get().isFeedExist(result.feedLink)) {
            _subscribeState.value =
                SubscribeState.Idle(
                    linkState = linkState,
                    importFromOpmlEnabled = importFromOpmlEnabled,
                    errorMessage = androidStringsHelper.getString(R.string.already_subscribed)
                )
            return
        }
        _subscribeState.value =
            SubscribeState.Configure(
                searchedFeed = result.feed,
                feedLink = result.feedLink,
                groups = groupsFlow.value,
                selectedGroupId = firstGroupId,
            )
    }

    fun cancelSearch() {
        _subscribeState.value.let {
            if (it is SubscribeState.Fetching && it.job.isActive) {
                it.job.cancel()
            }
        }
    }

    fun subscribe() {
        val state = _subscribeState.value
        if (state !is SubscribeState.Configure) return

        applicationScope.launch {
            val searchedFeed = state.searchedFeed
            rssService
                .get()
                .subscribe(
                    searchedFeed = searchedFeed,
                    feedLink = state.feedLink,
                    groupId = state.selectedGroupId,
                    isNotification = state.notification,
                    isFullContent = state.fullContent,
                    isBrowser = state.browser,
                )
            hideDrawer()
        }
    }

    fun inputNewGroup(content: String) {
        _subscribeUiState.update { it.copy(newGroupContent = content) }
    }

    fun handleSharedUrlFromIntent(url: String) {
        viewModelScope
            .launch {
                _subscribeState.update { SubscribeState.Idle(linkState = TextFieldState(url)) }
                delay(50)
            }
            .invokeOnCompletion { searchFeed() }
    }

    fun showDrawer() {
        _subscribeState.value =
            SubscribeState.Idle(importFromOpmlEnabled = rssService.get().importSubscription)
    }

    fun hideDrawer() {
        cancelSearch()
        _subscribeState.value = SubscribeState.Hidden
    }

    fun showNewGroupDialog() {
        _subscribeUiState.update { it.copy(newGroupDialogVisible = true) }
    }

    fun hideNewGroupDialog() {
        _subscribeUiState.update { it.copy(newGroupDialogVisible = false) }
    }

    fun showRenameDialog() {
        _subscribeUiState.update { it.copy(renameDialogVisible = true) }
        _subscribeUiState.update { uiState ->
            (_subscribeState.value as? SubscribeState.Configure)?.searchedFeed?.title?.let { title
                ->
                uiState.copy(newName = title)
            } ?: uiState
        }
    }

    fun hideRenameDialog() {
        _subscribeUiState.update { it.copy(renameDialogVisible = false, newName = "") }
    }

    fun inputNewName(content: String) {
        _subscribeUiState.update { it.copy(newName = content) }
    }

    fun renameFeed() {
        _subscribeState.update { state ->
            when (state) {
                is SubscribeState.Configure ->
                    state.copy(
                        searchedFeed =
                            state.searchedFeed.apply { title = _subscribeUiState.value.newName }
                    )

                else -> state
            }
        }
    }
}

data class SubscribeUiState(
    val newGroupDialogVisible: Boolean = false,
    val newGroupContent: String = "",
    val newName: String = "",
    val renameDialogVisible: Boolean = false,
)

sealed interface SubscribeState {
    object Hidden : SubscribeState

    sealed interface Visible

    sealed interface Input : SubscribeState, Visible {
        val linkState: TextFieldState
    }

    data class Idle(
        override val linkState: TextFieldState = TextFieldState(),
        val importFromOpmlEnabled: Boolean = false,
        val errorMessage: String? = null,
    ) : SubscribeState, Input

    data class Fetching(override val linkState: TextFieldState, val job: Job) :
        SubscribeState, Input

    /**
     * FD2 — the entered link resolved to multiple candidate feeds; the user
     * picks which one to configure (the picker rows carry [candidates]).
     */
    data class ChooseFeed(
        val candidates: List<DiscoveredFeed>,
        override val linkState: TextFieldState,
    ) : SubscribeState, Input

    data class Configure(
        val searchedFeed: SyndFeed,
        val feedLink: String,
        val groups: List<Group> = emptyList(),
        val notification: Boolean = false,
        val fullContent: Boolean = false,
        val browser: Boolean = false,
        val selectedGroupId: String,
    ) : SubscribeState, Visible
}

/**
 * FD2 — the multi-candidate routing decision, extracted as a pure function
 * for JVM unit testing: 2+ candidates ask the user to choose, exactly one
 * continues directly with that candidate's URL, and zero candidates fall
 * back to the entered link (legacy direct search error handling).
 */
internal sealed interface CandidatePlan {
    data class Choose(val candidates: List<DiscoveredFeed>) : CandidatePlan

    data class Direct(val url: String) : CandidatePlan
}

internal fun planForCandidates(
    candidates: List<DiscoveredFeed>,
    enteredLink: String,
): CandidatePlan =
    when {
        candidates.size >= 2 -> CandidatePlan.Choose(candidates)
        candidates.size == 1 -> CandidatePlan.Direct(candidates.first().url)
        else -> CandidatePlan.Direct(enteredLink)
    }
