package me.ash.reader.ui.page.home.flow

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * S15 — one saved-search chip in the flow filter bar row. Tap executes the
 * saved search (view=all + verbatim query); long-press opens management
 * (rename/delete). Selected state reflects that this search is the active
 * query string.
 *
 * Long-press is attached via [detectTapGestures] rather than a wrapping
 * combinedClickable so the chip keeps exactly ONE clickable (its own) —
 * nested clickables on Surface components fight over gesture consumption.
 */
@Composable
fun SavedSearchChip(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    FilterChip(
        modifier =
            Modifier
                .padding(end = 8.dp)
                .pointerInput(onLongClick) {
                    detectTapGestures(onLongPress = { onLongClick() })
                },
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = name,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        colors =
            FilterChipDefaults.filterChipColors(
                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
    )
}
