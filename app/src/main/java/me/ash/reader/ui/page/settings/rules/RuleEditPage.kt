package me.ash.reader.ui.page.settings.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import me.ash.reader.R
import me.ash.reader.domain.model.group.GroupWithFeed
import me.ash.reader.domain.model.rule.ActionRule
import me.ash.reader.ui.component.base.FeedbackIconButton
import me.ash.reader.ui.component.base.RYScaffold
import me.ash.reader.ui.component.base.DisplayText

/**
 * Wave D — rule/mute editor (S10/S13/S17a/S18a). Edits the full ActionRule
 * shape: name, type (standard rule vs mute), the canonical query string,
 * S13 scope (all feeds, or picked groups/feeds) and — for standard rules
 * only — the ingest effect CSV. The S18a preview/apply/undo affordances
 * live in [RulePreview] below the form.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RuleEditPage(
    ruleId: Long,
    viewModel: RulesViewModel,
    onBack: () -> Unit,
) {
    var draft by remember {
        mutableStateOf(
            ActionRule(
                type = ActionRule.TYPE_STANDARD,
                title = "",
                query = "",
                allFeeds = true,
                createdAt = System.currentTimeMillis(),
            )
        )
    }
    var loaded by remember { mutableStateOf(ruleId == 0L) }
    var groupsWithFeeds by remember { mutableStateOf<List<GroupWithFeed>>(emptyList()) }
    var appliedCount by remember { mutableStateOf<Int?>(null) }

    // Review lane 3 defect 1: `rules` is a StateFlow — an unbounded
    // `collect` here suspends forever and made the groupsWithFeeds load
    // below unreachable for existing rules/mutes. `first { }` returns as
    // soon as the rule appears, and the scope-picker data loads in its own
    // effect so it never depends on the rule lookup completing.
    LaunchedEffect(ruleId) {
        if (ruleId != 0L && !loaded) {
            val rules = viewModel.rules.first { list -> list.any { it.id == ruleId } }
            if (!loaded) {
                rules.first { it.id == ruleId }.let {
                    draft = it
                    loaded = true
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        groupsWithFeeds = viewModel.groupsWithFeeds()
    }

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
                imageVector = Icons.Rounded.Check,
                contentDescription = stringResource(R.string.save),
                tint = MaterialTheme.colorScheme.onSurface,
            ) {
                if (draft.title.isNotBlank()) {
                    viewModel.save(draft)
                    onBack()
                }
            }
        },
        content = {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp),
            ) {
                DisplayText(
                    text =
                        stringResource(
                            if (ruleId == 0L) R.string.rule_new else R.string.rule_edit,
                        ),
                    desc = "",
                )

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = draft.title,
                    onValueChange = { draft = draft.copy(title = it) },
                    label = { Text(stringResource(R.string.rule_name)) },
                    placeholder = { Text(stringResource(R.string.rule_name_hint)) },
                    singleLine = true,
                )
                Spacer(modifier = Modifier.height(16.dp))

                // Type: standard rules act on ingest; mutes filter views (S17a).
                Text(
                    text = stringResource(R.string.rules),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = draft.type == ActionRule.TYPE_STANDARD,
                        onClick = { draft = draft.copy(type = ActionRule.TYPE_STANDARD) },
                        label = { Text(stringResource(R.string.rule_type_standard)) },
                    )
                    FilterChip(
                        selected = draft.type == ActionRule.TYPE_MUTE,
                        onClick = { draft = draft.copy(type = ActionRule.TYPE_MUTE) },
                        label = { Text(stringResource(R.string.rule_type_mute)) },
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = draft.query,
                    onValueChange = { draft = draft.copy(query = it) },
                    label = { Text(stringResource(R.string.rule_query)) },
                    placeholder = { Text(stringResource(R.string.rule_query_hint)) },
                    singleLine = true,
                )
                Spacer(modifier = Modifier.height(16.dp))

                if (draft.type == ActionRule.TYPE_STANDARD) {
                    Text(
                        text = stringResource(R.string.rule_effects),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    val effects = draft.effects.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = ActionRule.EFFECT_MARK_READ in effects,
                            onClick = {
                                draft = draft.copy(effects = toggleEffect(effects, ActionRule.EFFECT_MARK_READ))
                            },
                            label = { Text(stringResource(R.string.rule_effect_mark_read)) },
                        )
                        FilterChip(
                            selected = ActionRule.EFFECT_STAR in effects,
                            onClick = {
                                draft = draft.copy(effects = toggleEffect(effects, ActionRule.EFFECT_STAR))
                            },
                            label = { Text(stringResource(R.string.rule_effect_star)) },
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.rule_mute_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))

                // S13 scope.
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.rule_scope_all_feeds),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = draft.allFeeds,
                        onCheckedChange = { draft = draft.copy(allFeeds = it) },
                    )
                }
                if (!draft.allFeeds) {
                    Text(
                        text = stringResource(R.string.rule_scope_pick),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    val selectedFeeds = draft.feedIds.split(',').filter { it.isNotEmpty() }.toSet()
                    val selectedGroups = draft.groupIds.split(',').filter { it.isNotEmpty() }.toSet()
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        groupsWithFeeds.forEach { groupWithFeed ->
                            FilterChip(
                                selected = groupWithFeed.group.id in selectedGroups,
                                onClick = {
                                    draft = draft.copy(
                                        groupIds = toggleId(selectedGroups, groupWithFeed.group.id),
                                    )
                                },
                                label = { Text(groupWithFeed.group.name) },
                            )
                            groupWithFeed.feeds.forEach { feed ->
                                FilterChip(
                                    selected = feed.id in selectedFeeds,
                                    onClick = {
                                        draft = draft.copy(
                                            feedIds = toggleId(selectedFeeds, feed.id),
                                        )
                                    },
                                    label = { Text(feed.name) },
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))

                RulePreview(rule = draft, viewModel = viewModel) { affected ->
                    appliedCount = affected
                }
                appliedCount?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.rule_applied, it),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        },
    )
}

private fun toggleEffect(effects: Set<String>, effect: String): String =
    (if (effect in effects) effects - effect else effects + effect)
        .joinToString(",")
        .ifEmpty { ActionRule.EFFECT_MARK_READ }

private fun toggleId(selected: Set<String>, id: String): String =
    (if (id in selected) selected - id else selected + id).joinToString(",")
