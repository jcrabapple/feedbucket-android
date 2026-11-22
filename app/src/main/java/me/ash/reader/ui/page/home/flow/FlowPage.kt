package me.ash.reader.ui.page.home.flow

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.paging.ItemSnapshotList
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.ash.reader.R
import me.ash.reader.domain.data.PagerData
import me.ash.reader.domain.model.article.ArticleFlowItem
import me.ash.reader.domain.model.article.ArticleWithFeed
import me.ash.reader.domain.model.general.Filter
import me.ash.reader.domain.model.general.MarkAsReadConditions
import me.ash.reader.domain.model.position.PositionScope
import me.ash.reader.domain.model.search.SavedSearch
import me.ash.reader.infrastructure.preference.LocalFlowArticleListDateStickyHeader
import me.ash.reader.infrastructure.preference.LocalFlowArticleListFeedIcon
import me.ash.reader.infrastructure.preference.LocalFlowArticleListTonalElevation
import me.ash.reader.infrastructure.preference.LocalFlowFilterBarPadding
import me.ash.reader.infrastructure.preference.LocalFlowFilterBarStyle
import me.ash.reader.infrastructure.preference.LocalFlowFilterBarTonalElevation
import me.ash.reader.infrastructure.preference.LocalFlowTopBarTonalElevation
import me.ash.reader.infrastructure.preference.LocalHideRecentlyRead
import me.ash.reader.infrastructure.preference.LocalMarkAllReadConfirmation
import me.ash.reader.infrastructure.preference.LocalMarkAsReadOnScroll
import me.ash.reader.infrastructure.preference.LocalOpenLink
import me.ash.reader.infrastructure.preference.LocalOpenLinkSpecificBrowser
import me.ash.reader.infrastructure.preference.LocalSettings
import me.ash.reader.infrastructure.preference.LocalSharedContent
import me.ash.reader.infrastructure.preference.LocalSortUnreadArticles
import me.ash.reader.infrastructure.preference.PullToLoadNextFeedPreference
import me.ash.reader.infrastructure.preference.SortUnreadArticlesPreference
import me.ash.reader.ui.component.FilterBar
import me.ash.reader.ui.component.base.FeedbackIconButton
import me.ash.reader.ui.component.base.RYExtensibleVisibility
import me.ash.reader.ui.component.base.RYScaffold
import me.ash.reader.ui.component.scrollbar.VerticalScrollIndicatorFactory
import me.ash.reader.ui.component.scrollbar.drawVerticalScrollIndicator
import me.ash.reader.ui.component.scrollbar.scrollIndicator
import me.ash.reader.ui.ext.collectAsStateValue
import me.ash.reader.ui.ext.openURL
import me.ash.reader.ui.motion.Direction
import me.ash.reader.ui.motion.sharedXAxisTransitionSlow
import me.ash.reader.ui.motion.sharedYAxisTransitionExpressive
import me.ash.reader.ui.page.adaptive.ArticleListReaderViewModel
import me.ash.reader.ui.page.home.reading.PullToLoadDefaults
import me.ash.reader.ui.page.home.reading.PullToLoadDefaults.ContentOffsetMultiple
import me.ash.reader.ui.page.home.reading.PullToLoadState
import me.ash.reader.ui.page.home.reading.pullToLoad
import me.ash.reader.ui.page.home.reading.rememberPullToLoadState

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterialApi::class,
    FlowPreview::class,
)
@Composable
fun FlowPage(
    isTwoPane: Boolean,
    viewModel: ArticleListReaderViewModel,
    onNavigateUp: () -> Unit,
    navigateToArticle: (String, Int) -> Unit,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val articleListTonalElevation = LocalFlowArticleListTonalElevation.current
    val articleListFeedIcon = LocalFlowArticleListFeedIcon.current
    val articleListDateStickyHeader = LocalFlowArticleListDateStickyHeader.current
    val topBarTonalElevation = LocalFlowTopBarTonalElevation.current
    val filterBarStyle = LocalFlowFilterBarStyle.current
    val filterBarPadding = LocalFlowFilterBarPadding.current
    val filterBarTonalElevation = LocalFlowFilterBarTonalElevation.current
    val sharedContent = LocalSharedContent.current
    val markAsReadOnScroll = LocalMarkAsReadOnScroll.current.value
    val context = LocalContext.current

    val openLink = LocalOpenLink.current
    val openLinkSpecificBrowser = LocalOpenLinkSpecificBrowser.current

    val settings = LocalSettings.current
    val pullToSwitchFeed = settings.pullToSwitchFeed
    val markAllReadConfirmation = LocalMarkAllReadConfirmation.current.value
    val hideRecentlyRead = LocalHideRecentlyRead.current.value

    val flowUiState = viewModel.flowUiState.collectAsStateValue()
    if (flowUiState == null) return

    val pagerData: PagerData = flowUiState.pagerData

    val filterUiState = pagerData.filterState

    val listState = rememberSaveable(pagerData, saver = LazyListState.Saver) { LazyListState(0, 0) }

    val isTopBarElevated = topBarTonalElevation.value > 0
    val scrolledTopBarContainerColor =
        with(MaterialTheme.colorScheme) { if (isTopBarElevated) surfaceContainer else surface }

    val titleText =
        when {
            filterUiState.group != null -> filterUiState.group.name
            filterUiState.feed != null -> filterUiState.feed.name
            else -> filterUiState.filter.toName()
        }

    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    var markAsRead by remember { mutableStateOf(false) }
    var onSearch by rememberSaveable { mutableStateOf(false) }

    var currentPullToLoadState: PullToLoadState? by remember { mutableStateOf(null) }
    var currentLoadAction: LoadAction? by remember { mutableStateOf(null) }

    // S25a confirmation dialog state: the pending condition and exact scoped count.
    var confirmMarkAll by remember { mutableStateOf<MarkAsReadConditions?>(null) }
    var confirmMarkAllCount by remember { mutableStateOf(0) }

    // S15 — saved search under management (rename/delete dialog).
    var managedSearch by remember { mutableStateOf<SavedSearch?>(null) }
    var manageName by remember { mutableStateOf("") }
    var confirmDeleteSearch by remember { mutableStateOf<SavedSearch?>(null) }

    val sortByEarliest =
        filterUiState.filter.isUnread() &&
            LocalSortUnreadArticles.current == SortUnreadArticlesPreference.Earliest

    // S24a — canonical position scope for the active list scope.
    val positionScope =
        PositionScope(
            view = filterUiState.filter.viewName(),
            feedId = filterUiState.feed?.id,
            groupId = filterUiState.group?.id,
            query = filterUiState.searchContent ?: "",
            sort = if (sortByEarliest) "asc" else "desc",
        )

    val settleSpec = remember { spring<Float>(dampingRatio = Spring.DampingRatioLowBouncy) }

    val lastVisibleIndex =
        remember(listState) {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
                .filterNotNull()
        }

    val onToggleStarred: (ArticleWithFeed) -> Unit = remember {
        { article ->
            viewModel.updateStarredStatus(
                articleId = article.article.id,
                isStarred = !article.article.isStarred,
            )
        }
    }

    val onToggleRead: (ArticleWithFeed) -> Unit = remember {
        { articleWithFeed -> viewModel.diffMapHolder.updateDiff(articleWithFeed) }
    }

    val onMarkAboveAsRead: ((ArticleWithFeed) -> Unit)? =
        remember(sortByEarliest) {
            {
                viewModel.markAsReadFromListByDate(
                    date = it.article.date,
                    isBefore = sortByEarliest,
                )
            }
        }

    val onMarkBelowAsRead: ((ArticleWithFeed) -> Unit)? =
        remember(sortByEarliest) {
            {
                viewModel.markAsReadFromListByDate(
                    date = it.article.date,
                    isBefore = !sortByEarliest,
                )
            }
        }

    val onShare: ((ArticleWithFeed) -> Unit)? = remember {
        { articleWithFeed ->
            with(articleWithFeed.article) { sharedContent.share(context, title, link) }
        }
    }

    LaunchedEffect(onSearch) {
        if (!onSearch) {
            keyboardController?.hide()
            viewModel.inputSearchContent(null)
        }
    }

    val topAppBarState = rememberTopAppBarState()

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(topAppBarState)

    val scrollAppBarToCollapsed =
        remember(topAppBarState) {
            {
                scope.launch {
                    val initial = topAppBarState.heightOffset
                    val target = topAppBarState.heightOffsetLimit
                    if (initial != target)
                        animate(
                            initialValue = initial,
                            targetValue = target,
                            initialVelocity = 0f,
                            animationSpec = settleSpec,
                        ) { value, _ ->
                            topAppBarState.heightOffset = value
                        }
                }
            }
        }

    val snapAppBarToCollapsed =
        remember(topAppBarState) {
            {
                scope.launch {
                    val initial = topAppBarState.heightOffset
                    val target = topAppBarState.heightOffsetLimit
                    if (initial != target) {
                        topAppBarState.heightOffset = target
                    }
                }
            }
        }

    val readerState = viewModel.readerStateStateFlow.collectAsStateValue()

    var pagingItems: LazyPagingItems<ArticleFlowItem>? by remember { mutableStateOf(null) }

    // queue-scroll — capture the active reader article at composition time:
    // on Back navigation the reading data is cleared shortly after the list
    // pane re-enters composition, and the return alignment below must run
    // against the article that was actually open.
    val returnArticleId = readerState.articleId

    if (isTwoPane) {
        LaunchedEffect(readerState) {
            val articleId = returnArticleId ?: return@LaunchedEffect
            val decision =
                listState.awaitReturnScrollDecision(
                    articleId = articleId,
                    stickyHeaderEnabled = articleListDateStickyHeader.value,
                    itemCount = { pagingItems?.itemCount ?: 0 },
                    items = { pagingItems?.itemSnapshotList },
                )
            // queue-scroll — only an article that is not cleanly visible
            // moves, and only to the top of the viewport (offset 0). The
            // former unconditional negative offset dragged the row toward
            // the bottom edge on every reader state change.
            if (decision is ReturnScrollDecision.ScrollTo) {
                scrollAppBarToCollapsed()
                listState.animateScrollToItem(decision.targetIndex, decision.scrollOffset)
            }
        }
    } else {
        LaunchedEffect(Unit) {
            val articleId = returnArticleId ?: return@LaunchedEffect
            val decision =
                listState.awaitReturnScrollDecision(
                    articleId = articleId,
                    stickyHeaderEnabled = articleListDateStickyHeader.value,
                    itemCount = { pagingItems?.itemCount ?: 0 },
                    items = { pagingItems?.itemSnapshotList },
                )
            // queue-scroll — returning from the reader, the saveable list
            // state already carries the tapped position: a cleanly visible
            // article stays exactly where the user tapped it (list and top
            // bar untouched). Only a clipped or off-screen target (e.g.
            // after swiping to another article inside the reader) scrolls,
            // and only to the top of the viewport. The scroll is requested
            // (applied on the next layout pass) rather than performed
            // inline: this coroutine resumes off a snapshotFlow of layout
            // state, and a suspending scroll here could re-enter an
            // in-flight measure pass.
            if (decision is ReturnScrollDecision.ScrollTo) {
                snapAppBarToCollapsed()
                listState.requestScrollToItem(decision.targetIndex, decision.scrollOffset)
            }
        }
    }

    val isSyncing = viewModel.isSyncingFlow.collectAsStateValue()

    // S24a — persist the position on ON_PAUSE / navigation-away.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, positionScope.key) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE) {
                    val index = listState.firstVisibleItemIndex
                    val offset = listState.firstVisibleItemScrollOffset
                    val anchorId =
                        anchorArticleIdAt(pagingItems?.itemSnapshotList, index)
                    scope.launch {
                        viewModel.persistPosition(positionScope, anchorId, index, offset)
                    }
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        RYScaffold(
            containerTonalElevation = articleListTonalElevation.value.dp,
            topBar = {
                MaterialTheme(
                    colorScheme = MaterialTheme.colorScheme,
                    typography =
                        MaterialTheme.typography.copy(
                            titleLarge =
                                MaterialTheme.typography.titleLarge.merge(
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Medium,
                                ),
                        ),
                ) {
                    LargeTopAppBar(
                        modifier =
                            Modifier.clickable(
                                onClick = {
                                    scope.launch {
                                        if (listState.firstVisibleItemIndex != 0) {
                                            listState.animateScrollToItem(0)
                                        }
                                    }
                                },
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                            ),
                        title = {
                            val textStyle = LocalTextStyle.current
                            val color = LocalContentColor.current
                            if (textStyle.fontSize.value > 18f) {
                                BasicText(
                                    modifier =
                                        Modifier.padding(
                                            start = if (articleListFeedIcon.value) 34.dp else 8.dp,
                                            end = 24.dp,
                                        ),
                                    text = titleText,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = textStyle,
                                    color = { color },
                                    autoSize =
                                        TextAutoSize.StepBased(
                                            minFontSize = 22.sp,
                                            maxFontSize = textStyle.fontSize,
                                        ),
                                )
                            } else {
                                Text(
                                    text = titleText,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        },
                        expandedHeight = 140.dp,
                        scrollBehavior = scrollBehavior,
                        navigationIcon = {
                            FeedbackIconButton(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                                tint = MaterialTheme.colorScheme.onSurface,
                            ) {
                                onSearch = false
                                onNavigateUp()
                            }
                        },
                        actions = {
                            // S15 — mark-all-read is meaningless in the starred
                            // and recentlyRead views.
                            RYExtensibleVisibility(
                                visible =
                                    filterUiState.filter.isUnread() || filterUiState.filter.isAll()
                            ) {
                                FeedbackIconButton(
                                    imageVector = Icons.Rounded.DoneAll,
                                    contentDescription = stringResource(R.string.mark_all_as_read),
                                    tint =
                                        if (markAsRead) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                ) {
                                    if (markAsRead) {
                                        markAsRead = false
                                    } else {
                                        scope
                                            .launch {
                                                if (listState.firstVisibleItemIndex != 0) {
                                                    listState.animateScrollToItem(0)
                                                }
                                            }
                                            .invokeOnCompletion {
                                                markAsRead = true
                                                onSearch = false
                                            }
                                    }
                                }
                            }
                            FeedbackIconButton(
                                imageVector = Icons.Rounded.Search,
                                contentDescription = stringResource(R.string.search),
                                tint =
                                    if (onSearch) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                            ) {
                                if (onSearch) {
                                    onSearch = false
                                } else {
                                    scope
                                        .launch {
                                            if (listState.firstVisibleItemIndex != 0) {
                                                listState.animateScrollToItem(0)
                                            }
                                        }
                                        .invokeOnCompletion {
                                            scope.launch {
                                                onSearch = true
                                                markAsRead = false
                                                delay(100)
                                                focusRequester.requestFocus()
                                            }
                                        }
                                }
                            }
                        },
                        colors =
                            TopAppBarDefaults.topAppBarColors(
                                scrolledContainerColor = scrolledTopBarContainerColor
                            ),
                    )
                }
            },
            content = {
                RYExtensibleVisibility(modifier = Modifier.zIndex(1f), visible = onSearch) {
                    BackHandler(onSearch) { onSearch = false }
                    SearchBar(
                        value = filterUiState.searchContent ?: "",
                        placeholder =
                            when {
                                filterUiState.group != null ->
                                    stringResource(
                                        R.string.search_for_in,
                                        filterUiState.filter.toName(),
                                        filterUiState.group.name,
                                    )

                                filterUiState.feed != null ->
                                    stringResource(
                                        R.string.search_for_in,
                                        filterUiState.filter.toName(),
                                        filterUiState.feed.name,
                                    )

                                else ->
                                    stringResource(
                                        R.string.search_for,
                                        filterUiState.filter.toName(),
                                    )
                            },
                        focusRequester = focusRequester,
                        onValueChange = { viewModel.inputSearchContent(it) },
                        onClose = {
                            onSearch = false
                            viewModel.inputSearchContent(null)
                        },
                        // S15 — persist the current query verbatim as a saved
                        // search; S10 — the grammar help states the grammar
                        // honestly (no regex/boolean claims).
                        onSaveSearch = { name, query -> viewModel.saveSearch(name, query) },
                        grammarHelp = true,
                    )
                    // S16 — honest FTS coverage: "index covers N of M
                    // articles" (account-scoped per R4) plus a stale-sync
                    // notice when the last sync is over 24 h old.
                    val searchCoverage = viewModel.searchCoverage.collectAsStateValue()
                    searchCoverage?.let { coverage ->
                        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                            Text(
                                text =
                                    stringResource(
                                        R.string.search_fts_coverage,
                                        coverage.covered,
                                        coverage.total,
                                    ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                            if (coverage.stale) {
                                Text(
                                    text = stringResource(R.string.search_fts_stale),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                        }
                    }
                }

                RYExtensibleVisibility(markAsRead) {
                    BackHandler(markAsRead) { markAsRead = false }

                    MarkAsReadBar { conditions ->
                        if (markAllReadConfirmation && conditions == MarkAsReadConditions.All) {
                            // S25a — confirmation default ON (web
                            // mark_as_read_confirmation default 1): report the
                            // exact scoped unread count before applying.
                            scope.launch {
                                confirmMarkAllCount = viewModel.countUnreadInScope()
                                confirmMarkAll = conditions
                                markAsRead = false
                            }
                        } else {
                            markAsRead = false
                            viewModel.updateReadStatus(
                                groupId = filterUiState.group?.id,
                                feedId = filterUiState.feed?.id,
                                articleId = null,
                                conditions = conditions,
                                isUnread = false,
                            )
                        }
                    }
                }
                confirmMarkAll?.let { conditions ->
                    AlertDialog(
                        onDismissRequest = { confirmMarkAll = null },
                        title = { Text(stringResource(R.string.mark_all_read_confirm_title)) },
                        text = {
                            Text(
                                pluralStringResource(
                                    R.plurals.mark_all_read_confirm_message,
                                    confirmMarkAllCount,
                                    confirmMarkAllCount,
                                )
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    confirmMarkAll = null
                                    viewModel.updateReadStatus(
                                        groupId = filterUiState.group?.id,
                                        feedId = filterUiState.feed?.id,
                                        articleId = null,
                                        conditions = conditions,
                                        isUnread = false,
                                    )
                                }
                            ) {
                                Text(stringResource(R.string.confirm))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmMarkAll = null }) {
                                Text(stringResource(R.string.cancel))
                            }
                        },
                    )
                }
                // S15 — saved search management: rename (keeps the verbatim
                // query) or delete.
                managedSearch?.let { saved ->
                    AlertDialog(
                        onDismissRequest = { managedSearch = null },
                        title = { Text(stringResource(R.string.saved_search_rename)) },
                        text = {
                            TextField(
                                value = manageName,
                                onValueChange = { manageName = it },
                                placeholder = { Text(stringResource(R.string.saved_search_name_hint)) },
                                singleLine = true,
                            )
                        },
                        confirmButton = {
                            TextButton(
                                enabled = manageName.isNotBlank() && manageName != saved.name,
                                onClick = {
                                    viewModel.renameSearch(saved.id, manageName)
                                    managedSearch = null
                                },
                            ) { Text(stringResource(R.string.save)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmDeleteSearch = saved }) {
                                Text(stringResource(R.string.delete))
                            }
                        },
                    )
                }
                confirmDeleteSearch?.let { saved ->
                    AlertDialog(
                        onDismissRequest = { confirmDeleteSearch = null },
                        text = {
                            Text(stringResource(R.string.saved_search_delete_confirm, saved.name))
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    viewModel.deleteSearch(saved.id)
                                    confirmDeleteSearch = null
                                    managedSearch = null
                                },
                            ) { Text(stringResource(R.string.confirm)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmDeleteSearch = null }) {
                                Text(stringResource(R.string.cancel))
                            }
                        },
                    )
                }
                val contentTransitionVertical =
                    sharedYAxisTransitionExpressive(direction = Direction.Forward)
                val contentTransitionBackward =
                    sharedXAxisTransitionSlow(direction = Direction.Backward)
                val contentTransitionForward =
                    sharedXAxisTransitionSlow(direction = Direction.Forward)
                AnimatedContent(
                    targetState = flowUiState,
                    contentKey = { it.pagerData.filterState.copy(searchContent = null) },
                    transitionSpec = {
                        val targetFilter = targetState.pagerData.filterState
                        val initialFilter = initialState.pagerData.filterState

                        if (targetFilter.filter.index > initialFilter.filter.index) {
                            contentTransitionForward
                        } else if (targetFilter.filter.index < initialFilter.filter.index) {
                            contentTransitionBackward
                        } else if (
                            targetFilter.group != initialFilter.group ||
                                targetFilter.feed != initialFilter.feed
                        ) {
                            contentTransitionVertical
                        } else {
                            EnterTransition.None togetherWith ExitTransition.None
                        }
                    },
                ) { flowUiState ->
                    val pager = flowUiState.pagerData.pager
                    val filterState = flowUiState.pagerData.filterState
                    val pagingItems = pager.collectAsLazyPagingItems().also { pagingItems = it }

                    if (markAsReadOnScroll && filterState.filter.isUnread()) {
                        LaunchedEffect(listState.isScrollInProgress) {
                            if (!listState.isScrollInProgress) {
                                val firstItemKey =
                                    listState.layoutInfo.visibleItemsInfo
                                        .firstOrNull { it.contentType == CONTENT_TYPE_ARTICLE }
                                        ?.key
                                val items = mutableListOf<ArticleWithFeed>()
                                var found = false
                                val itemCount = pagingItems.itemCount
                                for (index in 0 until itemCount) {
                                    pagingItems.peek(index).let {
                                        if (it is ArticleFlowItem.Article) {
                                            if (it.articleWithFeed.article.id == firstItemKey) {
                                                found = true
                                                break
                                            }
                                            items.add(it.articleWithFeed)
                                        }
                                    }
                                }
                                if (items.isNotEmpty() && found) {
                                    viewModel.diffMapHolder.updateDiff(
                                        articleWithFeed = items.toTypedArray(),
                                        isUnread = false,
                                    )
                                }
                            }
                        }
                    }

                    if (settings.flowArticleListDateStickyHeader.value) {
                        LaunchedEffect(lastVisibleIndex) {
                            lastVisibleIndex.collect {
                                if (it in (pagingItems.itemCount - 25..pagingItems.itemCount - 1)) {
                                    pagingItems.get(it)
                                }
                            }
                        }
                    }

                    val listState = remember(pager) { listState }

                    // S24a — restore the durable list position for this scope and
                    // generation: exact anchor id, else clamped index, else top
                    // (corrupt/missing rows never crash). Runs per pager, so a
                    // refresh/sync generation roll re-applies the stored anchor.
                    // queue-scroll — cold list states only: on Back from the
                    // reader pane the LazyListState Saver already carries the
                    // live position and the saveable flag below is set, so a
                    // debounced DB row (which may predate the tap that opened
                    // the reader) can never overwrite it.
                    var positionRestored by rememberSaveable(pager) { mutableStateOf(false) }
                    LaunchedEffect(pager) {
                        if (positionRestored) return@LaunchedEffect
                        if (
                            listState.firstVisibleItemIndex != 0 ||
                                listState.firstVisibleItemScrollOffset != 0 ||
                                returnArticleId != null
                        ) {
                            // Warm Saver state, or an active return-to-article
                            // override that owns the position.
                            positionRestored = true
                            return@LaunchedEffect
                        }
                        snapshotFlow { pagingItems.loadState.isIdle }
                            .first { it }
                        val ids =
                            viewModel.listGenerationFlow.value?.ids ?: emptyList()
                        viewModel.restorePosition(positionScope, ids)?.let {
                            listState.scrollToItem(it.itemIndex, it.itemOffsetPx)
                        }
                        positionRestored = true
                    }

                    // S24a — persist the position on scroll-idle (debounced 300 ms).
                    LaunchedEffect(pager, positionScope.key) {
                        snapshotFlow { listState.isScrollInProgress }
                            .filter { !it }
                            .drop(1)
                            .debounce(300)
                            .collect {
                                val index = listState.firstVisibleItemIndex
                                val offset = listState.firstVisibleItemScrollOffset
                                val anchorId =
                                    anchorArticleIdAt(pagingItems.itemSnapshotList, index)
                                viewModel.persistPosition(positionScope, anchorId, index, offset)
                            }
                    }

                    val loadAction =
                        remember(pager, flowUiState, pullToSwitchFeed) {
                                when (pullToSwitchFeed) {
                                    PullToLoadNextFeedPreference.None -> null
                                    else -> {
                                        when {
                                            flowUiState.nextFilterState != null ->
                                                LoadAction.NextFeed.fromFilterState(
                                                    flowUiState.nextFilterState
                                                )

                                            filterState.filter.isUnread() &&
                                                pullToSwitchFeed ==
                                                    PullToLoadNextFeedPreference
                                                        .MarkAsReadAndLoadNextFeed ->
                                                LoadAction.MarkAllAsRead

                                            else -> null
                                        }
                                    }
                                }
                            }
                            .also { currentLoadAction = it }

                    val onLoadNext: (() -> Unit)? =
                        when (loadAction) {
                            is LoadAction.NextFeed -> viewModel::loadNextFeedOrGroup
                            LoadAction.MarkAllAsRead -> {
                                {
                                    viewModel.markAllAsRead()
                                    currentPullToLoadState?.animateDistanceTo(
                                        targetValue = 0f,
                                        animationSpec = settleSpec,
                                    )
                                }
                            }

                            else -> null
                        }

                    val onPullToSync: (() -> Unit)? =
                        if (isSyncing) null
                        else {
                            {
                                viewModel.sync()
                                currentPullToLoadState?.animateDistanceTo(
                                    targetValue = 0f,
                                    animationSpec = settleSpec,
                                )
                            }
                        }

                    val pullToLoadState =
                        rememberPullToLoadState(
                                key = pager,
                                onLoadNext = onLoadNext,
                                onLoadPrevious = onPullToSync,
                                loadThreshold = PullToLoadDefaults.loadThreshold(.1f),
                            )
                            .also { currentPullToLoadState = it }

                    Box(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            modifier =
                                Modifier.pullToLoad(
                                        state = pullToLoadState,
                                        enabled = true,
                                        contentOffsetY = { fraction ->
                                            if (fraction > 0f) {
                                                (fraction * ContentOffsetMultiple * 1.5f)
                                                    .dp
                                                    .roundToPx()
                                            } else {
                                                (fraction * ContentOffsetMultiple * 2f)
                                                    .dp
                                                    .roundToPx()
                                            }
                                        },
                                        onScroll = {
                                            if (it < -10f) {
                                                markAsRead = false
                                            }
                                        },
                                    )
                                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                                    .fillMaxSize()
                                    .drawVerticalScrollIndicator(listState),
                            state = listState,
                        ) {
                            ArticleList(
                                pagingItems = pagingItems,
                                diffMap = viewModel.diffMapHolder.diffMap,
                                isShowFeedIcon = articleListFeedIcon.value,
                                isShowStickyHeader = articleListDateStickyHeader.value,
                                articleListTonalElevation = articleListTonalElevation.value,
                                isSwipeEnabled = { listState.isScrollInProgress },
                                onClick = { articleWithFeed, index ->
                                    if (articleWithFeed.feed.isBrowser) {
                                        viewModel.diffMapHolder.updateDiff(
                                            articleWithFeed,
                                            isUnread = false,
                                        )
                                        context.openURL(
                                            articleWithFeed.article.link,
                                            openLink,
                                            openLinkSpecificBrowser,
                                        )
                                    } else {
                                        // queue-scroll — persist the live position
                                        // before navigating: pane navigation emits
                                        // no ON_PAUSE, and a tap under 300 ms after
                                        // a scroll settle would otherwise leave the
                                        // durable store holding the pre-scroll
                                        // position.
                                        val firstIndex = listState.firstVisibleItemIndex
                                        val firstOffset = listState.firstVisibleItemScrollOffset
                                        val anchorId =
                                            anchorArticleIdAt(pagingItems.itemSnapshotList, firstIndex)
                                        scope.launch {
                                            viewModel.persistPosition(
                                                positionScope,
                                                anchorId,
                                                firstIndex,
                                                firstOffset,
                                            )
                                        }
                                        navigateToArticle(articleWithFeed.article.id, index)
                                    }
                                },
                                onToggleStarred = onToggleStarred,
                                onToggleRead = onToggleRead,
                                onMarkAboveAsRead = onMarkAboveAsRead,
                                onMarkBelowAsRead = onMarkBelowAsRead,
                                onShare = onShare,
                            )
                            item {
                                Spacer(modifier = Modifier.height(128.dp))
                                Spacer(
                                    modifier =
                                        Modifier.windowInsetsBottomHeight(
                                            WindowInsets.navigationBars
                                        )
                                )
                            }
                        }
                    }
                }
            },
            floatingActionButtonPosition = FabPosition.Center,
            bottomBar = {
                // S15 — saved searches execute as view=all + verbatim query;
                // long-press manages (rename/delete).
                val savedSearches by viewModel.savedSearches.collectAsState()
                Column {
                    if (savedSearches.isNotEmpty()) {
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            tonalElevation = 0.dp,
                        ) {
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState())
                                        .padding(horizontal = 16.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                savedSearches.forEach { saved ->
                                    SavedSearchChip(
                                        name = saved.name,
                                        selected = filterUiState.searchContent == saved.query,
                                        onClick = {
                                            if (filterUiState.searchContent == saved.query) {
                                                viewModel.inputSearchContent(null)
                                            } else {
                                                viewModel.applySavedSearch(saved.query)
                                            }
                                        },
                                        onLongClick = {
                                            manageName = saved.name
                                            managedSearch = saved
                                        },
                                    )
                                }
                            }
                        }
                    }
                    FilterBar(
                        filter = filterUiState.filter,
                        filters =
                            if (hideRecentlyRead) {
                                // S15 — HideRecentlyReadPreference (default OFF) hides
                                // the recentlyRead view from the bar; the view itself
                                // still exists.
                                Filter.values.filter { it != Filter.Read }
                            } else {
                                Filter.values
                            },
                        filterBarStyle = filterBarStyle.value,
                        filterBarFilled = true,
                        filterBarPadding = filterBarPadding.dp,
                        filterBarTonalElevation = filterBarTonalElevation.value.dp,
                    ) {
                        if (filterUiState.filter != it) {
                            viewModel.changeFilter(filterUiState.copy(filter = it))
                        } else {
                            scope.launch {
                                if (listState.firstVisibleItemIndex != 0) {
                                    listState.animateScrollToItem(0)
                                }
                            }
                        }
                    }
                }
            },
        )
        currentPullToLoadState?.let {
            PullToSyncIndicator(pullToLoadState = it, isSyncing = isSyncing)
            PullToLoadIndicator(
                state = it,
                loadAction = currentLoadAction,
                modifier =
                    Modifier.padding(bottom = 36.dp)
                        .windowInsetsPadding(
                            WindowInsets.safeContent.only(WindowInsetsSides.Horizontal)
                        ),
            )
        }
    }
}

/**
 * S24a anchor lookup, bounds-checked.
 *
 * `listState.firstVisibleItemIndex` can be stale while the paging snapshot is empty or
 * reloaded (e.g. switching Read -> Feeds fires ON_PAUSE before the new page arrives).
 * `LazyPagingItems.peek(index)` threw IndexOutOfBoundsException there (crash report
 * 2026-11-18, Android 17, SM-S948U1). Out-of-range or non-article positions yield a null
 * anchor; persistPosition still records index/offset.
 */
internal fun anchorArticleIdAt(items: ItemSnapshotList<ArticleFlowItem>?, index: Int): String? {
    if (items == null || index < 0 || index >= items.size) return null
    return (items[index] as? ArticleFlowItem.Article)?.articleWithFeed?.article?.id
}

/**
 * One lazy-list item's viewport snapshot in plain pixels (queue-scroll lane).
 * Keeps [computeReturnScrollTarget] JVM-testable without Compose layout types.
 */
internal data class ReturnScrollItemInfo(
    val index: Int,
    val offset: Int,
    val size: Int,
    val isDateHeader: Boolean = false,
)

/** Outcome of [computeReturnScrollTarget]. */
internal sealed interface ReturnScrollDecision {
    /** The target article is already cleanly visible: leave the list untouched. */
    data object NoOp : ReturnScrollDecision

    /**
     * The target is clipped or off-screen: bring [targetIndex] to the top of
     * the viewport. [scrollOffset] is always 0 — the historical negative
     * offsets (-400 / -200) pushed the tapped article toward and past the
     * bottom edge when returning from the reader.
     */
    data class ScrollTo(val targetIndex: Int, val scrollOffset: Int) : ReturnScrollDecision
}

/**
 * Decides where (or whether) the Read list scrolls when returning from the
 * reader with the article at [targetIndex] active (queue-scroll lane).
 *
 * The target is cleanly visible when it lies fully between the top clearance
 * and [viewportEndOffset]. The top clearance is [viewportStartOffset], raised
 * to the bottom edge of a sticky date header pinned over the viewport top
 * when sticky headers are enabled — an article partially hidden under the
 * pinned header is not cleanly visible.
 *
 * Cleanly visible returns [ReturnScrollDecision.NoOp]: the article stays
 * exactly where the user tapped it. Otherwise the decision scrolls to the top
 * of the viewport with offset 0; when sticky headers are enabled and the item
 * right before the target is its date header, the decision targets the date
 * item instead, so the pinned header never covers the article's top lines.
 */
internal fun computeReturnScrollTarget(
    targetIndex: Int,
    visibleItems: List<ReturnScrollItemInfo>,
    viewportStartOffset: Int,
    viewportEndOffset: Int,
    stickyHeaderEnabled: Boolean,
    previousItemIsDate: Boolean,
): ReturnScrollDecision {
    fun scrollToTop(): ReturnScrollDecision {
        val target =
            if (stickyHeaderEnabled && previousItemIsDate && targetIndex > 0) {
                targetIndex - 1
            } else {
                targetIndex
            }
        return ReturnScrollDecision.ScrollTo(targetIndex = target, scrollOffset = 0)
    }

    val item = visibleItems.firstOrNull { it.index == targetIndex } ?: return scrollToTop()
    val topClearance =
        if (stickyHeaderEnabled) {
            visibleItems
                .filter {
                    it.isDateHeader &&
                        it.offset <= viewportStartOffset &&
                        it.offset + it.size > viewportStartOffset
                }
                .maxOfOrNull { it.offset + it.size }
                ?: viewportStartOffset
        } else {
            viewportStartOffset
        }
    val cleanlyVisible =
        item.offset >= topClearance && item.offset + item.size <= viewportEndOffset
    return if (cleanlyVisible) ReturnScrollDecision.NoOp else scrollToTop()
}

/**
 * Waits until the list has presented items AND completed a layout pass that
 * contains them, then resolves the active [articleId] into a
 * [ReturnScrollDecision] (queue-scroll lane).
 *
 * The wait requires `layoutInfo.totalItemsCount > 0` in addition to a non-empty
 * [LazyPagingItems] count: right after the pane re-enters composition the
 * paging cache can present items before the first layout pass reflects them,
 * and deciding against that stale viewport snapshot would scroll a target that
 * is actually visible.
 */
internal suspend fun LazyListState.awaitReturnScrollDecision(
    articleId: String,
    stickyHeaderEnabled: Boolean,
    itemCount: () -> Int,
    items: () -> ItemSnapshotList<ArticleFlowItem>?,
): ReturnScrollDecision {
    snapshotFlow {
            itemCount() > 0 &&
                layoutInfo.totalItemsCount > 0 &&
                layoutInfo.visibleItemsInfo.isNotEmpty()
        }
        .first { it }
    val itemList = items() ?: return ReturnScrollDecision.NoOp
    val index =
        itemList.indexOfFirst {
            it is ArticleFlowItem.Article && it.articleWithFeed.article.id == articleId
        }
    if (index < 0) return ReturnScrollDecision.NoOp
    val info = layoutInfo
    return computeReturnScrollTarget(
        targetIndex = index,
        visibleItems =
            info.visibleItemsInfo.map { item ->
                ReturnScrollItemInfo(
                    index = item.index,
                    offset = item.offset,
                    size = item.size,
                    isDateHeader = item.contentType == CONTENT_TYPE_DATE_HEADER,
                )
            },
        viewportStartOffset = info.viewportStartOffset,
        viewportEndOffset = info.viewportEndOffset,
        stickyHeaderEnabled = stickyHeaderEnabled,
        previousItemIsDate = itemList.getOrNull(index - 1) is ArticleFlowItem.Date,
    )
}
