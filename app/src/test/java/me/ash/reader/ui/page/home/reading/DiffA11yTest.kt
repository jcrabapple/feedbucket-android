package me.ash.reader.ui.page.home.reading

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextDecoration
import me.ash.reader.domain.model.diff.ContentDiffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // EXACT string (review round 1 finding 4): every changed run is
        // separated from predecessor AND successor by deterministic
        // separators — "deleted: betainserted: delta" or "onedeleted:"
        // fusion is a failure. The double space before the tail equal run
        // is the post-run separator plus the equal run's own leading space.
        assertEquals("alpha deleted: beta inserted: delta  gamma", description)
    }

    @Test
    fun talkBackDescriptionDeletionOnlyIsExact() {
        // Pure deletion: no INSERT run exists (probe-verified segment list:
        // EQUAL ' alpha ' / DELETE 'beta ' / EQUAL 'gamma ').
        val diff = ContentDiffer.diff("<p>alpha beta gamma</p>", "<p>alpha gamma</p>")!!
        assertEquals(
            "alpha deleted: beta  gamma",
            diffAccessibilityDescription(diff, "deleted: ", "inserted: "),
        )
    }

    @Test
    fun talkBackDescriptionInsertionOnlyIsExact() {
        val diff = ContentDiffer.diff("<p>alpha gamma</p>", "<p>alpha beta gamma</p>")!!
        assertEquals(
            "alpha inserted: beta  gamma",
            diffAccessibilityDescription(diff, "deleted: ", "inserted: "),
        )
    }

    @Test
    fun whitespaceOnlyChangesAnnounceToo() {
        val diff =
            ContentDiffer.diff("<p>one  two</p>", "<p>one two</p>")!!
        assertEquals(
            "one deleted:    inserted:   two",
            diffAccessibilityDescription(diff, "deleted: ", "inserted: "),
        )
    }

    @Test
    fun renderedTextAndAnnouncementCarryNoMarkupOrEntityLiterals() {
        // Review round 1 finding 3: the pane must render DECODED article
        // text — no tags, no entity literals — while keeping exact spans.
        val diff =
            ContentDiffer.diff(
                "<p>alpha beta &amp; gamma</p>",
                "<p>alpha delta &amp; gamma</p>",
            )!!
        val annotated = buildDiffAnnotatedString(diff, deletedStyle, insertedStyle)
        val description = diffAccessibilityDescription(diff, "deleted: ", "inserted: ")

        for (text in listOf(annotated.text, description)) {
            assertFalse("markup leaked into readable text: $text", text.contains('<'))
            assertFalse("markup leaked into readable text: $text", text.contains('>'))
            assertFalse("entity literal leaked: $text", text.contains("&amp;"))
        }
        // Entity decoded for display.
        assertTrue(annotated.text.contains("&"))
        assertEquals("alpha deleted: beta inserted: delta  & gamma", description)
        // Spans still exact on the readable text.
        val del = annotated.getStringAnnotations(DIFF_TAG_DELETED, 0, annotated.length).single()
        assertEquals("beta", annotated.text.substring(del.start, del.end))
    }
}
