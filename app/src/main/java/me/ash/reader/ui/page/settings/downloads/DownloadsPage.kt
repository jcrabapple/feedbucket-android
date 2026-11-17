package me.ash.reader.ui.page.settings.downloads

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.ash.reader.R
import me.ash.reader.domain.model.download.ArticleDownload
import me.ash.reader.domain.model.download.DownloadPolicy
import me.ash.reader.domain.repository.DownloadWithTitle
import me.ash.reader.ui.component.base.DisplayText
import me.ash.reader.ui.component.base.FeedbackIconButton
import me.ash.reader.ui.component.base.RYScaffold
import me.ash.reader.ui.page.settings.SelectableSettingGroupItem

/**
 * S27a/S28/S29 — Settings → Downloads: storage bar (1 GiB cap, 90% warn),
 * capped bulk download of unread articles, count-confirmed "Remove all",
 * and per-item rows with the honest S27a state granularity:
 * `Saved` / `Text saved · images N/M` / `Waiting for Wi-Fi` / failed causes.
 */
@Composable
fun DownloadsPage(
    viewModel: DownloadsViewModel,
    onBack: () -> Unit,
) {
    val items by viewModel.items.collectAsState()
    val workStates by viewModel.workStates.collectAsState()
    val usageBytes by viewModel.usageBytes.collectAsState()
    val globalStatus by viewModel.globalStatus.collectAsState()
    val busy by viewModel.busy.collectAsState()

    var confirmRemoveAll by remember { mutableStateOf(false) }
    var confirmRemoveId by remember { mutableStateOf<String?>(null) }

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
            LazyColumn {
                item {
                    DisplayText(text = stringResource(R.string.downloads), desc = "")
                }
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    StorageBar(usageBytes = usageBytes, status = globalStatus)
                    Spacer(modifier = Modifier.height(16.dp))
                }
                item {
                    // S28 — the cap is stated in the UI.
                    SelectableSettingGroupItem(
                        title =
                            stringResource(
                                R.string.downloads_download_unread,
                                DownloadPolicy.BULK_MAX_ARTICLES,
                            ),
                        desc = "",
                        icon = Icons.Outlined.Download,
                    ) {
                        viewModel.downloadUnread()
                    }
                }
                item {
                    SelectableSettingGroupItem(
                        title = stringResource(R.string.downloads_remove_all),
                        desc = "",
                        icon = Icons.Outlined.DeleteSweep,
                    ) {
                        if (items.isNotEmpty()) confirmRemoveAll = true
                    }
                }
                if (items.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.downloads_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(horizontal = 24.dp).padding(vertical = 12.dp),
                        )
                    }
                }
                items(items, key = { it.download.articleId }) { entry ->
                    SelectableSettingGroupItem(
                        title = entry.articleTitle ?: entry.download.articleId,
                        desc = stateText(entry.download, workStates),
                        icon = Icons.Outlined.MenuBook,
                    ) {
                        confirmRemoveId = entry.download.articleId
                    }
                }
                item {
                    Spacer(modifier = Modifier.height(24.dp))
                    Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                }
            }
        },
    )

    if (confirmRemoveAll) {
        AlertDialog(
            onDismissRequest = { confirmRemoveAll = false },
            title = { Text(stringResource(R.string.downloads_remove_all)) },
            text = { Text(stringResource(R.string.downloads_remove_all_confirm, items.size)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.removeAll()
                        confirmRemoveAll = false
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemoveAll = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    confirmRemoveId?.let { articleId ->
        AlertDialog(
            onDismissRequest = { confirmRemoveId = null },
            title = { Text(stringResource(R.string.downloads_remove)) },
            text = { Text(stringResource(R.string.downloads_remove_all_confirm, 1)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.remove(articleId)
                        confirmRemoveId = null
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemoveId = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/** S27a observable states (SPEC §14) rendered as honest human text. */
@Composable
internal fun stateText(
    row: ArticleDownload,
    workStates: DownloadsViewModel.WorkStates,
): String {
    val waitingWifi =
        row.state == ArticleDownload.STATE_PENDING &&
            row.articleId !in workStates.manualEnqueued &&
            workStates.bulkEnqueued
    return when {
        waitingWifi -> stringResource(R.string.downloads_state_waiting_wifi)
        row.state == ArticleDownload.STATE_READY && row.assetsComplete &&
            row.imageCount >= row.expectedImageCount ->
            stringResource(R.string.downloads_state_saved)
        row.state == ArticleDownload.STATE_READY ->
            stringResource(R.string.downloads_state_text_only, row.imageCount, row.expectedImageCount)
        row.state == ArticleDownload.STATE_DOWNLOADING ->
            stringResource(R.string.downloads_state_downloading)
        row.state == ArticleDownload.STATE_PENDING ->
            stringResource(R.string.downloads_state_queued)
        row.errorCause == "storage" -> stringResource(R.string.downloads_state_failed_storage)
        row.errorCause == "oversize" -> stringResource(R.string.downloads_state_failed_oversize)
        row.errorCause == "network" -> stringResource(R.string.downloads_state_failed_network)
        else -> stringResource(R.string.downloads_state_failed)
    }
}
