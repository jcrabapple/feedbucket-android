package me.ash.reader.ui.page.home.reading

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import me.ash.reader.ui.ext.MotionExt
import kotlin.math.abs
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.ash.reader.R
import me.ash.reader.infrastructure.android.TextToSpeechManager
import me.ash.reader.infrastructure.preference.LocalPullToSwitchArticle
import me.ash.reader.infrastructure.preference.LocalReadingAutoHideToolbar
import me.ash.reader.infrastructure.preference.LocalReadingBoldCharacters
import me.ash.reader.infrastructure.preference.LocalReadingRenderer
import me.ash.reader.infrastructure.preference.LocalReadingTextLineHeight
import me.ash.reader.infrastructure.preference.ReadingRendererPreference
import me.ash.reader.infrastructure.preference.not
import me.ash.reader.ui.ext.collectAsStateValue
import me.ash.reader.ui.ext.showToast
import me.ash.reader.ui.page.adaptive.ArticleListReaderViewModel
import me.ash.reader.ui.page.adaptive.NavigationAction
import me.ash.reader.ui.page.adaptive.ReaderState
import me.ash.reader.ui.page.home.reading.tts.TtsButton
import timber.log.Timber

private const val UPWARD = 1
private const val DOWNWARD = -1

@OptIn(
    ExperimentalFoundationApi::class,
    ExperimentalMaterialApi::class,
    FlowPreview::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)
@Composable
fun ReadingPage(
    //    navController: NavHostController,
    viewModel: ArticleListReaderViewModel,
    navigationAction: NavigationAction,
    onLoadArticle: (String, Int) -> Unit,
    onNavAction: (NavigationAction) -> Unit,
    onNavigateToStylePage: () -> Unit,
    onNavigateToAiSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    val hapticFeedback = LocalHapticFeedback.current
    val isPullToSwitchArticleEnabled = LocalPullToSwitchArticle.current.value
    val readingUiState = viewModel.readingUiState.collectAsStateValue()
    val readerState = viewModel.readerStateStateFlow.collectAsStateValue()
    // S27a/S30a — download row for the open article (drives the TopBar
    // action and the hardened offline render routing).
    val currentDownload = viewModel.currentDownload.collectAsStateValue()
    val boldCharacters = LocalReadingBoldCharacters.current
    val coroutineScope = rememberCoroutineScope()

    var isReaderScrollingDown by remember { mutableStateOf(false) }
    var showFullScreenImageViewer by remember { mutableStateOf(false) }
    // AI Summaries — reader overlay visibility (brain button in BottomBar).
    var showAiSummaryOverlay by rememberSaveable { mutableStateOf(false) }
    val imageSavedToast = stringResource(R.string.image_saved)
    val imageSaveFailedToast = stringResource(R.string.image_save_failed)

    var currentImageData by remember { mutableStateOf(ImageData()) }

    val isShowToolBar =
        if (LocalReadingAutoHideToolbar.current.value) {
            readerState.articleId != null && !isReaderScrollingDown
        } else {
            true
        }

    // E11/E12 — the row carries a stored extraction (`fullContent != null`,
    // kept fresh in place by the reader state machine on success — E4/E22,
    // no Paging invalidation). Drives the BottomBar button semantics
    // (extract-when-none / toggle-when-extracted) and the TopBar
    // re-extract action visibility (extracted-only).
    val hasStoredExtraction = readingUiState.articleWithFeed?.article?.fullContent != null

    var showTopDivider by remember { mutableStateOf(false) }

    //    LaunchedEffect(readerState.listIndex) {
    //        readerState.listIndex?.let {
    //            navController.previousBackStackEntry?.savedStateHandle?.set("articleIndex", it)
    //        }
    //    }

    var bringToTop by remember { mutableStateOf(false) }

    // S27a/S30a — a committed download routes this article through the
    // hardened offline renderer. Startup reconciliation demotes rows whose
    // committed dir fails manifest validation, so `ready` is authoritative.
    val offlineTarget =
        remember(
            readerState.articleId,
            currentDownload?.state,
            // Lane 4 defect 3 — this block reads the article row, so its id
            // must be a remember key; without it a lagging `readingUiState`
            // could bind the PREVIOUS article's OfflineArticleTarget (and
            // route the offline renderer at the wrong directory).
            readingUiState.articleWithFeed?.article?.id,
        ) {
            val article = readingUiState.articleWithFeed?.article
            if (article != null && currentDownload?.state == me.ash.reader.domain.model.download.ArticleDownload.STATE_READY) {
                me.ash.reader.ui.component.webview.OfflineArticleTarget(article.accountId, article.id)
            } else {
                null
            }
        }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        content = { paddings ->
            Box(modifier = Modifier.fillMaxSize()) {
                if (readerState.articleId != null) {
                    TopBar(
                        isShow = isShowToolBar,
                        isScrolled = showTopDivider,
                        title = readerState.title,
                        link = readerState.link,
                        onClick = { bringToTop = true },
                        navigationAction = navigationAction,
                        onNavButtonClick = onNavAction,
                        onNavigateToStylePage = onNavigateToStylePage,
                        // S27a — per-article download action (manual = no
                        // network constraint; fails fast to failed(network)).
                        downloadState = currentDownload?.state,
                        onDownloadClick = { viewModel.downloadCurrentArticle() },
                        // E12 — re-extract action: visible ONLY while the row
                        // carries a stored extraction; in-flight/busy state
                        // mirrors the S27a download grammar.
                        isExtracted = hasStoredExtraction,
                        isReExtracting = readerState.isExtracting,
                        reExtractFailed = readerState.extractFailed,
                        onReExtractClick = { viewModel.reExtractFullContent() },
                    )
                }

                val isNextArticleAvailable = readerState.nextArticle != null
                val isPreviousArticleAvailable = readerState.previousArticle != null

                if (readerState.articleId != null) {
                    // Wave H (D17/§7): article deltas use the product-wide
                    // standard motion scheme; reduced motion cross-fades.
                    val reducedMotion = MotionExt.rememberReducedMotion()
                    val spatialSpecEnter =
                        MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
                    val spatialSpecExit =
                        MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
                    // Content
                    // Lane 4 defect 2 — the animation keys on the body
                    // identity only (ReaderBodyState): transient flags
                    // (isExtracting / extractFailed), prefetch neighbours
                    // and listIndex no longer trigger a slide/fade of the
                    // whole article nor recreate rememberScrollState().
                    // Direction detection still needs the PREVIOUS full
                    // state's neighbours: a plain (non-snapshot) ref,
                    // updated in a SideEffect, holds the last APPLIED
                    // ReaderState while transitionSpec evaluates the new
                    // target during composition.
                    val lastAppliedState = remember { arrayOf(readerState) }
                    SideEffect { lastAppliedState[0] = readerState }
                    AnimatedContent(
                        targetState = readerState.toBodyState(),
                        transitionSpec = {
                            if (reducedMotion) {
                                MotionExt.fadeEnter() togetherWith
                                    MotionExt.fadeExit()
                            } else {
                                val previous = lastAppliedState[0]
                                val direction =
                                    when {
                                        previous.nextArticle?.articleId == targetState.articleId ->
                                            UPWARD
                                        previous.previousArticle?.articleId ==
                                            targetState.articleId -> DOWNWARD
                                        initialState.articleId == targetState.articleId -> {
                                            when (targetState.content) {
                                                is ReaderState.Description -> DOWNWARD
                                                else -> UPWARD
                                            }
                                        }

                                        else -> UPWARD
                                    }
                                val exit = 100
                                val enter = exit * 2
                                (slideInVertically(
                                    initialOffsetY = { (it * 0.2f * direction).toInt() },
                                    animationSpec = spatialSpecEnter,
                                ) +
                                    fadeIn(
                                        tween(
                                            delayMillis = exit,
                                            durationMillis = enter,
                                            easing = LinearOutSlowInEasing,
                                        )
                                    )) togetherWith
                                    (slideOutVertically(
                                        targetOffsetY = { (it * -0.2f * direction).toInt() },
                                        animationSpec = spatialSpecExit,
                                    ) +
                                        fadeOut(
                                            tween(durationMillis = exit, easing = FastOutLinearInEasing)
                                        ))
                            }
                        },
                        label = "",
                    ) {
                        remember { it }
                            .run {
                                val state =
                                    rememberPullToLoadState(
                                        key = content,
                                        onLoadNext =
                                            if (isNextArticleAvailable) {
                                                {
                                                    val (id, index) = readerState.nextArticle
                                                    onLoadArticle(id, index)
                                                }
                                            } else null,
                                        onLoadPrevious =
                                            if (isPreviousArticleAvailable) {
                                                {
                                                    val (id, index) = readerState.previousArticle
                                                    onLoadArticle(id, index)
                                                }
                                            } else null,
                                    )

                                val listState =
                                    rememberSaveable(
                                        inputs = arrayOf(content),
                                        saver = LazyListState.Saver,
                                    ) {
                                        LazyListState()
                                    }

                                val scrollState = rememberScrollState()

                                val scope = rememberCoroutineScope()

                                val renderer = LocalReadingRenderer.current
                                val lifecycleOwner = LocalLifecycleOwner.current

                                // S26 — restore the durable reading position once
                                // per article content; missing/corrupt rows fall
                                // back to top-of-article, never a crash.
                                // Lane 4 defect 2 — keyed on the FROZEN body
                                // snapshot's id, so an exiting instance never
                                // re-runs the restore for the incoming article.
                                LaunchedEffect(articleId, content) {
                                    val aid = articleId ?: return@LaunchedEffect
                                    val position =
                                        viewModel.restoreReadingPosition(aid)
                                            ?: return@LaunchedEffect
                                    when (renderer) {
                                        ReadingRendererPreference.WebView -> {
                                            // Wait until the content has height so
                                            // the ratio resolves to a real offset.
                                            snapshotFlow { scrollState.maxValue }
                                                .first { it > 0 }
                                            scrollState.scrollTo(
                                                (
                                                    position.scrollRatioPx.toLong() *
                                                        scrollState.maxValue / 10000
                                                    )
                                                    .toInt(),
                                            )
                                        }

                                        ReadingRendererPreference.NativeComponent -> {
                                            val count = listState.layoutInfo.totalItemsCount
                                            if (count > 0) {
                                                listState.scrollToItem(
                                                    minOf(position.scrollRatioPx, count - 1),
                                                    position.contentHeightPx,
                                                )
                                            }
                                        }
                                    }
                                }

                                // S26 — persist on scroll-idle (debounced 300 ms).
                                // Lane 4 defect 2 — frozen snapshot id: an
                                // exiting instance can never persist its outgoing
                                // scroll position under the NEW article's id.
                                LaunchedEffect(articleId, renderer) {
                                    val aid = articleId ?: return@LaunchedEffect
                                    if (renderer == ReadingRendererPreference.WebView) {
                                        snapshotFlow { scrollState.value }
                                            .debounce(300)
                                            .collect { value ->
                                                val max = scrollState.maxValue
                                                if (max > 0) {
                                                    viewModel.persistReadingPosition(
                                                        aid,
                                                        value * 10000 / max,
                                                        max,
                                                    )
                                                }
                                            }
                                    } else {
                                        snapshotFlow {
                                                listState.firstVisibleItemIndex to
                                                    listState.firstVisibleItemScrollOffset
                                            }
                                            .debounce(300)
                                            .collect { (index, offset) ->
                                                if (index > 0 || offset > 0) {
                                                    viewModel.persistReadingPosition(
                                                        aid,
                                                        index,
                                                        offset,
                                                    )
                                                }
                                            }
                                    }
                                }

                                // S26 — persist on ON_PAUSE / navigation-away.
                                // Lane 4 defect 2 — frozen snapshot id (see above).
                                DisposableEffect(lifecycleOwner, articleId, renderer) {
                                    val observer =
                                        LifecycleEventObserver { _, event ->
                                            if (event == Lifecycle.Event.ON_PAUSE) {
                                                val aid = articleId
                                                if (aid != null) {
                                                    scope.launch {
                                                        if (renderer ==
                                                            ReadingRendererPreference.WebView
                                                        ) {
                                                            val max = scrollState.maxValue
                                                            if (max > 0) {
                                                                viewModel.persistReadingPosition(
                                                                    aid,
                                                                    scrollState.value * 10000 / max,
                                                                    max,
                                                                )
                                                            }
                                                        } else if (
                                                            listState.firstVisibleItemIndex > 0 ||
                                                                listState.firstVisibleItemScrollOffset > 0
                                                        ) {
                                                            viewModel.persistReadingPosition(
                                                                aid,
                                                                listState.firstVisibleItemIndex,
                                                                listState.firstVisibleItemScrollOffset,
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    lifecycleOwner.lifecycle.addObserver(observer)
                                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                                }

                                LaunchedEffect(bringToTop) {
                                    if (bringToTop) {
                                        scope
                                            .launch {
                                                if (scrollState.value != 0) {
                                                    scrollState.animateScrollTo(0)
                                                } else if (listState.firstVisibleItemIndex != 0) {
                                                    listState.animateScrollToItem(0)
                                                }
                                            }
                                            .invokeOnCompletion { bringToTop = false }
                                    }
                                }

                                showTopDivider =
                                    snapshotFlow {
                                            scrollState.value >= 120 ||
                                                listState.firstVisibleItemIndex != 0
                                        }
                                        .collectAsStateValue(initial = false)

                                CompositionLocalProvider(
                                    LocalTextStyle provides
                                        LocalTextStyle.current.run {
                                            merge(
                                                lineHeight =
                                                    if (lineHeight.isSpecified)
                                                        (lineHeight.value *
                                                                LocalReadingTextLineHeight.current)
                                                            .sp
                                                    else TextUnit.Unspecified
                                            )
                                        }
                                ) {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Content(
                                            modifier =
                                                Modifier.pullToLoad(
                                                    state = state,
                                                    onScroll = { f ->
                                                        if (abs(f) > 2f)
                                                            isReaderScrollingDown = f < 0f
                                                    },
                                                    enabled = isPullToSwitchArticleEnabled,
                                                ),
                                            contentPadding = paddings,
                                            content = content.text ?: "",
                                            feedName = feedName,
                                            title = title.toString(),
                                            author = author,
                                            link = link,
                                            publishedDate = publishedDate,
                                            isLoading = content is ReaderState.Loading,
                                            scrollState = scrollState,
                                            listState = listState,
                                            // S22 — the diff compares sanitized originalContent
                                            // against the CURRENT rawDescription, so it is only
                                            // offered on the description render path (never
                                            // against extracted full content).
                                            originalContent =
                                                if (content is ReaderState.Description) {
                                                    readingUiState.articleWithFeed?.article?.originalContent
                                                } else {
                                                    null
                                                },
                                            contentChangedAt =
                                                readingUiState.articleWithFeed?.article?.contentChangedAt,
                                            offlineTarget = offlineTarget,
                                            onImageClick = { imgUrl, altText ->
                                                currentImageData = ImageData(imgUrl, altText)
                                                showFullScreenImageViewer = true
                                            },
                                        )
                                        PullToLoadIndicator(
                                            state = state,
                                            canLoadPrevious = isPreviousArticleAvailable,
                                            canLoadNext = isNextArticleAvailable,
                                        )
                                    }
                                }
                            }
                    }
                }
                // Bottom Bar
                if (readerState.articleId != null) {
                    BottomBar(
                        isShow = isShowToolBar,
                        isUnread = readingUiState.isUnread,
                        isStarred = readingUiState.isStarred,
                        isNextArticleAvailable = isNextArticleAvailable,
                        isFullContent = readerState.content is ReaderState.FullContent,
                        // E11 — busy disables the button; E13 — transient
                        // (≤3 s) failure tint, shared with the TopBar icon.
                        isExtracting = readerState.isExtracting,
                        extractFailed = readerState.extractFailed,
                        isBoldCharacters = boldCharacters.value,
                        onUnread = { viewModel.updateReadStatus(it) },
                        onStarred = { viewModel.updateStarredStatus(it) },
                        onNextArticle = {
                            readerState.nextArticle?.let {
                                val (id, index) = it
                                onLoadArticle(id, index)
                            }
                        },
                        onFullContent = {
                            if (it) {
                                // E11 semantics: extract-when-none (MANUAL —
                                // explicit user intent, runs regardless of the
                                // E6 trigger and the E20 toggle), toggle-when-
                                // extracted (pure view switch to the stored
                                // row — zero network).
                                if (hasStoredExtraction) viewModel.renderFullContent()
                                else viewModel.extractFullContent()
                            } else {
                                viewModel.renderDescriptionContent()
                            }
                        },
                        onBoldCharacters = { (!boldCharacters).put(context, coroutineScope) },
                        onAiSummary = { showAiSummaryOverlay = true },
                        onReadAloud = {
                            viewModel.textToSpeechManager.readHtml(
                                readerState.content.text ?: return@BottomBar
                            )
                        },
                        ttsButton = {
                            TtsButton(
                                onClick = {
                                    when (it) {
                                        TextToSpeechManager.State.Error -> {
                                            context.showToast("TextToSpeech initialization failed")
                                        }

                                        TextToSpeechManager.State.Idle -> {
                                            viewModel.textToSpeechManager.readHtml(
                                                readerState.content.text ?: ""
                                            )
                                        }

                                        is TextToSpeechManager.State.Reading -> {
                                            viewModel.textToSpeechManager.stop()
                                        }

                                        TextToSpeechManager.State.Preparing -> {
                                            /* no-op */
                                        }
                                    }
                                },
                                state =
                                    viewModel.textToSpeechManager.stateFlow.collectAsStateValue(),
                            )
                        },
                    )
                }
            }
        },
    )
    if (showFullScreenImageViewer) {

        ReaderImageViewer(
            imageData = currentImageData,
            onDownloadImage = {
                viewModel.downloadImage(
                    it,
                    onSuccess = { context.showToast(imageSavedToast) },
                    // Lane 4 defect 4 — a failed image save is user-visible
                    // feedback, never a crash (was `throw th` under a FIXME).
                    onFailure = { th ->
                        Timber.w(th, "Failed to save image")
                        context.showToast(imageSaveFailedToast)
                    },
                )
            },
            onDismissRequest = { showFullScreenImageViewer = false },
        )
    }
    // AI Summaries — overlay above the reader; keyed on the current article
    // so a switch re-runs the cache-first flow under a fresh generation.
    val overlayArticleId = readerState.articleId
    if (showAiSummaryOverlay && overlayArticleId != null) {
        AiSummaryOverlay(
            articleId = overlayArticleId,
            isFullContent = readerState.content is ReaderState.FullContent,
            onNavigateToAiSettings = onNavigateToAiSettings,
            onDismiss = { showAiSummaryOverlay = false },
        )
    }
}
