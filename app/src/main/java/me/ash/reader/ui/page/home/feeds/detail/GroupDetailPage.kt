package me.ash.reader.ui.page.home.feeds.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import me.ash.reader.R
import me.ash.reader.domain.model.group.Group
import me.ash.reader.ui.component.RenameDialog
import me.ash.reader.ui.component.base.DisplayText
import me.ash.reader.ui.component.base.FeedbackIconButton
import me.ash.reader.ui.component.base.RYSelectionChip
import me.ash.reader.ui.component.base.Subtitle
import me.ash.reader.ui.ext.collectAsStateValue
import me.ash.reader.ui.ext.currentAccountId
import me.ash.reader.ui.ext.getDefaultGroupId
import me.ash.reader.ui.ext.showToast
import me.ash.reader.ui.page.home.feeds.drawer.group.AllAllowNotificationDialog
import me.ash.reader.ui.page.home.feeds.drawer.group.AllMoveToGroupDialog
import me.ash.reader.ui.page.home.feeds.drawer.group.AllOpenInBrowserDialog
import me.ash.reader.ui.page.home.feeds.drawer.group.AllParseFullContentDialog
import me.ash.reader.ui.page.home.feeds.drawer.group.ClearGroupDialog
import me.ash.reader.ui.page.home.feeds.drawer.group.DeleteGroupDialog
import me.ash.reader.ui.page.home.feeds.drawer.group.GroupOptionViewModel

/**
 * GroupDetail destination (UI-CONTRACT §2.2, parent Feeds; Back pops to
 * Feeds per §2.3.6). Full management surface for one group/folder: the
 * displaced group drawer actions (rename, move-to-group, notifications,
 * full text, browser, clear, delete), destructive actions confirmed.
 */
@Composable
fun GroupDetailPage(
    groupId: String,
    viewModel: GroupOptionViewModel = hiltViewModel(),
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val groupOptionUiState = viewModel.groupOptionUiState.collectAsStateValue()
    val group = groupOptionUiState.group
    val rssService = viewModel.rssService.get()
    val defaultGroupId = context.currentAccountId.getDefaultGroupId()
    val toastString = stringResource(R.string.rename_toast, groupOptionUiState.newName)

    LaunchedEffect(groupId) { viewModel.fetchGroup(groupId) }

    GroupDetailContent(
        group = group,
        groups = groupOptionUiState.groups,
        canMove = rssService.moveSubscription && groupOptionUiState.groups.size != 1,
        canDelete = rssService.deleteSubscription && group?.id != defaultGroupId,
        onBack = onBack,
        onRename = { viewModel.showRenameDialog() },
        onMoveToGroup = { viewModel.showAllMoveToGroupDialog(it) },
        onNotificationClick = { viewModel.showAllAllowNotificationDialog() },
        onFullContentClick = { viewModel.showAllParseFullContentDialog() },
        onBrowserClick = { viewModel.showAllOpenInBrowserDialog() },
        onClear = { viewModel.showClearDialog() },
        onDelete = { viewModel.showDeleteDialog() },
    )

    ClearGroupDialog(groupName = group?.name ?: "", groupOptionViewModel = viewModel, onConfirm = {})
    DeleteGroupDialog(
        groupName = group?.name ?: "",
        groupOptionViewModel = viewModel,
        onConfirm = onBack,
    )
    AllAllowNotificationDialog(
        groupName = group?.name ?: "",
        groupOptionViewModel = viewModel,
        onConfirm = {},
    )
    AllParseFullContentDialog(
        groupName = group?.name ?: "",
        groupOptionViewModel = viewModel,
        onConfirm = {},
    )
    AllOpenInBrowserDialog(
        groupName = group?.name ?: "",
        groupOptionViewModel = viewModel,
        onConfirm = {},
    )
    AllMoveToGroupDialog(
        groupName = group?.name ?: "",
        groupOptionViewModel = viewModel,
        onConfirm = {},
    )

    RenameDialog(
        visible = groupOptionUiState.renameDialogVisible,
        value = groupOptionUiState.newName,
        onValueChange = { viewModel.inputNewName(it) },
        onDismissRequest = { viewModel.hideRenameDialog() },
        onConfirm = {
            viewModel.rename()
            context.showToast(toastString)
        },
    )
}

/**
 * Stateless GroupDetail body; extracted so instrumented tests can compose it
 * without a ViewModel (48dp semantics + TalkBack assertions).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun GroupDetailContent(
    group: Group?,
    groups: List<Group>,
    canMove: Boolean,
    canDelete: Boolean,
    onBack: () -> Unit,
    onRename: () -> Unit,
    onMoveToGroup: (Group) -> Unit,
    onNotificationClick: () -> Unit,
    onFullContentClick: () -> Unit,
    onBrowserClick: () -> Unit,
    onClear: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val unknown = stringResource(R.string.unknown)

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
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
            // Group title (editable Rename action) + folder glyph.
            item {
                DisplayText(
                    // §8.1: one heading with the visible page title.
                    modifier = Modifier.semantics { heading() },
                    text = group?.name ?: unknown,
                    desc = "",
                    onTextClick = onRename,
                )
            }

            // Group-wide "all feeds in this group" presets; each opens the
            // established confirmation dialog (§7: dialogs only for
            // consequential confirmation).
            item {
                DetailActionRow(
                    title = stringResource(R.string.allow_notification),
                    desc = stringResource(R.string.group_option_tips),
                    icon = Icons.Outlined.Notifications,
                    onClick = onNotificationClick,
                    testTag = "group_detail_row_notifications",
                )
                DetailActionRow(
                    title = stringResource(R.string.parse_full_content),
                    desc = null,
                    icon = Icons.AutoMirrored.Outlined.Article,
                    onClick = onFullContentClick,
                    testTag = "group_detail_row_full_content",
                )
                DetailActionRow(
                    title = stringResource(R.string.open_in_browser),
                    desc = null,
                    icon = Icons.Outlined.OpenInBrowser,
                    onClick = onBrowserClick,
                    testTag = "group_detail_row_browser",
                )
            }

            // Move all feeds in this group to another group.
            if (canMove) {
                item {
                    Subtitle(
                        text = stringResource(R.string.move_to_group),
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    FlowRow(
                        modifier = Modifier.padding(horizontal = 24.dp),
                        horizontalArrangement =
                            Arrangement.spacedBy(10.dp, Alignment.Start),
                        verticalArrangement =
                            Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
                    ) {
                        groups
                            .filter { it.id != group?.id }
                            .forEach { target ->
                                RYSelectionChip(
                                    modifier =
                                        Modifier
                                            .heightIn(min = 48.dp)
                                            .testTag("group_detail_group_chip_${target.id}"),
                                    content = target.name,
                                    selected = false,
                                ) {
                                    onMoveToGroup(target)
                                }
                            }
                    }
                }
            }

            // Destructive; confirmed via the AlertDialogs hosted by the
            // page wrapper.
            item {
                DetailActionRow(
                    title = stringResource(R.string.clear_articles),
                    icon = Icons.Outlined.DeleteSweep,
                    onClick = onClear,
                    testTag = "group_detail_row_clear",
                )
                if (canDelete) {
                    DetailActionRow(
                        title = stringResource(R.string.delete_group),
                        icon = Icons.Outlined.DeleteForever,
                        onClick = onDelete,
                        testTag = "group_detail_row_delete",
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
