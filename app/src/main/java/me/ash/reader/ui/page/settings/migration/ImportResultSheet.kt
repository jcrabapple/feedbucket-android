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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.unit.dp
import me.ash.reader.R
import me.ash.reader.domain.service.migration.FeedBucketImporter
import me.ash.reader.ui.page.settings.migration.MigrationViewModel.RunResult

/**
 * S32a steps 5-8 — applying / completed / failed result sheet
 * (UI-CONTRACT §6.10). Applying shows literal progress (never a false
 * rollback). Completed shows the exact imported/skipped/failed ledger plus
 * itemized row errors, with Remove imported data and Done. Failed keeps the
 * retained source visible while Retry/Resume is offered.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportResultSheet(
    applying: Boolean,
    result: RunResult?,
    onRetry: () -> Unit,
    onDone: () -> Unit,
    onRemoveImported: () -> Unit,
    removalReport: FeedBucketImporter.RemovalReport?,
) {
    ModalBottomSheet(onDismissRequest = { if (!applying) onDone() }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            when {
                applying -> {
                    Text(
                        text = stringResource(R.string.migration_applying),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() },
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    // 4dp linear track + visible text; no spinner-only state.
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.migration_resumable_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                result is RunResult.Completed -> {
                    Text(
                        text = stringResource(R.string.migration_result_completed),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() },
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    val ledger = result.ledger
                    ResultRow(R.string.migration_ledger_imported, ledger.imported)
                    ResultRow(R.string.migration_ledger_skipped, ledger.skipped)
                    ResultRow(R.string.migration_ledger_failed, ledger.failed)
                    if (ledger.searchesImported > 0 || ledger.rulesImported > 0) {
                        ResultRow(
                            R.string.migration_ledger_searches_rules,
                            ledger.searchesImported + ledger.rulesImported,
                        )
                    }
                    if (ledger.failures.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.migration_failures),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.semantics { heading() },
                        )
                        ledger.failures.forEach { failure ->
                            Text(
                                text = failure,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.heightIn(min = 32.dp),
                            )
                        }
                    }
                    removalReport?.let { report ->
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(
                                R.string.migration_removed_skipped,
                                report.removed,
                                report.skipped,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = onRemoveImported,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.migration_remove_imported))
                        }
                        TextButton(
                            onClick = onDone,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.migration_done))
                        }
                    }
                }
                result is RunResult.Failed -> {
                    Text(
                        text = stringResource(R.string.migration_result_failed),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { heading() },
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = result.reason,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    // The retained copy survives while Retry/Resume exists.
                    Text(
                        text = stringResource(R.string.migration_source_kept),
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
                            onClick = onRetry,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.migration_retry))
                        }
                        TextButton(
                            onClick = onDone,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.migration_done))
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ResultRow(labelRes: Int, value: Int) {
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
