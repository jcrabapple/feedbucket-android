package me.ash.reader.domain.service

import com.rometools.rome.feed.synd.SyndFeed
import com.rometools.rome.io.SyndFeedInput
import com.rometools.rome.io.XmlReader
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import me.ash.reader.domain.model.discovery.DiscoveredFeed
import me.ash.reader.domain.model.discovery.DiscoveryQuery
import me.ash.reader.domain.model.discovery.DiscoveryResult
import me.ash.reader.domain.model.discovery.DiscoverySearchScope
import me.ash.reader.domain.model.discovery.FeedFormat
import me.ash.reader.domain.model.discovery.FeedPreview
import me.ash.reader.domain.model.discovery.DiscoverySource
import me.ash.reader.domain.model.discovery.PreviewEntry
import me.ash.reader.infrastructure.di.ExtractHttpClient
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.infrastructure.net.RedirectWalker
import me.ash.reader.infrastructure.net.SsrfGuard
import me.ash.reader.infrastructure.rss.JsonFeedParser
import me.ash.reader.infrastructure.rss.RssHelper
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okio.Buffer
import okio.BufferedSource

/**
 * FD1 — the feed discovery engine: one entry point ([discover]) over a raw
 * query that may be a topic, a website URL, or a platform shortcut, plus
 * [searchByTopic] and [previewFeed] for the discovery UI.
 *
 * Security posture (parity with the E17/E18 extraction lane): every HTTP
 * call initiated here runs the per-hop [SsrfGuard] check through
 * [RedirectWalker] on a no-auto-redirect platform-TLS client, so a public
 * web link can never redirect a discovery or probe fetch into
 * loopback/RFC1918 space. Response reads are streaming-capped
 * ([HTML_MAX_BYTES] for pages and JSON APIs, [FEED_MAX_BYTES] for feeds) —
 * a hostile multi-gigabyte body never lands in memory.
 *
 * Testability: all query classification, platform URL mapping, channel-id
 * extraction and Feedly/iTunes response parsing are pure companion
 * functions; the HTTP layer is the [ExtractFetch.Transport] seam (loopback
 * `ServerSocket` stubs in `FeedDiscoveryServiceTest`, no HTTP-mock-server
 * dependency).
 */
@Singleton
class FeedDiscoveryService
internal constructor(
    private val transport: ExtractFetch.Transport,
    private val probeTransport: ExtractFetch.Transport,
    private val urlGuard: (String) -> SsrfGuard.Verdict,
    private val ioDispatcher: CoroutineDispatcher,
    private val rssHelper: RssHelper,
    private val endpoints: SearchEndpoints,
    private val platforms: PlatformBases,
) {

    /**
     * Hilt entry point. Wraps the no-auto-redirect `@ExtractHttpClient` in the
     * [ExtractFetch.Transport] seam (a short-timeout derivative for well-known
     * path probes), binds the per-hop guard to [SsrfGuard.check], and uses the
     * production search endpoints and platform bases.
     */
    @Inject
    constructor(
        @ExtractHttpClient httpClient: OkHttpClient,
        ssrfGuard: SsrfGuard,
        rssHelper: RssHelper,
        @IODispatcher ioDispatcher: CoroutineDispatcher,
    ) : this(
        transport = ExtractFetch.OkHttpTransport(httpClient),
        probeTransport = ExtractFetch.OkHttpTransport(shortTimeoutClient(httpClient)),
        urlGuard = ssrfGuard::check,
        ioDispatcher = ioDispatcher,
        rssHelper = rssHelper,
        endpoints = SearchEndpoints(),
        platforms = PlatformBases(),
    )

    // --- public API ----------------------------------------------------------

    /**
     * Unified discovery entry: classifies [rawQuery], then routes to the
     * platform resolvers, direct-URL autodiscovery, or the topic search.
     * Never throws for network or policy failures — those surface as an empty
     * or partial [DiscoveryResult.feeds] plus a non-fatal
     * [DiscoveryResult.notice].
     */
    suspend fun discover(
        rawQuery: String,
        scope: DiscoverySearchScope = DiscoverySearchScope.ALL,
    ): DiscoveryResult {
        val query = rawQuery.trim()
        if (query.isEmpty()) return DiscoveryResult(emptyList())
        return when (val classified = classifyQuery(query)) {
            DiscoveryQuery.Keyword -> searchByTopic(query, scope)
            is DiscoveryQuery.DirectUrl -> {
                val (feeds, notice) = discoverFromUrlDetailed(classified.url)
                DiscoveryResult(feeds, notice)
            }
            else -> {
                val feeds = discoverPlatform(classified)
                DiscoveryResult(
                    feeds = feeds,
                    notice = if (feeds.isEmpty()) "No feeds could be resolved from \"$query\"." else null,
                )
            }
        }
    }

    /**
     * Multi-feed autodiscovery for one website/feed URL: direct feed parse,
     * else every HTML `link rel=alternate` feed candidate, else bounded
     * well-known path probing. Returns the candidate list (possibly empty);
     * use [discover] when a failure notice is wanted.
     */
    suspend fun discoverFromUrl(rawUrl: String): List<DiscoveredFeed> =
        discoverFromUrlDetailed(rawUrl).first

    /**
     * Keyless topic search: the bundled [StarterFeedCatalog] (instant,
     * offline) merged with Feedly feed search and iTunes podcast search per
     * [scope], de-duplicated by normalized URL with local matches first.
     * Remote failures are non-fatal: whatever succeeded is returned with a
     * notice.
     */
    suspend fun searchByTopic(
        query: String,
        scope: DiscoverySearchScope = DiscoverySearchScope.ALL,
    ): DiscoveryResult =
        withContext(ioDispatcher) {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) return@withContext DiscoveryResult(emptyList())
            val local = StarterFeedCatalog.search(trimmed)
            val remote = mutableListOf<DiscoveredFeed>()
            val failed = mutableListOf<String>()

            if (scope != DiscoverySearchScope.PODCASTS) {
                val feedlyUrl =
                    "${endpoints.feedly}?query=${urlEncode(trimmed)}&count=$FEEDLY_COUNT"
                when (val outcome = fetchGuarded(feedlyUrl, HTML_MAX_BYTES, transport)) {
                    is FetchOutcome.Body -> {
                        val parsed =
                            runCatching {
                                parseFeedlyResults(String(outcome.bytes, Charsets.UTF_8))
                            }.getOrNull()
                        if (parsed == null) failed += "Feedly" else remote += parsed
                    }
                    else -> failed += "Feedly"
                }
            }
            if (scope != DiscoverySearchScope.ARTICLES) {
                val itunesUrl =
                    "${endpoints.itunes}?term=${urlEncode(trimmed)}&media=podcast&limit=$ITUNES_LIMIT"
                when (val outcome = fetchGuarded(itunesUrl, HTML_MAX_BYTES, transport)) {
                    is FetchOutcome.Body -> {
                        val parsed =
                            runCatching {
                                parseItunesResults(String(outcome.bytes, Charsets.UTF_8))
                            }.getOrNull()
                        if (parsed == null) failed += "iTunes" else remote += parsed
                    }
                    else -> failed += "iTunes"
                }
            }

            val feeds = dedupeByNormalizedUrl(local + remote)
            val notice =
                when {
                    failed.isEmpty() -> null
                    feeds.isEmpty() ->
                        "Search failed (${failed.joinToString()}). Check your connection."
                    else ->
                        "Some sources were unavailable (${failed.joinToString()}). " +
                            "Showing bundled catalog matches for those."
                }
            DiscoveryResult(feeds, notice)
        }

    /**
     * Parses a candidate feed for the preview UI: up to
     * [PREVIEW_ENTRY_COUNT] recent entries plus the full wire model.
     *
     * The URL runs the full [SsrfGuard] check first; the actual fetch is
     * `RssHelper.searchFeed` (which also resolves HTML pages to their feed
     * URL — [FeedPreview.feedUrl] is the RESOLVED subscribe target).
     * Residual risk, documented as in E17: `searchFeed`'s own client follows
     * redirects without a per-hop guard, so the entry-URL check is the
     * policy boundary for the preview path.
     *
     * @throws IOException when the URL is rejected by the guard or the feed
     *   cannot be fetched/parsed.
     */
    @Throws(IOException::class)
    suspend fun previewFeed(feedUrl: String): FeedPreview =
        withContext(ioDispatcher) {
            when (val verdict = urlGuard(feedUrl)) {
                is SsrfGuard.Verdict.Rejected ->
                    throw IOException(
                        "Blocked by security policy (${verdict.reason}): $feedUrl"
                    )
                SsrfGuard.Verdict.Allowed -> Unit
            }
            val result = rssHelper.searchFeed(feedUrl)
            buildPreview(result.feed, result.feedLink)
        }

    // --- internal orchestration ----------------------------------------------

    private suspend fun discoverFromUrlDetailed(
        rawUrl: String
    ): Pair<List<DiscoveredFeed>, String?> =
        withContext(ioDispatcher) {
            val pageUrl =
                normalizeToUrl(rawUrl)
                    ?: return@withContext emptyList<DiscoveredFeed>() to "Not a valid URL: $rawUrl"
            when (val outcome = fetchGuarded(pageUrl, FEED_MAX_BYTES, transport)) {
                is FetchOutcome.Rejected ->
                    emptyList<DiscoveredFeed>() to
                        "Blocked by security policy (${outcome.reason}): ${outcome.url}"
                FetchOutcome.TooManyHops ->
                    emptyList<DiscoveredFeed>() to "Too many redirects for $pageUrl."
                FetchOutcome.Oversize ->
                    emptyList<DiscoveredFeed>() to
                        "Response from $pageUrl exceeded the ${FEED_MAX_BYTES / MIB} MiB cap."
                is FetchOutcome.NetworkError ->
                    emptyList<DiscoveredFeed>() to "Network error: ${outcome.message}"
                is FetchOutcome.Non2xx ->
                    emptyList<DiscoveredFeed>() to
                        "Could not fetch $pageUrl (HTTP ${outcome.code})."
                is FetchOutcome.Body -> {
                    // 1. The URL may itself be a feed.
                    tryParseFeed(outcome.bytes, outcome.contentType, outcome.finalUrl)?.let {
                        return@withContext listOf(
                            discoveredFromParsed(it, outcome.finalUrl, DiscoverySource.DIRECT)
                        ) to null
                    }
                    // 2. HTML page: every advertised alternate feed link.
                    val alternates =
                        if (looksLikeHtml(outcome.contentType, outcome.bytes)) {
                            rssHelper
                                .discoverFeedLinks(outcome.finalUrl, outcome.bytes)
                                .filter { it.format != FeedFormat.UNKNOWN }
                        } else {
                            emptyList()
                        }
                    if (alternates.isNotEmpty()) {
                        return@withContext dedupeByNormalizedUrl(alternates) to null
                    }
                    // 3. No advertisement: bounded well-known path probing.
                    val base = outcome.finalUrl.toHttpUrlOrNull()
                    val probed = if (base != null) probeWellKnown(base) else emptyList()
                    probed to
                        if (probed.isEmpty()) "No feeds found at $pageUrl." else null
                }
            }
        }

    private suspend fun discoverPlatform(query: DiscoveryQuery): List<DiscoveredFeed> =
        withContext(ioDispatcher) {
            when (query) {
                is DiscoveryQuery.RedditSubreddit ->
                    listOf(
                        shortcutFeed(
                            "${platforms.reddit}/r/${query.name}/.rss",
                            "r/${query.name}",
                        )
                    )
                is DiscoveryQuery.RedditUser ->
                    listOf(
                        shortcutFeed(
                            "${platforms.reddit}/user/${query.name}/.rss",
                            "reddit u/${query.name}",
                        )
                    )
                is DiscoveryQuery.MastodonProfile ->
                    listOf(
                        shortcutFeed(
                            "https://${query.domain}/@${query.user}.rss",
                            "@${query.user}@${query.domain}",
                        )
                    )
                is DiscoveryQuery.SubstackPublication ->
                    listOf(
                        shortcutFeed(
                            "https://${query.publication}.substack.com/feed",
                            "${query.publication} on Substack",
                        )
                    )
                is DiscoveryQuery.YouTubeChannel ->
                    listOf(
                        shortcutFeed(
                            "${platforms.youTube}/feeds/videos.xml?channel_id=${query.channelId}",
                            "YouTube ${query.channelId}",
                        )
                    )
                is DiscoveryQuery.YouTubePage -> resolveYouTubePage(query.path)
                is DiscoveryQuery.GitHubRepo ->
                    validateFeedUrls(
                        listOf(
                            "${platforms.github}/${query.owner}/${query.repo}/releases.atom",
                            "${platforms.github}/${query.owner}/${query.repo}/commits.atom",
                        )
                    )
                is DiscoveryQuery.GitHubUser ->
                    validateFeedUrls(listOf("${platforms.github}/${query.name}.atom"))
                else -> emptyList()
            }
        }

    /** Fetches the channel page and maps the extracted channel id to the feed URL. */
    private suspend fun resolveYouTubePage(path: String): List<DiscoveredFeed> {
        val pageUrl = "${platforms.youTube}/${path.trimStart('/')}"
        val outcome = fetchGuarded(pageUrl, HTML_MAX_BYTES, transport)
        if (outcome !is FetchOutcome.Body) return emptyList()
        val channelId =
            extractYouTubeChannelId(String(outcome.bytes, Charsets.UTF_8)) ?: return emptyList()
        return listOf(
            shortcutFeed(
                "${platforms.youTube}/feeds/videos.xml?channel_id=$channelId",
                "YouTube ${path.trimStart('/')}",
            )
        )
    }

    /** Keeps only candidates that are reachable AND parse as a feed. */
    private suspend fun validateFeedUrls(urls: List<String>): List<DiscoveredFeed> =
        coroutineScope {
            urls
                .map { url ->
                    async(ioDispatcher) {
                        val outcome = fetchGuarded(url, FEED_MAX_BYTES, transport)
                        if (outcome is FetchOutcome.Body) {
                            tryParseFeed(outcome.bytes, outcome.contentType, url)?.let {
                                discoveredFromParsed(it, url, DiscoverySource.PLATFORM_SHORTCUT)
                            }
                        } else {
                            null
                        }
                    }
                }
                .awaitAll()
                .filterNotNull()
        }

    /**
     * Well-known relative path probing, bounded per FD1: at most
     * [PROBE_CONCURRENCY] concurrent requests on the short-timeout transport,
     * at most [WELL_KNOWN_PATHS].size total probes, stopping after the first
     * chunk that yields at least one valid feed.
     */
    private suspend fun probeWellKnown(pageUrl: HttpUrl): List<DiscoveredFeed> {
        val origin = pageUrl.newBuilder().encodedPath("/").build()
        for (chunk in WELL_KNOWN_PATHS.chunked(PROBE_CONCURRENCY)) {
            val found =
                coroutineScope {
                    chunk
                        .map { path ->
                            async(ioDispatcher) {
                                val url = origin.newBuilder().encodedPath(path).build().toString()
                                val outcome = fetchGuarded(url, FEED_MAX_BYTES, probeTransport)
                                if (outcome is FetchOutcome.Body) {
                                    tryParseFeed(outcome.bytes, outcome.contentType, url)?.let {
                                        discoveredFromParsed(
                                            it,
                                            url,
                                            DiscoverySource.WELL_KNOWN_PATH,
                                        )
                                    }
                                } else {
                                    null
                                }
                            }
                        }
                        .awaitAll()
                        .filterNotNull()
                }
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    // --- guarded fetch (walker + streaming cap) ------------------------------

    private sealed interface FetchOutcome {
        /** Final 2xx body, already size-capped and fully buffered. */
        data class Body(
            val bytes: ByteArray,
            val contentType: String?,
            val finalUrl: String,
        ) : FetchOutcome

        /** A hop URL (start included) failed the SSRF guard. */
        data class Rejected(
            val url: String,
            val reason: SsrfGuard.RejectReason,
        ) : FetchOutcome

        data object TooManyHops : FetchOutcome

        data object Oversize : FetchOutcome

        data class Non2xx(val code: Int) : FetchOutcome

        data class NetworkError(val message: String) : FetchOutcome
    }

    /**
     * One guarded GET: manual redirect walk with the full guard on EVERY hop
     * (E17 parity — the transport never follows redirects itself), then a
     * streaming byte-count cap at [maxBytes]. The final response is fetched
     * once and reused for the body read; intermediate hops are closed as soon
     * as their `Location` is read.
     */
    private suspend fun fetchGuarded(
        startUrl: String,
        maxBytes: Long,
        client: ExtractFetch.Transport,
    ): FetchOutcome =
        withContext(ioDispatcher) {
            val responses = LinkedHashMap<String, ExtractFetch.TransportResponse>()
            try {
                val walker =
                    RedirectWalker(
                        guard = { url -> urlGuard(url) },
                        transport = { url ->
                            val response = client.open(url)
                            if (response.code in REDIRECT_CODES) {
                                val location = response.locationHeader
                                runCatching { response.close() }
                                location
                            } else {
                                responses[url] = response
                                null
                            }
                        },
                    )
                when (val walked = walker.walk(startUrl)) {
                    is RedirectWalker.WalkOutcome.Rejected ->
                        FetchOutcome.Rejected(walked.url, walked.reason)
                    is RedirectWalker.WalkOutcome.TooManyHops -> FetchOutcome.TooManyHops
                    is RedirectWalker.WalkOutcome.Succeeded -> {
                        val response = responses.getValue(walked.finalUrl)
                        if (response.code !in SUCCESS_CODES) {
                            FetchOutcome.Non2xx(response.code)
                        } else {
                            val bytes = readAtMost(response.bodySource(), maxBytes)
                            if (bytes == null) {
                                FetchOutcome.Oversize
                            } else {
                                FetchOutcome.Body(bytes, response.contentTypeHeader, walked.finalUrl)
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                FetchOutcome.NetworkError(e.message ?: e.javaClass.simpleName)
            } catch (e: IllegalArgumentException) {
                FetchOutcome.NetworkError(e.message ?: "Malformed request URL")
            } finally {
                responses.values.forEach { runCatching { it.close() } }
            }
        }

    /** Streaming cap identical in shape to E18: null as soon as the cap is exceeded. */
    private fun readAtMost(source: BufferedSource, maxBytes: Long): ByteArray? {
        val buffer = Buffer()
        var total = 0L
        while (total <= maxBytes) {
            val read = source.read(buffer, READ_CHUNK_BYTES)
            if (read == -1L) return buffer.readByteArray()
            total += read
        }
        return null
    }

    // --- parsing helpers ------------------------------------------------------

    /** JSON Feed first (content-type or body sniff), then Rome XML; null when neither parses. */
    private fun tryParseFeed(bytes: ByteArray, contentType: String?, url: String): ParsedFeed? {
        if (JsonFeedParser.isJsonFeed(contentType, bytes)) {
            val jsonFeed =
                runCatching { JsonFeedParser.parse(String(bytes, Charsets.UTF_8), url) }.getOrNull()
            if (jsonFeed != null) return ParsedFeed(jsonFeed, FeedFormat.JSON_FEED)
        }
        val xmlFeed =
            runCatching {
                ByteArrayInputStream(bytes).use { SyndFeedInput().build(XmlReader(it)) }
            }.getOrNull()
        if (xmlFeed != null) return ParsedFeed(xmlFeed, formatOfWireFeed(xmlFeed))
        return null
    }

    private data class ParsedFeed(val feed: SyndFeed, val baseFormat: FeedFormat)

    private fun discoveredFromParsed(
        parsed: ParsedFeed,
        url: String,
        source: DiscoverySource,
    ): DiscoveredFeed {
        val feed = parsed.feed
        return DiscoveredFeed(
            url = url,
            title = feed.title?.takeIf { it.isNotBlank() }
                ?: (url.toHttpUrlOrNull()?.host ?: url),
            description = feed.description?.takeIf { it.isNotBlank() },
            siteUrl = feed.link?.takeIf { it.isNotBlank() },
            iconUrl = (feed.image?.url ?: feed.image?.link)?.takeIf { it.isNotBlank() },
            format =
                if (hasAudioEnclosure(feed)) FeedFormat.PODCAST else parsed.baseFormat,
            source = source,
        )
    }

    private fun shortcutFeed(url: String, title: String): DiscoveredFeed =
        DiscoveredFeed(
            url = url,
            title = title,
            description = null,
            siteUrl = null,
            iconUrl = null,
            format = DiscoveredFeed.guessFormat(url),
            subscribers = null,
            source = DiscoverySource.PLATFORM_SHORTCUT,
        )

    private fun looksLikeHtml(contentType: String?, bytes: ByteArray): Boolean {
        val ct = contentType?.lowercase(Locale.ROOT).orEmpty()
        if (ct.contains("html")) return true
        if (ct.contains("xml") || ct.contains("json")) return false
        val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.UTF_8).lowercase(Locale.ROOT)
        return head.contains("<html") || head.contains("<!doctype html")
    }

    companion object {

        private const val MIB = 1024L * 1024L

        /** Streaming read cap for HTML pages and JSON API responses. */
        const val HTML_MAX_BYTES: Long = 2L * MIB

        /** Streaming read cap for feed bodies (XML and JSON Feed). */
        const val FEED_MAX_BYTES: Long = 5L * MIB

        /** Max concurrent well-known path probes. */
        const val PROBE_CONCURRENCY: Int = 3

        /** Entries kept in a [FeedPreview]. */
        const val PREVIEW_ENTRY_COUNT: Int = 5

        /** Well-known relative paths probed when a page advertises nothing. */
        val WELL_KNOWN_PATHS: List<String> =
            listOf("/feed", "/rss", "/rss.xml", "/atom.xml", "/feed.xml", "/index.xml", "/feed.json")

        private const val READ_CHUNK_BYTES = 8_192L
        private const val FEEDLY_COUNT = 20
        private const val ITUNES_LIMIT = 10
        private const val PROBE_CONNECT_TIMEOUT_SECS = 5L
        private const val PROBE_READ_TIMEOUT_SECS = 8L
        private const val PROBE_CALL_TIMEOUT_SECS = 10L
        private val REDIRECT_CODES = 300..399
        private val SUCCESS_CODES = 200..299

        private val MASTODON_HANDLE_REGEX =
            Regex(
                "^@([A-Za-z0-9_]{1,64})@" +
                    "([A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+)$"
            )
        private val REDDIT_BARE_REGEX =
            Regex("^/?r/([A-Za-z0-9_]{1,64})/?$", RegexOption.IGNORE_CASE)
        private val YOUTUBE_ID_REGEX = Regex("^[0-9A-Za-z_-]{6,64}$")

        // YouTube channel-id extraction, most authoritative pattern first:
        // the microformat externalId, the meta itemprop tag (both attribute
        // orders), the canonical link, then a generic channelId JSON key.
        private val YT_EXTERNAL_ID = Regex("\"externalId\"\\s*:\\s*\"(UC[0-9A-Za-z_-]{22})\"")
        private val YT_META_CHANNEL_ID =
            Regex("itemprop=\"channelId\"[^>]*content=\"(UC[0-9A-Za-z_-]{22})\"")
        private val YT_META_CHANNEL_ID_REVERSED =
            Regex("content=\"(UC[0-9A-Za-z_-]{22})\"[^>]*itemprop=\"channelId\"")
        private val YT_CANONICAL_CHANNEL =
            Regex("<link[^>]+rel=\"canonical\"[^>]+href=\"[^\"]*youtube\\.com/channel/(UC[0-9A-Za-z_-]{22})\"")
        private val YT_JSON_CHANNEL_ID = Regex("\"channelId\"\\s*:\\s*\"(UC[0-9A-Za-z_-]{22})\"")

        /**
         * Pure query classification: platform shortcuts first, then direct
         * URLs/domains (a scheme-less query must contain a dotted host to
         * count as a URL, so single words stay topics), else keyword.
         */
        fun classifyQuery(rawQuery: String): DiscoveryQuery {
            val query = rawQuery.trim()
            if (query.isEmpty()) return DiscoveryQuery.Keyword

            MASTODON_HANDLE_REGEX.matchEntire(query)?.let { match ->
                return DiscoveryQuery.MastodonProfile(
                    domain = match.groupValues[2],
                    user = match.groupValues[1],
                )
            }
            REDDIT_BARE_REGEX.matchEntire(query)?.let { match ->
                return DiscoveryQuery.RedditSubreddit(match.groupValues[1])
            }

            val hasScheme =
                query.startsWith("http://", ignoreCase = true) ||
                    query.startsWith("https://", ignoreCase = true)
            val candidate = if (hasScheme) query else "https://$query"
            if (candidate.contains(' ')) return DiscoveryQuery.Keyword
            // A scheme-less query with '@' in the AUTHORITY position (before
            // the first '/') is an email-like token or a bare handle (not
            // matched above), never a plain domain — treat it as a keyword so
            // `bob@gmail.com` does not parse as userinfo@host. An '@' in the
            // path (`youtube.com/@handle`, `domain/@user`) is still a URL.
            if (!hasScheme && query.substringBefore('/').contains('@')) {
                return DiscoveryQuery.Keyword
            }
            val httpUrl = candidate.toHttpUrlOrNull() ?: return DiscoveryQuery.Keyword
            if (!hasScheme && !httpUrl.host.contains('.')) return DiscoveryQuery.Keyword

            val host = httpUrl.host.lowercase(Locale.ROOT)
            val segments = httpUrl.pathSegments.filter { it.isNotBlank() }

            if (host == "reddit.com" || host.endsWith(".reddit.com")) {
                when (segments.firstOrNull()) {
                    "r" ->
                        segments.getOrNull(1)?.let { return DiscoveryQuery.RedditSubreddit(it) }
                    "u", "user" ->
                        segments.getOrNull(1)?.let { return DiscoveryQuery.RedditUser(it) }
                }
            }
            if (host == "github.com" || host == "www.github.com") {
                return when (segments.size) {
                    1 -> DiscoveryQuery.GitHubUser(segments[0])
                    2 -> DiscoveryQuery.GitHubRepo(segments[0], segments[1])
                    else -> DiscoveryQuery.DirectUrl(httpUrl.toString())
                }
            }
            if (host == "youtube.com" || host.endsWith(".youtube.com")) {
                val first = segments.firstOrNull()
                if (first == "channel") {
                    segments.getOrNull(1)?.takeIf { YOUTUBE_ID_REGEX.matches(it) }?.let {
                        return DiscoveryQuery.YouTubeChannel(it)
                    }
                }
                if (first != null && first.startsWith("@")) {
                    return DiscoveryQuery.YouTubePage(first)
                }
                if (first == "c" || first == "user") {
                    if (segments.size >= 2) {
                        return DiscoveryQuery.YouTubePage("${segments[0]}/${segments[1]}")
                    }
                }
            }
            if (segments.firstOrNull()?.startsWith("@") == true && segments.size >= 1) {
                return DiscoveryQuery.MastodonProfile(
                    domain = host,
                    user = segments[0].removePrefix("@"),
                )
            }
            if (host.endsWith(".substack.com") && host != ".substack.com") {
                val publication = host.removeSuffix(".substack.com")
                if (publication.isNotBlank() && !publication.contains('.')) {
                    return DiscoveryQuery.SubstackPublication(publication)
                }
            }
            return DiscoveryQuery.DirectUrl(httpUrl.toString())
        }

        /**
         * Absolute URL for a direct-URL query: scheme-less bare domains get
         * `https://` prepended; single-word queries (no dot in the host) and
         * anything unparseable return null.
         */
        fun normalizeToUrl(rawQuery: String): String? {
            val query = rawQuery.trim()
            if (query.isEmpty() || query.contains(' ')) return null
            val hasScheme =
                query.startsWith("http://", ignoreCase = true) ||
                    query.startsWith("https://", ignoreCase = true)
            val candidate = if (hasScheme) query else "https://$query"
            val parsed = candidate.toHttpUrlOrNull() ?: return null
            if (!hasScheme && !parsed.host.contains('.')) return null
            return parsed.toString()
        }

        /**
         * Extracts a YouTube channel id (`UC` + 22 characters) from a channel
         * page: microformat `externalId`, `meta itemprop=channelId` (either
         * attribute order), the canonical link, then a generic `channelId`
         * JSON key. Null when absent.
         */
        fun extractYouTubeChannelId(html: String): String? =
            firstMatch(
                html,
                YT_EXTERNAL_ID,
                YT_META_CHANNEL_ID,
                YT_META_CHANNEL_ID_REVERSED,
                YT_CANONICAL_CHANNEL,
                YT_JSON_CHANNEL_ID,
            )

        private fun firstMatch(html: String, vararg patterns: Regex): String? {
            for (pattern in patterns) {
                pattern.find(html)?.groupValues?.get(1)?.let { return it }
            }
            return null
        }

        /**
         * Parses the Feedly `search/feeds` response body. `feedId` values
         * carry a `feed/` prefix that is stripped; entries without an
         * http(s) URL are dropped. Malformed JSON throws (the caller treats a
         * throw as a source failure).
         */
        fun parseFeedlyResults(body: String): List<DiscoveredFeed> {
            val root = Json.parseToJsonElement(body).jsonObject
            val results = root["results"] as? JsonArray
                ?: return emptyList()
            return results.mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val feedId = (item["feedId"] as? JsonPrimitive)?.contentOrNull
                    ?: return@mapNotNull null
                val url = feedId.removePrefix("feed/").takeIf { it.startsWith("http") }
                    ?: return@mapNotNull null
                DiscoveredFeed(
                    url = url,
                    title = item.string("title")?.takeIf { it.isNotBlank() }
                        ?: (url.toHttpUrlOrNull()?.host ?: url),
                    description = item.string("description")?.takeIf { it.isNotBlank() },
                    siteUrl = item.string("website")?.takeIf { it.isNotBlank() },
                    iconUrl = item.string("iconUrl")?.takeIf { it.isNotBlank() },
                    format = DiscoveredFeed.guessFormat(url),
                    subscribers = (item["subscribers"] as? JsonPrimitive)?.intOrNull,
                    source = DiscoverySource.FEEDLY_SEARCH,
                )
            }
        }

        /**
         * Parses the iTunes podcast-search response body. Results without a
         * usable `feedUrl` are dropped. Malformed JSON throws (source failure).
         */
        fun parseItunesResults(body: String): List<DiscoveredFeed> {
            val root = Json.parseToJsonElement(body).jsonObject
            val results = root["results"] as? JsonArray
                ?: return emptyList()
            return results.mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val url = item.string("feedUrl")?.takeIf { it.startsWith("http") }
                    ?: return@mapNotNull null
                DiscoveredFeed(
                    url = url,
                    title = item.string("collectionName")?.takeIf { it.isNotBlank() }
                        ?: (url.toHttpUrlOrNull()?.host ?: url),
                    description = item.string("artistName")?.takeIf { it.isNotBlank() },
                    siteUrl = item.string("collectionViewUrl")?.takeIf { it.isNotBlank() },
                    iconUrl = item.string("artworkUrl100")?.takeIf { it.isNotBlank() },
                    format = FeedFormat.PODCAST,
                    subscribers = null,
                    source = DiscoverySource.ITUNES_SEARCH,
                )
            }
        }

        /**
         * Maps a parsed feed plus its resolved URL to a [FeedPreview] with
         * the top [PREVIEW_ENTRY_COUNT] entries in feed order. Pure —
         * unit-tested without any HTTP.
         */
        fun buildPreview(feed: SyndFeed, feedUrl: String): FeedPreview {
            val entries =
                feed.entries
                    .orEmpty()
                    .take(PREVIEW_ENTRY_COUNT)
                    .map { entry ->
                        PreviewEntry(
                            title = entry.title.orEmpty(),
                            link = entry.link,
                            publishedAt = (entry.publishedDate ?: entry.updatedDate)?.time,
                        )
                    }
            return FeedPreview(feed = feed, feedUrl = feedUrl, recentEntries = entries)
        }

        /** Rome wire-feed type string (`atom_1.0`, `rss_2.0`, ...) to [FeedFormat]. */
        fun formatOfWireFeed(feed: SyndFeed): FeedFormat {
            val type = feed.feedType?.lowercase(Locale.ROOT) ?: return FeedFormat.UNKNOWN
            return when {
                type.startsWith("atom") -> FeedFormat.ATOM
                type.startsWith("rss") || type.startsWith("rdf") -> FeedFormat.RSS
                else -> FeedFormat.UNKNOWN
            }
        }

        /** True when any entry advertises an audio enclosure (podcast signal). */
        fun hasAudioEnclosure(feed: SyndFeed): Boolean =
            feed.entries.orEmpty().any { entry ->
                entry.enclosures.orEmpty().any { enclosure ->
                    enclosure.type?.lowercase(Locale.ROOT)?.startsWith("audio/") == true
                }
            }

        /** First occurrence wins; identity is [DiscoveredFeed.normalizeUrl]. */
        fun dedupeByNormalizedUrl(feeds: List<DiscoveredFeed>): List<DiscoveredFeed> {
            val seen = mutableSetOf<String>()
            return feeds.filter { seen.add(DiscoveredFeed.normalizeUrl(it.url)) }
        }

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.contentOrNull

        private fun urlEncode(value: String): String =
            URLEncoder.encode(value, Charsets.UTF_8.name())

        /** Short-timeout derivative of the shared client for well-known probes. */
        internal fun shortTimeoutClient(base: OkHttpClient): OkHttpClient =
            base.newBuilder()
                .connectTimeout(PROBE_CONNECT_TIMEOUT_SECS, TimeUnit.SECONDS)
                .readTimeout(PROBE_READ_TIMEOUT_SECS, TimeUnit.SECONDS)
                .callTimeout(PROBE_CALL_TIMEOUT_SECS, TimeUnit.SECONDS)
                .build()
    }

    /** Keyless topic-search API endpoints (default: production; tests override). */
    internal data class SearchEndpoints(
        val feedly: String = "https://cloud.feedly.com/v3/search/feeds",
        val itunes: String = "https://itunes.apple.com/search",
    )

    /** Platform-shortcut base origins (default: production; tests override). */
    internal data class PlatformBases(
        val reddit: String = "https://www.reddit.com",
        val github: String = "https://github.com",
        val youTube: String = "https://www.youtube.com",
    )
}
