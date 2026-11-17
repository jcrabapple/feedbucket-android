package me.ash.reader.ui.page.settings.migration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.ash.reader.R
import me.ash.reader.domain.service.migration.ImportPreview
import me.ash.reader.domain.service.migration.ImportSchemaChecker

/**
 * S32a step 3 — no-writes preview sheet (UI-CONTRACT §6.10): the exact
 * counts table, the first 10 subscription titles, the input kind and the
 * file fingerprint, then Import / Cancel. Cancel writes nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportPreviewSheet(
    preview: ImportPreview?,
    onImport: () -> Unit,
    onCancel: () -> Unit,
) {
    if (preview == null) return
    ModalBottomSheet(onDismissRequest = onCancel) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = stringResource(R.string.migration_preview_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(
                    when (preview.kind) {
                        ImportSchemaChecker.SourceKind.PackageV1 -> R.string.migration_kind_package
                        else -> R.string.migration_kind_raw
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))

            val c = preview.counts
            CountRow(R.string.migration_counts_subscriptions, c.subscriptions)
            CountRow(R.string.migration_counts_tags, c.tags)
            CountRow(R.string.migration_counts_saved_searches, c.savedSearches)
            CountRow(R.string.migration_counts_entries, c.entries)
            CountRow(R.string.migration_counts_unread, c.unread)
            CountRow(R.string.migration_counts_starred, c.starred)
            CountRow(R.string.migration_counts_updated, c.updated)
            CountRow(R.string.migration_counts_recently_read, c.recentlyRead)
            CountRow(R.string.migration_counts_standard_actions, c.standardActions)
            CountRow(R.string.migration_counts_mute_actions, c.muteActions)
            CountRow(R.string.migration_counts_snapshots, c.snapshots)
            CountRow(R.string.migration_counts_extracted, c.extracted)

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.migration_first_titles),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() },
            )
            preview.firstSubscriptionTitles.forEach { title ->
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.heightIn(min = 32.dp),
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(
                    R.string.migration_fingerprint,
                    preview.fingerprint.take(16),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.migration_cancel))
                }
                TextButton(
                    onClick = onImport,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.migration_import))
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/** One count/evidence row: label wraps, value uses tabular figures (§3.3). */
@Composable
private fun CountRow(labelRes: Int, value: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}
