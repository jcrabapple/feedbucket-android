package me.ash.reader.ui.page.home.reading

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextDecoration
import me.ash.reader.domain.model.diff.ContentDiffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S22 — diff annotated-text semantics, acceptance #5 companion. Asserts
 * the DESTINATION state (Gate B reviewer advisory #2): exact span ranges,
 * exact semantics-annotation ranges, redundant non-color channels
 * (strikethrough/underline), and the TalkBack announcement order.
 *
 * Every assertion here fails for a specific rendering regression (the
 * failure-witness contract): pointing the builders at the wrong kind,
 * dropping an annotation, or losing a text decoration flips exactly the
 * matching test red.
 */
class DiffA11yTest {

    private val deletedStyle =
        SpanStyle(color = androidx.compose.ui.graphics.Color.Red, textDecoration = TextDecoration.LineThrough)
    private val insertedStyle =
        SpanStyle(color = androidx.compose.ui.graphics.Color.Blue, textDecoration = TextDecoration.Underline)

    private fun sampleDiff() =
        ContentDiffer.diff(
            "<p>alpha beta gamma</p>",
            "<p>alpha delta gamma</p>",
        )!!

    @Test
    fun annotatedStringCarriesDeleteAndInsertSpansAtExactRanges() {
        val diff = sampleDiff()
        val annotated = buildDiffAnnotatedString(diff, deletedStyle, insertedStyle)

        // The rendered string shows every run: equal text, then each
        // deletion (struck through) and insertion (underlined) in place.
        val delSpans = annotated.spanStyles.filter { it.item.textDecoration == TextDecoration.LineThrough }
        val insSpans = annotated.spanStyles.filter { it.item.textDecoration == TextDecoration.Underline }

        assertEquals("exactly one deleted run", 1, delSpans.size)
        assertEquals("exactly one inserted run", 1, insSpans.size)

        // Deleted run: "beta" — located between "alpha " and " gamma".
        val deletedRange = delSpans.single().let { it.start until it.end }
        assertEquals("beta", annotated.text.substring(deletedRange))

        // Inserted run: "delta".
        val insertedRange = insSpans.single().let { it.start until it.end }
        assertEquals("delta", annotated.text.substring(insertedRange))

        // Reading order: deletion BEFORE insertion (beta replaced by delta).
        assertTrue(delSpans.single().start < insSpans.single().start)
    }

    @Test
    fun semanticsAnnotationRangesMatchSpanRanges() {
        val diff = sampleDiff()
        val annotated = buildDiffAnnotatedString(diff, deletedStyle, insertedStyle)

        val delAnnotations = annotated.getStringAnnotations(DIFF_TAG_DELETED, 0, annotated.length)
        val insAnnotations = annotated.getStringAnnotations(DIFF_TAG_INSERTED, 0, annotated.length)
        assertEquals(1, delAnnotations.size)
        assertEquals(1, insAnnotations.size)
        assertEquals("deleted", delAnnotations.single().item)
        assertEquals("inserted", insAnnotations.single().item)
        assertEquals("beta", annotated.text.substring(delAnnotations.single().start, delAnnotations.single().end))
        assertEquals("delta", annotated.text.substring(insAnnotations.single().start, insAnnotations.single().end))
    }

    @Test
    fun redundantChannelsArePresentWithoutColor() {
        // S22: color is never the only channel. SpanStyles must carry the
        // text decorations even when a caller supplies colorless styles.
        val diff = sampleDiff()
        val plainDeleted = SpanStyle(textDecoration = TextDecoration.LineThrough)
        val plainInserted = SpanStyle(textDecoration = TextDecoration.Underline)
        val annotated = buildDiffAnnotatedString(diff, plainDeleted, plainInserted)
        assertEquals(
            annotated.spanStyles.filter { it.item.textDecoration == TextDecoration.LineThrough }.size,
            1,
        )
        assertEquals(
            annotated.spanStyles.filter { it.item.textDecoration == TextDecoration.Underline }.size,
            1,
        )
    }

    @Test
    fun talkBackDescriptionAnnouncesRunsInReadingOrder() {
        val diff = sampleDiff()
        val description =
            diffAccessibilityDescription(
                diff = diff,
                deletedPrefix = "deleted: ",
                insertedPrefix = "inserted: ",
            )
        val delAt = description.indexOf("deleted: beta")
        val insAt = description.indexOf("inserted: delta")
        assertTrue("deleted run announced", delAt >= 0)
        assertTrue("inserted run announced", insAt >= 0)
        assertTrue("deleted announced before inserted", delAt < insAt)
        // Equal context survives around the runs.
        assertTrue(description.startsWith("<p>alpha "))
        assertTrue(description.endsWith(" gamma</p>"))
    }

    @Test
    fun whitespaceOnlyChangesAnnounceToo() {
        val diff =
            ContentDiffer.diff("<p>one  two</p>", "<p>one two</p>")!!
        val description =
            diffAccessibilityDescription(diff, "deleted: ", "inserted: ")
        assertTrue(description.contains("deleted:") || description.contains("inserted:"))
    }
}
