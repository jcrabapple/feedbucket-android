package me.ash.reader.ui.page.home.reading

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.util.Date
import me.ash.reader.R
import me.ash.reader.domain.model.diff.ContentDiffer
import me.ash.reader.infrastructure.preference.LocalReadingRenderer
import me.ash.reader.infrastructure.preference.LocalReadingSubheadUpperCase
import me.ash.reader.infrastructure.preference.ReadingRendererPreference
import me.ash.reader.ui.component.reader.LocalTextContentWidth
import me.ash.reader.ui.component.reader.Reader
import me.ash.reader.ui.component.scrollbar.drawVerticalScrollIndicator
import me.ash.reader.ui.component.webview.RYWebView
import me.ash.reader.ui.ext.extractDomain
import me.ash.reader.ui.ext.formatAsString
import me.ash.reader.ui.ext.roundClick

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun Content(
    modifier: Modifier = Modifier,
    content: String,
    feedName: String,
    title: String,
    author: String? = null,
    link: String? = null,
    publishedDate: Date,
    scrollState: ScrollState,
    listState: LazyListState,
    isLoading: Boolean,
    contentPadding: PaddingValues = PaddingValues(),
    onImageClick: ((imgUrl: String, altText: String) -> Unit)? = null,
    // S22 — publisher-update diff. The caller passes originalContent ONLY
    // when the rendered `content` IS the current rawDescription (the
    // description render path); the diff compares sanitized
    // originalContent vs sanitized current rawDescription.
    originalContent: String? = null,
    // S21a — update provenance badge data; displayed above the diff
    // toggle. Never a sort key anywhere (D8).
    contentChangedAt: Date? = null,
) {
    val context = LocalContext.current
    val subheadUpperCase = LocalReadingSubheadUpperCase.current
    val renderer = LocalReadingRenderer.current

    val textContentWidth = LocalTextContentWidth.current
    val maxWidthModifier = Modifier.widthIn(max = textContentWidth)
    val uriHandler = LocalUriHandler.current

    // S22: "View changes" is available only when a first-version snapshot
    // exists AND a diff actually renders (equal token lists -> no diff,
    // web None parity). Computation failure -> feature silently absent.
    val diff =
        remember(originalContent, content) {
            originalContent?.let { ContentDiffer.diff(it, content) }
        }
    var showDiff by rememberSaveable { mutableStateOf(false) }

    val headline =
        @Composable {
            Column(modifier = Modifier.then(maxWidthModifier).padding(horizontal = 12.dp)) {
                DisableSelection {
                    Metadata(
                        feedName = feedName,
                        title = title,
                        author = author,
                        publishedDate = publishedDate,
                        modifier = Modifier.roundClick { link?.let { uriHandler.openUri(it) } },
                    )
                }
                // S22 — update diff toggle (publisher-changed articles).
                if (diff != null) {
                    DiffUpdateToggle(
                        contentChangedAt = contentChangedAt,
                        showingDiff = showDiff,
                        onToggle = { showDiff = !showDiff },
                    )
                }
            }
        }

    if (isLoading) {
        Column { LoadingIndicator(modifier = Modifier.size(56.dp)) }
    } else {

        when (renderer) {
            ReadingRendererPreference.WebView -> {
                Column(
                    modifier =
                        modifier
                            .padding(top = contentPadding.calculateTopPadding())
                            .fillMaxSize()
                            .drawVerticalScrollIndicator(scrollState)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(scrollState),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Column(modifier = Modifier.then(maxWidthModifier)) {
                            // Top bar height
                            Spacer(modifier = Modifier.height(64.dp))
                            // padding
                            headline()

                            if (showDiff && diff != null) {
                                DiffPane(
                                    diff = diff,
                                    modifier = Modifier.padding(vertical = 12.dp),
                                )
                            } else {
                                RYWebView(
                                    modifier = Modifier.fillMaxSize(),
                                    content = content,
                                    refererDomain = link.extractDomain(),
                                    onImageClick = onImageClick,
                                )
                            }
                            Spacer(modifier = Modifier.height(128.dp))
                            Spacer(
                                modifier = Modifier.height(contentPadding.calculateBottomPadding())
                            )
                        }
                    }
                }
            }

            ReadingRendererPreference.NativeComponent -> {
                SelectionContainer {
                    LazyColumn(
                        modifier = modifier.fillMaxSize().drawVerticalScrollIndicator(listState),
                        state = listState,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        item {
                            // Top bar height
                            Spacer(modifier = Modifier.height(64.dp))
                            // padding
                            Spacer(modifier = Modifier.height(contentPadding.calculateTopPadding()))
                            headline()
                        }

                        if (showDiff && diff != null) {
                            item {
                                DiffPane(
                                    diff = diff,
                                    modifier = Modifier.then(maxWidthModifier).padding(horizontal = 12.dp),
                                )
                            }
                        } else {
                            Reader(
                                context = context,
                                subheadUpperCase = subheadUpperCase.value,
                                link = link ?: "",
                                content = content,
                                onImageClick = onImageClick,
                                onLinkClick = { uriHandler.openUri(it) },
                            )
                        }

                        item {
                            Spacer(modifier = Modifier.height(128.dp))
                            Spacer(
                                modifier = Modifier.height(contentPadding.calculateBottomPadding())
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * S22 — the "View changes" toggle row with the S21a update-provenance
 * caption. Uses the M3 token set only (D17: no restyle); FilterChip keeps
 * its standard ≥48dp touch target.
 */
@Composable
private fun DiffUpdateToggle(
    contentChangedAt: Date?,
    showingDiff: Boolean,
    onToggle: () -> Unit,
) {
    val context = LocalContext.current
    Column {
        contentChangedAt?.let {
            Text(
                text =
                    stringResource(R.string.updated_caption, it.formatAsString(context, atHourMinute = true)),
                color = MaterialTheme.colorScheme.outline.copy(alpha = .7f),
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
        FilterChip(
            selected = showingDiff,
            onClick = onToggle,
            label = {
                Text(
                    text =
                        stringResource(
                            if (showingDiff) R.string.hide_changes else R.string.view_changes
                        ),
                    style = MaterialTheme.typography.labelLarge,
                )
            },
            colors =
                FilterChipDefaults.filterChipColors(
                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
        )
        Spacer(modifier = Modifier.height(12.dp))
    }
}
