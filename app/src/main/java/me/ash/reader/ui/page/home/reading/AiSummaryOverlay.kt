package me.ash.reader.ui.page.home.reading

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import me.ash.reader.R
import me.ash.reader.ui.ext.showToast

/**
 * AI-summary overlay on top of the reader: an elevated rounded card with a
 * prominent X close button (top-right) and Back support. Room cache first,
 * a setup prompt with a deep link into Settings → AI Summaries when
 * unconfigured, else generate with spinner, error + Retry, and on success
 * the summary text with model attribution plus Copy / Regenerate actions.
 */
@Composable
fun AiSummaryOverlay(
    articleId: String,
    isFullContent: Boolean,
    onNavigateToAiSettings: () -> Unit,
    onDismiss: () -> Unit,
    viewModel: AiSummaryViewModel = hiltViewModel(),
) {
    LaunchedEffect(articleId) { viewModel.open(articleId, isFullContent) }
    DisposableEffect(viewModel) {
        onDispose { viewModel.close() }
    }

    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val copiedToast = stringResource(R.string.ai_summary_copied)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BackHandler { onDismiss() }
        Surface(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier.padding(24.dp).heightIn(max = 480.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Psychology,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.size(12.dp))
                    Text(
                        text = stringResource(R.string.ai_summaries),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.close),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                when (val current = state) {
                    AiSummaryUiState.Loading,
                    AiSummaryUiState.Generating -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 24.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                            Spacer(modifier = Modifier.size(16.dp))
                            Text(
                                text = stringResource(R.string.ai_summary_generating),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }

                    AiSummaryUiState.SetupNeeded -> {
                        Text(
                            text = stringResource(R.string.ai_summary_setup_needed),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = {
                                onDismiss()
                                onNavigateToAiSettings()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.ai_summary_open_settings))
                        }
                    }

                    is AiSummaryUiState.Success -> {
                        Text(
                            text = current.summary,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier =
                                Modifier.weight(1f, fill = false)
                                    .verticalScroll(rememberScrollState()),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.ai_summary_generated_with, current.model),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilledTonalButton(
                                onClick = {
                                    clipboard.setText(AnnotatedString(current.summary))
                                    context.showToast(copiedToast)
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.ai_summary_copy))
                            }
                            FilledTonalButton(
                                onClick = { viewModel.regenerate() },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.ai_summary_regenerate))
                            }
                        }
                    }

                    is AiSummaryUiState.Error -> {
                        Text(
                            text = current.message,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = { viewModel.retry() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.ai_summary_retry))
                        }
                    }
                }
            }
        }
    }
}
