package me.ash.reader.domain.model.diff

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S22 — [ContentDiffer] on ANDROID's ICU-backed `java.util.regex`. Closes the
 * coverage gap the JVM-only `DiffTokenizerTest` / `DiffA11yTest` cannot: the
 * same class of JVM-only regex flag that shipped 0.1.3 in `ExtractCleaner`
 * (`(?U)` / `UNICODE_CHARACTER_CLASS`) crashed EVERY feed open on-device with
 * `ExceptionInInitializerError` → `PatternSyntaxException` at `<clinit>`
 * (real-world red witness: Jason's S26, API 37) while all host-JVM unit tests
 * stayed green. `ContentDiffer.TOKEN_REGEX` carried the same landmine via
 * `Pattern.compile(..., Pattern.UNICODE_CHARACTER_CLASS)`; it now uses
 * portable explicit Unicode classes (`\p{Z}` separators plus explicit
 * NBSP/NEL/U+001C–U+001F) that behave identically on the JVM and on ICU.
 *
 * These cases reuse the RECORDED PYTHON GROUND TRUTH fixtures from
 * `DiffTokenizerTest` verbatim (plus non-ASCII word runs), so any JVM↔ICU
 * divergence in `\s` / word-run semantics turns exactly one case red, and
 * [classInitializesOnAndroidIcu] is the permanent crash witness.
 */
@RunWith(AndroidJUnit4::class)
class ContentDifferInstrumentedTest {

    /**
     * Crash witness: on 0.1.3 even TOUCHING an object with a static regex
     * initializer threw `ExceptionInInitializerError` on Android. Forcing one
     * tokenize compiles [ContentDiffer.TOKEN_REGEX] on ICU.
     */
    @Test
    fun classInitializesOnAndroidIcu() {
        assertEquals(listOf("a", " ", "b"), ContentDiffer.tokenize("a b"))
    }

    /** ASCII word/whitespace runs — same fixtures as `DiffTokenizerTest.rawFixtures`. */
    @Test
    fun tokenizeAsciiParityOnIcu() {
        assertEquals(
            listOf("the", " ", "quick", "  ", "brown", "\t", "fox"),
            ContentDiffer.tokenize("the quick  brown\tfox"),
        )
        assertEquals(listOf("<b>", "x</b>", " ", "y"), ContentDiffer.tokenize("<b>x</b> y"))
        assertEquals(listOf("a<b", " ", "c"), ContentDiffer.tokenize("a<b c"))
        assertEquals(
            listOf("<p>", "t</p>", "\n", "next", "  ", "line"),
            ContentDiffer.tokenize("<p>t</p>\nnext  line"),
        )
    }

    /** NBSP (U+00A0) and EM SPACE (U+2003) are separators — Python `str.isspace` parity. */
    @Test
    fun tokenizeUnicodeSpaceSeparatorsOnIcu() {
        assertEquals(listOf("a", "\u00A0", "b", " ", "c"), ContentDiffer.tokenize("a\u00A0b c"))
        assertEquals(listOf("a", "\u2003", "b"), ContentDiffer.tokenize("a\u2003b"))
        // NEL (U+0085): Unicode/Python whitespace, added explicitly since
        // Java's `\s` omits it — must be a separator on ICU exactly as on JVM.
        assertEquals(listOf("a", "\u0085", "b"), ContentDiffer.tokenize("a\u0085b"))
    }

    /**
     * U+001C–U+001F information separators: Python `\s` includes them (via
     * `str.isspace`); Java's whitespace class does NOT even with
     * UNICODE_CHARACTER_CLASS, so the shipped regex must add them explicitly.
     */
    @Test
    fun tokenizePythonWhitespaceSeparatorsOnIcu() {
        assertEquals(listOf("a", "\u001C", "b"), ContentDiffer.tokenize("a\u001Cb"))
        assertEquals(listOf("a", "\u001D", "b"), ContentDiffer.tokenize("a\u001Db"))
        assertEquals(listOf("a", "\u001E", "b"), ContentDiffer.tokenize("a\u001Eb"))
        assertEquals(listOf("a", "\u001F", "b"), ContentDiffer.tokenize("a\u001Fb"))
        assertEquals(
            listOf("a", "\u001C", "b", " ", "c", "\u001F", "d"),
            ContentDiffer.tokenize("a\u001Cb c\u001Fd"),
        )
    }

    /**
     * Non-ASCII word runs stay INTACT on ICU: accented Latin, CJK and Cyrillic
     * letters are all non-whitespace, so each contiguous run is ONE token and
     * the separators between them (ASCII space, NBSP) split exactly as on the
     * JVM. This pins that ICU does not treat any of these code points as
     * whitespace differently from the host JVM.
     */
    @Test
    fun tokenizeNonAsciiWordRunsOnIcu() {
        assertEquals(listOf("café", " ", "müller"), ContentDiffer.tokenize("café müller"))
        assertEquals(listOf("東京", " ", "物語"), ContentDiffer.tokenize("東京 物語"))
        assertEquals(listOf("Привет", " ", "мир"), ContentDiffer.tokenize("Привет мир"))
        assertEquals(listOf("café", "\u00A0", "東京"), ContentDiffer.tokenize("café\u00A0東京"))
    }

    /** Sanitizer parity on-device — link_rel enforcement, script/comment removal, scheme drop. */
    @Test
    fun sanitizeMarkupParityOnIcu() {
        assertEquals(
            "<p>Hello <strong>world</strong>!</p><p>Second para.</p>",
            ContentDiffer.sanitize("<p>Hello <strong>world</strong>!</p><p>Second para.</p>"),
        )
        assertEquals(
            "<p><a href=\"https://example.com/x\" rel=\"noopener noreferrer\">link text</a> done</p>",
            ContentDiffer.sanitize("<p><a href=\"https://example.com/x\">link text</a> done</p>"),
        )
        // ammonia semantics: script element AND its content removed.
        assertEquals(
            "<p>before</p><p>after</p>",
            ContentDiffer.sanitize("<p>before</p><script>alert(1)</script><p>after</p>"),
        )
        // comments stripped, surrounding text joined.
        assertEquals("<p>ab</p>", ContentDiffer.sanitize("<p>a<!-- hidden -->b</p>"))
        // disallowed scheme dropped on both sides, rel still enforced.
        assertEquals(
            "<p><a rel=\"noopener noreferrer\">click</a></p>",
            ContentDiffer.sanitize("<p><a href=\"javascript:evil()\">click</a></p>"),
        )
    }

    /** Full sanitize→tokenize pipeline on ICU (recorded Python ground truth). */
    @Test
    fun sanitizeTokenizePipelineParityOnIcu() {
        assertEquals(
            listOf("<p>", "Hello", " ", "<strong>", "world</strong>!</p><p>Second", " ", "para.</p>"),
            ContentDiffer.tokenize(
                ContentDiffer.sanitize("<p>Hello <strong>world</strong>!</p><p>Second para.</p>"),
            ),
        )
    }

    /** Word-level diff on ICU: a markup change yields DELETE then INSERT of display text. */
    @Test
    fun diffMarkupChangeOnIcu() {
        val diff = ContentDiffer.diff("<p>alpha beta gamma</p>", "<p>alpha delta gamma</p>")
        assertNotNull(diff)
        assertTrue(diff!!.hasChanges)
        assertEquals("beta", diff.deletedText)
        assertEquals("delta", diff.insertedText)
    }

    /** Diff null-contracts on ICU: equal tokens → null; blank/null original → null. */
    @Test
    fun diffNullContractsOnIcu() {
        val html = "<p>identical <strong>content</strong> here</p>"
        assertNull(ContentDiffer.diff(html, html))
        assertNull(ContentDiffer.diff(null, "<p>x</p>"))
        assertNull(ContentDiffer.diff("   ", "<p>x</p>"))
    }
}
