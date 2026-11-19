package me.ash.reader.infrastructure.html

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E15 on ANDROID's ICU-backed `java.util.regex` — closes the coverage gap
 * that shipped 0.1.3: no instrumented test ever loaded [ExtractCleaner], so
 * the JVM-only `(?U)` inline flag in NON_WORD_RUN/WHITESPACE_RUN survived
 * green host-JVM unit tests and then crashed EVERY feed open on-device with
 * `ExceptionInInitializerError` → `PatternSyntaxException … near index 3
 * (?U)[^\w\s]+` at `ExtractCleaner.<clinit>` (real-world red witness:
 * Jason's S26, API 37). The patterns now use explicit Unicode classes
 * (`\p{L}\p{M}\p{N}_`, `[\s\p{Z}]`) that behave identically on the JVM and
 * on ICU; [classInitializesOnAndroidIcu] is the permanent crash witness and
 * the non-ASCII cases pin ICU's Unicode normalization to the same results
 * as the JVM `ExtractCleanerTest` (same fixtures, same expectations).
 *
 * Synthetic HTML fixtures only (D16).
 */
@RunWith(AndroidJUnit4::class)
class ExtractCleanerInstrumentedTest {

    private fun h1Count(html: String): Int = Regex("<h1[ >]").findAll(html).count()

    /** Crash witness: on 0.1.3 even TOUCHING the object threw on Android. */
    @Test
    fun classInitializesOnAndroidIcu() {
        val out = ExtractCleaner.clean("<p>Body prose survives.</p>", title = "Unrelated")
        assertTrue(out.contains("Body prose survives."))
    }

    /** E15.1(b) — exact title dedupe plus the "Title — SiteName" suffix rule. */
    @Test
    fun titleDedupeExactAndSiteSuffix() {
        val exact = ExtractCleaner.clean(
            "<h1>My Post</h1><p>Body prose stays here.</p>",
            title = "My Post",
        )
        assertFalse(exact.contains("<h1"))
        assertTrue(exact.contains("Body prose stays here."))

        // Normalized: target "the case for extraction" (23) vs heading
        // "the case for extraction snakepit" (32); startsWith ✓ and
        // 23 >= 0.6 × 32 = 19.2 ✓ → dropped (extract.py:214–215). The
        // em dash is punctuation → one space on ICU exactly as on the JVM.
        val suffix = ExtractCleaner.clean(
            "<h1>The Case for Extraction — Snakepit</h1><p>Real prose.</p>",
            title = "The Case for Extraction",
        )
        assertFalse(suffix.contains("<h1"))
        assertTrue(suffix.contains("Real prose."))

        // Only the FIRST matching heading is removed.
        val firstOnly = ExtractCleaner.clean(
            "<h1>My Post</h1><p>middle</p><h1>My Post</h1>",
            title = "My Post",
        )
        assertEquals(1, h1Count(firstOnly))
    }

    /**
     * E15.1(c) — NON-ASCII dedupe on ICU: accented Latin, Cyrillic and CJK
     * titles. Letters must survive normalization (with ASCII-only `\w` the
     * é/ü/и/漢 would be punctuated to spaces and the 0.6-ratio rule flips);
     * punctuation (—, 「」) must collapse to single spaces.
     */
    @Test
    fun nonAsciiTitleDedupeOnIcu() {
        // "café müller schreibt" (20) vs "café müller schreibt sitename"
        // (29): 20 >= 17.4 ✓ → dropped.
        val accent = ExtractCleaner.clean(
            "<h1>Café Müller schreibt — SiteName</h1><p>Accented prose.</p>",
            title = "Café Müller schreibt",
        )
        assertFalse(accent.contains("<h1"))
        assertTrue(accent.contains("Accented prose."))

        // "статья о вычислениях" (20) vs "статья о вычислениях сайт"
        // (25): 20 >= 15 ✓ → dropped.
        val cyrillic = ExtractCleaner.clean(
            "<h1>Статья о вычислениях — Сайт</h1><p>Cyrillic prose.</p>",
            title = "Статья о вычислениях",
        )
        assertFalse(cyrillic.contains("<h1"))
        assertTrue(cyrillic.contains("Cyrillic prose."))

        // CJK: brackets 「」 (Ps/Pe) → spaces; "東京物語の全文掲載について"
        // (13) vs "東京物語の全文掲載について site" (18): 13 >= 10.8 ✓ → dropped.
        val cjk = ExtractCleaner.clean(
            "<h1>「東京物語の全文掲載について」— Site</h1><p>CJK prose.</p>",
            title = "東京物語の全文掲載について",
        )
        assertFalse(cjk.contains("<h1"))
        assertTrue(cjk.contains("CJK prose."))

        // A non-matching non-ASCII heading is KEPT (dedupe is not a
        // blanket non-ASCII strip).
        val kept = ExtractCleaner.clean(
            "<h1>Completamente differente</h1><p>x</p>",
            title = "Unrelated title",
        )
        assertTrue(kept.contains("Completamente differente"))
    }

    /**
     * E15.1 normalization pinned directly on ICU: Unicode letters/marks/
     * numbers/underscore survive; punctuation runs and Unicode separators
     * (em dash, NBSP, ideographic comma) collapse to single spaces.
     */
    @Test
    fun normalizeTitleUnicodeClassesOnIcu() {
        assertEquals("café müller", ExtractCleaner.normalizeTitle("Café — Müller"))
        assertEquals("café müller", ExtractCleaner.normalizeTitle("Café\u00A0Müller!!"))
        assertEquals("こんにちは 世界", ExtractCleaner.normalizeTitle("こんにちは、世界！"))
        assertEquals("слово дело", ExtractCleaner.normalizeTitle("Слово  —  ДЕЛО"))
        // Digits and underscore are word characters (\p{N}, _).
        assertEquals("abc_123 def", ExtractCleaner.normalizeTitle("abc_123 : def"))
    }

    /** E15.2(d) — raw YAML frontmatter `<p>` dropped, prose kept. */
    @Test
    fun frontmatterParagraphDroppedOnAndroid() {
        val out = ExtractCleaner.clean(
            "<p>--- title: \"On Computation\" author: Someone date: 2026-05-05 " +
                "tags: [essay, math] ---</p>" +
                "<p>Real prose paragraph stays.</p>",
            title = "On Computation",
        )
        assertFalse(out.contains("author: Someone"))
        assertFalse(out.contains("---"))
        assertTrue(out.contains("Real prose paragraph stays."))
    }

    /** E15.3(e) — `toc-` / `table-of-contents` chrome subtrees dropped. */
    @Test
    fun tocChromeDroppedOnAndroid() {
        val out = ExtractCleaner.clean(
            "<div class=\"toc-nav\"><a href=\"#c1\">Chapter 1</a>" +
                "<ul><li>nested child</li></ul></div>" +
                "<nav id=\"table-of-contents\">TOC entries</nav>" +
                "<p class=\"tocless-note\">Prose stays.</p>",
            title = "Unrelated",
        )
        assertFalse(out.contains("Chapter 1"))
        assertFalse(out.contains("nested child"))
        assertFalse(out.contains("TOC entries"))
        assertTrue(out.contains("Prose stays."))
    }

    /** E15.4(f) — page-level dateline dropped; inline prose `<time>` kept. */
    @Test
    fun pageDatelineDroppedInlineTimeKeptOnAndroid() {
        val out = ExtractCleaner.clean(
            "<time class=\"post-date\" datetime=\"2026-01-01\">January 1, 2026</time>" +
                "<p>The <time datetime=\"2026-02-02\">festival</time> continued.</p>",
            title = "Unrelated",
        )
        assertFalse(out.contains("January 1, 2026"))
        assertTrue("inline prose <time> without the classes is kept", out.contains("festival"))
    }
}
