package me.ash.reader.ui.page.settings.aisummary

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import me.ash.reader.R
import me.ash.reader.infrastructure.aisummary.AiSummarySettingsStore
import me.ash.reader.ui.component.base.DisplayText
import me.ash.reader.ui.component.base.FeedbackIconButton
import me.ash.reader.ui.component.base.RYScaffold
import me.ash.reader.ui.ext.showToast

private const val PRESET_OPENAI = "https://api.openai.com/v1"
private const val PRESET_OLLAMA_LAN = "http://192.168.1.100:11434/v1"

/**
 * Settings → AI Summaries: OpenAI-compatible endpoint configuration.
 * Base URL + provider preset chips, masked API key (Android Keystore
 * encrypted at rest), model picker with a curated OpenRouter catalog,
 * a live connection probe, and explicit save with persistence feedback.
 */
@Composable
fun AiSummariesPage(
    viewModel: AiSummariesViewModel = hiltViewModel(),
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val baseUrl by viewModel.baseUrl.collectAsState()
    val model by viewModel.model.collectAsState()
    val apiKey by viewModel.apiKey.collectAsState()
    val loaded by viewModel.loaded.collectAsState()
    val testState by viewModel.testState.collectAsState()

    var showKey by remember { mutableStateOf(false) }
    var showRecommended by remember { mutableStateOf(false) }
    val savedToast = stringResource(R.string.ai_summary_saved)

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
                    DisplayText(text = stringResource(R.string.ai_summaries), desc = "")
                }
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = baseUrl,
                            enabled = loaded,
                            onValueChange = { viewModel.baseUrl.value = it },
                            label = { Text(stringResource(R.string.ai_summary_base_url)) },
                            singleLine = true,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AssistChip(
                                onClick = {
                                    viewModel.baseUrl.value =
                                        AiSummarySettingsStore.DEFAULT_BASE_URL
                                },
                                label = { Text("OpenRouter") },
                            )
                            AssistChip(
                                onClick = { viewModel.baseUrl.value = PRESET_OPENAI },
                                label = { Text("OpenAI") },
                            )
                            AssistChip(
                                onClick = { viewModel.baseUrl.value = PRESET_OLLAMA_LAN },
                                label = { Text("Ollama (LAN)") },
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = apiKey,
                            enabled = loaded,
                            onValueChange = { viewModel.apiKey.value = it },
                            label = { Text(stringResource(R.string.ai_summary_api_key)) },
                            singleLine = true,
                            visualTransformation =
                                if (showKey) {
                                    VisualTransformation.None
                                } else {
                                    PasswordVisualTransformation()
                                },
                            trailingIcon = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (apiKey.isNotEmpty()) {
                                        IconButton(
                                            onClick = { viewModel.apiKey.value = "" },
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.Close,
                                                contentDescription =
                                                    stringResource(R.string.ai_summary_clear_key),
                                            )
                                        }
                                    }
                                    IconButton(onClick = { showKey = !showKey }) {
                                        Icon(
                                            imageVector =
                                                if (showKey) {
                                                    Icons.Outlined.VisibilityOff
                                                } else {
                                                    Icons.Outlined.Visibility
                                                },
                                            contentDescription =
                                                stringResource(
                                                    if (showKey) {
                                                        R.string.ai_summary_hide_key
                                                    } else {
                                                        R.string.ai_summary_show_key
                                                    }
                                                ),
                                        )
                                    }
                                }
                            },
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.ai_summary_key_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = model,
                            enabled = loaded,
                            onValueChange = { viewModel.model.value = it },
                            label = { Text(stringResource(R.string.ai_summary_model)) },
                            singleLine = true,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        FilledTonalButton(onClick = { showRecommended = true }) {
                            Icon(
                                imageVector = Icons.Outlined.Psychology,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.size(8.dp))
                            Text(stringResource(R.string.ai_summary_recommended_models))
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                        TestStatusCard(testState = testState)
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { viewModel.testConnection() },
                            enabled = testState != AiSummaryTestState.Running,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (testState == AiSummaryTestState.Running) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                                Spacer(modifier = Modifier.size(8.dp))
                                Text(stringResource(R.string.ai_summary_testing))
                            } else {
                                Text(stringResource(R.string.ai_summary_test))
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { viewModel.save { context.showToast(savedToast) } },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.save))
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
                item {
                    Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                }
            }
        },
    )

    if (showRecommended) {
        RecommendedModelsDialog(
            onDismiss = { showRecommended = false },
            onPick = {
                viewModel.applyRecommendedModel(it)
                showRecommended = false
            },
        )
    }
}

@Composable
private fun TestStatusCard(testState: AiSummaryTestState) {
    when (testState) {
        AiSummaryTestState.Idle,
        AiSummaryTestState.Running -> Unit
        is AiSummaryTestState.Pass ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer,
            ) {
                Text(
                    modifier = Modifier.padding(16.dp),
                    text =
                        stringResource(
                            R.string.ai_summary_test_passed,
                            testState.model,
                            testState.latencyMs,
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        is AiSummaryTestState.Fail ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.errorContainer,
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.ai_summary_test_failed),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = testState.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
    }
}

@Composable
private fun RecommendedModelsDialog(
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Psychology, contentDescription = null) },
        title = { Text(stringResource(R.string.ai_summary_recommended_models)) },
        text = {
            Column {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(recommendedModels, key = { it.id }) { entry ->
                        Column(
                            modifier =
                                Modifier.fillMaxWidth()
                                    .clickable { onPick(entry.id) }
                                    .padding(vertical = 10.dp, horizontal = 4.dp)
                        ) {
                            Text(
                                text = entry.id,
                                style = MaterialTheme.typography.bodyLarge,
                                fontFamily = FontFamily.Monospace,
                            )
                            Text(
                                text = entry.note,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.ai_summary_recommended_models_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        },
    )
}
