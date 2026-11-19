package me.ash.reader.infrastructure.html

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.jsoup.Jsoup

/**
 * E15 — deterministic post-cleaner rules 1–4 on SYNTHETIC HTML fixtures
 * only (D16), including the mandated Wolfram-style frontmatter fixture and
 * the "Title — SiteName" suffix fixture. Web ports pinned:
 * `_norm_title`/`_drop_duplicate_title` (extract.py:193–217),
 * `_FRONTMATTER_KEY`/`_strip_frontmatter` (extract.py:169–190),
 * JUNK_HINTS `toc-`/`table-of-contents` (extract.py:30), page-level
 * `<time>` class tokens (extract.py:85–92).
 *
 * REDCHECKs (recorded in the review handoff):
 *  - delete rule 2 (`stripFrontmatter` call) → `frontmatterParagraphDropped`
 *    red;
 *  - delete the suffix branch in `titleMatches` (equality only) →
 *    `siteSuffixHeadingDroppedByPrefixRule` red.
 */
class ExtractCleanerTest {

    private fun h1Count(html: String): Int = Regex("<h1[ >]").findAll(html).count()

    // ---- Rule 1: title dedupe ----

    @Test
    fun firstH1ExactTitleDropped() {
        val out = ExtractCleaner.clean(
            "<h1>My Post</h1><p>Body prose stays here.</p>",
            title = "My Post",
        )
        assertFalse(out.contains("<h1"))
        assertTrue(out.contains("Body prose stays here."))
    }

    @Test
    fun siteSuffixHeadingDroppedByPrefixRule() {
        // D16 fixture: "Title — SiteName". Normalized: target
        // "the case for extraction" (23) vs heading
        // "the case for extraction snakepit" (32); startsWith ✓ and
        // 23 >= 0.6 × 32 = 19.2 ✓ → dropped (extract.py:214–215).
        val out = ExtractCleaner.clean(
            "<h1>The Case for Extraction — Snakepit</h1><p>Real prose.</p>",
            title = "The Case for Extraction",
        )
        assertFalse(out.contains("<h1"))
        assertTrue(out.contains("Real prose."))
    }

    @Test
    fun ratioJustBelowSixtyPercentKeepsHeading() {
        // Normalized heading "abcdefghij" (10) vs target "abcde" (5):
        // prefix ✓ but 5 >= 0.6 × 10 = 6.0 FAILS → heading kept.
        val kept = ExtractCleaner.clean("<h1>abcdefghij</h1><p>x</p>", title = "abcde")
        assertTrue(kept.contains("<h1>abcdefghij</h1>"))
        // Exact boundary: 6 >= 0.6 × 10 = 6.0 → dropped (>= semantics).
        val dropped = ExtractCleaner.clean("<h1>abcdefghij</h1><p>x</p>", title = "abcdef")
        assertFalse(dropped.contains("<h1"))
    }

    @Test
    fun h2VariantDroppedButH3IsNotACandidate() {
        val out = ExtractCleaner.clean("<h2>My Post</h2><p>x</p>", title = "My Post")
        assertFalse(out.contains("<h2"))
        // Rule 1 covers h1|h2 ONLY (web `_HEADING = <(h[12])…>`).
        val h3 = ExtractCleaner.clean("<h3>My Post</h3><p>x</p>", title = "My Post")
        assertTrue(h3.contains("<h3>My Post</h3>"))
    }

    @Test
    fun nonMatchingFirstHeadingKept() {
        val out = ExtractCleaner.clean(
            "<h1>Weekly Digest: Issue 7</h1><p>x</p>",
            title = "My Post",
        )
        assertTrue(out.contains("Weekly Digest: Issue 7"))
    }

    @Test
    fun onlyFirstMatchingHeadingRemoved() {
        val out = ExtractCleaner.clean(
            "<h1>My Post</h1><p>middle</p><h1>My Post</h1>",
            title = "My Post",
        )
        assertEquals("only the FIRST match is removed", 1, h1Count(out))
        assertTrue(out.contains("middle"))
    }

    @Test
    fun blankTitleDisablesRuleOne() {
        // Web `if not target: return html` — no title, no dedupe.
        val out = ExtractCleaner.clean("<h1>My Post</h1><p>x</p>", title = "   ")
        assertTrue(out.contains("<h1>My Post</h1>"))
    }

    @Test
    fun headinglessOutputUntouched() {
        val out = ExtractCleaner.clean("<p>Just prose, no headings at all.</p>", title = "My Post")
        assertTrue(out.contains("Just prose, no headings at all."))
    }

    // ---- Rule 1, non-ASCII (mirrors ExtractCleanerInstrumentedTest: the
    // JVM and Android ICU must normalize these fixtures IDENTICALLY — the
    // 0.1.3 `(?U)` crash shipped because only the JVM ran the cleaner) ----

    @Test
    fun nonAsciiTitleDedupeSameAsAndroid() {
        // "café müller schreibt" (20) vs "café müller schreibt sitename"
        // (29): 20 >= 0.6 × 29 = 17.4 ✓ → dropped. With ASCII-only `\w`
        // the é/ü would be punctuated to spaces and the ratio flips — this
        // pins the Unicode-class intent of the old `(?U)\w` on the JVM.
        val accent = ExtractCleaner.clean(
            "<h1>Café Müller schreibt — SiteName</h1><p>Accented prose.</p>",
            title = "Café Müller schreibt",
        )
        assertFalse(accent.contains("<h1"))
        assertTrue(accent.contains("Accented prose."))

        // "статья о вычислениях" (20) vs "статья о вычислениях сайт" (25):
        // 20 >= 15 ✓ → dropped.
        val cyrillic = ExtractCleaner.clean(
            "<h1>Статья о вычислениях — Сайт</h1><p>Cyrillic prose.</p>",
            title = "Статья о вычислениях",
        )
        assertFalse(cyrillic.contains("<h1"))
        assertTrue(cyrillic.contains("Cyrillic prose."))

        // CJK: brackets 「」 (Ps/Pe) → spaces; 13 vs 18: 13 >= 10.8 ✓.
        val cjk = ExtractCleaner.clean(
            "<h1>「東京物語の全文掲載について」— Site</h1><p>CJK prose.</p>",
            title = "東京物語の全文掲載について",
        )
        assertFalse(cjk.contains("<h1"))
        assertTrue(cjk.contains("CJK prose."))

        // A non-matching non-ASCII heading is KEPT.
        val kept = ExtractCleaner.clean(
            "<h1>Completamente differente</h1><p>x</p>",
            title = "Unrelated title",
        )
        assertTrue(kept.contains("Completamente differente"))
    }

    @Test
    fun normalizeTitleUnicodeClassesSameAsAndroid() {
        assertEquals("café müller", ExtractCleaner.normalizeTitle("Café — Müller"))
        assertEquals("café müller", ExtractCleaner.normalizeTitle("Café\u00A0Müller!!"))
        assertEquals("こんにちは 世界", ExtractCleaner.normalizeTitle("こんにちは、世界！"))
        assertEquals("слово дело", ExtractCleaner.normalizeTitle("Слово  —  ДЕЛО"))
        // Digits and underscore are word characters (\p{N}, _).
        assertEquals("abc_123 def", ExtractCleaner.normalizeTitle("abc_123 : def"))
    }

    // ---- Rule 2: YAML frontmatter ----

    @Test
    fun frontmatterParagraphDropped() {
        // D16 Wolfram-style fixture: the post's raw YAML rendered as a
        // visible <p> block (extract.py `_strip_frontmatter` lesson).
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

    @Test
    fun frontmatterMultilineParagraphDropped() {
        val out = ExtractCleaner.clean(
            "<p>---<br>title: X<br>date: 2026-01-01<br>---</p><p>Prose.</p>",
            title = "Other",
        )
        assertFalse(out.contains("date: 2026"))
        assertTrue(out.contains("Prose."))
    }

    @Test
    fun proseContainingDashesIsKept() {
        // `---` in ordinary prose (and even a leading dash-run WITHOUT a
        // frontmatter key) must survive — the key alternation is required.
        val out = ExtractCleaner.clean(
            "<p>The final score was 5 --- 3, a margin that surprised everyone.</p>" +
                "<p>--- but this is just prose, not frontmatter ---</p>",
            title = "Unrelated",
        )
        assertTrue(out.contains("a margin that surprised everyone"))
        assertTrue(out.contains("just prose, not frontmatter"))
    }

    // ---- Rule 3: TOC chrome ----

    @Test
    fun tocSubtreesDropped() {
        val out = ExtractCleaner.clean(
            "<div class=\"toc-nav\"><a href=\"#c1\">Chapter 1</a>" +
                "<ul><li>nested child</li></ul></div>" +
                "<nav id=\"table-of-contents\">TOC entries</nav>" +
                "<p>Prose stays.</p>",
            title = "Unrelated",
        )
        assertFalse(out.contains("Chapter 1"))
        assertFalse(out.contains("nested child")) // whole SUBTREE gone
        assertFalse(out.contains("TOC entries"))
        assertTrue(out.contains("Prose stays."))
    }

    @Test
    fun tocMatchIsCaseInsensitiveSubstring() {
        val out = ExtractCleaner.clean(
            "<div class=\"TOC-Sidebar\">upper junk</div>" +
                "<aside id=\"post-table-of-contents-x\">id junk</aside>" +
                "<p class=\"tocless-note\">kept prose</p>",
            title = "Unrelated",
        )
        assertFalse(out.contains("upper junk"))
        assertFalse(out.contains("id junk"))
        // "tocless-note" contains "toc" but NOT "toc-" → kept (web
        // substring semantics `hint in merged`).
        assertTrue(out.contains("kept prose"))
    }

    // ---- Rule 4: page-level dateline ----

    @Test
    fun datelineTimeDroppedInlineTimeKept() {
        val out = ExtractCleaner.clean(
            "<time class=\"post-date\" datetime=\"2026-01-01\">January 1, 2026</time>" +
                "<p>The <time datetime=\"2026-02-02\">festival</time> continued.</p>",
            title = "Unrelated",
        )
        assertFalse(out.contains("January 1, 2026"))
        assertTrue("inline prose <time> without the classes is kept", out.contains("festival"))
    }

    @Test
    fun datelineUsesExactClassTokens() {
        val out = ExtractCleaner.clean(
            "<time class=\"entry-date published\">multi token</time>" +
                "<time class=\"datetime\">2026</time>" +
                "<time class=\"update\">u</time>" +
                "<p>prose</p>",
            title = "Unrelated",
        )
        assertFalse(out.contains("multi token"))
        // "datetime" / "update" are NOT exact tokens of
        // {date, post-date, entry-date, published, updated} → kept.
        assertTrue(out.contains("2026"))
        assertTrue(out.contains(">u<"))
    }

    // ---- Well-formedness ----

    @Test
    fun outputRemainsWellFormedJsoupRoundTrip() {
        // Unbalanced/implicit markup in → the jsoup-serialized output must
        // re-parse byte-identically (proves well-formedness; no regex
        // surgery anywhere in the cleaner).
        val messy = "<div><p>Unclosed paragraph<p>Second one<span>inline</span></div>" +
            "<div class=\"toc-nav\">junk</div>" +
            "<time class=\"post-date\">Jan 1</time>" +
            "<h1>Title &amp; Entities</h1>"
        val out = ExtractCleaner.clean(messy, title = "Unrelated")
        val reparsed = Jsoup.parseBodyFragment(out)
        reparsed.outputSettings().prettyPrint(false)
        assertEquals("clean() output is a stable, well-formed fragment", out, reparsed.body().html())
        assertTrue(out.contains("Unclosed paragraph"))
        assertTrue(out.contains("Title &amp; Entities"))
    }
}
