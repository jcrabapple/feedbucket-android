package me.ash.reader.ui.page.home.feeds.subscribe

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.RssFeed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.ash.reader.R
import me.ash.reader.domain.model.discovery.DiscoveredFeed
import me.ash.reader.ui.component.FeedIcon
import me.ash.reader.ui.page.home.feeds.discover.feedFormatLabel
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * FD2 — the multi-feed candidate picker rows shown by [SubscribeDialog] in
 * [SubscribeState.ChooseFeed]: each advertised feed renders as a tappable row
 * (icon, title, host, format badge) so the user picks which feed to configure
 * instead of the dialog silently taking the first advertisement.
 */
@Composable
fun SubscribeCandidateList(
    candidates: List<DiscoveredFeed>,
    onCandidateClick: (DiscoveredFeed) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState())
    ) {
        Text(
            text = stringResource(R.string.multiple_feeds_found),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        candidates.forEachIndexed { index, candidate ->
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .clickable { onCandidateClick(candidate) }
                        .padding(vertical = 10.dp, horizontal = 4.dp)
                        .testTag("subscribe_candidate_$index"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FeedIcon(
                    modifier = Modifier.size(24.dp),
                    feedName = candidate.title,
                    iconUrl = candidate.iconUrl,
                    placeholderIcon = Icons.Rounded.RssFeed,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = candidate.title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = candidate.url.toHttpUrlOrNull()?.host ?: candidate.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                feedFormatLabel(candidate.format)?.let { label ->
                    Surface(
                        shape = MaterialTheme.shapes.extraSmall,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Text(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
            if (index != candidates.lastIndex) {
                HorizontalDivider()
            }
        }
    }
}
