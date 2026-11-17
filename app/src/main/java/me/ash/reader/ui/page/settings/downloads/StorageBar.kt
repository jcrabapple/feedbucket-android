package me.ash.reader.ui.page.settings.downloads

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.util.Locale
import me.ash.reader.R
import me.ash.reader.domain.model.download.DownloadPolicy

/**
 * S28 — global download store usage bar: 1 GiB cap, 90% warning tint,
 * 100% error tint. NEVER silent eviction (D9) — the manage screen offers
 * bulk remove instead.
 */
@Composable
fun StorageBar(usageBytes: Long, status: DownloadPolicy.GlobalStatus) {
    val color =
        when (status) {
            DownloadPolicy.GlobalStatus.OK -> MaterialTheme.colorScheme.primary
            DownloadPolicy.GlobalStatus.WARN -> MaterialTheme.colorScheme.tertiary
            DownloadPolicy.GlobalStatus.EXCEEDED -> MaterialTheme.colorScheme.error
        }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        Text(
            text =
                stringResource(
                    R.string.downloads_usage,
                    formatBytes(usageBytes),
                    formatBytes(DownloadPolicy.GLOBAL_MAX_BYTES),
                ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = {
                (usageBytes.toDouble() / DownloadPolicy.GLOBAL_MAX_BYTES).coerceIn(0.0, 1.0).toFloat()
            },
            modifier = Modifier.fillMaxWidth(),
            color = color,
        )
    }
}

internal fun formatBytes(bytes: Long): String =
    when {
        bytes >= 1L shl 30 -> format(bytes, 1L shl 30, "GiB")
        bytes >= 1L shl 20 -> format(bytes, 1L shl 20, "MiB")
        bytes >= 1L shl 10 -> format(bytes, 1L shl 10, "KiB")
        else -> "$bytes B"
    }

private fun format(bytes: Long, unit: Long, suffix: String): String =
    String.format(Locale.US, "%.1f %s", bytes.toDouble() / unit, suffix)
