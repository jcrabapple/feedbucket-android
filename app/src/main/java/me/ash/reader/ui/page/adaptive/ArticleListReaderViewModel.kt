package me.ash.reader.ui.page.adaptive

import android.net.Uri
import androidx.compose.ui.util.fastFirstOrNull
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Date
import java.util.UUID
import javax.inject.Inject
import kotlin.collections.any
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.ash.reader.domain.data.ArticlePagingListUseCase
import me.ash.reader.domain.data.DiffMapHolder
import me.ash.reader.domain.data.FilterState
import me.ash.reader.domain.data.FilterStateUseCase
import me.ash.reader.domain.data.GroupWithFeedsListUseCase
import me.ash.reader.domain.data.PagerData
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.ArticleFlowItem
import me.ash.reader.domain.model.article.ArticleWithFeed
import me.ash.reader.domain.model.article.Neighbors
import me.ash.reader.domain.model.article.ReadLifecycle
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.general.MarkAsReadConditions
import me.ash.reader.domain.model.position.PositionScope
import me.ash.reader.domain.model.position.PositionStore
import me.ash.reader.domain.model.position.ReadingPosition
import me.ash.reader.domain.model.position.RestoredPosition
import me.ash.reader.domain.model.download.ArticleDownload
import me.ash.reader.domain.repository.DownloadDao
import me.ash.reader.domain.service.DownloadService
import me.ash.reader.domain.service.GoogleReaderRssService
import me.ash.reader.domain.service.LocalRssService
import me.ash.reader.domain.service.RssService
import me.ash.reader.domain.service.SyncWorker
import me.ash.reader.infrastructure.android.AndroidImageDownloader
import me.ash.reader.infrastructure.android.TextToSpeechManager
import me.ash.reader.infrastructure.di.ApplicationScope
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.infrastructure.preference.PullToLoadNextFeedPreference
import me.ash.reader.infrastructure.preference.SettingsProvider
import me.ash.reader.infrastructure.rss.ReaderCacheHelper
import timber.log.Timber

private const val TAG = "FlowViewModel"

@OptIn(FlowPreview::class)
@HiltViewModel()
class ArticleListReaderViewModel
@Inject
constructor(
    private val rssService: RssService,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val applicationScope: CoroutineScope,
    val diffMapHolder: DiffMapHolder,
    private val filterStateUseCase: FilterStateUseCase,
    private val groupWithFeedsListUseCase: GroupWithFeedsListUseCase,
    private val settingsProvider: SettingsProvider,
    private val readerCacheHelper: ReaderCacheHelper,
    val textToSpeechManager: TextToSpeechManager,
    private val imageDownloader: AndroidImageDownloader,
    private val articleListUseCase: ArticlePagingListUseCase,
    private val positionStore: PositionStore,
    private val savedSearchService: me.ash.reader.domain.service.SavedSearchService,
    private val downloadDao: DownloadDao,
    private val downloadService: DownloadService,
    private val articleDao: me.ash.reader.domain.repository.ArticleDao,
    private val accountService: me.ash.reader.domain.service.AccountService,
    workManager: WorkManager,
) : ViewModel() {

    /** S16 — "index covers N of M articles" (+ stale-sync notice). */
    val searchCoverage = MutableStateFlow<SearchCoverage?>(null)

    init {
        // S16 — account-scoped FTS coverage indicator for the search UI.
        viewModelScope.launch(ioDispatcher) {
            val accountId = accountService.getCurrentAccountId()
            kotlinx.coroutines.flow.combine(
                articleDao.countFtsCoveredArticlesFlow(accountId),
                articleDao.countAccountArticlesFlow(accountId),
                accountService.currentAccountFlow,
            ) { covered, total, account ->
                SearchCoverage(
                    covered = covered,
                    total = total,
                    stale =
                        account?.updateAt?.let {
                            System.currentTimeMillis() - it.time > SearchCoverage.STALE_SYNC_MILLIS
                        } == true,
                )
            }.collect { searchCoverage.value = it }
        }
    }

    val flowUiState: StateFlow<FlowUiState?> =
        articleListUseCase.pagerFlow
            .combine(groupWithFeedsListUseCase.groupWithFeedListFlow) {
                pagerData,
                groupWithFeedsList ->
                val filterState = pagerData.filterState
                var nextFilterState: FilterState? = null
                if (filterState.group != null) {
                    val groupList = groupWithFeedsList.map { it.group }
                    val index = groupList.indexOfFirst { it.id == filterState.group.id }
                    if (index != -1) {
                        val nextGroup = groupList.getOrNull(index + 1)
                        if (nextGroup != null) {
                            nextFilterState = filterState.copy(group = nextGroup)
                        }
                    } else {
                        val allGroupList =
                            rssService.get().queryAllGroupWithFeeds().map { it.group }
                        val index = allGroupList.indexOfFirst { it.id == filterState.group.id }
                        if (index != -1) {
                            val nextGroup =
                                allGroupList.subList(index, allGroupList.size).fastFirstOrNull {
                                    groupList.map { it.id }.contains(it.id)
                                }
                            if (nextGroup != null) {
                                nextFilterState = filterState.copy(group = nextGroup)
                            }
                        }
                    }
                } else if (filterState.feed != null) {
                    val feedList = groupWithFeedsList.flatMap { it.feeds }
                    val index = feedList.indexOfFirst { it.id == filterState.feed.id }
                    if (index != -1) {
                        val nextFeed = feedList.getOrNull(index + 1)
                        if (nextFeed != null) {
                            nextFilterState = filterState.copy(feed = nextFeed)
                        }
                    } else {
                        val allFeedList =
                            rssService.get().queryAllGroupWithFeeds().flatMap { it.feeds }
                        val index = allFeedList.indexOfFirst { it.id == filterState.feed.id }
                        if (index != -1) {
                            val nextFeed =
                                allFeedList.subList(index, allFeedList.size).fastFirstOrNull {
                                    feedList.map { it.id }.contains(it.id)
                                }
                            if (nextFeed != null) {
                                nextFilterState = filterState.copy(feed = nextFeed)
                            }
                        }
                    }
                }
                FlowUiState(nextFilterState = nextFilterState, pagerData = pagerData)
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val syncWorkerStatusFlow =
        workManager
            .getWorkInfosByTagFlow(SyncWorker.SYNC_TAG)
            .map { it.any { workInfo -> workInfo.state == WorkInfo.State.RUNNING } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _isSyncingFlow = MutableStateFlow(false)
    val isSyncingFlow = _isSyncingFlow.asStateFlow()

    init {
        viewModelScope.launch {
            syncWorkerStatusFlow.debounce(500L).collect { _isSyncingFlow.value = it }
        }
        // S25a(2) — sync completion rolls a new list generation (MAY include new
        // articles); deduped by work id so an observed terminal state rolls once.
        viewModelScope.launch {
            workManager.getWorkInfosByTagFlow(SyncWorker.SYNC_TAG).collect { infos ->
                infos
                    .filter { it.state == WorkInfo.State.SUCCEEDED }
                    .forEach { info ->
                        if (seenSyncIds.add(info.id)) {
                            articleListUseCase.rollGeneration()
                        }
                    }
            }
        }
    }

    private val seenSyncIds = mutableSetOf<UUID>()

    /** S25a — the current list generation (null on legacy search scopes). */
    val listGenerationFlow = articleListUseCase.generationFlow

    // S24a — 30-day position retention is pruned on app start.
    init {
        applicationScope.launch(ioDispatcher) {
            positionStore.prune()
        }
    }

    /** S24a — persist the durable list position for the active scope. */
    suspend fun persistPosition(
        scope: PositionScope,
        anchorArticleId: String?,
        index: Int,
        offset: Int,
    ) {
        positionStore.persist(scope, anchorArticleId, index, offset)
    }

    /** S24a — restore order: exact anchor id, else clamped index, else null (top). */
    suspend fun restorePosition(scope: PositionScope, ids: List<String>): RestoredPosition? =
        positionStore.restore(scope, ids)

    /** S26 — per-article reading position. */
    suspend fun persistReadingPosition(articleId: String, scrollRatioPx: Int, contentHeightPx: Int) =
        positionStore.persistReading(articleId, scrollRatioPx, contentHeightPx)

    /** S26 — per-article reading position; null → open at top. */
    suspend fun restoreReadingPosition(articleId: String): ReadingPosition? =
        positionStore.restoreReading(articleId)

    fun updateReadStatus(
        groupId: String?,
        feedId: String?,
        articleId: String?,
        conditions: MarkAsReadConditions,
        isUnread: Boolean,
    ) {
        applicationScope.launch(ioDispatcher) {
            // Keep loaded badges fresh in the sticky generation (S25a): the DB
            // write covers the whole scoped match set; the overlay updates the
            // loaded items in place without invalidating the generation.
            if (!isUnread) {
                val before = conditions.toDate()
                val items =
                    articleListUseCase.itemSnapshotList.items
                        .filterIsInstance<ArticleFlowItem.Article>()
                        .map { it.articleWithFeed }
                        .filter { it.article.isUnread && it.article.date.before(before) }
                if (items.isNotEmpty()) {
                    diffMapHolder.updateDiff(articleWithFeed = items.toTypedArray(), isUnread = false)
                }
            }
            rssService
                .get()
                .markAsRead(
                    groupId = groupId,
                    feedId = feedId,
                    articleId = articleId,
                    before = conditions.toDate(),
                    isUnread = isUnread,
                )
        }
    }

    fun updateStarredStatus(articleId: String?, isStarred: Boolean) {
        applicationScope.launch(ioDispatcher) {
            if (articleId != null) {
                rssService.get().markAsStarred(articleId = articleId, isStarred = isStarred)
            }
        }
    }

    fun markAsReadFromListByDate(date: Date, isBefore: Boolean) {
        viewModelScope.launch(ioDispatcher) {
            val items =
                articleListUseCase.itemSnapshotList
                    .filterIsInstance<ArticleFlowItem.Article>()
                    .map { it.articleWithFeed }
                    .filter {
                        if (isBefore) {
                            date > it.article.date && it.article.isUnread
                        } else {
                            date < it.article.date && it.article.isUnread
                        }
                    }
                    .distinctBy { it.article.id }

            diffMapHolder.updateDiff(articleWithFeed = items.toTypedArray(), isUnread = false)
        }
    }

    fun loadNextFeedOrGroup() {
        viewModelScope.launch {
            if (
                settingsProvider.settings.pullToSwitchFeed ==
                    PullToLoadNextFeedPreference.MarkAsReadAndLoadNextFeed
            ) {
                markAllAsRead()
            }
            flowUiState.value?.nextFilterState?.let { filterStateUseCase.updateFilterState(it) }
        }
    }

    /**
     * S25a — scoped mark-all-read over the current list scope (view/feed/group):
     * the DB write covers the WHOLE scope in one transaction (S21a semantics:
     * readAt set, isUpdated drained, stateVersion bumped) while the overlay
     * updates already-loaded items in place (sticky generation). Returns the
     * exact affected count (web mark_all_read parity).
     */
    fun markAllAsRead() {
        viewModelScope.launch(ioDispatcher) {
            val items =
                articleListUseCase.itemSnapshotList.items
                    .filterIsInstance<ArticleFlowItem.Article>()
                    .map { it.articleWithFeed }
                    .filter { it.article.isUnread }

            diffMapHolder.updateDiff(articleWithFeed = items.toTypedArray(), isUnread = false)

            val filterState = filterStateUseCase.filterStateFlow.value
            rssService
                .get()
                .markAsRead(
                    groupId = filterState.group?.id,
                    feedId = filterState.feed?.id,
                    articleId = null,
                    before = Date(Long.MAX_VALUE),
                    isUnread = false,
                )
        }
    }

    /** S25a — exact unread count in the current scope, for the confirmation dialog. */
    suspend fun countUnreadInScope(): Int {
        val filterState = filterStateUseCase.filterStateFlow.value
        return rssService
            .get()
            .countUnreadInScope(
                feedId = filterState.feed?.id,
                groupId = filterState.group?.id,
                before = Date(Long.MAX_VALUE),
            )
    }

    fun sync() {
        diffMapHolder.commitDiffsToDb()
        viewModelScope.launch {
            _isSyncingFlow.value = true
            val isSyncing = syncWorkerStatusFlow.value
            if (!isSyncing) {
                delay(1000L)
                if (syncWorkerStatusFlow.value == false) {
                    _isSyncingFlow.value = false
                }
            }
        }
        applicationScope.launch(ioDispatcher) {
            val filterState = filterStateUseCase.filterStateFlow.value
            val service = rssService.get()
            when (service) {
                is LocalRssService ->
                    service.doSyncOneTime(
                        feedId = filterState.feed?.id,
                        groupId = filterState.group?.id,
                    )

                is GoogleReaderRssService ->
                    service.doSyncOneTime(
                        feedId = filterState.feed?.id,
                        groupId = filterState.group?.id,
                    )

                else -> service.doSyncOneTime()
            }
        }
    }

    fun resetFilter() =
        filterStateUseCase.updateFilterState(feed = null, group = null, searchContent = null)

    fun changeFilter(filterState: FilterState) {
        filterStateUseCase.updateFilterState(
            filterState.feed,
            filterState.group,
            filterState.filter,
        )
    }

    fun inputSearchContent(content: String? = null) {
        if (content != filterStateUseCase.filterStateFlow.value.searchContent)
            filterStateUseCase.updateFilterState(searchContent = content)
    }

    // --- S15 saved searches (verbatim-query persistence, view=all scope) ----

    val savedSearches = savedSearchService.listFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun saveSearch(name: String, query: String) {
        viewModelScope.launch(ioDispatcher) {
            savedSearchService.create(name = name, query = query)
        }
    }

    fun renameSearch(id: Long, name: String) {
        viewModelScope.launch(ioDispatcher) {
            savedSearchService.rename(id, name)
        }
    }

    fun deleteSearch(id: Long) {
        viewModelScope.launch(ioDispatcher) {
            savedSearchService.delete(id)
        }
    }

    /** S15 — execute a saved search: view=all plus the verbatim query. */
    fun applySavedSearch(query: String) {
        filterStateUseCase.updateFilterState(
            feed = null,
            group = null,
            filter = me.ash.reader.domain.model.general.Filter.All,
            searchContent = query,
        )
    }

    private val _readingUiState = MutableStateFlow(ReadingUiState())
    val readingUiState: StateFlow<ReadingUiState> = _readingUiState.asStateFlow()

    private val _readerState: MutableStateFlow<ReaderState> = MutableStateFlow(ReaderState())
    val readerStateStateFlow = _readerState.asStateFlow()

    // --- S27a/S30a — downloads -------------------------------------------------

    /** The download row for the currently open article (null = never requested). */
    @OptIn(ExperimentalCoroutinesApi::class)
    val currentDownload: StateFlow<ArticleDownload?> =
        readerStateStateFlow
            .map { it.articleId }
            .distinctUntilChanged()
            .flatMapLatest { articleId ->
                if (articleId == null) flowOf(null)
                else downloadDao.queryByArticleIdFlow(articleId)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Manual single-article download (NO network constraint — R6). */
    fun downloadCurrentArticle() {
        val articleId = readerStateStateFlow.value.articleId ?: return
        applicationScope.launch(ioDispatcher) { downloadService.enqueueManual(articleId) }
    }

    fun removeCurrentArticleDownload() {
        val articleId = readerStateStateFlow.value.articleId ?: return
        applicationScope.launch(ioDispatcher) { downloadService.remove(articleId) }
    }

    private val currentArticle: Article?
        get() = readingUiState.value.articleWithFeed?.article

    private val currentFeed: Feed?
        get() = readingUiState.value.articleWithFeed?.feed

    fun initData(articleId: String, listIndex: Int? = null) {
        viewModelScope.launch {
            val snapshotList = articleListUseCase.itemSnapshotList

            val itemByIndex =
                listIndex?.let { snapshotList.getOrNull(it) as? ArticleFlowItem.Article }

            val itemFromList =
                if (itemByIndex != null && itemByIndex.articleWithFeed.article.id != articleId) {
                    itemByIndex
                } else {
                    snapshotList.find { item ->
                        item is ArticleFlowItem.Article &&
                            item.articleWithFeed.article.id == articleId
                    } as? ArticleFlowItem.Article
                }

            val item =
                itemByIndex?.articleWithFeed
                    ?: (itemFromList?.articleWithFeed
                        ?: rssService.get().findArticleById(articleId)!!)

            // S23 — mark-read-on-open (default ON, web parity): the overlay marks
            // the article read in place immediately (sticky generation, S25a);
            // the S21a DB write (readAt + isUpdated drain + stateVersion) lands in
            // one transaction when the diff holder commits.
            if (
                ReadLifecycle.onOpen(
                    markReadOnOpen = settingsProvider.settings.markReadOnOpen.value,
                    wasUnread = diffMapHolder.checkIfUnread(item),
                ) != null
            ) {
                diffMapHolder.updateDiff(item, isUnread = false)
            }
            item.run {
                _readingUiState.update {
                    it.copy(
                        articleWithFeed = this,
                        isStarred = article.isStarred,
                        isUnread = diffMapHolder.checkIfUnread(this),
                    )
                }
                _readerState.update {
                    it.copy(
                            articleId = article.id,
                            feedName = feed.name,
                            title = article.title,
                            author = article.author,
                            link = article.link,
                            publishedDate = article.date,
                        )
                        .prefetchArticleId()
                        .renderContent(this)
                }
            }
        }
    }

    fun clearReadingData() {
        _readingUiState.update { ReadingUiState() }
        _readerState.update { ReaderState() }
    }

    suspend fun ReaderState.renderContent(articleWithFeed: ArticleWithFeed): ReaderState {
        val contentState =
            if (articleWithFeed.feed.isFullContent) {
                val fullContent =
                    readerCacheHelper.readFullContent(articleWithFeed.article.id).getOrNull()
                if (fullContent != null) ReaderState.FullContent(fullContent)
                else {
                    renderFullContent()
                    ReaderState.Loading
                }
            } else ReaderState.Description(articleWithFeed.article.rawDescription)

        return copy(content = contentState)
    }

    fun renderDescriptionContent() {
        _readerState.update {
            it.copy(
                content = ReaderState.Description(content = currentArticle?.rawDescription ?: "")
            )
        }
    }

    fun renderFullContent() {
        val fetchJob =
            viewModelScope.launch {
                readerCacheHelper
                    .readOrFetchFullContent(currentArticle!!)
                    .onSuccess { content ->
                        _readerState.update {
                            it.copy(content = ReaderState.FullContent(content = content))
                        }
                    }
                    .onFailure { th ->
                        _readerState.update {
                            it.copy(content = ReaderState.Error(th.message.toString()))
                        }
                    }
            }
        viewModelScope.launch {
            delay(100L)
            if (fetchJob.isActive) {
                setLoading()
            }
        }
    }

    fun updateReadStatus(isUnread: Boolean) {
        readingUiState.value.articleWithFeed?.let {
            diffMapHolder.updateDiff(it, isUnread = isUnread)
        }
        _readingUiState.update {
            it.copy(isUnread = diffMapHolder.checkIfUnread(it.articleWithFeed!!))
        }
    }

    fun updateStarredStatus(isStarred: Boolean) {
        applicationScope.launch(ioDispatcher) {
            _readingUiState.update { it.copy(isStarred = isStarred) }
            currentArticle?.let {
                rssService.get().markAsStarred(articleId = it.id, isStarred = isStarred)
            }
        }
    }

    private fun setLoading() {
        _readerState.update { it.copy(content = ReaderState.Loading) }
    }

    /**
     * S25a — resolve prev/next over the CURRENT scope ordering. When a list
     * generation is active, neighbors come from the generation rows (membership
     * or in-generation position fallback); when the current article is outside
     * the generation entirely, the SQL row-value fallback (port of db.py:678)
     * resolves by sort position. Search scopes carry the parsed query into
     * the fallback (Wave D canonical generations).
     */
    suspend fun ReaderState.prefetchArticleId(): ReaderState {
        val currentId = currentArticle?.id ?: return copy(listIndex = null)
        val filterState = filterStateUseCase.filterStateFlow.value
        val sortAscending = settingsProvider.settings.flowSortUnreadArticles.value
        val generation = articleListUseCase.generationFlow.value

        if (generation != null) {
            val neighbors =
                if (generation.indexOf(currentId) != -1) {
                    generation.neighbors(currentId, ascending = sortAscending)
                } else {
                    rssService
                        .get()
                        .queryScopeNeighbors(
                            view = filterState.filter.viewName(),
                            feedId = filterState.feed?.id,
                            groupId = filterState.group?.id,
                            sortAscending = sortAscending,
                            articleId = currentId,
                            searchContent = filterState.searchContent,
                        )
                }
            val index = generation.indexOf(currentId)
            return copy(
                listIndex = index,
                previousArticle =
                    neighbors.previous?.let {
                        ReaderState.PrefetchResult(
                            articleId = it,
                            index = generation.indexOf(it),
                        )
                    },
                nextArticle =
                    neighbors.next?.let {
                        ReaderState.PrefetchResult(
                            articleId = it,
                            index = generation.indexOf(it),
                        )
                    },
            )
        }

        val items = articleListUseCase.itemSnapshotList
        val index =
            items.indexOfFirst { item ->
                item is ArticleFlowItem.Article && item.articleWithFeed.article.id == currentId
            }
        var previousArticle: ReaderState.PrefetchResult? = null
        var nextArticle: ReaderState.PrefetchResult? = null

        if (index != -1 || currentId == null) {
            val prevIterator = items.listIterator(index)
            while (prevIterator.hasPrevious()) {
                val previousIndex = prevIterator.previousIndex()
                val prev = prevIterator.previous()
                if (prev is ArticleFlowItem.Article) {
                    previousArticle =
                        ReaderState.PrefetchResult(
                            articleId = prev.articleWithFeed.article.id,
                            index = previousIndex,
                        )
                    break
                }
            }
            val nextIterator = items.listIterator(index + 1)
            while (nextIterator.hasNext()) {
                val nextIndex = nextIterator.nextIndex()
                val next = nextIterator.next()
                if (
                    next is ArticleFlowItem.Article && next.articleWithFeed.article.id != currentId
                ) {
                    nextArticle =
                        ReaderState.PrefetchResult(
                            articleId = next.articleWithFeed.article.id,
                            index = nextIndex,
                        )
                    break
                }
            }
        }

        Timber.d("$previousArticle, $nextArticle, $listIndex")
        return copy(nextArticle = nextArticle, previousArticle = previousArticle, listIndex = index)
    }

    fun downloadImage(
        url: String,
        onSuccess: (Uri) -> Unit = {},
        onFailure: (Throwable) -> Unit = {},
    ) {
        viewModelScope.launch {
            imageDownloader.downloadImage(url).onSuccess(onSuccess).onFailure(onFailure)
        }
    }
}

data class FlowUiState(val pagerData: PagerData, val nextFilterState: FilterState? = null)

/** S16 — search-screen FTS coverage indicator (account-scoped, R4). */
data class SearchCoverage(
    val covered: Int,
    val total: Int,
    /** account.updateAt older than 24 h. */
    val stale: Boolean,
) {
    companion object {
        const val STALE_SYNC_MILLIS: Long = 24L * 3600 * 1000
    }
}

data class ReadingUiState(
    val articleWithFeed: ArticleWithFeed? = null,
    val isUnread: Boolean = false,
    val isStarred: Boolean = false,
)

data class ReaderState(
    val articleId: String? = null,
    val feedName: String = "",
    val title: String? = null,
    val author: String? = null,
    val link: String? = null,
    val publishedDate: Date = Date(0L),
    val content: ContentState = Loading,
    val listIndex: Int? = null,
    val nextArticle: PrefetchResult? = null,
    val previousArticle: PrefetchResult? = null,
) {
    data class PrefetchResult(val articleId: String, val index: Int)

    sealed interface ContentState {
        val text: String?
            get() {
                return when (this) {
                    is Description -> content
                    is Error -> message
                    is FullContent -> content
                    Loading -> null
                }
            }
    }

    data class FullContent(val content: String) : ContentState

    data class Description(val content: String) : ContentState

    data class Error(val message: String) : ContentState

    data object Loading : ContentState
}
