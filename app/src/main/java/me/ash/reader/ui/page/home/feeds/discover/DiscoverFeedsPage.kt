package me.ash.reader.ui.page.home.feeds.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.RssFeed
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import me.ash.reader.R
import me.ash.reader.domain.model.discovery.DiscoveredFeed
import me.ash.reader.domain.model.discovery.DiscoverySearchScope
import me.ash.reader.domain.model.discovery.FeedFormat
import me.ash.reader.domain.service.StarterFeedCatalog
import me.ash.reader.ui.component.FeedIcon
import me.ash.reader.ui.component.base.DisplayText
import me.ash.reader.ui.component.base.FeedbackIconButton
import me.ash.reader.ui.component.base.RYScaffold
import me.ash.reader.ui.component.base.RYSelectionChip
import me.ash.reader.ui.component.base.Subtitle
import me.ash.reader.ui.component.base.TextFieldDialog
import me.ash.reader.ui.ext.collectAsStateValue
import me.ash.reader.ui.ext.showToast
import me.ash.reader.ui.page.home.feeds.FeedOptionView
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * FD2 — the Discover feeds destination: unified search (topic, website URL or
 * platform shortcut), curated-category browsing, feed previews and one-tap
 * subscribe. [DiscoverFeedsContent] is the stateless rendering half (driven
 * directly by the instrumented UI test).
 */
@Composable
fun DiscoverFeedsPage(
    viewModel: DiscoverFeedsViewModel = hiltViewModel(),
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val state = viewModel.uiState.collectAsStateValue()
    var inputQuery by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(state.toast) {
        state.toast?.let {
            context.showToast(it)
            viewModel.consumeToast()
        }
    }

    DiscoverFeedsContent(
        state = state,
        inputQuery = inputQuery,
        onBack = onBack,
        onQueryChange = {
            inputQuery = it
            if (it.isBlank()) viewModel.clearSearch()
        },
        onSearch = { viewModel.search(it) },
        onScopeSelect = viewModel::selectScope,
        onCategorySelect = {
            inputQuery = ""
            viewModel.selectCategory(it)
        },
        onFeedClick = viewModel::openPreview,
        onFeedSubscribeClick = { viewModel.openSubscribeSheet(it) },
        onRetry = viewModel::retrySearch,
        onPreviewClose = viewModel::closePreview,
        onPreviewRetry = { viewModel.openPreview(it) },
        onPreviewSubscribe = { loaded ->
            viewModel.openSubscribeSheet(loaded.feed, preloaded = loaded.preview)
        },
        onSubscribeSheetClose = viewModel::closeSubscribeSheet,
        onSubscribeGroupSelect = viewModel::selectSubscribeGroup,
        onSubscribeNotificationToggle = viewModel::toggleSubscribeNotification,
        onSubscribeFullContentToggle = viewModel::toggleSubscribeFullContent,
        onSubscribeBrowserToggle = viewModel::toggleSubscribeBrowser,
        onSubscribeAddGroup = viewModel::addNewGroup,
        onSubscribeConfirm = viewModel::confirmSubscribe,
    )
}

/** Test tags for the instrumented UI test. */
internal object DiscoverTestTags {
    const val SEARCH_FIELD = "discover_search_field"
    const val SCOPE_CHIP_PREFIX = "discover_scope_chip_"
    const val CATEGORY_CHIP_PREFIX = "discover_category_chip_"
    const val FEED_CARD_PREFIX = "discover_feed_card_"
    const val SUBSCRIBED_CHIP_PREFIX = "discover_subscribed_chip_"
    const val SUBSCRIBE_BUTTON_PREFIX = "discover_subscribe_button_"
    const val RETRY_BUTTON = "discover_retry"
    const val PREVIEW_LOADING = "discover_preview_loading"
    const val PREVIEW_ENTRY_PREFIX = "discover_preview_entry_"
    const val PREVIEW_SUBSCRIBE = "discover_preview_subscribe"
    const val PREVIEW_RETRY = "discover_preview_retry"
    const val SUBSCRIBE_CONFIRM = "discover_subscribe_confirm"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverFeedsContent(
    state: DiscoverUiState,
    inputQuery: String,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onScopeSelect: (DiscoverySearchScope) -> Unit,
    onCategorySelect: (String) -> Unit,
    onFeedClick: (DiscoveredFeed) -> Unit,
    onFeedSubscribeClick: (DiscoveredFeed) -> Unit,
    onRetry: () -> Unit,
    onPreviewClose: () -> Unit,
    onPreviewRetry: (DiscoveredFeed) -> Unit,
    onPreviewSubscribe: (DiscoverPreviewState.Loaded) -> Unit,
    onSubscribeSheetClose: () -> Unit,
    onSubscribeGroupSelect: (String) -> Unit,
    onSubscribeNotificationToggle: () -> Unit,
    onSubscribeFullContentToggle: () -> Unit,
    onSubscribeBrowserToggle: () -> Unit,
    onSubscribeAddGroup: (String) -> Unit,
    onSubscribeConfirm: () -> Unit,
) {
    RYScaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        navigationIcon = {
            FeedbackIconButton(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.back),
                tint = MaterialTheme.colorScheme.onSurface,
                onClick = onBack,
            )
        },
        content = {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                item {
                    DisplayText(text = stringResource(R.string.discover_feeds), desc = "")
                }
                item {
                    OutlinedTextField(
                        modifier =
                            Modifier.fillMaxWidth()
                                .padding(horizontal = 26.dp)
                                .testTag(DiscoverTestTags.SEARCH_FIELD),
                        value = inputQuery,
                        onValueChange = onQueryChange,
                        singleLine = true,
                        placeholder = {
                            Text(stringResource(R.string.discover_search_placeholder))
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions =
                            KeyboardActions(onSearch = { onSearch(inputQuery) }),
                        trailingIcon = {
                            if (inputQuery.isNotBlank()) {
                                FeedbackIconButton(
                                    imageVector = Icons.Outlined.Clear,
                                    contentDescription = stringResource(R.string.clear),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                ) {
                                    onQueryChange("")
                                }
                            }
                        },
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
                if (state.searching) {
                    item {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                item {
                    if (state.browsing) {
                        CategoryChipRow(
                            selectedCategoryId = state.selectedCategoryId,
                            onCategorySelect = onCategorySelect,
                        )
                    } else {
                        ScopeChipRow(selectedScope = state.scope, onScopeSelect = onScopeSelect)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
                state.errorMessage?.let { message ->
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 26.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                modifier = Modifier.weight(1f),
                                text = message,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (!state.browsing) {
                                TextButton(
                                    modifier = Modifier.testTag(DiscoverTestTags.RETRY_BUTTON),
                                    onClick = onRetry,
                                ) {
                                    Text(stringResource(R.string.discover_retry))
                                }
                            }
                        }
                    }
                }
                state.notice?.let { notice ->
                    item {
                        Text(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 26.dp, vertical = 8.dp),
                            text = notice,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (!state.searching && state.results.isEmpty() && state.errorMessage == null) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.discover_no_results),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                items(state.results, key = { DiscoveredFeed.normalizeUrl(it.url) }) { feed ->
                    DiscoverFeedCard(
                        feed = feed,
                        subscribed = state.isSubscribed(feed),
                        onClick = { onFeedClick(feed) },
                        onSubscribeClick = { onFeedSubscribeClick(feed) },
                    )
                }
                item {
                    Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                }
            }
        },
    )

    when (val preview = state.preview) {
        DiscoverPreviewState.Hidden -> Unit
        is DiscoverPreviewState.Loading,
        is DiscoverPreviewState.Failed,
        is DiscoverPreviewState.Loaded ->
            FeedPreviewSheet(
                previewState = preview,
                onDismiss = onPreviewClose,
                onRetry = { url ->
                    state.results.firstOrNull { it.url == url }?.let(onPreviewRetry)
                },
                onSubscribe = onPreviewSubscribe,
            )
    }

    state.subscribeSheet?.let { sheet ->
        DiscoverSubscribeSheetContent(
            sheet = sheet,
            onDismiss = onSubscribeSheetClose,
            onGroupSelect = onSubscribeGroupSelect,
            onNotificationToggle = onSubscribeNotificationToggle,
            onFullContentToggle = onSubscribeFullContentToggle,
            onBrowserToggle = onSubscribeBrowserToggle,
            onAddNewGroup = onSubscribeAddGroup,
            onConfirm = onSubscribeConfirm,
        )
    }
}

@Composable
private fun CategoryChipRow(
    selectedCategoryId: String,
    onCategorySelect: (String) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 26.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(StarterFeedCatalog.categories, key = { it.id }) { category ->
            RYSelectionChip(
                modifier = Modifier.testTag(DiscoverTestTags.CATEGORY_CHIP_PREFIX + category.id),
                content = category.label,
                selected = category.id == selectedCategoryId,
                selectedIcon = null,
            ) {
                onCategorySelect(category.id)
            }
        }
    }
}

@Composable
private fun ScopeChipRow(
    selectedScope: DiscoverySearchScope,
    onScopeSelect: (DiscoverySearchScope) -> Unit,
) {
    val scopes =
        listOf(
            DiscoverySearchScope.ALL to stringResource(R.string.all),
            DiscoverySearchScope.ARTICLES to stringResource(R.string.discover_articles),
            DiscoverySearchScope.PODCASTS to stringResource(R.string.discover_podcasts),
        )
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 26.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        scopes.forEach { (scope, label) ->
            RYSelectionChip(
                modifier = Modifier.testTag(DiscoverTestTags.SCOPE_CHIP_PREFIX + scope.name),
                content = label,
                selected = scope == selectedScope,
                selectedIcon = null,
            ) {
                onScopeSelect(scope)
            }
        }
    }
}

/**
 * FD2 — one discovery result card: icon, title, host subtitle, description,
 * format badge, subscriber count and either a Subscribed chip or a Subscribe
 * action. Tapping the card opens the preview.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DiscoverFeedCard(
    feed: DiscoveredFeed,
    subscribed: Boolean,
    onClick: () -> Unit,
    onSubscribeClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 26.dp, vertical = 6.dp)
                .testTag(DiscoverTestTags.FEED_CARD_PREFIX + DiscoveredFeed.normalizeUrl(feed.url)),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            FeedIcon(
                modifier = Modifier.size(36.dp),
                feedName = feed.title,
                iconUrl = feed.iconUrl,
                placeholderIcon = Icons.Rounded.RssFeed,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = feed.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = displayHost(feed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                feed.description?.takeIf { it.isNotBlank() }?.let { description ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                ) {
                    feedFormatLabel(feed.format)?.let { label ->
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Text(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                    feed.subscribers?.let { count ->
                        Text(
                            text =
                                stringResource(
                                    R.string.discover_subscribers_count,
                                    String.format(Locale.getDefault(), "%,d", count),
                                ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (subscribed) {
                        Surface(
                            modifier =
                                Modifier.testTag(
                                    DiscoverTestTags.SUBSCRIBED_CHIP_PREFIX +
                                        DiscoveredFeed.normalizeUrl(feed.url)
                                ),
                            shape = MaterialTheme.shapes.extraSmall,
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    modifier = Modifier.size(12.dp),
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                                Text(
                                    text = stringResource(R.string.discover_subscribed),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            if (!subscribed) {
                Button(
                    modifier =
                        Modifier.testTag(
                            DiscoverTestTags.SUBSCRIBE_BUTTON_PREFIX +
                                DiscoveredFeed.normalizeUrl(feed.url)
                        ),
                    onClick = onSubscribeClick,
                    contentPadding = PaddingValues(horizontal = 16.dp),
                ) {
                    Text(stringResource(R.string.subscribe))
                }
            }
        }
    }
}

/** FD2 — human-readable wire-format badge label (null hides the badge). */
internal fun feedFormatLabel(format: FeedFormat): String? =
    when (format) {
        FeedFormat.RSS -> "RSS"
        FeedFormat.ATOM -> "Atom"
        FeedFormat.JSON_FEED -> "JSON"
        FeedFormat.PODCAST -> "Podcast"
        FeedFormat.UNKNOWN -> null
    }

/** Clean domain subtitle: the site host when known, else the feed URL host. */
internal fun displayHost(feed: DiscoveredFeed): String {
    val raw = feed.siteUrl?.takeIf { it.isNotBlank() } ?: feed.url
    return raw.toHttpUrlOrNull()?.host ?: raw
}

/**
 * FD2 — Material 3 preview bottom sheet: feed metadata plus the latest
 * entries, with Subscribe/Retry actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedPreviewSheet(
    previewState: DiscoverPreviewState,
    onDismiss: () -> Unit,
    onRetry: (String) -> Unit,
    onSubscribe: (DiscoverPreviewState.Loaded) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        when (previewState) {
            DiscoverPreviewState.Hidden -> Unit
            is DiscoverPreviewState.Loading -> {
                Box(
                    modifier =
                        Modifier.fillMaxWidth()
                            .height(220.dp)
                            .testTag(DiscoverTestTags.PREVIEW_LOADING),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            is DiscoverPreviewState.Failed -> {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 26.dp),
                ) {
                    Text(
                        text = previewState.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            modifier = Modifier.testTag(DiscoverTestTags.PREVIEW_RETRY),
                            onClick = { onRetry(previewState.url) },
                        ) {
                            Text(stringResource(R.string.discover_retry))
                        }
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.close))
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
            is DiscoverPreviewState.Loaded -> {
                val preview = previewState.preview
                Column(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier =
                            Modifier.fillMaxWidth()
                                .weight(1f, fill = false)
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 26.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FeedIcon(
                                modifier = Modifier.size(40.dp),
                                feedName = preview.feed.title ?: previewState.feed.title,
                                iconUrl = previewState.feed.iconUrl,
                                placeholderIcon = Icons.Rounded.RssFeed,
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = preview.feed.title ?: previewState.feed.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = displayHost(previewState.feed),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                        preview.feed.description?.takeIf { it.isNotBlank() }?.let { description ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = preview.feedUrl,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(12.dp))
                        Subtitle(text = stringResource(R.string.discover_latest_articles))
                        Spacer(modifier = Modifier.height(8.dp))
                        preview.recentEntries.forEachIndexed { index, entry ->
                            Column(
                                modifier =
                                    Modifier.fillMaxWidth()
                                        .padding(vertical = 6.dp)
                                        .testTag(DiscoverTestTags.PREVIEW_ENTRY_PREFIX + index),
                            ) {
                                Text(
                                    text = entry.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                entry.publishedAt?.let { millis ->
                                    Text(
                                        text = formatPreviewDate(millis),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 26.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.close))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            modifier = Modifier.testTag(DiscoverTestTags.PREVIEW_SUBSCRIBE),
                            onClick = { onSubscribe(previewState) },
                        ) {
                            Text(stringResource(R.string.subscribe))
                        }
                    }
                    Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                }
            }
        }
    }
}

private fun formatPreviewDate(millis: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault()).format(Date(millis))

/**
 * FD2 — the compact target-group and options confirmation sheet reusing
 * [FeedOptionView], with a confirm/cancel action row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverSubscribeSheetContent(
    sheet: DiscoverSubscribeSheet,
    onDismiss: () -> Unit,
    onGroupSelect: (String) -> Unit,
    onNotificationToggle: () -> Unit,
    onFullContentToggle: () -> Unit,
    onBrowserToggle: () -> Unit,
    onAddNewGroup: (String) -> Unit,
    onConfirm: () -> Unit,
) {
    var newGroupDialogVisible by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 26.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FeedIcon(
                    modifier = Modifier.size(32.dp),
                    feedName = sheet.feed.title,
                    iconUrl = sheet.feed.iconUrl,
                    placeholderIcon = Icons.Rounded.RssFeed,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    modifier = Modifier.weight(1f),
                    text = sheet.feed.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            FeedOptionView(
                link = sheet.resolvedFeedUrl,
                groups = sheet.groups,
                selectedAllowNotificationPreset = sheet.notification,
                selectedParseFullContentPreset = sheet.fullContent,
                selectedOpenInBrowserPreset = sheet.browser,
                selectedGroupId = sheet.selectedGroupId,
                allowNotificationPresetOnClick = onNotificationToggle,
                fullContentPresetOnClick = onFullContentToggle,
                openInBrowserPresetOnClick = onBrowserToggle,
                onGroupClick = onGroupSelect,
                onAddNewGroup = { newGroupDialogVisible = true },
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    modifier = Modifier.testTag(DiscoverTestTags.SUBSCRIBE_CONFIRM),
                    enabled = !sheet.subscribing && sheet.selectedGroupId.isNotBlank(),
                    onClick = onConfirm,
                ) {
                    if (sheet.subscribing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text(stringResource(R.string.subscribe))
                    }
                }
            }
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
    if (newGroupDialogVisible) {
        NewGroupDialog(
            onDismiss = { newGroupDialogVisible = false },
            onConfirm = { name ->
                onAddNewGroup(name)
                newGroupDialogVisible = false
            },
        )
    }
}

@Composable
private fun NewGroupDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    TextFieldDialog(
        visible = true,
        title = stringResource(R.string.create_new_group),
        icon = Icons.Outlined.CreateNewFolder,
        value = value,
        placeholder = stringResource(R.string.name),
        onValueChange = { value = it },
        onDismissRequest = onDismiss,
        onConfirm = { onConfirm(value) },
    )
}
