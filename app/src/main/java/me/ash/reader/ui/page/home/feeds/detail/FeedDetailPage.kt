package me.ash.reader.ui.page.home.feeds.detail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import me.ash.reader.R
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.Group
import me.ash.reader.infrastructure.preference.LocalOpenLink
import me.ash.reader.infrastructure.preference.LocalOpenLinkSpecificBrowser
import me.ash.reader.ui.component.ChangeUrlDialog
import me.ash.reader.ui.component.FeedIcon
import me.ash.reader.ui.component.RenameDialog
import me.ash.reader.ui.component.base.DisplayText
import me.ash.reader.ui.component.base.FeedbackIconButton
import me.ash.reader.ui.component.base.RYSelectionChip
import me.ash.reader.ui.component.base.Subtitle
import me.ash.reader.ui.component.base.TextFieldDialog
import me.ash.reader.ui.ext.collectAsStateValue
import me.ash.reader.ui.ext.openURL
import me.ash.reader.ui.ext.showToast
import me.ash.reader.ui.page.home.feeds.drawer.feed.ClearFeedDialog
import me.ash.reader.ui.page.home.feeds.drawer.feed.DeleteFeedDialog
import me.ash.reader.ui.page.home.feeds.drawer.feed.FeedOptionViewModel

/**
 * FeedDetail destination (UI-CONTRACT §2.2, parent Feeds; Back pops to Feeds
 * per §2.3.6). Full management surface for one feed: the displaced
 * long-press drawer actions in contract order (§2.4: URL, rename, folder,
 * notifications, full text, browser, clear, unsubscribe; destructive
 * actions confirmed).
 */
@Composable
fun FeedDetailPage(
    feedId: String,
    viewModel: FeedOptionViewModel = hiltViewModel(),
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val openLink = LocalOpenLink.current
    val openLinkSpecificBrowser = LocalOpenLinkSpecificBrowser.current
    val feedOptionUiState = viewModel.feedOptionUiState.collectAsStateValue()
    val feed = feedOptionUiState.feed
    val rssService = viewModel.rssService.get()
    val toastString = stringResource(R.string.rename_toast, feedOptionUiState.newName)

    LaunchedEffect(feedId) { viewModel.fetchFeed(feedId) }

    FeedDetailContent(
        feed = feed,
        groups = feedOptionUiState.groups,
        selectedGroupId = feed?.groupId ?: "",
        canEdit = rssService.updateSubscription,
        canMove = rssService.moveSubscription,
        canDelete = rssService.deleteSubscription,
        onBack = onBack,
        onOpenOriginal = { context.openURL(feed?.url, openLink, openLinkSpecificBrowser) },
        onChangeUrl = { viewModel.showFeedUrlDialog() },
        onRename = { viewModel.showRenameDialog() },
        onGroupClick = { viewModel.selectedGroup(it) },
        onAddNewGroup = { viewModel.showNewGroupDialog() },
        onNotificationChange = { viewModel.changeAllowNotificationPreset() },
        onFullContentChange = { viewModel.changeParseFullContentPreset() },
        onBrowserChange = { viewModel.changeOpenInBrowserPreset() },
        onClear = { viewModel.showClearDialog() },
        onUnsubscribe = { viewModel.showDeleteDialog() },
        onIconClick = { viewModel.reloadIcon() },
    )

    DeleteFeedDialog(
        feedName = feed?.name ?: "",
        feedOptionViewModel = viewModel,
        onConfirm = onBack,
    )
    ClearFeedDialog(feedName = feed?.name ?: "", feedOptionViewModel = viewModel)

    TextFieldDialog(
        visible = feedOptionUiState.newGroupDialogVisible,
        title = stringResource(R.string.create_new_group),
        icon = Icons.Outlined.CreateNewFolder,
        value = feedOptionUiState.newGroupContent,
        placeholder = stringResource(R.string.name),
        onValueChange = { viewModel.inputNewGroup(it) },
        onDismissRequest = { viewModel.hideNewGroupDialog() },
        onConfirm = { viewModel.addNewGroup() },
    )

    RenameDialog(
        visible = feedOptionUiState.renameDialogVisible,
        value = feedOptionUiState.newName,
        onValueChange = { viewModel.inputNewName(it) },
        onDismissRequest = { viewModel.hideRenameDialog() },
        onConfirm = {
            viewModel.renameFeed()
            context.showToast(toastString)
        },
    )

    ChangeUrlDialog(
        visible = feedOptionUiState.changeUrlDialogVisible,
        value = feedOptionUiState.newUrl,
        onValueChange = { viewModel.inputNewUrl(it) },
        onDismissRequest = { viewModel.hideFeedUrlDialog() },
        onConfirm = { viewModel.changeFeedUrl() },
    )
}

/**
 * Stateless FeedDetail body; extracted so instrumented tests can compose it
 * without a ViewModel (48dp semantics + TalkBack assertions).
 */
@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalLayoutApi::class,
    ExperimentalFoundationApi::class,
)
@Composable
internal fun FeedDetailContent(
    feed: Feed?,
    groups: List<Group>,
    selectedGroupId: String,
    canEdit: Boolean,
    canMove: Boolean,
    canDelete: Boolean,
    onBack: () -> Unit,
    onOpenOriginal: () -> Unit,
    onChangeUrl: () -> Unit,
    onRename: () -> Unit,
    onGroupClick: (String) -> Unit,
    onAddNewGroup: () -> Unit,
    onNotificationChange: () -> Unit,
    onFullContentChange: () -> Unit,
    onBrowserChange: () -> Unit,
    onClear: () -> Unit,
    onUnsubscribe: () -> Unit,
    onIconClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val unknown = stringResource(R.string.unknown)
    val feedUrl = feed?.url ?: unknown

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    // 48dp floor is enforced inside FeedbackIconButton.
                    FeedbackIconButton(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.back_to_feeds),
                        tint = MaterialTheme.colorScheme.onSurface,
                        onClick = onBack,
                    )
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = padding) {
            // §6.3.1: feed title and favicon; editable Rename action.
            item {
                Row(
                    modifier =
                        Modifier.fillMaxWidth().padding(start = 24.dp, top = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FeedIcon(
                        feedName = feed?.name,
                        iconUrl = feed?.icon,
                        size = 28.dp,
                        modifier =
                            Modifier
                                .clip(MaterialTheme.shapes.small)
                                .clickable(onClick = onIconClick),
                    )
                }
                DisplayText(
                    // §8.1: one heading with the visible page title.
                    modifier = Modifier.semantics { heading() },
                    text = feed?.name ?: unknown,
                    desc = "",
                    onTextClick = if (canEdit) onRename else null,
                )
            }

            // §6.3.2: full feed URL (wrapped anywhere) + Open original /
            // Change URL. Semantics carry the raw URL even though the
            // display copy inserts zero-width break opportunities (§8.1,
            // §8.3: URLs never widen the page).
            item {
                Text(
                    modifier =
                        Modifier
                            .testTag("feed_detail_url")
                            .padding(horizontal = 24.dp)
                            .heightIn(min = 48.dp)
                            .clip(MaterialTheme.shapes.small)
                            .combinedClickable(
                                onClick = onOpenOriginal,
                                onLongClick = if (canEdit) onChangeUrl else null,
                            )
                            .padding(horizontal = 4.dp, vertical = 12.dp)
                            .semantics { contentDescription = feedUrl },
                    text = feedUrl.withBreakOpportunities(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(modifier = Modifier.padding(horizontal = 20.dp)) {
                    TextButton(
                        modifier =
                            Modifier
                                .testTag("feed_detail_open_original")
                                .heightIn(min = 48.dp),
                        contentPadding = PaddingValues(horizontal = 24.dp),
                        onClick = onOpenOriginal,
                    ) {
                        Text(stringResource(R.string.open_original))
                    }
                    TextButton(
                        modifier =
                            Modifier
                                .testTag("feed_detail_change_url")
                                .heightIn(min = 48.dp),
                        contentPadding = PaddingValues(horizontal = 24.dp),
                        enabled = canEdit,
                        onClick = onChangeUrl,
                    ) {
                        Text(stringResource(R.string.change_url))
                    }
                }
            }

            // §6.3.3: folder (single groupId) selector.
            if (canMove) {
                item {
                    Subtitle(
                        text = stringResource(R.string.folder),
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    FlowRow(
                        modifier =
                            Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                        horizontalArrangement =
                            Arrangement.spacedBy(10.dp, Alignment.Start),
                        verticalArrangement =
                            Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
                    ) {
                        groups.forEach { group ->
                            RYSelectionChip(
                                modifier =
                                    Modifier
                                        .heightIn(min = 48.dp)
                                        .testTag("feed_detail_group_chip_${group.id}"),
                                content = group.name,
                                selected = group.id == selectedGroupId,
                            ) {
                                onGroupClick(group.id)
                            }
                        }
                        NewGroupButton(onClick = onAddNewGroup)
                    }
                }
            }

            // §6.3.6: notifications, full text, browser (per-feed toggles).
            item {
                DetailSwitchRow(
                    title = stringResource(R.string.allow_notification),
                    desc = null,
                    checked = feed?.isNotification ?: false,
                    onCheckedChange = onNotificationChange,
                    icon = Icons.Outlined.Notifications,
                    testTag = "feed_detail_notifications",
                )
                DetailSwitchRow(
                    title = stringResource(R.string.parse_full_content),
                    desc = null,
                    checked = feed?.isFullContent ?: false,
                    onCheckedChange = onFullContentChange,
                    icon = Icons.AutoMirrored.Outlined.Article,
                    testTag = "feed_detail_full_content",
                )
                DetailSwitchRow(
                    title = stringResource(R.string.open_in_browser),
                    desc = null,
                    checked = feed?.isBrowser ?: false,
                    onCheckedChange = onBrowserChange,
                    icon = Icons.Outlined.OpenInBrowser,
                    testTag = "feed_detail_browser",
                )
            }

            // §6.3.7: clear and unsubscribe; destructive, confirmed via the
            // AlertDialogs hosted by the page wrapper.
            item {
                DetailActionRow(
                    title = stringResource(R.string.clear_articles),
                    icon = Icons.Outlined.DeleteSweep,
                    onClick = onClear,
                    testTag = "feed_detail_row_clear",
                )
                if (canDelete) {
                    DetailActionRow(
                        title = stringResource(R.string.unsubscribe),
                        icon = Icons.Outlined.DeleteForever,
                        onClick = onUnsubscribe,
                        testTag = "feed_detail_row_unsubscribe",
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
                Spacer(
                    modifier =
                        Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars)
                )
            }
        }
    }
}

/** Display copy of a URL that can wrap at any character (§8.3). */
private fun String.withBreakOpportunities(): String =
    replace("/", "/\u200B").replace(".", ".\u200B").replace("?", "?\u200B")

@Composable
private fun NewGroupButton(onClick: () -> Unit) {
    Box(
        modifier =
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onClick)
                .testTag("feed_detail_new_group"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Add,
            contentDescription = stringResource(R.string.create_new_group),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
