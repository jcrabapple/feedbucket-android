package me.ash.reader.ui.page.settings.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.ash.reader.R
import me.ash.reader.domain.model.rule.ActionRule
import me.ash.reader.domain.service.StateApplyLedger

/**
 * S18a — live rule preview: total match count over the current library plus
 * the first 20 samples, the count-confirmed apply-to-existing action and
 * its result. Preview refreshes when the draft's match-relevant fields
 * change; mutes never offer apply (they are query-time filters, S17a).
 */
@Composable
fun RulePreview(
    rule: ActionRule,
    viewModel: RulesViewModel,
    onApplied: (Int) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<StateApplyLedger.Preview?>(null) }
    var loading by remember { mutableStateOf(false) }
    var confirmApply by remember { mutableStateOf(false) }

    LaunchedEffect(rule.type, rule.query, rule.allFeeds, rule.feedIds, rule.groupIds) {
        preview = null
        if (rule.type == ActionRule.TYPE_STANDARD && rule.query.isNotBlank()) {
            loading = true
            preview = viewModel.preview(rule)
            loading = false
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        Text(
            text = stringResource(R.string.rule_preview),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(8.dp))
        when {
            rule.type != ActionRule.TYPE_STANDARD ->
                Text(
                    text = stringResource(R.string.rule_mute_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

            loading -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.height(20.dp))
                Text(
                    text = stringResource(R.string.rule_preview),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            preview != null -> {
                Text(
                    text = pluralStringResource(R.plurals.rule_preview_count, preview!!.count, preview!!.count),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(modifier = Modifier.height(8.dp))
                preview!!.samples.take(StateApplyLedger.PREVIEW_SAMPLE_LIMIT).forEach { sample ->
                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = sample.title,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = sample.feedName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { confirmApply = true },
                    enabled = preview!!.count > 0,
                ) {
                    Text(stringResource(R.string.rule_apply))
                }
            }
        }
    }

    if (confirmApply && preview != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmApply = false },
            title = { Text(stringResource(R.string.rule_apply_confirm_title)) },
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.rule_apply_confirm_message,
                        preview!!.count,
                        preview!!.count,
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmApply = false
                        scope.launch {
                            val affected = viewModel.applyToExisting(rule)
                            onApplied(affected)
                        }
                    }
                ) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmApply = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
