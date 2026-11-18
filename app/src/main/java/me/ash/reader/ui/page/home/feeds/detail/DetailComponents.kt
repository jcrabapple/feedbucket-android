package me.ash.reader.ui.page.home.feeds.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import me.ash.reader.R

/**
 * Feed/GroupDetail action row (UI-CONTRACT §3.3 "Settings/feed/rule rows"):
 * minimum 64dp two-line / 48dp one-line height via 16dp horizontal and 12dp
 * vertical padding, 24dp leading glyph with 16dp gap, text determines final
 * height. The icon is decorative (null description) because the row label is
 * always present (§8.1).
 */
@Composable
internal fun DetailActionRow(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    desc: String? = null,
    enabled: Boolean = true,
    testTag: String,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .testTag(testTag)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color =
                    if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (desc != null) {
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}

/**
 * Toggle row for the detail pages: an M3 [Switch] with a guaranteed 48dp
 * target wrapped in a 48dp box, inside a [DetailActionRow] so the whole row
 * is actionable. The switch exposes toggleableState and its default state
 * description per §8.1; the row click toggles the same value.
 */
@Composable
internal fun DetailSwitchRow(
    title: String,
    desc: String?,
    checked: Boolean,
    onCheckedChange: () -> Unit,
    icon: ImageVector,
    testTag: String,
) {
    // material3 does not set a StateDescription on the switch node in every
    // resolved version; §8.1 requires one, so name the state explicitly.
    val stateDescription = stringResource(if (checked) R.string.on else R.string.off)
    DetailActionRow(
        title = title,
        desc = desc,
        icon = icon,
        onClick = onCheckedChange,
        testTag = "${testTag}_row",
        trailing = {
            Box(
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Switch(
                    modifier =
                        Modifier
                            .testTag(testTag)
                            .semantics { this.stateDescription = stateDescription },
                    checked = checked,
                    onCheckedChange = { onCheckedChange() },
                )
            }
        },
    )
}
