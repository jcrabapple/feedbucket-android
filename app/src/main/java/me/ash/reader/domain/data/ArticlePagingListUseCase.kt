package me.ash.reader.domain.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.paging.ItemSnapshotList
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingDataEvent
import androidx.paging.PagingDataPresenter
import androidx.paging.cachedIn
import javax.inject.Inject
import kotlin.text.trim
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.ash.reader.domain.model.article.ArticleFlowItem
import me.ash.reader.domain.model.article.GenerationPagingSource
import me.ash.reader.domain.model.article.ListGeneration
import me.ash.reader.domain.model.article.NeighborRow
import me.ash.reader.domain.model.article.mapPagingFlowItem
import me.ash.reader.domain.service.AccountService
import me.ash.reader.domain.service.RssService
import me.ash.reader.infrastructure.android.AndroidStringsHelper
import me.ash.reader.infrastructure.di.ApplicationScope
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.infrastructure.preference.SettingsProvider

class ArticlePagingListUseCase
@Inject
constructor(
    private val rssService: RssService,
    private val androidStringsHelper: AndroidStringsHelper,
    @ApplicationScope private val applicationScope: CoroutineScope,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
    private val settingsProvider: SettingsProvider,
    private val filterStateUseCase: FilterStateUseCase,
    private val accountService: AccountService,
) {

    private val mutablePagerFlow =
        MutableStateFlow<PagerData>(
            PagerData(filterState = filterStateUseCase.filterStateFlow.value)
        )
    val pagerFlow: StateFlow<PagerData> = mutablePagerFlow

    /**
     * S25a — the current list generation. Read-state writes never invalidate
     * it; only a scope change, account switch, or [rollGeneration] (sync
     * completion / explicit refresh) rolls a new one. Search scopes roll
     * canonical query generations since Wave D (S10/S12a).
     */
    private val _generationFlow = MutableStateFlow<ListGeneration?>(null)
    val generationFlow: StateFlow<ListGeneration?> = _generationFlow

    /** S25a(2) — refresh/sync completion rolls a new generation. */
    private val generationTrigger = MutableStateFlow(0L)

    fun rollGeneration() {
        generationTrigger.value += 1L
    }

    var itemSnapshotList by
        mutableStateOf(
            ItemSnapshotList<ArticleFlowItem>(
                placeholdersBefore = 0,
                placeholdersAfter = 0,
                items = emptyList(),
            )
        )
        private set

    val pagingDataPresenter =
        object : PagingDataPresenter<ArticleFlowItem>() {
            override suspend fun presentPagingDataEvent(event: PagingDataEvent<ArticleFlowItem>) {
                itemSnapshotList = snapshot()
            }
        }

    init {
        applicationScope.launch(ioDispatcher) {
            combine(
                filterStateUseCase.filterStateFlow,
                accountService.currentAccountIdFlow,
                generationTrigger,
            ) { filterState, accountId, _ -> filterState to accountId }
                .collectLatest { (filterState, _) ->
                    val searchContent = filterState.searchContent
                    val sortAscending =
                        settingsProvider.settings.flowSortUnreadArticles.value

                    if (!searchContent.isNullOrBlank()) {
                        // Wave D — canonical query generations (S10/S12a/S14/
                        // S15): the search string parses through the ONE
                        // grammar; terms filter via SearchSql over the
                        // *Lower shadows; `sort:` tokens drive ordering
                        // (relevance per S14); query mutes still apply on
                        // unread/updated views. Search scopes now roll
                        // generations like every other scope.
                        val rows =
                            rssService
                                .get()
                                .queryScopeRows(
                                    view = filterState.filter.viewName(),
                                    feedId = filterState.feed?.id,
                                    groupId = filterState.group?.id,
                                    sortAscending = sortAscending,
                                    searchContent = searchContent,
                                )
                        val generation =
                            ListGeneration(
                                rows =
                                    rows.map {
                                        NeighborRow(
                                            articleId = it.articleId,
                                            sortKey = it.sortKey,
                                            rowid = it.rowId,
                                        )
                                    },
                                rolledAt = System.currentTimeMillis(),
                            )
                        _generationFlow.value = generation
                        mutablePagerFlow.value =
                            PagerData(
                                Pager(
                                        config =
                                            PagingConfig(pageSize = 50, enablePlaceholders = false)
                                    ) {
                                        GenerationPagingSource(generation) { ids ->
                                            rssService.get().queryArticleWithFeedByIds(ids)
                                        }
                                    }
                                    .flow
                                    .map { it.mapPagingFlowItem(androidStringsHelper) }
                                    .cachedIn(applicationScope),
                                filterState = filterState,
                            )
                    } else {
                        // S25a — roll a generation anchored at scope entry or the
                        // last refresh/sync; read-state writes never invalidate it.
                        val rows =
                            rssService
                                .get()
                                .queryScopeRows(
                                    view = filterState.filter.viewName(),
                                    feedId = filterState.feed?.id,
                                    groupId = filterState.group?.id,
                                    sortAscending = sortAscending,
                                )
                        val generation =
                            ListGeneration(
                                rows =
                                    rows.map {
                                        NeighborRow(
                                            articleId = it.articleId,
                                            sortKey = it.sortKey,
                                            rowid = it.rowId,
                                        )
                                    },
                                rolledAt = System.currentTimeMillis(),
                            )
                        _generationFlow.value = generation
                        mutablePagerFlow.value =
                            PagerData(
                                Pager(
                                        config =
                                            PagingConfig(pageSize = 50, enablePlaceholders = false)
                                    ) {
                                        GenerationPagingSource(generation) { ids ->
                                            rssService.get().queryArticleWithFeedByIds(ids)
                                        }
                                    }
                                    .flow
                                    .map { it.mapPagingFlowItem(androidStringsHelper) }
                                    .cachedIn(applicationScope),
                                filterState = filterState,
                            )
                    }
                }
        }
        applicationScope.launch {
            pagerFlow.collectLatest { (pager, _) ->
                pager.collectLatest { pagingDataPresenter.collectFrom(it) }
            }
        }
    }
}

data class PagerData(
    val pager: Flow<PagingData<ArticleFlowItem>> = emptyFlow(),
    val filterState: FilterState = FilterState(),
)
