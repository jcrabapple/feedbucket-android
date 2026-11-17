package me.ash.reader.ui.page.settings.rules

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Rule
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.ash.reader.R
import me.ash.reader.domain.model.rule.ActionRule
import me.ash.reader.ui.component.base.DisplayText
import me.ash.reader.ui.component.base.FeedbackIconButton
import me.ash.reader.ui.component.base.RYScaffold
import me.ash.reader.ui.page.settings.SelectableSettingGroupItem

/**
 * Wave D — rules & mutes list (S17a/S18a). Each row shows type, query and
 * scope; tap edits. The undo card at the bottom drives the S18a durable
 * undo of the most recent apply-to-existing run.
 */
@Composable
fun RulesPage(
    viewModel: RulesViewModel,
    onBack: () -> Unit,
    onNavigateToEdit: (Long) -> Unit,
) {
    val rules by viewModel.rules.collectAsState()
    val undoableRun by viewModel.undoableRun.collectAsState()
    val scope = rememberCoroutineScope()
    var undoResult by remember { mutableStateOf<Pair<Int, Int>?>(null) }

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
        actions = {
            FeedbackIconButton(
                imageVector = Icons.Rounded.Add,
                contentDescription = stringResource(R.string.rule_new),
                tint = MaterialTheme.colorScheme.onSurface,
            ) {
                onNavigateToEdit(0L)
            }
        },
        content = {
            LazyColumn {
                item {
                    DisplayText(text = stringResource(R.string.rules), desc = "")
                }
                items(rules, key = { it.id }) { rule ->
                    SelectableSettingGroupItem(
                        title = rule.title,
                        desc = ruleDesc(rule),
                        icon =
                            if (rule.type == ActionRule.TYPE_MUTE) {
                                Icons.Outlined.NotificationsOff
                            } else {
                                Icons.Outlined.Rule
                            },
                        onClick = { onNavigateToEdit(rule.id) },
                    )
                }
                item {
                    undoableRun?.let { run ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.rule_undo),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    text =
                                        stringResource(
                                            R.string.rule_applied,
                                            run.appliedCount,
                                        ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        viewModel.undo()?.let { counts ->
                                            undoResult = counts.restored to counts.skipped
                                        }
                                    }
                                }
                            ) {
                                Text(stringResource(R.string.rule_undo))
                            }
                        }
                    }
                }
                item {
                    Spacer(modifier = Modifier.height(24.dp))
                    Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                }
            }
        },
    )

    undoResult?.let { (restored, skipped) ->
        AlertDialog(
            onDismissRequest = { undoResult = null },
            text = { Text(stringResource(R.string.rule_undo_result, restored, skipped)) },
            confirmButton = {
                TextButton(onClick = { undoResult = null }) { Text(stringResource(R.string.confirm)) }
            },
        )
    }
}

private fun ruleDesc(rule: ActionRule): String {
    val scope = if (rule.allFeeds) "all feeds" else "selected feeds/groups"
    val effects =
        rule.effects.split(',').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")
    return buildString {
        append(if (rule.type == ActionRule.TYPE_MUTE) "Mute" else effects.ifEmpty { "no effects" })
        append(" · ")
        append(rule.query.ifEmpty { "(no query)" })
        append(" · ")
        append(scope)
    }
}
