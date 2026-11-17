package me.ash.reader.domain.model.diff

import com.github.difflib.DiffUtils
import com.github.difflib.patch.DeltaType
import com.github.difflib.patch.Patch
import java.util.regex.Pattern
import org.jsoup.Jsoup
import org.jsoup.nodes.Comment
import org.jsoup.nodes.Entities
import org.jsoup.nodes.Node
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Whitelist

/**
 * S22 — content diff (D8). Word-level diff between sanitized
 * `originalContent` and sanitized current `rawDescription`, using the web
 * tokenization (`<[^>]+>|\S+|\s+`, web `feedbucket/diff.py:_TOKEN`) and a
 * longest-common-subsequence differ (java-diff-utils 4.x, Apache-2.0 —
 * the only Wave E dependency addition, license check in
 * `build/recon/java-diff-utils-license.txt`).
 *
 * Deliberate improvements over web (pinned by `DiffTokenizerTest`):
 *  - web returns None on equal LENGTH; Android compares TOKEN LISTS, so
 *    equal-length-but-different text still diffs (strict improvement).
 *  - equal token lists → no diff.
 *  - computation failure → feature silently unavailable for that article
 *    (web exception→None parity): [diff] returns null.
 *
 * The sanitizer mirrors web `sanitize_html` (security.py ALLOWED_TAGS /
 * ALLOWED_ATTRIBUTES / url schemes / `link_rel` / strip_comments) with
 * Jsoup — the same parser the app's reader pipeline already uses. jsoup
 * arrives on the compile classpath via readability4j (exactly like
 * `HtmlToComposable.kt` and `RssHelper.kt` already use it); no new
 * dependency beyond java-diff-utils. Two documented adaptations:
 *  - script/style ELEMENTS are removed with their content before cleaning
 *    (ammonia/nh3 semantics; Jsoup's Cleaner would keep their text).
 *  - disallowed URL schemes drop the attribute on both sides.
 */
object ContentDiffer {

    /**
     * Web `_TOKEN` parity (feedbucket/diff.py:8). Compiled through
     * `java.util.regex.Pattern` because Kotlin's [RegexOption] has no
     * UNICODE_CHARACTER_CLASS: the flag keeps `\s` semantics identical to
     * Python 3 str patterns. Two explicit additions pin Python `str.isspace`
     * characters Java's Unicode whitespace class omits: `\u00A0` (NBSP)
     * and `\u001C-\u001F` (information separators) — all pinned by
     * recorded fixtures (`nbsp_raw`/`em_space`, `tokenRegexParityPythonWhitespaceSeparators`).
     */
    val TOKEN_REGEX: Regex =
        Pattern.compile(
            "<[^>]+>|[^\\s\\u00A0\\u001C-\\u001F]+|[\\s\\u00A0\\u001C-\\u001F]+",
            Pattern.UNICODE_CHARACTER_CLASS,
        ).toRegex()

    enum class SegmentKind { EQUAL, DELETE, INSERT }

    /** One renderable run of the diff; [text] is readable display text. */
    data class Segment(val kind: SegmentKind, val text: String)

    /** The computed diff: ordered runs over the sanitized token streams. */
    data class ContentDiff(val segments: List<Segment>) {
        val hasChanges: Boolean get() = segments.any { it.kind != SegmentKind.EQUAL }
        val deletedText: String
            get() = segments.filter { it.kind == SegmentKind.DELETE }.joinToString("") { it.text }
        val insertedText: String
            get() = segments.filter { it.kind == SegmentKind.INSERT }.joinToString("") { it.text }
    }

    /** Injectable differ seam (test pins the failure→null contract). */
    private val DEFAULT_DIFFER: (List<String>, List<String>) -> Patch<String> =
        { original, current -> DiffUtils.diff(original, current) }

    /** Web ALLOWED_TAGS (security.py:26-32). */
    private val ALLOWED_TAGS = listOf(
        "a", "abbr", "article", "aside", "b", "blockquote", "br", "caption", "code",
        "dd", "del", "details", "div", "dl", "dt", "em", "figcaption", "figure", "h1",
        "h2", "h3", "h4", "h5", "h6", "hr", "i", "img", "ins", "kbd", "li", "mark",
        "ol", "p", "pre", "q", "s", "section", "small", "span", "strong", "sub",
        "summary", "sup", "table", "tbody", "td", "tfoot", "th", "thead", "tr", "ul",
    )

    private val SAFELIST: Whitelist =
        Whitelist()
            .addTags(*ALLOWED_TAGS.toTypedArray())
            .addAttributes("a", "href", "title")
            .addAttributes("img", "src", "alt", "width", "height")
            .addAttributes("th", "colspan", "rowspan")
            .addAttributes("td", "colspan", "rowspan")
            .addProtocols("a", "href", "http", "https", "mailto")
            .addProtocols("img", "src", "http", "https")
            // web link_rel parity: nh3 adds rel="noopener noreferrer".
            .addEnforcedAttribute("a", "rel", "noopener noreferrer")

    /** Android mirror of web `sanitize_html` (security.py:172-180). */
    fun sanitize(html: String): String {
        val dirty = Jsoup.parse(html)
        // nh3 emits a tight fragment: no newlines between block elements.
        // Jsoup pretty-prints by default — disable on BOTH documents.
        dirty.outputSettings().prettyPrint(false)
        // ammonia removes script/style WITH their content; Jsoup's Cleaner
        // would keep the text — remove explicitly first.
        dirty.select("script,style").remove()
        stripComments(dirty)
        val clean = Cleaner(SAFELIST).clean(dirty)
        clean.outputSettings().prettyPrint(false)
        return clean.body()?.html() ?: ""
    }

    private fun stripComments(node: Node) {
        // jsoup 1.11 throws UnsupportedOperationException for
        // childNodes() on leaf nodes — only Elements can nest comments.
        if (node !is org.jsoup.nodes.Element) return
        for (child in node.childNodes().toList()) {
            if (child is Comment) child.remove() else stripComments(child)
        }
    }

    /** Web `_TOKEN.findall` parity over the given (already sanitized) HTML. */
    fun tokenize(html: String): List<String> =
        TOKEN_REGEX.findAll(html).map { it.value }.toList()

    /**
     * Display text for one sanitized-HTML token (review round 1 finding 3):
     * the pane must render DECODED article text, never markup. Tag fragments
     * (`<...>`, including tags glued inside word tokens by the web
     * tokenizer, e.g. `gamma</p>`) are removed — block-level tags leave a
     * single space so adjacent paragraphs/words don't fuse; inline tags
     * leave nothing. Entity refs in the remaining text are decoded
     * (`&amp;` → `&`).
     */
    fun displayToken(token: String): String =
        TAG_FRAGMENT_REGEX.replace(token) { match -> if (isBlockTag(match.value)) " " else "" }
            .let { Entities.unescape(it) }

    private val TAG_FRAGMENT_REGEX = Regex("<[^>]+>")

    /** Block-level tags: removed fragments become a single space. */
    private val BLOCK_TAGS =
        setOf(
            "address", "article", "aside", "blockquote", "br", "caption", "dd", "details",
            "div", "dl", "dt", "fieldset", "figcaption", "figure", "footer", "form",
            "h1", "h2", "h3", "h4", "h5", "h6", "header", "hr", "li", "main", "nav",
            "ol", "p", "pre", "section", "summary", "table", "tbody", "td", "tfoot",
            "th", "thead", "tr", "ul",
        )

    private fun isBlockTag(tagToken: String): Boolean {
        val name =
            tagToken.removePrefix("<").removeSuffix(">")
                .trimStart('/').takeWhile { it.isLetterOrDigit() }
                .lowercase()
        return name in BLOCK_TAGS
    }

    /**
     * S22 contract: null when [original] is null/blank, when the sanitized
     * token sequences are EQUAL (web returns None on equal length; Android
     * requires equal tokens — the strict improvement), or when computation
     * fails (web exception→None parity → feature silently unavailable).
     */
    fun diff(
        original: String?,
        current: String?,
        differ: (List<String>, List<String>) -> Patch<String> = DEFAULT_DIFFER,
    ): ContentDiff? {
        if (original.isNullOrBlank()) return null
        return runCatching {
                val originalTokens = tokenize(sanitize(original))
                val currentTokens = tokenize(sanitize(current ?: ""))
                if (originalTokens == currentTokens) return null
                buildSegments(originalTokens, differ(originalTokens, currentTokens))
            }
            .getOrNull()
            ?.takeIf { it.hasChanges }
    }

    /** Join a token range into its readable display text (see [displayToken]). */
    private fun List<String>.toDisplayText(): String = joinToString("") { displayToken(it) }

    private fun buildSegments(originalTokens: List<String>, patch: Patch<String>): ContentDiff {
        val segments = mutableListOf<Segment>()
        var cursor = 0
        for (delta in patch.deltas) {
            val source = delta.source
            val target = delta.target
            if (source.position > cursor) {
                segments += Segment(SegmentKind.EQUAL, originalTokens.subList(cursor, source.position).toDisplayText())
            }
            when (delta.type) {
                DeltaType.CHANGE -> {
                    segments += Segment(SegmentKind.DELETE, source.lines.toDisplayText())
                    segments += Segment(SegmentKind.INSERT, target.lines.toDisplayText())
                }
                DeltaType.DELETE -> segments += Segment(SegmentKind.DELETE, source.lines.toDisplayText())
                DeltaType.INSERT -> segments += Segment(SegmentKind.INSERT, target.lines.toDisplayText())
                DeltaType.EQUAL -> segments += Segment(SegmentKind.EQUAL, source.lines.toDisplayText())
            }
            cursor = source.position + source.lines.size
        }
        if (cursor < originalTokens.size) {
            segments += Segment(SegmentKind.EQUAL, originalTokens.subList(cursor, originalTokens.size).toDisplayText())
        }
        return ContentDiff(segments)
    }
}
