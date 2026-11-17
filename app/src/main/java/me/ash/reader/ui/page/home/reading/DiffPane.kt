package me.ash.reader.ui.page.home.reading

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import me.ash.reader.R
import me.ash.reader.domain.model.diff.ContentDiffer
import me.ash.reader.domain.model.diff.ContentDiffer.ContentDiff

/** Annotated-string tag marking a deletion run (S22 TalkBack semantics). */
internal const val DIFF_TAG_DELETED = "me.ash.reader.diff.deleted"

/** Annotated-string tag marking an insertion run (S22 TalkBack semantics). */
internal const val DIFF_TAG_INSERTED = "me.ash.reader.diff.inserted"

/**
 * S22 — render a [ContentDiff] as Compose annotated text (NOT WebView).
 *
 * Deletions are struck-through ([DIFF_TAG_DELETED] runs), insertions are
 * underlined ([DIFF_TAG_INSERTED] runs); color is NEVER the only channel —
 * strikethrough/underline are the redundant channels (S22 AA requirement),
 * and colors come from the M3 semantic token set ([MaterialTheme.colorScheme])
 * so light/dark/dynamic surfaces stay AA by construction (D17 matrix).
 *
 * TalkBack: the Text announces [diffAccessibilityDescription] — the full
 * text with "deleted:"/"inserted:" prefixes on changed runs, so screen
 * readers hear every change in reading order (S22).
 */
@Composable
fun DiffPane(diff: ContentDiff, modifier: Modifier = Modifier) {
    val deletedColor = MaterialTheme.colorScheme.error
    val insertedColor = MaterialTheme.colorScheme.primary
    val annotated =
        remember(diff, deletedColor, insertedColor) {
            buildDiffAnnotatedString(
                diff = diff,
                deletedStyle =
                    SpanStyle(
                        color = deletedColor,
                        textDecoration = TextDecoration.LineThrough,
                    ),
                insertedStyle =
                    SpanStyle(
                        color = insertedColor,
                        textDecoration = TextDecoration.Underline,
                    ),
            )
        }
    val description =
        diffAccessibilityDescription(
            diff = diff,
            deletedPrefix = stringResource(R.string.diff_deleted_prefix),
            insertedPrefix = stringResource(R.string.diff_inserted_prefix),
        )
    Text(
        text = annotated,
        style = MaterialTheme.typography.bodyLarge,
        modifier = modifier.semantics { contentDescription = description },
    )
}

/**
 * Pure AnnotatedString builder (JVM-testable): one span per diff run, with
 * string annotations marking the semantics ranges TalkBack consumes.
 */
internal fun buildDiffAnnotatedString(
    diff: ContentDiff,
    deletedStyle: SpanStyle,
    insertedStyle: SpanStyle,
): AnnotatedString =
    buildAnnotatedString {
        for (segment in diff.segments) {
            val start = length
            when (segment.kind) {
                ContentDiffer.SegmentKind.EQUAL -> append(segment.text)
                ContentDiffer.SegmentKind.DELETE -> {
                    withStyle(deletedStyle) { append(segment.text) }
                    addStringAnnotation(DIFF_TAG_DELETED, "deleted", start, length)
                }
                ContentDiffer.SegmentKind.INSERT -> {
                    withStyle(insertedStyle) { append(segment.text) }
                    addStringAnnotation(DIFF_TAG_INSERTED, "inserted", start, length)
                }
            }
        }
    }

/**
 * Pure TalkBack description (JVM-testable): every changed run is announced
 * with its [deletedPrefix]/[insertedPrefix] in reading order; equal runs
 * pass through verbatim so the announcement keeps full context.
 */
internal fun diffAccessibilityDescription(
    diff: ContentDiff,
    deletedPrefix: String,
    insertedPrefix: String,
): String =
    buildString {
        for (segment in diff.segments) {
            when (segment.kind) {
                ContentDiffer.SegmentKind.EQUAL -> append(segment.text)
                ContentDiffer.SegmentKind.DELETE -> append(deletedPrefix).append(segment.text)
                ContentDiffer.SegmentKind.INSERT -> append(insertedPrefix).append(segment.text)
            }
        }
    }.trim()
