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
import kotlinx.coroutines.Job
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
import me.ash.reader.domain.model.extract.TeaserTrigger
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.general.MarkAsReadConditions
import me.ash.reader.domain.model.position.PositionScope
import me.ash.reader.domain.model.position.PositionStore
import me.ash.reader.domain.model.position.ReadingPosition
import me.ash.reader.domain.model.position.RestoredPosition
import me.ash.reader.domain.model.download.ArticleDownload
import me.ash.reader.domain.repository.DownloadDao
import me.ash.reader.domain.service.DownloadService
import me.ash.reader.domain.service.ExtractCoordinator
import me.ash.reader.domain.service.ExtractMode
import me.ash.reader.domain.service.ExtractOutcome
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
import timber.log.Timber

private const val TAG = "FlowViewModel"

/** E13 — the transient extraction-failure tint lasts at most 3 seconds. */
private const val EXTRACT_FAILURE_TINT_MILLIS = 3_000L

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
    private val extractCoordinator: ExtractCoordinator,
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
                        .filter { isUnreadBeforeCutoff(it.article, before) }
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

    /**
     * Review lane 2 — the in-flight [initData] job. Opening a new article
     * cancels the previous open so two rapid switches can never interleave
     * their state writes out of order (last call wins).
     */
    private var initDataJob: Job? = null

    /** Review lane 2 — the in-flight extraction job for the current article. */
    private var extractionJob: Job? = null

    /**
     * Review lane 2 — open-generation token, bumped on every article open
     * ([initData]), reader reset ([clearReadingData]) and new extraction
     * launch. An extraction completion carrying a stale generation is
     * ignored, so article A's pipeline finishing after a switch to B can
     * never overwrite B's reader state. Read from IO completion threads.
     */
    @Volatile private var extractionGeneration = 0

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
        // Review lane 2 — cancel the previous open and any extraction still
        // in flight for it BEFORE starting the new one, so rapid article
        // switching cannot interleave state writes out of order and a stale
        // extraction completion cannot overwrite the new article's state.
        initDataJob?.cancel()
        cancelActiveExtraction()
        initDataJob =
            viewModelScope.launch {
                val snapshotList = articleListUseCase.itemSnapshotList

                // Review lane 2 — the index-based fast path is only trusted
                // when it actually holds the REQUESTED article; a stale
                // `listIndex` (list mutated since the tap) falls through to
                // the id scan instead of opening the wrong row.
                val itemByIndex =
                    listIndex
                        ?.let { snapshotList.getOrNull(it) as? ArticleFlowItem.Article }
                        ?.takeIf { it.articleWithFeed.article.id == articleId }

                val itemFromList =
                    snapshotList.find { item ->
                        item is ArticleFlowItem.Article &&
                            item.articleWithFeed.article.id == articleId
                    } as? ArticleFlowItem.Article

                val item =
                    itemByIndex?.articleWithFeed
                        ?: itemFromList?.articleWithFeed
                        ?: rssService.get().findArticleById(articleId)
                if (item == null) {
                    // Deleted/missing article (or a concurrent clear): reset
                    // the reader instead of crashing on a null dereference.
                    Timber.tag(TAG)
                        .w("initData: article $articleId not found — resetting reader state")
                    _readingUiState.update { ReadingUiState() }
                    _readerState.update { ReaderState() }
                    return@launch
                }

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
                                // Review lane 2 — a fresh open must not
                                // inherit the PREVIOUS article's busy/tint
                                // flags; renderContent re-arms them below if
                                // it launches an extraction for THIS article.
                                isExtracting = false,
                                extractFailed = false,
                            )
                            .prefetchArticleId()
                            .renderContent(this)
                    }
                }
            }
    }

    /**
     * Review lane 2 — cancels the in-flight extraction job (if any) and bumps
     * the open-generation token so a completion already racing on another
     * thread is ignored by the stale-generation guard in [launchExtraction].
     */
    private fun cancelActiveExtraction() {
        extractionGeneration++
        extractionJob?.cancel()
        extractionJob = null
    }

    fun clearReadingData() {
        initDataJob?.cancel()
        cancelActiveExtraction()
        _readingUiState.update { ReadingUiState() }
        _readerState.update { ReaderState() }
    }

    /**
     * E8/E10 — on-open render state machine. Evaluation order (binding):
     *  1. `fullContent != null` → render the stored extraction (no network);
     *  2. `feed.isFullContent` → FORCE extraction (E9 — independent of the
     *     E20 global toggle and of the E6 teaser trigger);
     *  3. global toggle ON (E20) AND E6 trigger → AUTO extraction;
     *  4. else → `Description(rawDescription)`.
     *
     * While an extraction runs the state is `Loading` + `isExtracting` (the
     * existing `Content.kt` indicator, E10); success updates the state
     * IN PLACE (E4 — no Paging invalidation, no generation roll); ANY
     * failure falls back silently to `Description` (E10/E21 — the legacy
     * error-wall content state is deleted).
     */
    suspend fun ReaderState.renderContent(articleWithFeed: ArticleWithFeed): ReaderState {
        val article = articleWithFeed.article
        val feed = articleWithFeed.feed
        // 1 — stored extraction: render the row's sanitized HTML, zero network.
        val stored = article.fullContent
        if (stored != null) return copy(content = ReaderState.FullContent(stored))
        // 2 — flagged feed: force regardless of the global toggle (E9/E20
        // independence). PREFETCH is the coordinator's forced best-effort
        // mode: trigger bypassed, fresh-guard set, fully silent failure.
        if (feed.isFullContent) {
            launchExtraction(articleWithFeed, ExtractMode.PREFETCH)
            return copy(content = ReaderState.Loading, isExtracting = true)
        }
        // 3 — adaptive on-open path: E20 global gate + E6 teaser trigger.
        val autoExtractEnabled = settingsProvider.settings.autoExtractFullContent.value
        val isTeaser =
            TeaserTrigger.isTeaserCandidate(
                link = article.link,
                fullContent = article.fullContent,
                rawDescription = article.rawDescription,
                medianBodyLength = feed.medianBodyLength,
            )
        if (autoExtractEnabled && isTeaser) {
            launchExtraction(articleWithFeed, ExtractMode.AUTO)
            return copy(content = ReaderState.Loading, isExtracting = true)
        }
        // 4 — feed content as-is.
        return copy(content = ReaderState.Description(article.rawDescription))
    }

    fun renderDescriptionContent() {
        _readerState.update {
            it.copy(
                content = ReaderState.Description(content = currentArticle?.rawDescription ?: "")
            )
        }
    }

    /**
     * E11 — toggle path when a stored extraction exists: renders the row's
     * `fullContent`. Pure view switch — zero network; the legacy fetch +
     * error-wall branch is deleted (E10).
     */
    fun renderFullContent() {
        val stored = currentArticle?.fullContent ?: return
        _readerState.update { it.copy(content = ReaderState.FullContent(content = stored)) }
    }

    /**
     * E11 — MANUAL extract entry point (BottomBar article button when
     * `fullContent == null`): runs for ANY article, any feed, regardless of
     * the E6 trigger or the E20 toggle (explicit user intent, web parity).
     * Busy → the button is disabled and the content area shows the E10
     * `Loading` indicator; success → `FullContent`; failure → silent
     * `Description` fallback + the ≤3 s transient icon tint (E13).
     */
    fun extractFullContent() {
        val articleWithFeed = readingUiState.value.articleWithFeed ?: return
        if (_readerState.value.isExtracting) return // busy guard (E11)
        _readerState.update { it.copy(content = ReaderState.Loading, isExtracting = true) }
        launchExtraction(articleWithFeed, ExtractMode.MANUAL)
    }

    /**
     * E12 — MANUAL re-extract (TopBar action, extracted-only rows). Busy:
     * the content stays rendered while `isExtracting` drives the in-flight
     * icon (S27a download-state grammar). Success updates the stored row;
     * the rendered `FullContent` view refreshes in place, and a user
     * viewing `Description` is NEVER force-switched. Failure leaves the
     * stored extraction untouched (silent + transient tint, E13/E21).
     */
    fun reExtractFullContent() {
        val articleWithFeed = readingUiState.value.articleWithFeed ?: return
        if (articleWithFeed.article.fullContent == null) return // extracted-only (E12)
        if (_readerState.value.isExtracting) return
        _readerState.update { it.copy(isExtracting = true) }
        launchExtraction(articleWithFeed, ExtractMode.RE_EXTRACT)
    }

    /**
     * Runs one extraction on the single E2 pipeline. Completion updates the
     * reader state IN PLACE — extraction never rolls a list generation nor
     * refreshes Paging (E4/E22: the write leaves `rawDescription` /
     * `shortDescription` untouched, so the flow preview cannot change).
     *
     * Review lane 2 — the job is tracked and the open-generation token is
     * captured at launch: `extractionGeneration` is bumped synchronously by
     * every article open ([initData]), reader reset ([clearReadingData]) and
     * new extraction launch, so a completion arriving after the reader moved
     * on carries a stale generation and is ignored before any handler runs.
     * (The generation token is the race-free form of an
     * `_readerState.value.articleId == articleId` check — the articleId read
     * would race the very `initData` update that dispatches this extraction,
     * and it also misses same-article re-opens.) The new job supersedes
     * (cancels) the previous one; note the coordinator pipeline itself is
     * caller-cancellation resilient, so a cancelled switch-away still lets
     * the DB write for the OLD article land (best-effort by contract) — only
     * the UI application is suppressed.
     */
    private fun launchExtraction(articleWithFeed: ArticleWithFeed, mode: ExtractMode) {
        val articleId = articleWithFeed.article.id
        val generation = ++extractionGeneration
        extractionJob?.cancel()
        extractionJob =
            viewModelScope.launch(ioDispatcher) {
                val outcome =
                    extractCoordinator.extract(articleWithFeed.article, articleWithFeed.feed, mode)
                if (generation != extractionGeneration) {
                    Timber.tag(TAG)
                        .d("extraction($articleId, $mode) finished after navigation — ignored")
                    return@launch
                }
                when (outcome) {
                    is ExtractOutcome.Stored -> onExtractionStored(generation, articleId, mode)
                    is ExtractOutcome.Skipped ->
                        onExtractionNotStored(generation, articleWithFeed, mode, failed = false)
                    is ExtractOutcome.Failure ->
                        onExtractionNotStored(generation, articleWithFeed, mode, failed = true)
                }
            }
    }

    /** E4/E10/E12 — in-place state update on success; no list invalidation. */
    private suspend fun onExtractionStored(generation: Int, articleId: String, mode: ExtractMode) {
        // Review lane 2 — a stale completion for a previously open article
        // must never touch the current reader state (re-checked after the
        // DB re-read suspends, and atomically inside the state update).
        if (generation != extractionGeneration) return
        // Re-read the row so the rendered state and the in-memory article
        // carry the DB truth (sanitized fullContent + extractedAt, E3).
        val fresh = rssService.get().findArticleById(articleId)
        if (generation != extractionGeneration) return
        if (fresh != null && _readingUiState.value.articleWithFeed?.article?.id == articleId) {
            _readingUiState.update { it.copy(articleWithFeed = fresh) }
        }
        val stored = fresh?.article?.fullContent
        _readerState.update { state ->
            if (generation != extractionGeneration) return@update state
            val content =
                when {
                    // E12: re-extract success while viewing `Description`
                    // updates ONLY the stored row — no forced view switch
                    // (keeps the S22 diff pane reachable, E22b).
                    mode == ExtractMode.RE_EXTRACT && state.content !is ReaderState.FullContent ->
                        state.content

                    stored != null -> ReaderState.FullContent(stored)
                    else -> state.content
                }
            state.copy(content = content, isExtracting = false, extractFailed = false)
        }
    }

    /**
     * E10/E13/E21 — silent fallback: `Loading` → `Description` (the feed
     * content the user would have seen anyway). The transient (≤3 s)
     * "unavailable" tint fires ONLY for explicit MANUAL/RE_EXTRACT
     * failures; AUTO and force-on-open PREFETCH failures are fully silent.
     */
    private fun onExtractionNotStored(
        generation: Int,
        articleWithFeed: ArticleWithFeed,
        mode: ExtractMode,
        failed: Boolean,
    ) {
        // Review lane 2 — never clear another article's busy flag or tint the
        // current icons for a previous article's failure.
        if (generation != extractionGeneration) return
        val transientSignal =
            failed && (mode == ExtractMode.MANUAL || mode == ExtractMode.RE_EXTRACT)
        _readerState.update { state ->
            if (generation != extractionGeneration) return@update state
            state.copy(
                isExtracting = false,
                extractFailed = transientSignal,
                content =
                    if (state.content is ReaderState.Loading) {
                        ReaderState.Description(articleWithFeed.article.rawDescription)
                    } else {
                        state.content
                    },
            )
        }
        if (transientSignal) {
            // E13 — ephemeral state, never persisted, never a toast/dialog,
            // and it never steals focus (tint-only change on the existing
            // icons, stable contentDescriptions). The token keeps a stale
            // clear from an earlier failure shortening the current tint.
            val token = ++extractFailureToken
            viewModelScope.launch {
                delay(EXTRACT_FAILURE_TINT_MILLIS)
                if (token == extractFailureToken) {
                    _readerState.update { it.copy(extractFailed = false) }
                }
            }
        }
    }

    /** E13 transient-tint generation token (see [onExtractionNotStored]). */
    private var extractFailureToken = 0

    fun updateReadStatus(isUnread: Boolean) {
        val current = readingUiState.value.articleWithFeed
        if (current != null) {
            diffMapHolder.updateDiff(current, isUnread = isUnread)
        }
        // Review lane 2 — a concurrent clearReadingData() can null the row
        // between the diff write and this update; never dereference with !!.
        _readingUiState.update { state ->
            val awf = state.articleWithFeed ?: return@update state
            state.copy(isUnread = diffMapHolder.checkIfUnread(awf))
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
    /**
     * E10/E11/E12 — an extraction is in flight on the single E2 pipeline:
     * the BottomBar article button is disabled (E11), the TopBar re-extract
     * icon shows its in-flight state (E12), and AUTO/MANUAL-from-description
     * render the existing `Loading` indicator.
     */
    val isExtracting: Boolean = false,
    /**
     * E13 — transient (≤3 s) "unavailable" tint on the extraction icons
     * after an explicit MANUAL/RE_EXTRACT failure. Ephemeral UI state only:
     * never persisted, never a dialog/toast, never a focus change; AUTO and
     * force-on-open failures are fully silent and never set it.
     */
    val extractFailed: Boolean = false,
) {
    data class PrefetchResult(val articleId: String, val index: Int)

    // E10 — `Error` was REMOVED from the sealed interface: the extraction
    // path was its only producer (the legacy renderFullContent error wall),
    // and every failure now falls back silently (E21).
    sealed interface ContentState {
        val text: String?
            get() {
                return when (this) {
                    is Description -> content
                    is FullContent -> content
                    Loading -> null
                }
            }
    }

    data class FullContent(val content: String) : ContentState

    data class Description(val content: String) : ContentState

    data object Loading : ContentState
}

/**
 * Mark-all-read overlay predicate for loaded articles.
 *
 * A null [cutoff] means "all" (`MarkAsReadConditions.All.toDate()` is null), so every unread
 * loaded article matches. Calling `Date.before(null)` there threw an NPE on mark-all-read
 * (crash report 2026-11-18, Android 17, SM-S948U1).
 */
internal fun isUnreadBeforeCutoff(article: Article, cutoff: Date?): Boolean =
    article.isUnread && (cutoff == null || article.date.before(cutoff))
