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
 * readable text with "deleted:"/"inserted:" prefixes on changed runs and a
 * deterministic [separator] between every changed run and its successor, so
 * screen readers hear every change in reading order without fused runs
 * (S22; review round 1 finding 4).
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
 * with its [deletedPrefix]/[insertedPrefix] in reading order, each followed
 * by [separator] so consecutive runs never fuse ("deleted: betainserted:"
 * is a defect, not a style choice); equal runs pass through verbatim so
 * the announcement keeps full context.
 */
internal fun diffAccessibilityDescription(
    diff: ContentDiff,
    deletedPrefix: String,
    insertedPrefix: String,
    separator: String = " ",
): String =
    buildString {
        for (segment in diff.segments) {
            when (segment.kind) {
                ContentDiffer.SegmentKind.EQUAL -> append(segment.text)
                ContentDiffer.SegmentKind.DELETE ->
                    appendChangedRun(deletedPrefix, segment.text, separator)
                ContentDiffer.SegmentKind.INSERT ->
                    appendChangedRun(insertedPrefix, segment.text, separator)
            }
        }
    }.trim()

/**
 * Deterministic separation in BOTH directions (review round 1 finding 4):
 * a changed run never fuses with its predecessor ("onedeleted:") nor its
 * successor ("betainserted:"). The trailing [separator] is trimmed when the
 * run is the last thing in the announcement.
 */
private fun StringBuilder.appendChangedRun(prefix: String, text: String, separator: String) {
    if (isNotEmpty() && !last().isWhitespace()) append(separator)
    append(prefix).append(text).append(separator)
}
