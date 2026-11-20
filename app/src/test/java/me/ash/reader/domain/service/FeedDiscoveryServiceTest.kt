package me.ash.reader.domain.service

import android.content.Context
import com.rometools.rome.feed.synd.SyndEntryImpl
import com.rometools.rome.feed.synd.SyndFeedImpl
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.discovery.DiscoveredFeed
import me.ash.reader.domain.model.discovery.DiscoveryQuery
import me.ash.reader.domain.model.discovery.DiscoverySearchScope
import me.ash.reader.domain.model.discovery.DiscoverySource
import me.ash.reader.domain.model.discovery.FeedFormat
import me.ash.reader.infrastructure.net.SsrfGuard
import me.ash.reader.infrastructure.rss.RssHelper
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * FD1 — discovery engine suite. Network behavior runs against a routing
 * loopback stub built on [ServerSocket] (pure `java.net`, no HTTP-mock-server
 * dependency, per S36): the stub maps request paths to canned responses,
 * records every request target, and supports redirects and error codes.
 * Query classification, platform URL mapping, channel-id extraction and
 * Feedly/iTunes parsing are pure companion functions tested without sockets.
 *
 * Guard policy in tests: loopback `127.0.0.1` (the stub origin) is allowed;
 * every other URL runs the REAL [SsrfGuard], so private-range redirect and
 * probe targets are rejected exactly as in production.
 */
class FeedDiscoveryServiceTest {

    // --- loopback routing stub --------------------------------------------------

    private class StubResponse(
        val code: Int = 200,
        val contentType: String = "application/rss+xml",
        val body: String = "",
        val location: String? = null,
    )

    private class RoutingStubServer : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val routes = ConcurrentHashMap<String, StubResponse>()
        private val recorded = CopyOnWriteArrayList<String>()

        @Volatile
        var fallback: StubResponse = StubResponse(404, "text/plain", "not found")

        val origin: String get() = "http://127.0.0.1:${server.localPort}"
        val requests: List<String> get() = recorded.toList()

        init {
            val acceptThread = Thread { acceptLoop() }
            acceptThread.isDaemon = true
            acceptThread.start()
        }

        fun route(path: String, response: StubResponse) {
            routes[path] = response
        }

        fun route(path: String, body: String, contentType: String = "application/rss+xml") {
            routes[path] = StubResponse(body = body, contentType = contentType)
        }

        fun url(path: String): String = origin + path

        private fun acceptLoop() {
            while (!server.isClosed) {
                val socket =
                    try {
                        server.accept()
                    } catch (_: Exception) {
                        return
                    }
                val handler = Thread { handle(socket) }
                handler.isDaemon = true
                handler.start()
            }
        }

        private fun handle(socket: Socket) {
            try {
                val input = socket.getInputStream()
                val headerBlock = readHeaderBlock(input)
                val lines = headerBlock.split("\r\n").filter { it.isNotEmpty() }
                if (lines.isEmpty()) return
                val target = lines[0].split(" ").getOrNull(1) ?: "/"
                recorded += target
                val response = routes[target.substringBefore('?')] ?: fallback
                val payload = response.body.toByteArray(Charsets.UTF_8)
                val head =
                    buildString {
                        append("HTTP/1.1 ${response.code} ${reasonFor(response.code)}\r\n")
                        append("Content-Type: ${response.contentType}\r\n")
                        append("Content-Length: ${payload.size}\r\n")
                        response.location?.let { append("Location: $it\r\n") }
                        append("Connection: close\r\n")
                        append("\r\n")
                    }
                val out: OutputStream = socket.getOutputStream()
                out.write(head.toByteArray(Charsets.US_ASCII))
                out.write(payload)
                out.flush()
            } catch (_: Exception) {
                // always close below
            } finally {
                try {
                    socket.close()
                } catch (_: Exception) {
                }
            }
        }

        private fun readHeaderBlock(input: InputStream): String {
            val builder = StringBuilder()
            while (true) {
                val b = input.read()
                if (b == -1) break
                builder.append(b.toChar())
                if (builder.length >= 4 && builder.endsWith("\r\n\r\n")) break
            }
            return builder.toString()
        }

        private fun reasonFor(code: Int): String =
            when (code) {
                200 -> "OK"
                302 -> "Found"
                404 -> "Not Found"
                500 -> "Internal Server Error"
                else -> "Status"
            }

        override fun close() {
            try {
                server.close()
            } catch (_: Exception) {
            }
        }
    }

    // --- fixtures ------------------------------------------------------------------

    private val rssXml =
        """<?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0"><channel><title>Stub Feed</title><link>https://stub.example/</link>
        <description>Stub description</description>
        <item><title>Entry One</title><link>https://stub.example/1</link><guid>1</guid>
        <pubDate>Sat, 01 Nov 2026 10:00:00 GMT</pubDate></item>
        </channel></rss>""".trimIndent()

    private val atomXml =
        """<?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom"><title>Stub Atom</title>
        <link href="https://stub.example/atom"/><id>urn:uuid:stub-atom</id>
        <updated>2026-11-01T10:00:00Z</updated>
        <entry><title>Atom One</title><link href="https://stub.example/a1"/>
        <id>urn:uuid:a1</id><updated>2026-11-01T10:00:00Z</updated></entry></feed>""".trimIndent()

    private val jsonFeedBody =
        """{"version":"https://jsonfeed.org/version/1.1","title":"Stub JSON",
        "home_page_url":"https://stub.example/",
        "items":[{"id":"j1","url":"https://stub.example/j1","title":"JSON One",
        "content_text":"Body"}]}""".trimIndent()

    private val htmlWithAlternates =
        """
        <html><head><title>Site Title</title>
        <link rel="alternate" type="application/rss+xml" title="Main RSS" href="/feed.xml"/>
        <link rel="alternate" type="application/atom+xml" title="Comments" href="https://cdn.example/comments.atom"/>
        <link rel="alternate" type="application/rss+xml" href="/feed.xml#frag"/>
        <link rel="alternate" type="text/html" hreflang="es" href="https://es.example/"/>
        <link rel="alternate" type="application/feed+json" title="JSON version" href="/feed.json"/>
        </head><body>hi</body></html>
        """.trimIndent()

    private val htmlNoFeeds =
        """<html><head><title>Plain Site</title></head><body>Nothing here</body></html>"""

    private val feedlyJson =
        """{"results":[
           {"feedId":"feed/https://remote.example/f","title":"Remote Feed",
            "description":"Remote desc","website":"https://remote.example",
            "iconUrl":"https://remote.example/i.png","subscribers":4242,"velocity":1.5},
           {"feedId":"notaurl","title":"Dropped"},
           {"title":"missing id"}
        ]}"""

    private val itunesJson =
        """{"resultCount":2,"results":[
           {"collectionName":"Stub Pod","artistName":"Pod Artist",
            "feedUrl":"https://pod.example/f.xml","artworkUrl100":"https://pod.example/a.png",
            "collectionViewUrl":"https://itunes.apple.com/pod"},
           {"collectionName":"No Feed Url"}
        ]}"""

    // --- harness ---------------------------------------------------------------------

    private val realGuard = SsrfGuard()

    private val loopbackGuard: (String) -> SsrfGuard.Verdict = { url ->
        val host = url.toHttpUrlOrNull()?.host?.removeSurrounding("[", "]")
        if (host == "127.0.0.1") SsrfGuard.Verdict.Allowed else realGuard.check(url)
    }

    private val httpClient =
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()

    private lateinit var stub: RoutingStubServer

    @Before
    fun setUp() {
        stub = RoutingStubServer()
    }

    @After
    fun tearDown() {
        stub.close()
    }

    /** Service wired to the stub (loopback allowed, everything else really guarded). */
    private fun stubbedService(
        guard: (String) -> SsrfGuard.Verdict = loopbackGuard,
        rssHelper: RssHelper = RssHelper(mock(), Dispatchers.IO, httpClient),
    ): FeedDiscoveryService = newService(stub, guard, rssHelper)

    /** Service with production endpoints/bases; remote calls hit a dead port. */
    private fun offlineService(
        guard: (String) -> SsrfGuard.Verdict = loopbackGuard,
        rssHelper: RssHelper = mock(),
    ): FeedDiscoveryService = newService(null, guard, rssHelper)

    private fun newService(
        stub: RoutingStubServer?,
        guard: (String) -> SsrfGuard.Verdict,
        rssHelper: RssHelper,
    ): FeedDiscoveryService =
        FeedDiscoveryService(
            transport = ExtractFetch.OkHttpTransport(httpClient),
            probeTransport = ExtractFetch.OkHttpTransport(httpClient),
            urlGuard = guard,
            ioDispatcher = Dispatchers.IO,
            rssHelper = rssHelper,
            endpoints =
                FeedDiscoveryService.SearchEndpoints(
                    feedly = stub?.url("/feedly") ?: "http://127.0.0.1:9/feedly",
                    itunes = stub?.url("/itunes") ?: "http://127.0.0.1:9/search",
                ),
            platforms =
                stub?.let {
                    FeedDiscoveryService.PlatformBases(
                        reddit = it.origin,
                        github = it.origin,
                        youTube = it.origin,
                    )
                } ?: FeedDiscoveryService.PlatformBases(),
        )

    // --- query classification (pure) --------------------------------------------------

    @Test
    fun classifyKeywordQueries() {
        assertEquals(DiscoveryQuery.Keyword, FeedDiscoveryService.classifyQuery("linux"))
        assertEquals(DiscoveryQuery.Keyword, FeedDiscoveryService.classifyQuery("linux security"))
        assertEquals(DiscoveryQuery.Keyword, FeedDiscoveryService.classifyQuery("  "))
        assertEquals(DiscoveryQuery.Keyword, FeedDiscoveryService.classifyQuery("https://"))
        assertEquals(DiscoveryQuery.Keyword, FeedDiscoveryService.classifyQuery("bob@gmail.com"))
    }

    @Test
    fun classifyDirectUrlsAndBareDomains() {
        assertEquals(
            DiscoveryQuery.DirectUrl("https://example.com/"),
            FeedDiscoveryService.classifyQuery("example.com"),
        )
        assertEquals(
            DiscoveryQuery.DirectUrl("https://example.com/blog"),
            FeedDiscoveryService.classifyQuery("https://example.com/blog"),
        )
        assertEquals(
            DiscoveryQuery.DirectUrl("https://arstechnica.com/"),
            FeedDiscoveryService.classifyQuery("arstechnica.com/"),
        )
    }

    @Test
    fun classifyRedditShortcuts() {
        assertEquals(
            DiscoveryQuery.RedditSubreddit("androiddev"),
            FeedDiscoveryService.classifyQuery("r/androiddev"),
        )
        assertEquals(
            DiscoveryQuery.RedditSubreddit("androiddev"),
            FeedDiscoveryService.classifyQuery("/r/androiddev"),
        )
        assertEquals(
            DiscoveryQuery.RedditSubreddit("androiddev"),
            FeedDiscoveryService.classifyQuery("reddit.com/r/androiddev"),
        )
        assertEquals(
            DiscoveryQuery.RedditSubreddit("androiddev"),
            FeedDiscoveryService.classifyQuery("https://old.reddit.com/r/androiddev/"),
        )
        assertEquals(
            DiscoveryQuery.RedditUser("spez"),
            FeedDiscoveryService.classifyQuery("reddit.com/u/spez"),
        )
        assertEquals(
            DiscoveryQuery.RedditUser("spez"),
            FeedDiscoveryService.classifyQuery("https://www.reddit.com/user/spez"),
        )
    }

    @Test
    fun classifyMastodonShortcuts() {
        assertEquals(
            DiscoveryQuery.MastodonProfile("mastodon.social", "user"),
            FeedDiscoveryService.classifyQuery("@user@mastodon.social"),
        )
        assertEquals(
            DiscoveryQuery.MastodonProfile("fosstodon.org", "feedbucket"),
            FeedDiscoveryService.classifyQuery("https://fosstodon.org/@feedbucket"),
        )
    }

    @Test
    fun classifyGitHubShortcuts() {
        assertEquals(
            DiscoveryQuery.GitHubRepo("ashinch", "readyou"),
            FeedDiscoveryService.classifyQuery("github.com/ashinch/readyou"),
        )
        assertEquals(
            DiscoveryQuery.GitHubRepo("ashinch", "readyou"),
            FeedDiscoveryService.classifyQuery("https://github.com/ashinch/readyou/"),
        )
        assertEquals(
            DiscoveryQuery.GitHubUser("torvalds"),
            FeedDiscoveryService.classifyQuery("github.com/torvalds"),
        )
        // Deeper paths are not repo shortcuts; they stay plain URLs.
        val deep = FeedDiscoveryService.classifyQuery("github.com/o/r/issues")
        assertTrue(deep is DiscoveryQuery.DirectUrl)
    }

    @Test
    fun classifyYouTubeShortcuts() {
        assertEquals(
            DiscoveryQuery.YouTubeChannel("UCVHFbqXqoYvEWM1Ddxl0QDg"),
            FeedDiscoveryService.classifyQuery("youtube.com/channel/UCVHFbqXqoYvEWM1Ddxl0QDg"),
        )
        assertEquals(
            DiscoveryQuery.YouTubePage("@mkbhd"),
            FeedDiscoveryService.classifyQuery("youtube.com/@mkbhd"),
        )
        assertEquals(
            DiscoveryQuery.YouTubePage("c/LinusTechTips"),
            FeedDiscoveryService.classifyQuery("https://www.youtube.com/c/LinusTechTips"),
        )
        assertEquals(
            DiscoveryQuery.YouTubePage("user/google"),
            FeedDiscoveryService.classifyQuery("youtube.com/user/google"),
        )
    }

    @Test
    fun classifySubstackShortcuts() {
        assertEquals(
            DiscoveryQuery.SubstackPublication("on"),
            FeedDiscoveryService.classifyQuery("on.substack.com"),
        )
        assertEquals(
            DiscoveryQuery.SubstackPublication("newsletter"),
            FeedDiscoveryService.classifyQuery("https://newsletter.substack.com/"),
        )
    }

    @Test
    fun normalizeToUrlHandlesBareDomainsAndRejectsNonUrls() {
        assertEquals("https://example.com/", FeedDiscoveryService.normalizeToUrl("example.com"))
        assertEquals(
            "https://example.com/x",
            FeedDiscoveryService.normalizeToUrl("https://example.com/x"),
        )
        assertNull(FeedDiscoveryService.normalizeToUrl("linux"))
        assertNull(FeedDiscoveryService.normalizeToUrl("linux security"))
        assertNull(FeedDiscoveryService.normalizeToUrl("   "))
    }

    // --- format guess + URL normalization (pure) ---------------------------------------

    @Test
    fun guessFormatMapsUrlShapes() {
        assertEquals(FeedFormat.RSS, DiscoveredFeed.guessFormat("https://x.com/feed.rss"))
        assertEquals(FeedFormat.RSS, DiscoveredFeed.guessFormat("https://x.com/index.xml"))
        assertEquals(FeedFormat.RSS, DiscoveredFeed.guessFormat("https://x.com/feed"))
        assertEquals(FeedFormat.RSS, DiscoveredFeed.guessFormat("https://x.com/rss"))
        assertEquals(FeedFormat.ATOM, DiscoveredFeed.guessFormat("https://x.com/f.atom"))
        assertEquals(
            FeedFormat.JSON_FEED,
            DiscoveredFeed.guessFormat("https://x.com/feed.json"),
        )
        assertEquals(
            FeedFormat.ATOM,
            DiscoveredFeed.guessFormat(
                "https://www.youtube.com/feeds/videos.xml?channel_id=UCx"
            ),
        )
        assertEquals(FeedFormat.UNKNOWN, DiscoveredFeed.guessFormat("https://x.com/"))
        assertEquals(
            FeedFormat.UNKNOWN,
            DiscoveredFeed.guessFormat("https://x.com/subscribe?format=xml"),
        )
    }

    @Test
    fun normalizeUrlDropsFragmentsAndTrailingSlashButKeepsQueryAndCase() {
        assertEquals(
            "https://x.com/feed",
            DiscoveredFeed.normalizeUrl("https://x.com/feed#frag"),
        )
        assertEquals(
            "https://x.com/feed",
            DiscoveredFeed.normalizeUrl("https://x.com/feed/"),
        )
        assertEquals(
            "https://x.com/",
            DiscoveredFeed.normalizeUrl("https://x.com/"),
        )
        assertEquals(
            "https://x.com/f?a=1",
            DiscoveredFeed.normalizeUrl("https://x.com/f?a=1"),
        )
        assertEquals(
            "https://x.com/Feed.XML",
            DiscoveredFeed.normalizeUrl("HTTPS://X.COM/Feed.XML"),
        )
    }

    @Test
    fun dedupeByNormalizedUrlKeepsFirstOccurrence() {
        val first =
            DiscoveredFeed("https://x.com/feed/", "First", source = DiscoverySource.STARTER_CATALOG)
        val dup =
            DiscoveredFeed(
                "https://x.com/feed#top",
                "Dup",
                source = DiscoverySource.FEEDLY_SEARCH,
            )
        val other =
            DiscoveredFeed("https://y.com/feed", "Other", source = DiscoverySource.FEEDLY_SEARCH)
        val deduped = FeedDiscoveryService.dedupeByNormalizedUrl(listOf(first, dup, other))
        assertEquals(2, deduped.size)
        assertEquals("First", deduped[0].title)
        assertEquals("Other", deduped[1].title)
    }

    // --- YouTube channel-id extraction (pure) --------------------------------------------

    @Test
    fun extractYouTubeChannelIdFromMetaItemprop() {
        val html =
            """<html><head><meta itemprop="channelId" content="UCVHFbqXqoYvEWM1Ddxl0QDg"></head>"""
        assertEquals(
            "UCVHFbqXqoYvEWM1Ddxl0QDg",
            FeedDiscoveryService.extractYouTubeChannelId(html),
        )
    }

    @Test
    fun extractYouTubeChannelIdFromReversedMetaAttributeOrder() {
        val html =
            """<meta content="UCVHFbqXqoYvEWM1Ddxl0QDg" itemprop="channelId">"""
        assertEquals(
            "UCVHFbqXqoYvEWM1Ddxl0QDg",
            FeedDiscoveryService.extractYouTubeChannelId(html),
        )
    }

    @Test
    fun extractYouTubeChannelIdFromMicroformatExternalId() {
        val html =
            """{"microformat":{"externalId":"UCabcdefghijklmnopqrstuv","isFamilySafe":true}}"""
        assertEquals(
            "UCabcdefghijklmnopqrstuv",
            FeedDiscoveryService.extractYouTubeChannelId(html),
        )
    }

    @Test
    fun extractYouTubeChannelIdFromCanonicalLink() {
        val html =
            """<link rel="canonical" href="https://www.youtube.com/channel/UCabcdefghijklmnopqrstuv">"""
        assertEquals(
            "UCabcdefghijklmnopqrstuv",
            FeedDiscoveryService.extractYouTubeChannelId(html),
        )
    }

    @Test
    fun extractYouTubeChannelIdReturnsNullWhenAbsent() {
        assertNull(FeedDiscoveryService.extractYouTubeChannelId("<html><body>no ids</body></html>"))
    }

    // --- Feedly / iTunes response parsing (pure) -------------------------------------------

    @Test
    fun parseFeedlyResultsStripsFeedPrefixAndMapsFields() {
        val feeds = FeedDiscoveryService.parseFeedlyResults(feedlyJson)
        assertEquals(1, feeds.size)
        val feed = feeds[0]
        assertEquals("https://remote.example/f", feed.url)
        assertEquals("Remote Feed", feed.title)
        assertEquals("Remote desc", feed.description)
        assertEquals("https://remote.example", feed.siteUrl)
        assertEquals("https://remote.example/i.png", feed.iconUrl)
        assertEquals(4242, feed.subscribers)
        assertEquals(DiscoverySource.FEEDLY_SEARCH, feed.source)
    }

    @Test
    fun parseFeedlyResultsRejectsMalformedJson() {
        try {
            FeedDiscoveryService.parseFeedlyResults("not json")
            fail("expected malformed Feedly body to throw")
        } catch (expected: IllegalArgumentException) {
            // pass
        }
        // A well-formed document without a results array is simply empty.
        assertEquals(0, FeedDiscoveryService.parseFeedlyResults("""{"results":null}""").size)
    }

    @Test
    fun parseItunesResultsMapsPodcastFields() {
        val feeds = FeedDiscoveryService.parseItunesResults(itunesJson)
        assertEquals(1, feeds.size)
        val feed = feeds[0]
        assertEquals("https://pod.example/f.xml", feed.url)
        assertEquals("Stub Pod", feed.title)
        assertEquals("Pod Artist", feed.description)
        assertEquals("https://pod.example/a.png", feed.iconUrl)
        assertEquals("https://itunes.apple.com/pod", feed.siteUrl)
        assertEquals(FeedFormat.PODCAST, feed.format)
        assertEquals(DiscoverySource.ITUNES_SEARCH, feed.source)
    }

    @Test
    fun parseItunesResultsRejectsMalformedJson() {
        try {
            FeedDiscoveryService.parseItunesResults("<html>nope</html>")
            fail("expected malformed iTunes body to throw")
        } catch (expected: IllegalArgumentException) {
            // pass
        }
    }

    // --- direct URL discovery over the loopback stub ---------------------------------------

    @Test
    fun directUrlThatIsAnRssFeedYieldsSingleDirectCandidate() = runBlocking {
        stub.route("/", rssXml)
        val result = stubbedService().discover(stub.url("/"))
        assertNull(result.notice)
        assertEquals(1, result.feeds.size)
        val feed = result.feeds[0]
        assertEquals(stub.url("/"), feed.url)
        assertEquals("Stub Feed", feed.title)
        assertEquals("Stub description", feed.description)
        assertEquals("https://stub.example/", feed.siteUrl)
        assertEquals(FeedFormat.RSS, feed.format)
        assertEquals(DiscoverySource.DIRECT, feed.source)
    }

    @Test
    fun directUrlThatIsAJsonFeedYieldsJsonFeedCandidate() = runBlocking {
        stub.route("/", jsonFeedBody, "application/feed+json")
        val feeds = stubbedService().discoverFromUrl(stub.url("/"))
        assertEquals(1, feeds.size)
        assertEquals(FeedFormat.JSON_FEED, feeds[0].format)
        assertEquals("Stub JSON", feeds[0].title)
        assertEquals(DiscoverySource.DIRECT, feeds[0].source)
    }

    @Test
    fun htmlPageYieldsEveryTypedAlternateCandidateInOrder() = runBlocking {
        stub.route("/", htmlWithAlternates, "text/html; charset=utf-8")
        val feeds = stubbedService().discoverFromUrl(stub.url("/"))
        assertEquals(3, feeds.size)
        assertEquals("Main RSS", feeds[0].title)
        assertEquals(FeedFormat.RSS, feeds[0].format)
        assertEquals(stub.url("/feed.xml"), feeds[0].url)
        assertEquals("Comments", feeds[1].title)
        assertEquals(FeedFormat.ATOM, feeds[1].format)
        assertEquals("https://cdn.example/comments.atom", feeds[1].url)
        assertEquals("JSON version", feeds[2].title)
        assertEquals(FeedFormat.JSON_FEED, feeds[2].format)
        assertEquals(stub.url("/feed.json"), feeds[2].url)
        assertTrue(feeds.all { it.source == DiscoverySource.HTML_ALTERNATE })
        assertTrue(feeds.all { it.siteUrl == stub.url("/") })
    }

    @Test
    fun discoverFeedLinksKeepsUntypedAlternatesAsFallbackParity() {
        val helper = RssHelper(mock(), Dispatchers.IO, httpClient)
        val links =
            helper.discoverFeedLinks(
                "https://site.example/page",
                htmlWithAlternates.toByteArray(Charsets.UTF_8),
            )
        // 5 alternate tags, /feed.xml and /feed.xml#frag dedupe to one.
        assertEquals(4, links.size)
        assertEquals("Main RSS", links[0].title)
        assertEquals("Comments", links[1].title)
        assertEquals("JSON version", links[2].title)
        // The hreflang HTML alternate is kept LAST (legacy single-link
        // fallback parity) and typed UNKNOWN so discovery filters it out.
        assertEquals(FeedFormat.UNKNOWN, links[3].format)
        assertEquals("https://es.example/", links[3].url)
        assertEquals("Site Title", links[3].title) // page title fallback
        assertTrue(links.all { it.source == DiscoverySource.HTML_ALTERNATE })
    }

    @Test
    fun htmlWithoutAlternatesFallsBackToWellKnownProbing() = runBlocking {
        stub.route("/", htmlNoFeeds, "text/html")
        stub.route("/feed", rssXml)
        val feeds = stubbedService().discoverFromUrl(stub.url("/"))
        assertEquals(1, feeds.size)
        assertEquals(stub.url("/feed"), feeds[0].url)
        assertEquals(DiscoverySource.WELL_KNOWN_PATH, feeds[0].source)
        assertEquals("Stub Feed", feeds[0].title)
        // First chunk only: /feed, /rss, /rss.xml — probing stopped there.
        val requested = stub.requests
        assertTrue(requested.contains("/feed"))
        assertTrue(requested.contains("/rss"))
        assertTrue(requested.contains("/rss.xml"))
        assertFalse(requested.any { it.startsWith("/atom.xml") })
        assertFalse(requested.any { it.startsWith("/feed.json") })
    }

    @Test
    fun wellKnownProbingStopsAfterFirstChunkWithAValidFeed() = runBlocking {
        stub.route("/", htmlNoFeeds, "text/html")
        // chunk 1 (/feed /rss /rss.xml) all fall through to the 404 fallback
        stub.route("/atom.xml", atomXml, "application/atom+xml")
        stub.route("/feed.json", jsonFeedBody, "application/feed+json")
        val feeds = stubbedService().discoverFromUrl(stub.url("/"))
        assertEquals(1, feeds.size)
        assertEquals(stub.url("/atom.xml"), feeds[0].url)
        assertEquals(FeedFormat.ATOM, feeds[0].format)
        // chunk 3 was never probed
        assertFalse(stub.requests.any { it.startsWith("/feed.json") })
    }

    @Test
    fun redirectsAreWalkedAndTheFinalUrlWins() = runBlocking {
        stub.route("/start", StubResponse(302, location = "/final"))
        stub.route("/final", rssXml)
        val feeds = stubbedService().discoverFromUrl(stub.url("/start"))
        assertEquals(1, feeds.size)
        assertEquals(stub.url("/final"), feeds[0].url)
        assertEquals(DiscoverySource.DIRECT, feeds[0].source)
        assertTrue(stub.requests.contains("/start"))
        assertTrue(stub.requests.contains("/final"))
    }

    @Test
    fun redirectIntoPrivateRangeIsRejectedByTheGuard() = runBlocking {
        stub.route("/r", StubResponse(302, location = "http://10.9.8.7/x"))
        val result = stubbedService().discover(stub.url("/r"))
        assertTrue(result.feeds.isEmpty())
        assertNotNull(result.notice)
        assertTrue(result.notice!!.contains("security policy"))
        // only the initial hop was ever requested
        assertEquals(listOf("/r"), stub.requests)
    }

    @Test
    fun productionGuardRejectsPrivateAndLoopbackEntryUrls() = runBlocking {
        val strict = stubbedService(guard = { url -> realGuard.check(url) })
        for (bad in listOf("http://192.168.1.1/feed", "http://10.0.0.5/rss", "http://127.0.0.1:1/x")) {
            val result = strict.discover(bad)
            assertTrue("expected rejection for $bad", result.feeds.isEmpty())
            assertNotNull(result.notice)
            assertTrue(result.notice!!.contains("security policy"))
        }
        assertTrue(stub.requests.isEmpty())
    }

    @Test
    fun non2xxAndNothingFoundSurfaceNonFatalNotices() = runBlocking {
        stub.route("/", StubResponse(404, "text/plain", "gone"))
        val notFound = stubbedService().discover(stub.url("/"))
        assertTrue(notFound.feeds.isEmpty())
        assertTrue(notFound.notice!!.contains("HTTP 404"))

        stub.route("/plain", htmlNoFeeds, "text/html")
        val noFeeds = stubbedService().discover(stub.url("/plain"))
        assertTrue(noFeeds.feeds.isEmpty())
        assertTrue(noFeeds.notice!!.contains("No feeds found"))
    }

    @Test
    fun oversizeBodiesAreCappedWithoutBuffering() = runBlocking {
        val huge = "x".repeat((5 * 1024 * 1024) + 1)
        stub.route("/big", StubResponse(200, "text/plain", huge))
        val result = stubbedService().discover(stub.url("/big"))
        assertTrue(result.feeds.isEmpty())
        assertNotNull(result.notice)
        assertTrue(result.notice!!.contains("cap"))
    }

    // --- platform shortcuts ------------------------------------------------------------------

    @Test
    fun platformShortcutsThatNeedNoNetworkMapToExactFeedUrls() = runBlocking {
        val service = offlineService()
        val reddit = service.discover("r/androiddev")
        assertEquals(1, reddit.feeds.size)
        assertEquals(
            "https://www.reddit.com/r/androiddev/.rss",
            reddit.feeds[0].url,
        )
        assertEquals(FeedFormat.RSS, reddit.feeds[0].format)
        assertEquals(DiscoverySource.PLATFORM_SHORTCUT, reddit.feeds[0].source)

        val redditUser = service.discover("reddit.com/u/spez")
        assertEquals("https://www.reddit.com/user/spez/.rss", redditUser.feeds[0].url)

        val mastodon = service.discover("@user@mastodon.social")
        assertEquals("https://mastodon.social/@user.rss", mastodon.feeds[0].url)

        val substack = service.discover("on.substack.com")
        assertEquals("https://on.substack.com/feed", substack.feeds[0].url)

        val youTube = service.discover("youtube.com/channel/UCVHFbqXqoYvEWM1Ddxl0QDg")
        assertEquals(
            "https://www.youtube.com/feeds/videos.xml?channel_id=UCVHFbqXqoYvEWM1Ddxl0QDg",
            youTube.feeds[0].url,
        )
        assertEquals(FeedFormat.ATOM, youTube.feeds[0].format)
        assertTrue(stub.requests.isEmpty())
    }

    @Test
    fun gitHubRepoCandidatesAreValidatedBeforeReturning() = runBlocking {
        stub.route("/o/r/releases.atom", atomXml, "application/atom+xml")
        // commits.atom intentionally unrouted -> 404 fallback
        val feeds = stubbedService().discover("github.com/o/r").feeds
        assertEquals(1, feeds.size)
        assertEquals(stub.url("/o/r/releases.atom"), feeds[0].url)
        assertEquals("Stub Atom", feeds[0].title)
        assertEquals(FeedFormat.ATOM, feeds[0].format)
        assertEquals(DiscoverySource.PLATFORM_SHORTCUT, feeds[0].source)
        assertTrue(stub.requests.any { it == "/o/r/releases.atom" })
        assertTrue(stub.requests.any { it == "/o/r/commits.atom" })
    }

    @Test
    fun gitHubUserAtomIsValidated() = runBlocking {
        stub.route("/torvalds.atom", atomXml, "application/atom+xml")
        val feeds = stubbedService().discover("github.com/torvalds").feeds
        assertEquals(1, feeds.size)
        assertEquals(stub.url("/torvalds.atom"), feeds[0].url)
    }

    @Test
    fun gitHubShortcutWithNoValidCandidateReportsNotice() = runBlocking {
        val result = stubbedService().discover("github.com/o/r")
        assertTrue(result.feeds.isEmpty())
        assertNotNull(result.notice)
        assertTrue(result.notice!!.contains("No feeds could be resolved"))
    }

    @Test
    fun youTubeHandleResolvesViaChannelPageExtraction() = runBlocking {
        val page =
            """<html><head><meta itemprop="channelId" content="UCVHFbqXqoYvEWM1Ddxl0QDg">
               </head><body>channel page</body></html>"""
        stub.route("/@mkbhd", StubResponse(200, "text/html", page))
        val feeds = stubbedService().discover("youtube.com/@mkbhd").feeds
        assertEquals(1, feeds.size)
        assertEquals(
            "${stub.origin}/feeds/videos.xml?channel_id=UCVHFbqXqoYvEWM1Ddxl0QDg",
            feeds[0].url,
        )
        assertEquals(DiscoverySource.PLATFORM_SHORTCUT, feeds[0].source)
        assertEquals(listOf("/@mkbhd"), stub.requests)
    }

    @Test
    fun youTubeHandleWithoutChannelIdYieldsNothing() = runBlocking {
        stub.route("/@nobody", StubResponse(200, "text/html", "<html><body>no id</body></html>"))
        val result = stubbedService().discover("youtube.com/@nobody")
        assertTrue(result.feeds.isEmpty())
        assertNotNull(result.notice)
    }

    // --- topic search -------------------------------------------------------------------------

    @Test
    fun searchByTopicMergesFeedlyAndItunesResults() = runBlocking {
        stub.route("/feedly", feedlyJson, "application/json")
        stub.route("/itunes", itunesJson, "application/json")
        val result = stubbedService().searchByTopic("zzzqqq", DiscoverySearchScope.ALL)
        assertNull(result.notice)
        assertEquals(2, result.feeds.size)
        assertEquals(DiscoverySource.FEEDLY_SEARCH, result.feeds[0].source)
        assertEquals("https://remote.example/f", result.feeds[0].url)
        assertEquals(4242, result.feeds[0].subscribers)
        assertEquals(DiscoverySource.ITUNES_SEARCH, result.feeds[1].source)
        assertEquals(FeedFormat.PODCAST, result.feeds[1].format)
        // request shape: encoded query + count on Feedly; term/media/limit on iTunes
        assertTrue(
            stub.requests.any {
                it.startsWith("/feedly?") && it.contains("query=zzzqqq") && it.contains("count=20")
            }
        )
        assertTrue(
            stub.requests.any {
                it.startsWith("/itunes?") &&
                    it.contains("term=zzzqqq") &&
                    it.contains("media=podcast") &&
                    it.contains("limit=10")
            }
        )
    }

    @Test
    fun searchScopeSelectsWhichRemoteSourcesRun() = runBlocking {
        stub.route("/feedly", feedlyJson, "application/json")
        stub.route("/itunes", itunesJson, "application/json")
        val service = stubbedService()

        val articles = service.searchByTopic("zzzqqq", DiscoverySearchScope.ARTICLES)
        assertEquals(1, articles.feeds.size)
        assertEquals(DiscoverySource.FEEDLY_SEARCH, articles.feeds[0].source)
        val afterArticles = stub.requests
        assertTrue(afterArticles.any { it.startsWith("/feedly") })
        assertFalse(afterArticles.any { it.startsWith("/itunes") })

        val podcasts = service.searchByTopic("zzzqqq", DiscoverySearchScope.PODCASTS)
        assertEquals(1, podcasts.feeds.size)
        assertEquals(DiscoverySource.ITUNES_SEARCH, podcasts.feeds[0].source)
        // Only the requests issued by the PODCASTS call (after the ARTICLES
        // snapshot) may be considered — the stub accumulates across calls.
        val podcastRequests = stub.requests.drop(afterArticles.size)
        assertTrue(podcastRequests.any { it.startsWith("/itunes") })
        assertFalse(podcastRequests.any { it.startsWith("/feedly") })
    }

    @Test
    fun localCatalogMatchesComeFirstAndDedupeRemoteOnes() = runBlocking {
        stub.route(
            "/feedly",
            """{"results":[
               {"feedId":"feed/https://www.phoronix.com/rss.php","title":"Remote Phoronix","subscribers":99},
               {"feedId":"feed/https://extra.example/f","title":"Extra"}
            ]}""",
            "application/json",
        )
        stub.route("/itunes", """{"results":[]}""", "application/json")
        val result = stubbedService().searchByTopic("phoronix")
        assertNull(result.notice)
        assertEquals(2, result.feeds.size)
        val phoronix = result.feeds[0]
        assertEquals("https://www.phoronix.com/rss.php", phoronix.url)
        assertEquals(DiscoverySource.STARTER_CATALOG, phoronix.source)
        assertEquals("Phoronix", phoronix.title)
        assertEquals("Extra", result.feeds[1].title)
    }

    @Test
    fun partialRemoteFailureKeepsOtherResultsAndSurfacesNotice() = runBlocking {
        stub.route("/feedly", StubResponse(500, "text/plain", "boom"))
        stub.route("/itunes", itunesJson, "application/json")
        val result = stubbedService().searchByTopic("zzzqqq")
        assertEquals(1, result.feeds.size)
        assertEquals(DiscoverySource.ITUNES_SEARCH, result.feeds[0].source)
        assertNotNull(result.notice)
        assertTrue(result.notice!!.contains("unavailable"))
        assertTrue(result.notice!!.contains("Feedly"))
    }

    @Test
    fun malformedRemoteJsonCountsAsSourceFailure() = runBlocking {
        stub.route("/feedly", StubResponse(200, "application/json", "not json at all"))
        stub.route("/itunes", itunesJson, "application/json")
        val result = stubbedService().searchByTopic("zzzqqq")
        assertEquals(1, result.feeds.size)
        assertNotNull(result.notice)
        assertTrue(result.notice!!.contains("Feedly"))
    }

    @Test
    fun offlineSearchStillReturnsStarterCatalogMatches() = runBlocking {
        // offlineService points both endpoints at a dead loopback port.
        val result = offlineService().searchByTopic("linux")
        assertTrue(result.feeds.isNotEmpty())
        assertTrue(result.feeds.all { it.source == DiscoverySource.STARTER_CATALOG })
        assertNotNull(result.notice)
    }

    @Test
    fun keywordQueriesRouteThroughTopicSearchFromDiscover() = runBlocking {
        val result = offlineService().discover("linux")
        assertTrue(result.feeds.isNotEmpty())
        assertTrue(result.feeds.all { it.source == DiscoverySource.STARTER_CATALOG })
    }

    // --- preview -----------------------------------------------------------------------------

    @Test
    fun previewFeedRejectsPrivateUrlsBeforeAnyFetch() = runBlocking {
        val rssHelper: RssHelper = mock()
        val strict = stubbedService(guard = { url -> realGuard.check(url) }, rssHelper = rssHelper)
        try {
            strict.previewFeed("http://192.168.0.5/feed.xml")
            fail("expected the guard to reject a private address")
        } catch (expected: java.io.IOException) {
            assertTrue(expected.message!!.contains("security policy"))
        }
        verifyNoInteractions(rssHelper)
    }

    @Test
    fun previewFeedUsesSearchFeedAndCapsRecentEntries() = runBlocking {
        val rssHelper: RssHelper = mock()
        val wireFeed =
            SyndFeedImpl().apply {
                title = "Preview Feed"
                entries =
                    (1..7).map { i ->
                        SyndEntryImpl().apply {
                            title = "E$i"
                            link = "https://p.example/$i"
                            publishedDate = Date(1700000000000L + i * 1000L)
                        }
                    }
            }
        whenever(rssHelper.searchFeed(any()))
            .thenReturn(
                RssHelper.SearchFeedResult(feed = wireFeed, feedLink = "https://p.example/feed.xml")
            )
        val preview = stubbedService(rssHelper = rssHelper).previewFeed("http://127.0.0.1:9/p")
        assertEquals("https://p.example/feed.xml", preview.feedUrl)
        assertEquals("Preview Feed", preview.feed.title)
        assertEquals(5, preview.recentEntries.size)
        assertEquals("E1", preview.recentEntries[0].title)
        assertEquals("https://p.example/1", preview.recentEntries[0].link)
        assertEquals(1700000001000L, preview.recentEntries[0].publishedAt)
        assertEquals("E5", preview.recentEntries[4].title)
    }

    @Test
    fun buildPreviewToleratesMissingTitlesAndDates() {
        val feed =
            SyndFeedImpl().apply {
                title = "Sparse"
                entries =
                    listOf(
                        SyndEntryImpl().apply { link = "https://p.example/x" },
                        SyndEntryImpl().apply {
                            title = "Dated"
                            updatedDate = Date(1700000000000L)
                        },
                    )
            }
        val preview = FeedDiscoveryService.buildPreview(feed, "https://p.example/f")
        assertEquals(2, preview.recentEntries.size)
        assertEquals("", preview.recentEntries[0].title)
        assertNull(preview.recentEntries[0].publishedAt)
        // updated date is the published-date fallback
        assertEquals(1700000000000L, preview.recentEntries[1].publishedAt)
    }

    @Test
    fun audioEnclosuresMarkDirectFeedsAsPodcasts() = runBlocking {
        val podcastRss =
            """<?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0"><channel><title>Pod</title><link>https://pod.example/</link>
            <item><title>Ep</title><link>https://pod.example/ep</link><guid>ep</guid>
            <enclosure url="https://pod.example/ep.mp3" length="1000" type="audio/mpeg"/>
            </item></channel></rss>""".trimIndent()
        stub.route("/", podcastRss)
        val feeds = stubbedService().discoverFromUrl(stub.url("/"))
        assertEquals(1, feeds.size)
        assertEquals(FeedFormat.PODCAST, feeds[0].format)
    }

    // --- starter catalog ------------------------------------------------------------------------

    @Test
    fun catalogHasEightNonEmptyCategoriesWithExactLabels() {
        val categories = StarterFeedCatalog.categories
        assertEquals(8, categories.size)
        assertEquals(
            listOf(
                "Tech",
                "Security",
                "Programming",
                "Science",
                "World News",
                "Linux & Open Source",
                "Design",
                "Indie & Culture",
            ),
            categories.map { it.label },
        )
        assertTrue(categories.all { it.feeds.isNotEmpty() })
        assertTrue(categories.all { it.id.isNotBlank() })
        val total = categories.sumOf { it.feeds.size }
        assertTrue("expected 45..55 feeds, got $total", total in 45..55)
    }

    @Test
    fun catalogUrlsAreHttpsUniqueAndParseable() {
        val seen = mutableSetOf<String>()
        for (category in StarterFeedCatalog.categories) {
            for (feed in category.feeds) {
                assertTrue("${feed.url} must be HTTPS", feed.url.startsWith("https://"))
                assertNotNull("${feed.url} must parse", feed.url.toHttpUrlOrNull())
                assertTrue(feed.title.isNotBlank())
                assertTrue(
                    "duplicate catalog URL ${feed.url}",
                    seen.add(DiscoveredFeed.normalizeUrl(feed.url)),
                )
            }
        }
    }

    @Test
    fun catalogSearchMatchesByTokenAndReturnsDiscoveryCandidates() {
        val matches = StarterFeedCatalog.search("phoronix")
        assertTrue(matches.isNotEmpty())
        assertEquals("https://www.phoronix.com/rss.php", matches[0].url)
        assertTrue(matches.all { it.source == DiscoverySource.STARTER_CATALOG })

        val linux = StarterFeedCatalog.search("linux")
        assertTrue(linux.size >= 5)

        assertTrue(StarterFeedCatalog.search("zzzqqq").isEmpty())
        assertTrue(StarterFeedCatalog.search("   ").isEmpty())
    }
}
