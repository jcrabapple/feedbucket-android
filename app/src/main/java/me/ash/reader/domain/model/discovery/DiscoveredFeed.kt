package me.ash.reader.domain.model.discovery

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** FD1 — wire format of a discovered or parsed feed. */
enum class FeedFormat {
    RSS,
    ATOM,
    JSON_FEED,
    PODCAST,
    UNKNOWN,
}

/** FD1 — where a [DiscoveredFeed] candidate came from. */
enum class DiscoverySource {
    /** The entered URL was itself a parseable feed. */
    DIRECT,

    /** An HTML `link rel=alternate` advertisement on the fetched page. */
    HTML_ALTERNATE,

    /** A well-known relative path probe (for example `/feed` or `/rss.xml`). */
    WELL_KNOWN_PATH,

    /** A platform resolver (Reddit, Mastodon, GitHub, YouTube, Substack). */
    PLATFORM_SHORTCUT,

    /** The keyless Feedly feed-search API. */
    FEEDLY_SEARCH,

    /** The keyless iTunes podcast-search API. */
    ITUNES_SEARCH,

    /** The bundled curated starter catalog. */
    STARTER_CATALOG,
}

/**
 * FD1 — one feed candidate surfaced by discovery. [url] is always absolute;
 * [format] is the advertised or sniffed wire format; [subscribers] is only
 * populated by sources that report it (Feedly). Equality is by value so
 * candidates can be de-duplicated with plain collection operations.
 */
data class DiscoveredFeed(
    val url: String,
    val title: String,
    val description: String? = null,
    val siteUrl: String? = null,
    val iconUrl: String? = null,
    val format: FeedFormat = FeedFormat.UNKNOWN,
    val subscribers: Int? = null,
    val source: DiscoverySource,
) {

    companion object {

        /**
         * Heuristic wire-format guess from a URL shape alone (used when the
         * body was not fetched: Feedly results, platform shortcuts, catalog
         * entries). Suffix rules win over the generic `.xml` rule; YouTube's
         * channel feed is Atom despite its `.xml` suffix.
         */
        fun guessFormat(url: String): FeedFormat {
            val parsed = url.trim().toHttpUrlOrNull()
            val host = parsed?.host?.lowercase() ?: ""
            val path = (parsed?.encodedPath ?: "").ifEmpty {
                url.trim().substringBefore('?').substringAfter("://", "").substringAfter('/', "")
            }.lowercase()
            return when {
                host.endsWith("youtube.com") && path == "/feeds/videos.xml" -> FeedFormat.ATOM
                path.endsWith(".json") -> FeedFormat.JSON_FEED
                path.endsWith(".atom") -> FeedFormat.ATOM
                path.endsWith(".rss") || path.endsWith(".xml") -> FeedFormat.RSS
                path.endsWith("/feed") || path.endsWith("/rss") -> FeedFormat.RSS
                else -> FeedFormat.UNKNOWN
            }
        }

        /**
         * Canonical form for de-duplication: fragment dropped, host and port
         * normalized by the okhttp parser, one trailing path slash removed
         * (so `/feed` and `/feed/` collapse). Query strings and path case are
         * preserved — feeds such as YouTube channel URLs are
         * query-parameter- and case-sensitive.
         */
        fun normalizeUrl(url: String): String {
            val trimmed = url.trim()
            val parsed = trimmed.toHttpUrlOrNull() ?: return trimmed
            val rebuilt = parsed.newBuilder().fragment(null).build()
            val text = rebuilt.toString()
            return if (rebuilt.encodedPath.length > 1 && text.endsWith("/")) {
                text.dropLast(1)
            } else {
                text
            }
        }
    }
}
