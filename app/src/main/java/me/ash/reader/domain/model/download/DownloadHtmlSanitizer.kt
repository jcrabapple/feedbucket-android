package me.ash.reader.domain.model.download

import java.util.Locale
import me.ash.reader.domain.model.article.ArticleIdentity
import org.jsoup.Jsoup
import org.jsoup.safety.Whitelist

/**
 * S30a — sanitization for DOWNLOADED content, applied at write time (D16).
 * Jsoup `Whitelist.relaxed()` (jsoup is pinned at 1.11.2 by the app
 * resolution strategy; `Whitelist` is the pre-1.15 class name) extended to
 * allow `class` attributes and http/https/mailto protocols ONLY; `<script>`,
 * event-handler attributes and javascript:/data:/file:/intent: URIs are
 * stripped. Also rewrites `<img src>` to local `download_asset` paths so the
 * offline render issues ZERO network calls.
 */
object DownloadHtmlSanitizer {

    private val safelist: Whitelist =
        Whitelist.relaxed()
            .addAttributes(":all", "class")
            // relaxed already limits a[href] to http/https/mailto and
            // img[src] to http/https; pin every protocol-bearing attribute
            // explicitly so the "only" in S30a is enforced, not assumed.
            .apply {
                listOf("a" to "href", "img" to "src", "blockquote" to "cite", "q" to "cite")
                    .forEach { (tag, attr) -> addProtocols(tag, attr, "http", "https", "mailto") }
            }

    fun sanitize(html: String): String = Jsoup.clean(html, safelist)

    /**
     * Rewrites every http(s) `<img src>` through [localPathFor] (which maps a
     * URL to its committed local asset path, or null when it did not land
     * locally — the src is then REMOVED so the offline render never touches
     * the network; missing images render as placeholders, S27a).
     *
     * Returns the rewritten HTML and the ordered, de-duplicated candidate URL
     * list (the asset download set, capped later by DownloadPolicy).
     */
    fun rewriteImages(html: String, localPathFor: (String) -> String?): Pair<String, List<String>> {
        val doc = Jsoup.parseBodyFragment(sanitize(html))
        val candidates = linkedMapOf<String, Unit>()
        for (img in doc.select("img")) {
            val src = img.attr("src")
            if (!src.startsWith("http://") && !src.startsWith("https://")) {
                img.remove()
                continue
            }
            candidates[src] = Unit
            val local = localPathFor(src)
            if (local == null) {
                img.removeAttr("src")
            } else {
                img.attr("src", local)
            }
        }
        return doc.body().html() to candidates.keys.toList()
    }

    /** Deterministic local asset name: sha256(url) + lowercase extension. */
    fun assetFileName(url: String): String {
        val ext =
            url.substringBefore('?')
                .substringAfterLast('.', "")
                .lowercase(Locale.ROOT)
                .filter { it in 'a'..'z' || it in '0'..'9' }
                .take(8)
        return ArticleIdentity.sha256Hex(url) + (if (ext.isNotEmpty()) ".$ext" else "")
    }

    // S19 challenge markers — a fetched document whose title/head matches a
    // bot wall is treated as a download failure, never as content.
    private val challengeMarkers =
        listOf("just a moment", "access denied", "cloudflare", "captcha")

    fun isChallenge(documentHead: String): Boolean {
        if (documentHead.isBlank()) return false
        val head = documentHead.take(500).lowercase(Locale.ROOT)
        return challengeMarkers.any { it in head }
    }
}
