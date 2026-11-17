package me.ash.reader.domain.model.diff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S22 — diff tokenizer/sanitizer parity + diff semantics, acceptance #5.
 *
 * The `sanitized`/`tokens` literals are RECORDED PYTHON GROUND TRUTH,
 * generated read-only from the web repo venv (D14):
 *
 *   env -u PYTHONPATH /home/jason/projects/feedbucket/.venv/bin/python \
 *     build/recon/gen-diff-token-fixtures.py
 *
 * which runs web `sanitize_html` (security.py ALLOWED_TAGS/ATTRIBUTES,
 * schemes, link_rel, strip_comments) then web `_TOKEN.findall`
 * (feedbucket/diff.py:8) over each input. Generator + raw JSON live in
 * build/recon/ (gitignored). The Kotlin side must reproduce both the
 * sanitized HTML (Jsoup mirror) and the token stream exactly.
 *
 * RED protocol: written first against a pass-through ContentDiffer stub
 * (see wave review packet); every parity/diff assertion was red.
 */
class DiffTokenizerTest {

    private class Fixture(val sanitized: String, val tokens: List<String>)

    /** (raw input) -> (nh3-sanitized HTML, recorded `_TOKEN.findall` tokens). */
    private val sanitizedFixtures: List<Pair<String, Fixture>> = buildList {
        // para
        add(
            "<p>Hello <strong>world</strong>!</p><p>Second para.</p>" to Fixture(
                sanitized = "<p>Hello <strong>world</strong>!</p><p>Second para.</p>",
                tokens = listOf("<p>", "Hello", " ", "<strong>", "world</strong>!</p><p>Second", " ", "para.</p>"),
            )
        )
        // anchor (link_rel enforcement parity)
        add(
            "<p><a href=\"https://example.com/x\">link text</a> done</p>" to Fixture(
                sanitized = "<p><a href=\"https://example.com/x\" rel=\"noopener noreferrer\">link text</a> done</p>",
                tokens = listOf("<p>", "<a href=\"https://example.com/x\" rel=\"noopener noreferrer\">", "link", " ", "text</a>", " ", "done</p>"),
            )
        )
        // anchor_stripped_attr (onclick dropped, href kept)
        add(
            "<p><a href=\"https://example.com/x\" onclick=\"evil()\">x</a></p>" to Fixture(
                sanitized = "<p><a href=\"https://example.com/x\" rel=\"noopener noreferrer\">x</a></p>",
                tokens = listOf("<p>", "<a href=\"https://example.com/x\" rel=\"noopener noreferrer\">", "x</a></p>"),
            )
        )
        // img
        add(
            "<p><img src=\"https://example.com/i.png\" alt=\"an image\"> tail</p>" to Fixture(
                sanitized = "<p><img src=\"https://example.com/i.png\" alt=\"an image\"> tail</p>",
                tokens = listOf("<p>", "<img src=\"https://example.com/i.png\" alt=\"an image\">", " ", "tail</p>"),
            )
        )
        // entity (named refs decoded; markup-significant refs re-escaped)
        add(
            "<p>fish &amp; chips &lt;tag&gt; caf&eacute;</p>" to Fixture(
                sanitized = "<p>fish &amp; chips &lt;tag&gt; café</p>",
                tokens = listOf("<p>", "fish", " ", "&amp;", " ", "chips", " ", "&lt;tag&gt;", " ", "café</p>"),
            )
        )
        // script (ammonia: element AND content removed)
        add(
            "<p>before</p><script>alert(1)</script><p>after</p>" to Fixture(
                sanitized = "<p>before</p><p>after</p>",
                tokens = listOf("<p>", "before</p><p>after</p>"),
            )
        )
        // nbsp (escaped, single \S+ run)
        add(
            "<p>a\u00a0b\u00a0c</p>" to Fixture(
                sanitized = "<p>a&nbsp;b&nbsp;c</p>",
                tokens = listOf("<p>", "a&nbsp;b&nbsp;c</p>"),
            )
        )
        // list
        add(
            "<ul><li>one</li><li>two</li></ul>" to Fixture(
                sanitized = "<ul><li>one</li><li>two</li></ul>",
                tokens = listOf("<ul>", "<li>", "one</li><li>two</li></ul>"),
            )
        )
        // delins (web-allowed diff markup tags survive sanitization)
        add(
            "<p>old <del>gone</del> <ins>new</ins></p>" to Fixture(
                sanitized = "<p>old <del>gone</del> <ins>new</ins></p>",
                tokens = listOf("<p>", "old", " ", "<del>", "gone</del>", " ", "<ins>", "new</ins></p>"),
            )
        )
        // blockquote
        add(
            "<blockquote>quoted text</blockquote><p>after</p>" to Fixture(
                sanitized = "<blockquote>quoted text</blockquote><p>after</p>",
                tokens = listOf("<blockquote>", "quoted", " ", "text</blockquote><p>after</p>"),
            )
        )
        // comment (stripped, text joined)
        add(
            "<p>a<!-- hidden -->b</p>" to Fixture(
                sanitized = "<p>ab</p>",
                tokens = listOf("<p>", "ab</p>"),
            )
        )
        // bad_scheme (javascript: href dropped on both sides, rel enforced)
        add(
            "<p><a href=\"javascript:evil()\">click</a></p>" to Fixture(
                sanitized = "<p><a rel=\"noopener noreferrer\">click</a></p>",
                tokens = listOf("<p>", "<a rel=\"noopener noreferrer\">", "click</a></p>"),
            )
        )
        // table (colspan kept)
        add(
            "<table><thead><tr><th>H</th></tr></thead><tbody><tr><td colspan=\"2\">cell</td></tr></tbody></table>" to Fixture(
                sanitized = "<table><thead><tr><th>H</th></tr></thead><tbody><tr><td colspan=\"2\">cell</td></tr></tbody></table>",
                tokens = listOf("<table>", "<thead>", "<tr>", "<th>", "H</th></tr></thead><tbody><tr><td", " ", "colspan=\"2\">cell</td></tr></tbody></table>"),
            )
        )
    }

    /** Raw (unsanitized) inputs pinned against Python `_TOKEN.findall`. */
    private val rawFixtures: Map<String, List<String>> = linkedMapOf(
        "the quick  brown\tfox" to listOf("the", " ", "quick", "  ", "brown", "\t", "fox"),
        "a\u00a0b c" to listOf("a", "\u00a0", "b", " ", "c"),
        "a\u2003b" to listOf("a", "\u2003", "b"),
        "<b>x</b> y" to listOf("<b>", "x</b>", " ", "y"),
        "a<b c" to listOf("a<b", " ", "c"),
        "<p>t</p>\nnext  line" to listOf("<p>", "t</p>", "\n", "next", "  ", "line"),
    )

    /**
     * Python `\s` includes U+001C–U+001F (Python `str.isspace()`); Java's
     * whitespace class does NOT, even with UNICODE_CHARACTER_CLASS — the
     * shipped regex must add them explicitly. Recorded Python ground truth
     * in build/recon/e4-plaintext-fixtures.json ("tokens"), generated
     * read-only via the web venv (D14).
     */
    private val pythonWhitespaceFixtures: Map<String, List<String>> = linkedMapOf(
        "a\u001Cb" to listOf("a", "\u001C", "b"),
        "a\u001Db" to listOf("a", "\u001D", "b"),
        "a\u001Eb" to listOf("a", "\u001E", "b"),
        "a\u001Fb" to listOf("a", "\u001F", "b"),
        "a\u001Cb c\u001Fd" to listOf("a", "\u001C", "b", " ", "c", "\u001F", "d"),
    )

    @Test
    fun tokenRegexParityPythonWhitespaceSeparators() {
        for ((input, expected) in pythonWhitespaceFixtures) {
            assertEquals("tokenize(Python whitespace probe)", expected, ContentDiffer.tokenize(input))
        }
    }

    @Test
    fun tokenRegexParityRaw() {
        for ((input, expected) in rawFixtures) {
            assertEquals("tokenize($input)", expected, ContentDiffer.tokenize(input))
        }
    }

    @Test
    fun sanitizeParityAgainstNh3() {
        for ((input, fixture) in sanitizedFixtures) {
            assertEquals("sanitize($input)", fixture.sanitized, ContentDiffer.sanitize(input))
        }
    }

    @Test
    fun sanitizeTokenizePipelineParity() {
        for ((input, fixture) in sanitizedFixtures) {
            assertEquals(
                "tokenize(sanitize($input))",
                fixture.tokens,
                ContentDiffer.tokenize(ContentDiffer.sanitize(input)),
            )
        }
    }

    // ---- diff semantics (acceptance #5) ----

    @Test
    fun equalTokenListsNoDiff() {
        val html = "<p>identical <strong>content</strong> here</p>"
        assertNull(ContentDiffer.diff(html, html))
    }

    @Test
    fun equalLengthDifferentTextStillDiffs() {
        // S22 strict-improvement pin: web returns None on equal LENGTH;
        // Android compares token lists, so same-length-different-text
        // MUST still produce a diff. Segment text is DISPLAY text: the
        // closing block tag glued to the word token renders as a trailing
        // space (see ContentDiffer.displayToken).
        val original = "<p>aaaaaaaaaaaaaaaaaaaaaaaa</p>"
        val current = "<p>bbbbbbbbbbbbbbbbbbbbbbbb</p>"
        val diff = ContentDiffer.diff(original, current)
        assertNotNull(diff)
        assertEquals("aaaaaaaaaaaaaaaaaaaaaaaa ", diff!!.deletedText)
        assertEquals("bbbbbbbbbbbbbbbbbbbbbbbb ", diff.insertedText)
    }

    @Test
    fun blankOrNullOriginalNoDiff() {
        assertNull(ContentDiffer.diff(null, "<p>x</p>"))
        assertNull(ContentDiffer.diff("", "<p>x</p>"))
        assertNull(ContentDiffer.diff("   ", "<p>x</p>"))
    }

    @Test
    fun failureReturnsNull() {
        // Web parity: exception -> None -> feature silently unavailable.
        val diff = ContentDiffer.diff("<p>one</p>", "<p>two</p>") { _, _ ->
            throw IllegalStateException("simulated differ failure")
        }
        assertNull(diff)
    }

    @Test
    fun changeProducesDeleteThenInsertRuns() {
        val diff = ContentDiffer.diff("<p>old words</p>", "<p>new words</p>")
        assertNotNull(diff)
        val kinds = diff!!.segments.map { it.kind }
        assertTrue(
            "expected a DELETE and an INSERT run, got $kinds",
            ContentDiffer.SegmentKind.DELETE in kinds &&
                ContentDiffer.SegmentKind.INSERT in kinds,
        )
        assertTrue(diff.hasChanges)
    }
}
