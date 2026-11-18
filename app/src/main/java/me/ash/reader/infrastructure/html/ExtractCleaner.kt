package me.ash.reader.infrastructure.html

import java.util.Locale
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * E15 — deterministic post-cleaner applied to the Readability4J output
 * BEFORE the length guard (X3 wires it; pure here). Ports the four
 * 2026-01-14 Wolfram-writings cleanup lessons that readability4j 1.0.8
 * does NOT cover (verified against its `RegExUtilExtended` patterns):
 *
 *  1. Title dedupe (replaces `RssHelper`'s exact-h1 removal): drop the
 *     FIRST `h1`/`h2` whose normalized text matches the entry title —
 *     normalization = strip tags, `lowercase(Locale.ROOT)`, every
 *     non-`[\w\s]` run → one space, collapse whitespace (port of
 *     `_norm_title`, `extract.py:193–196`); match = equality OR the
 *     site-suffix prefix rule `longer.startsWith(shorter) &&
 *     shorter.length >= 0.6 * longer.length` (port of
 *     `_drop_duplicate_title:214–215`). Only the first matching heading is
 *     removed; headingless or non-matching output is untouched.
 *  2. YAML frontmatter: drop `<p>` blocks whose stripped text matches
 *     `^\s*-{3,}\s*(title|description|canonical_url|date|author|slug|tags|
 *     categories|layout|permalink|id)\s*:` case-insensitively (port of
 *     `_FRONTMATTER_KEY` / `_strip_frontmatter`, `extract.py:169–190`).
 *     Prose that merely contains `---` is kept (the key alternation must
 *     follow the dash run).
 *  3. TOC chrome: remove elements whose `class`/`id` contains the token
 *     `toc-` or `table-of-contents` (substring match on the lowercased
 *     attribute values, mirroring web's `hint in merged` — the two
 *     JUNK_HINTS readability4j lacks, `extract.py:30`).
 *  4. Page-level dateline: remove `<time>` elements whose `class` carries
 *     an EXACT whitespace-split token in {date, post-date, entry-date,
 *     published, updated} (port of `_is_junk:85–92`); inline prose `<time>`
 *     without those classes is kept.
 *
 * Web rules NOT ported, with reasons (binding per E15 — do not "fix" these
 * in review): `<head>`/`<title>` subtree dropping and html/body unwrapping
 * are structurally impossible in the jsoup/Readability output (the fragment
 * is a body subtree; the web rule exists because nh3 keeps disallowed tags'
 * TEXT and the web cleaner is regex-based); void-tag stack safety is N/A
 * for a DOM parser (research correction C3).
 *
 * Output is well-formed by construction: the fragment is parsed and
 * re-serialized by jsoup (round-trip pinned in `ExtractCleanerTest`).
 */
object ExtractCleaner {

    /** E15.2 — web `_FRONTMATTER_KEY` (`extract.py:169–173`). */
    private val FRONTMATTER_KEY = Regex(
        "^\\s*-{3,}\\s*(title|description|canonical_url|date|author|slug|tags|" +
            "categories|layout|permalink|id)\\s*:",
        RegexOption.IGNORE_CASE,
    )

    /** E15.1 — every non-`[\w\s]` RUN → one space (`(?U)` = Python's Unicode `\w`/`\s`). */
    private val NON_WORD_RUN = Regex("(?U)[^\\w\\s]+")
    private val WHITESPACE_RUN = Regex("(?U)\\s+")

    /** E15.3 — the two JUNK_HINTS readability4j lacks (`extract.py:30`). */
    private val TOC_TOKENS = listOf("toc-", "table-of-contents")

    /** E15.4 — EXACT class tokens of web `_is_junk:85–92`. */
    private val DATELINE_CLASS_TOKENS = setOf("date", "post-date", "entry-date", "published", "updated")

    /**
     * Cleans [html] (the Readability output fragment) against entry [title]
     * with the E15 rules in order 1→2→3→4. Returns the serialized body
     * fragment; a blank normalized [title] disables rule 1 only (web
     * `if not target: return html`).
     */
    fun clean(html: String, title: String): String {
        val doc = Jsoup.parseBodyFragment(html)
        doc.outputSettings().prettyPrint(false)
        dropDuplicateTitle(doc, title)
        stripFrontmatter(doc)
        removeTocChrome(doc)
        removePageDatelines(doc)
        return doc.body().html()
    }

    /** E15.1 — first matching `h1`/`h2` only; everything else untouched. */
    private fun dropDuplicateTitle(doc: Document, title: String) {
        val target = normalizeTitle(title)
        if (target.isEmpty()) return // web `if not target: return html`
        // Jsoup select returns document order; h1 and h2 interleaved.
        for (heading in doc.select("h1, h2")) {
            val normalized = normalizeTitle(heading.text())
            if (normalized.isEmpty()) continue // web `if not heading: return html`
            if (titleMatches(normalized, target)) {
                heading.remove()
                return // ONLY the first match
            }
        }
    }

    /**
     * Web `_drop_duplicate_title:214–215`: equality OR the site-suffix
     * prefix rule with the 0.6 length ratio on the LONGER string.
     */
    private fun titleMatches(heading: String, target: String): Boolean {
        if (heading == target) return true
        val shorter: String
        val longer: String
        if (heading.length <= target.length) {
            shorter = heading
            longer = target
        } else {
            shorter = target
            longer = heading
        }
        return longer.startsWith(shorter) && shorter.length >= 0.6 * longer.length
    }

    /** E15.1 normalization — port of web `_norm_title` (`extract.py:193–196`). */
    internal fun normalizeTitle(text: String): String {
        val lowered = text.lowercase(Locale.ROOT)
        val depunctuated = NON_WORD_RUN.replace(lowered, " ")
        return WHITESPACE_RUN.replace(depunctuated, " ").trim()
    }

    /** E15.2 — `<p>` blocks that are raw markdown front matter. */
    private fun stripFrontmatter(doc: Document) {
        for (p in doc.select("p").toList()) {
            if (FRONTMATTER_KEY.containsMatchIn(p.text())) {
                p.removeIfAttached()
            }
        }
    }

    /** E15.3 — TOC subtrees keyed off class/id substrings. */
    private fun removeTocChrome(doc: Document) {
        for (element in doc.select("[class],[id]").toList()) {
            val merged = (element.attr("class") + " " + element.attr("id"))
                .lowercase(Locale.ROOT)
            if (TOC_TOKENS.any { merged.contains(it) }) {
                element.removeIfAttached()
            }
        }
    }

    /** E15.4 — page-level dateline `<time class="post-date …">`. */
    private fun removePageDatelines(doc: Document) {
        for (time in doc.select("time[class]").toList()) {
            val tokens = time.attr("class").lowercase(Locale.ROOT)
                .split(WHITESPACE_RUN)
                .filter { it.isNotEmpty() }
            if (tokens.any { it in DATELINE_CLASS_TOKENS }) {
                time.removeIfAttached()
            }
        }
    }

    /**
     * Nested matches can already be detached when an ancestor was removed
     * first; jsoup 1.11.2's `remove()` requires a parent, so skip detached
     * nodes instead of throwing.
     */
    private fun Element.removeIfAttached() {
        if (parent() != null) remove()
    }
}
