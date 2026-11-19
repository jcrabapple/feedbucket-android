package me.ash.reader.domain.service

import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.io.IOException
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.UpdateDetection
import me.ash.reader.domain.model.extract.ExtractPolicy
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.query.TextNormalizer
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.infrastructure.html.ExtractCleaner
import me.ash.reader.infrastructure.net.SsrfGuard
import okhttp3.Dns
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

// ---------------------------------------------------------------------------
// Shared fakes (same package — reused by ReaderWorkerExtractTest). All are
// table-driven seams per the X3 card: transport, Dns, DAO, clock. No
// HTTP-mock-server dependency anywhere (S36).
// ---------------------------------------------------------------------------

internal const val EXTRACT_TEST_NOW = 1_700_000_000_000L

/** Public literal (example.com range) — the guard's literal fast path. */
internal const val EXTRACT_TEST_LINK = "http://93.184.216.34/post"

internal class FakeTransportResponse(
    override val code: Int = 200,
    override val contentTypeHeader: String? = "text/html; charset=utf-8",
    override val locationHeader: String? = null,
    private val body: ByteArray = ByteArray(0),
) : ExtractFetch.TransportResponse {
    override fun bodySource(): BufferedSource = Buffer().write(body)
    override fun close() {}
}

internal class FakeTransport : ExtractFetch.Transport {
    val handlers = mutableMapOf<String, () -> ExtractFetch.TransportResponse>()
    private val opens = java.util.concurrent.atomic.AtomicInteger()

    /** Thread-safe: prefetch runs extractions concurrently (E9d Semaphore(2)). */
    val openCount: Int get() = opens.get()

    override fun open(url: String): ExtractFetch.TransportResponse {
        opens.incrementAndGet()
        return (handlers[url] ?: error("unexpected fetch: $url"))()
    }

    fun respond(url: String, html: String, code: Int = 200, contentType: String? = "text/html") {
        handlers[url] = { FakeTransportResponse(code, contentType, null, html.toByteArray()) }
    }

    fun respondBytes(url: String, bytes: ByteArray, code: Int = 200, contentType: String? = "text/html") {
        handlers[url] = { FakeTransportResponse(code, contentType, null, bytes) }
    }

    fun redirect(url: String, location: String) {
        handlers[url] = { FakeTransportResponse(302, null, location, ByteArray(0)) }
    }

    fun failWith(url: String, t: Throwable) {
        handlers[url] = { throw t }
    }
}

/** Table-driven fake resolver; unknown hosts fail like real DNS. */
internal class FakeDns(
    private val table: Map<String, List<String>> = emptyMap(),
) : Dns {
    var lookupCount = 0
    override fun lookup(hostname: String): List<InetAddress> {
        lookupCount++
        return table[hostname]?.map { InetAddress.getByName(it) }
            ?: throw UnknownHostException(hostname)
    }
}

internal class DaoRecorder {
    @Volatile var writeCount = 0
    var rowsUpdated = 1
    var throwOnWrite: Throwable? = null
    var lastId: String? = null
    var lastContent: String? = null
    var lastLower: String? = null
    var lastExtractedAt: Date? = null
}

internal fun fakeArticleDao(recorder: DaoRecorder): ArticleDao {
    val dao = mock<ArticleDao>()
    runBlocking {
        whenever(dao.applyExtraction(any(), any(), any(), any())).thenAnswer { invocation ->
            recorder.throwOnWrite?.let { throw it }
            recorder.writeCount++
            recorder.lastId = invocation.getArgument(0)
            recorder.lastContent = invocation.getArgument(1)
            recorder.lastLower = invocation.getArgument(2)
            recorder.lastExtractedAt = invocation.getArgument(3)
            recorder.rowsUpdated
        }
    }
    return dao
}

internal fun extractTestArticle(
    id: String = "1\$article-1",
    link: String = EXTRACT_TEST_LINK,
    title: String = "Test Article",
    rawDescription: String = "A short teaser body.",
    fullContent: String? = null,
    feedId: String = "1\$feed-1",
): Article = Article(
    id = id,
    date = Date(0),
    title = title,
    rawDescription = rawDescription,
    shortDescription = rawDescription.take(20),
    fullContent = fullContent,
    link = link,
    feedId = feedId,
    accountId = 1,
)

internal fun extractTestFeed(
    id: String = "1\$feed-1",
    medianBodyLength: Int = 0,
): Feed = Feed(
    id = id,
    name = "Test Feed",
    url = "http://feed.example/rss",
    groupId = "1\$group-1",
    accountId = 1,
    medianBodyLength = medianBodyLength,
)

internal val TEST_PARAGRAPH =
    "The quick brown fox jumps over the lazy dog while the diligent reader watches " +
        "carefully and takes notes about the weather, the harvest, and the long road home. "

/**
 * Synthetic article page (D16): enough scored paragraph text for the
 * readability4j container pick, an exact-title `h1` (E15.1 drop), optional
 * frontmatter (E15.2 drop) and optional hostile markup (E19 sanitize pin).
 */
internal fun extractTestPage(
    title: String = "Test Article",
    paragraphs: Int = 4,
    hostileMarkup: Boolean = false,
): String = buildString {
    append("<!DOCTYPE html><html><head><title>")
    append(title)
    append(" — Example Site</title></head><body>")
    append("<nav class=\"menu\"><a href=\"/\">Home</a><a href=\"/about\">About</a></nav>")
    append("<article><h1>").append(title).append("</h1>")
    if (hostileMarkup) {
        append("<script>alert('owned')</script>")
        append("<p onclick=\"stealCookies()\">--- title: My Secret Frontmatter ---</p>")
        append("<a href=\"javascript:alert(1)\">click me</a>")
    }
    repeat(paragraphs) {
        append("<p>").append(TEST_PARAGRAPH).append(TEST_PARAGRAPH).append("</p>")
    }
    append("</article><footer>Copyright Example</footer></body></html>")
}

internal val CHALLENGE_HTML =
    "<!DOCTYPE html><html><head><title>Just a moment...</title></head>" +
        "<body><p>Checking your browser before accessing the site.</p></body></html>"

// Verified via build/recon/ReadabilityProbe.java against the shipped
// readability4j 1.0.8 + RYArticleGrabberExtended: an empty body yields a
// NULL articleContent (a <title> would be wrapped as content instead).
internal val EMPTY_HTML = "<html><body></body></html>"

internal fun testExtractCoordinator(
    transport: ExtractFetch.Transport = FakeTransport(),
    dns: Dns = FakeDns(),
    dao: ArticleDao = fakeArticleDao(DaoRecorder()),
    clock: () -> Long = { EXTRACT_TEST_NOW },
    cleaner: (String, String) -> String = { html, title -> ExtractCleaner.clean(html, title) },
    dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
): ExtractCoordinator = ExtractCoordinator(
    fetch = ExtractFetch(transport = transport, ssrfGuard = SsrfGuard(dns), ioDispatcher = dispatcher),
    articleDao = dao,
    ioDispatcher = dispatcher,
    clock = clock,
    cleaner = cleaner,
)

// ---------------------------------------------------------------------------

/**
 * X3 task 1–3 — the E2 pipeline success path, the E21 failure no-op matrix
 * and the in-flight dedupe / AUTO-skip contract, all against table-driven
 * fakes (transport, Dns, DAO, clock — no HTTP-mock-server, S36).
 *
 * REDCHECK (recorded in the review handoff): writing `extractedAt` in the
 * length-guard reject branch BEFORE the guard turns
 * [lengthGuardRejectIsNoOp] red.
 */
class ExtractCoordinatorTest {

    // ---- task 1: success path ---------------------------------------------

    @Test
    fun successPathRunsFullPipelineAndWritesExactlyOnce() = runBlocking {
        val recorder = DaoRecorder()
        val transport = FakeTransport()
        transport.respond(EXTRACT_TEST_LINK, extractTestPage(hostileMarkup = true))
        val coordinator = testExtractCoordinator(transport = transport, dao = fakeArticleDao(recorder))

        val outcome = coordinator.extract(extractTestArticle(), extractTestFeed(), ExtractMode.PREFETCH)

        assertTrue("expected Stored, got $outcome", outcome is ExtractOutcome.Stored)
        assertEquals("1\$article-1", (outcome as ExtractOutcome.Stored).articleId)
        // E3/E4 — extractedAt = now, exactly one write.
        assertEquals(Date(EXTRACT_TEST_NOW), outcome.extractedAt)
        assertEquals(1, recorder.writeCount)
        assertEquals(1, transport.openCount)
        assertEquals("1\$article-1", recorder.lastId)
        assertEquals(Date(EXTRACT_TEST_NOW), recorder.lastExtractedAt)

        val stored = recorder.lastContent!!
        // E19 — stored HTML is sanitized: hostile fixture input is absent.
        assertFalse(stored.contains("<script", ignoreCase = true))
        assertFalse(stored.contains("alert(", ignoreCase = true))
        assertFalse(stored.contains("onclick", ignoreCase = true))
        assertFalse(stored.contains("javascript:", ignoreCase = true))
        // E15 — cleaner ran: exact-title h1 and frontmatter block dropped.
        assertFalse(stored.contains("<h1", ignoreCase = true))
        assertFalse(stored.contains("My Secret Frontmatter", ignoreCase = true))
        // Real content survived.
        assertTrue(stored.contains("quick brown fox"))
        // E4/S11a — fullLower shadow normalized in the same write.
        assertEquals(TextNormalizer.normalize(stored), recorder.lastLower)
    }

    @Test
    fun charsetFromContentTypeHeaderIsHonored() = runBlocking {
        val transport = FakeTransport()
        transport.respondBytes(
            EXTRACT_TEST_LINK,
            "café naïve".toByteArray(charset("ISO-8859-1")),
            contentType = "text/html; charset=ISO-8859-1",
        )
        val fetch = ExtractFetch(transport, SsrfGuard(FakeDns()), Dispatchers.Unconfined)
        val outcome = fetch.fetch(EXTRACT_TEST_LINK)
        assertTrue(outcome is ExtractFetch.FetchOutcome.Body)
        assertEquals("café naïve", (outcome as ExtractFetch.FetchOutcome.Body).html)
    }

    @Test
    fun charsetFromMetaTagIsHonoredWhenHeaderOmitsIt() = runBlocking {
        val html = "<html><head><meta http-equiv=\"content-type\" " +
            "content=\"text/html; charset=windows-1252\"></head><body>café</body></html>"
        val transport = FakeTransport()
        transport.respondBytes(EXTRACT_TEST_LINK, html.toByteArray(charset("windows-1252")), contentType = "text/html")
        val fetch = ExtractFetch(transport, SsrfGuard(FakeDns()), Dispatchers.Unconfined)
        val outcome = fetch.fetch(EXTRACT_TEST_LINK)
        assertTrue(outcome is ExtractFetch.FetchOutcome.Body)
        assertTrue((outcome as ExtractFetch.FetchOutcome.Body).html.contains("café"))
    }

    @Test
    fun redirectChainAtHopLimitSucceeds() = runBlocking {
        val recorder = DaoRecorder()
        val transport = FakeTransport()
        val base = "http://93.184.216.34/r"
        (0 until ExtractPolicy.MAX_HOPS).forEach { transport.redirect("$base$it", "$base${it + 1}") }
        transport.respond("$base${ExtractPolicy.MAX_HOPS}", extractTestPage())
        val coordinator = testExtractCoordinator(transport = transport, dao = fakeArticleDao(recorder))

        val outcome = coordinator.extract(
            extractTestArticle(link = "${base}0"),
            extractTestFeed(),
            ExtractMode.PREFETCH,
        )

        assertTrue("expected Stored after exactly MAX_HOPS redirects, got $outcome", outcome is ExtractOutcome.Stored)
        assertEquals(ExtractPolicy.MAX_HOPS + 1, transport.openCount)
        assertEquals(1, recorder.writeCount)
    }

    // ---- task 2: E21 failure no-op matrix ----------------------------------

    private data class MatrixRow(
        val name: String,
        val link: String = EXTRACT_TEST_LINK,
        val dnsTable: Map<String, List<String>> = emptyMap(),
        val transport: (FakeTransport) -> Unit = {},
        val rawDescription: String = "A short teaser body.",
        val mode: ExtractMode = ExtractMode.PREFETCH,
        val expected: ExtractFailureReason,
    )

    @Test
    fun failureNoOpMatrixProducesTypedReasonAndZeroWrites() = runBlocking {
        val base = "http://93.184.216.34/r"
        val rows = listOf(
            MatrixRow("blank link", link = "   ", expected = ExtractFailureReason.BLANK_LINK),
            MatrixRow(
                "ssrf reject (private IP)",
                link = "http://192.168.1.1/x",
                expected = ExtractFailureReason.SSRF_REJECTED,
            ),
            MatrixRow(
                "ssrf reject (scheme)",
                link = "ftp://93.184.216.34/x",
                expected = ExtractFailureReason.SSRF_REJECTED,
            ),
            MatrixRow(
                "ssrf reject (userinfo)",
                link = "http://user:***@93.184.216.34/x",
                expected = ExtractFailureReason.SSRF_REJECTED,
            ),
            MatrixRow(
                "dns failure",
                link = "http://no-such-host.invalid/x",
                expected = ExtractFailureReason.DNS_FAILURE,
            ),
            MatrixRow(
                "dns failure (multi-address host with one private answer)",
                link = "http://mixed.example/x",
                dnsTable = mapOf("mixed.example" to listOf("93.184.216.34", "10.0.0.1")),
                expected = ExtractFailureReason.SSRF_REJECTED,
            ),
            MatrixRow(
                "timeout (socket)",
                transport = { it.failWith(EXTRACT_TEST_LINK, SocketTimeoutException("read timed out")) },
                expected = ExtractFailureReason.TIMEOUT,
            ),
            MatrixRow(
                "timeout (call cap)",
                transport = { it.failWith(EXTRACT_TEST_LINK, java.io.InterruptedIOException("timeout")) },
                expected = ExtractFailureReason.TIMEOUT,
            ),
            MatrixRow(
                "network io error",
                transport = { it.failWith(EXTRACT_TEST_LINK, IOException("connection reset")) },
                expected = ExtractFailureReason.NETWORK,
            ),
            MatrixRow(
                "non-2xx",
                transport = { it.respond(EXTRACT_TEST_LINK, "server exploded", code = 500) },
                expected = ExtractFailureReason.NON_2XX,
            ),
            MatrixRow(
                "more than MAX_HOPS redirects",
                link = "${base}0",
                transport = { t -> (0..ExtractPolicy.MAX_HOPS).forEach { t.redirect("$base$it", "$base${it + 1}") } },
                expected = ExtractFailureReason.TOO_MANY_HOPS,
            ),
            MatrixRow(
                "oversize response (> 5 MiB stream)",
                transport = {
                    it.respondBytes(EXTRACT_TEST_LINK, ByteArray((ExtractPolicy.MAX_RESPONSE_BYTES + 1).toInt()) { 'a'.code.toByte() })
                },
                expected = ExtractFailureReason.OVERSIZE_RESPONSE,
            ),
            MatrixRow(
                "challenge wall",
                transport = { it.respond(EXTRACT_TEST_LINK, CHALLENGE_HTML) },
                expected = ExtractFailureReason.CHALLENGE,
            ),
            MatrixRow(
                "null readability result",
                transport = { it.respond(EXTRACT_TEST_LINK, EMPTY_HTML) },
                expected = ExtractFailureReason.NULL_READABILITY,
            ),
            MatrixRow(
                "length-guard reject",
                transport = { it.respond(EXTRACT_TEST_LINK, extractTestPage()) },
                rawDescription = "x".repeat(5000),
                expected = ExtractFailureReason.LENGTH_REJECTED,
            ),
        )

        for (row in rows) {
            val recorder = DaoRecorder()
            val transport = FakeTransport()
            row.transport(transport)
            val coordinator = testExtractCoordinator(
                transport = transport,
                dns = FakeDns(row.dnsTable),
                dao = fakeArticleDao(recorder),
            )
            val outcome = coordinator.extract(
                extractTestArticle(link = row.link, rawDescription = row.rawDescription),
                extractTestFeed(),
                row.mode,
            )
            assertTrue(
                "${row.name}: expected Failure(${row.expected}), got $outcome",
                outcome is ExtractOutcome.Failure && outcome.reason == row.expected,
            )
            assertEquals("${row.name}: E21 zero DAO writes", 0, recorder.writeCount)
        }
    }

    @Test
    fun cleanerThrowIsTypedNoOp() = runBlocking {
        val recorder = DaoRecorder()
        val transport = FakeTransport()
        transport.respond(EXTRACT_TEST_LINK, extractTestPage())
        val coordinator = testExtractCoordinator(
            transport = transport,
            dao = fakeArticleDao(recorder),
            cleaner = { _, _ -> throw IllegalStateException("cleaner boom") },
        )

        val outcome = coordinator.extract(extractTestArticle(), extractTestFeed(), ExtractMode.MANUAL)

        assertTrue(outcome is ExtractOutcome.Failure)
        assertEquals(ExtractFailureReason.CLEANER_ERROR, (outcome as ExtractOutcome.Failure).reason)
        assertEquals(0, recorder.writeCount)
    }

    @Test
    fun unproductiveRatioIsTypedNoOp() = runBlocking {
        // E16.1 + amendment A1 — <5% productive text: ~280 chars of text
        // inside ~11 KiB of SURVIVING fragment markup (long-href anchors;
        // the grabber drops contentless filler and readability4j strips
        // class attributes, so only pipeline-preserved markup weights the
        // fragment). Ratio guard fires BEFORE the length guard (card
        // order). A1: the junk sits inside the sanitized fragment, so the
        // reject holds under the fragment basis — pinned arithmetically
        // below, not incidentally via the raw document size.
        val page = markupHeavyJunkPage()
        val sanitized = pipelineSanitizedFragment(page, "Test Article")
        val textLength = UpdateDetection.plainTextLength(sanitized)
        assertTrue(
            "A1 basis: 20×$textLength must stay below fragment ${sanitized.length}",
            textLength * 20L < sanitized.length.toLong(),
        )

        val recorder = DaoRecorder()
        val transport = FakeTransport()
        transport.respond(EXTRACT_TEST_LINK, page)
        val coordinator = testExtractCoordinator(transport = transport, dao = fakeArticleDao(recorder))

        val outcome = coordinator.extract(extractTestArticle(), extractTestFeed(), ExtractMode.PREFETCH)

        assertTrue("expected Failure(UNPRODUCTIVE), got $outcome", outcome is ExtractOutcome.Failure)
        assertEquals(ExtractFailureReason.UNPRODUCTIVE, (outcome as ExtractOutcome.Failure).reason)
        assertEquals(0, recorder.writeCount)
    }

    @Test
    fun oversizeResultIsTypedNoOp() = runBlocking {
        // E16.3 — sanitized result > 2 MiB (DownloadPolicy.TEXT_MAX_BYTES):
        // ~2.25 MiB of plain words inside the 5 MiB response cap.
        val hugeText = "word ".repeat(460_000) // 2.3M chars
        val page = "<!DOCTYPE html><html><head><title>t</title></head><body><article>" +
            "<p>$hugeText</p></article></body></html>"
        val recorder = DaoRecorder()
        val transport = FakeTransport()
        transport.respondBytes(EXTRACT_TEST_LINK, page.toByteArray())
        val coordinator = testExtractCoordinator(transport = transport, dao = fakeArticleDao(recorder))

        val outcome = coordinator.extract(extractTestArticle(), extractTestFeed(), ExtractMode.PREFETCH)

        assertTrue("expected Failure(OVERSIZE_RESULT), got $outcome", outcome is ExtractOutcome.Failure)
        assertEquals(ExtractFailureReason.OVERSIZE_RESULT, (outcome as ExtractOutcome.Failure).reason)
        assertEquals(0, recorder.writeCount)
    }

    @Test
    fun daoWriteErrorIsTypedNoOp() = runBlocking {
        val recorder = DaoRecorder()
        recorder.throwOnWrite = RuntimeException("db locked")
        val transport = FakeTransport()
        transport.respond(EXTRACT_TEST_LINK, extractTestPage())
        val coordinator = testExtractCoordinator(transport = transport, dao = fakeArticleDao(recorder))

        // E21 — the DAO exception must NOT escape.
        val outcome = coordinator.extract(extractTestArticle(), extractTestFeed(), ExtractMode.PREFETCH)

        assertTrue(outcome is ExtractOutcome.Failure)
        assertEquals(ExtractFailureReason.DAO_ERROR, (outcome as ExtractOutcome.Failure).reason)
        assertEquals(0, recorder.writeCount)
    }

    @Test
    fun daoUpdatingZeroRowsIsTypedFailure() = runBlocking {
        val recorder = DaoRecorder()
        recorder.rowsUpdated = 0 // article vanished between query and write
        val transport = FakeTransport()
        transport.respond(EXTRACT_TEST_LINK, extractTestPage())
        val coordinator = testExtractCoordinator(transport = transport, dao = fakeArticleDao(recorder))

        val outcome = coordinator.extract(extractTestArticle(), extractTestFeed(), ExtractMode.PREFETCH)

        assertTrue(outcome is ExtractOutcome.Failure)
        assertEquals(ExtractFailureReason.DAO_ERROR, (outcome as ExtractOutcome.Failure).reason)
    }

    /**
     * REDCHECK target (X3 task 2): the length-guard reject branch must never
     * touch `extractedAt`/the DAO. Mutating the branch to write BEFORE the
     * guard turns this test red.
     */
    @Test
    fun lengthGuardRejectIsNoOp() = runBlocking {
        val recorder = DaoRecorder()
        val transport = FakeTransport()
        transport.respond(EXTRACT_TEST_LINK, extractTestPage())
        val coordinator = testExtractCoordinator(transport = transport, dao = fakeArticleDao(recorder))

        val outcome = coordinator.extract(
            extractTestArticle(rawDescription = "x".repeat(5000)),
            extractTestFeed(),
            ExtractMode.PREFETCH,
        )

        assertTrue(outcome is ExtractOutcome.Failure)
        assertEquals(ExtractFailureReason.LENGTH_REJECTED, (outcome as ExtractOutcome.Failure).reason)
        assertEquals("E21/D6: rejected extraction never claims success", 0, recorder.writeCount)
    }

    // ---- task 3: dedupe + AUTO skip ----------------------------------------

    @Test
    fun concurrentExtractsForOneArticleFetchOnce() {
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val recorder = DaoRecorder()
        val transport = FakeTransport()
        transport.handlers[EXTRACT_TEST_LINK] = {
            entered.countDown()
            if (!gate.await(10, TimeUnit.SECONDS)) error("gate never released")
            FakeTransportResponse(200, "text/html", null, extractTestPage().toByteArray())
        }
        val singleThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val coordinator = testExtractCoordinator(
            transport = transport,
            dao = fakeArticleDao(recorder),
            dispatcher = singleThread,
        )
        try {
            runBlocking {
                val article = extractTestArticle()
                // UNDISPATCHED: each call runs on THIS thread until its first
                // suspension, making the interleaving deterministic — `first`
                // owns the in-flight slot and blocks in the transport; the
                // second registers as a joiner before the gate opens.
                val first = async(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.extract(article, extractTestFeed(), ExtractMode.MANUAL)
                }
                assertTrue("owner never reached the transport", entered.await(10, TimeUnit.SECONDS))
                val second = async(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.extract(article, extractTestFeed(), ExtractMode.MANUAL)
                }
                gate.countDown()
                val outcomeA = first.await()
                val outcomeB = second.await()
                assertTrue("expected Stored, got $outcomeA", outcomeA is ExtractOutcome.Stored)
                assertEquals(outcomeA, outcomeB)
                assertEquals("exactly ONE fetch for concurrent callers", 1, transport.openCount)
                assertEquals(1, recorder.writeCount)
            }
        } finally {
            singleThread.close()
        }
    }

    @Test
    fun autoModeOnExtractedArticleSkipsWithoutFetch() = runBlocking {
        val recorder = DaoRecorder()
        val transport = FakeTransport()
        val coordinator = testExtractCoordinator(transport = transport, dao = fakeArticleDao(recorder))

        val outcome = coordinator.extract(
            extractTestArticle(fullContent = "<p>already extracted</p>"),
            extractTestFeed(),
            ExtractMode.AUTO,
        )

        assertEquals(ExtractOutcome.Skipped, outcome)
        assertEquals(0, transport.openCount)
        assertEquals(0, recorder.writeCount)
    }

    @Test
    fun autoModeOnNonTeaserSkipsWithoutFetch() = runBlocking {
        val recorder = DaoRecorder()
        val transport = FakeTransport()
        val coordinator = testExtractCoordinator(transport = transport, dao = fakeArticleDao(recorder))

        // E6 cond 3: len 800 >= 700 → not a candidate.
        val outcome = coordinator.extract(
            extractTestArticle(rawDescription = "x".repeat(800)),
            extractTestFeed(),
            ExtractMode.AUTO,
        )

        assertEquals(ExtractOutcome.Skipped, outcome)
        assertEquals(0, transport.openCount)
    }

    @Test
    fun autoModeHonorsFeedMedianThreshold() = runBlocking {
        val transport = FakeTransport()
        transport.respond(EXTRACT_TEST_LINK, extractTestPage())
        val coordinator = testExtractCoordinator(transport = transport)

        // E6 cond 4: len 500, median 1000 → threshold max(200, 400) = 400 →
        // 500 >= 400 → NOT a candidate (the coordinator consumes feed.medianBodyLength).
        val skipped = coordinator.extract(
            extractTestArticle(rawDescription = "x".repeat(500)),
            extractTestFeed(medianBodyLength = 1000),
            ExtractMode.AUTO,
        )
        assertEquals(ExtractOutcome.Skipped, skipped)
        assertEquals(0, transport.openCount)

        // Same length, median 2500 → threshold max(200, 1000) = 1000 → 500 < 1000 → candidate.
        val recorder = DaoRecorder()
        val coordinator2 = testExtractCoordinator(transport = transport, dao = fakeArticleDao(recorder))
        val fetched = coordinator2.extract(
            extractTestArticle(rawDescription = "x".repeat(500)),
            extractTestFeed(medianBodyLength = 2500),
            ExtractMode.AUTO,
        )
        assertTrue("expected Stored, got $fetched", fetched is ExtractOutcome.Stored)
        assertEquals(1, transport.openCount)
    }

    @Test
    fun prefetchForcesNonTeaserArticles() = runBlocking {
        // E9 — PREFETCH is the explicit per-feed force contract: no trigger.
        val recorder = DaoRecorder()
        val transport = FakeTransport()
        transport.respond(EXTRACT_TEST_LINK, extractTestPage())
        val coordinator = testExtractCoordinator(transport = transport, dao = fakeArticleDao(recorder))

        val outcome = coordinator.extract(
            extractTestArticle(rawDescription = "x".repeat(800)),
            extractTestFeed(),
            ExtractMode.PREFETCH,
        )

        assertTrue("expected Stored, got $outcome", outcome is ExtractOutcome.Stored)
        assertEquals(1, transport.openCount)
        assertEquals(1, recorder.writeCount)
    }

    @Test
    fun reExtractAcceptsShorterResultWhileFreshGuardRejectsIt() = runBlocking {
        // E16.2 — re-extract floor is 120 (a shorter clean result may replace
        // the stored extraction); the fresh guard baseline is rawDescription.
        val bigRaw = "z".repeat(3000)
        val recorder = DaoRecorder()
        val transport = FakeTransport()
        transport.respond(EXTRACT_TEST_LINK, extractTestPage())
        val coordinator = testExtractCoordinator(transport = transport, dao = fakeArticleDao(recorder))
        val article = extractTestArticle(rawDescription = bigRaw, fullContent = "<p>previous extraction</p>")

        val manual = coordinator.extract(article, extractTestFeed(), ExtractMode.MANUAL)
        assertTrue(manual is ExtractOutcome.Failure)
        assertEquals(ExtractFailureReason.LENGTH_REJECTED, (manual as ExtractOutcome.Failure).reason)
        assertEquals(0, recorder.writeCount)

        val reExtract = coordinator.extract(article, extractTestFeed(), ExtractMode.RE_EXTRACT)
        assertTrue("expected Stored on RE_EXTRACT, got $reExtract", reExtract is ExtractOutcome.Stored)
        assertEquals(1, recorder.writeCount)
        assertEquals(Date(EXTRACT_TEST_NOW), recorder.lastExtractedAt)
    }

    // ---- E18 streaming-cap boundary ----------------------------------------

    @Test
    fun exactlyFiveMiBResponseIsAccepted() = runBlocking {
        val bytes = ByteArray(ExtractPolicy.MAX_RESPONSE_BYTES.toInt()) { 'a'.code.toByte() }
        val transport = FakeTransport()
        transport.respondBytes(EXTRACT_TEST_LINK, bytes)
        val fetch = ExtractFetch(transport, SsrfGuard(FakeDns()), Dispatchers.Unconfined)

        val outcome = fetch.fetch(EXTRACT_TEST_LINK)

        assertTrue("expected Body at exactly the cap, got $outcome", outcome is ExtractFetch.FetchOutcome.Body)
        assertEquals(ExtractPolicy.MAX_RESPONSE_BYTES.toInt(), (outcome as ExtractFetch.FetchOutcome.Body).html.length)
    }

    // ---- review lane 2, defect 4: non-HTML Content-Type mapping -------------

    @Test
    fun unsupportedContentTypeIsTypedNoOp() = runBlocking {
        val recorder = DaoRecorder()
        val transport = FakeTransport()
        transport.respond(EXTRACT_TEST_LINK, "%PDF-1.7 not html", contentType = "application/pdf")
        val coordinator = testExtractCoordinator(transport = transport, dao = fakeArticleDao(recorder))

        val outcome = coordinator.extract(extractTestArticle(), extractTestFeed(), ExtractMode.PREFETCH)

        assertTrue("expected Failure, got $outcome", outcome is ExtractOutcome.Failure)
        assertEquals(
            ExtractFailureReason.UNSUPPORTED_CONTENT_TYPE,
            (outcome as ExtractOutcome.Failure).reason,
        )
        assertEquals("E21 zero DAO writes", 0, recorder.writeCount)
    }

    // ---- review lane 2, defect 3: caller cancellation vs shared waiter ------

    /**
     * Cancelling the OWNING caller mid-extraction must (a) propagate to that
     * caller as an ordinary CancellationException (structured concurrency)
     * and (b) leave the shared pipeline running so a concurrent joined
     * caller (PREFETCH / second open) still receives the real outcome —
     * never a spurious Failure(CANCELLED).
     *
     * REDCHECK: re-attaching the pipeline to the owning caller (running it in
     * the caller's context and absorbing CancellationException into
     * Failure(CANCELLED) on the shared deferred) turns this test red — the
     * waiter receives Failure(CANCELLED) instead of Stored.
     */
    @Test
    fun cancelledOwnerDoesNotFailConcurrentWaiter() {
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val recorder = DaoRecorder()
        val transport = FakeTransport()
        transport.handlers[EXTRACT_TEST_LINK] = {
            entered.countDown()
            if (!gate.await(10, TimeUnit.SECONDS)) error("gate never released")
            FakeTransportResponse(200, "text/html", null, extractTestPage().toByteArray())
        }
        val singleThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val coordinator = testExtractCoordinator(
            transport = transport,
            dao = fakeArticleDao(recorder),
            dispatcher = singleThread,
        )
        try {
            runBlocking {
                val article = extractTestArticle()
                // Owner registers and its pipeline blocks in the transport.
                val owner = async(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.extract(article, extractTestFeed(), ExtractMode.MANUAL)
                }
                assertTrue("owner never reached the transport", entered.await(10, TimeUnit.SECONDS))
                // Waiter provably joins the SAME in-flight deferred before
                // the gate opens (UNDISPATCHED: suspends only on await).
                val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.extract(article, extractTestFeed(), ExtractMode.PREFETCH)
                }

                owner.cancel()
                gate.countDown()

                val ownerCancelled = try {
                    owner.await()
                    false
                } catch (e: CancellationException) {
                    true
                }
                assertTrue("the cancelled caller must see CancellationException", ownerCancelled)

                val waiterOutcome = waiter.await()
                assertTrue(
                    "waiter must NOT be failed by the owner's cancellation, got $waiterOutcome",
                    waiterOutcome is ExtractOutcome.Stored,
                )
                assertEquals("exactly ONE fetch for both callers", 1, transport.openCount)
                assertEquals(1, recorder.writeCount)
            }
        } finally {
            singleThread.close()
        }
    }

    /**
     * A sole caller's cancellation propagates out of [extract] (E21's
     * "never throws" covers PIPELINE failures, not the caller's own
     * cancellation) while the coordinator-owned pipeline completes
     * best-effort and cleans up `inFlight`, so a later extract for the same
     * article runs a FRESH pipeline instead of joining a dead one.
     */
    @Test
    fun callerCancellationPropagatesWhilePipelineCompletesIndependently() {
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val recorder = DaoRecorder()
        val transport = FakeTransport()
        transport.handlers[EXTRACT_TEST_LINK] = {
            entered.countDown()
            if (!gate.await(10, TimeUnit.SECONDS)) error("gate never released")
            FakeTransportResponse(200, "text/html", null, extractTestPage().toByteArray())
        }
        val singleThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val coordinator = testExtractCoordinator(
            transport = transport,
            dao = fakeArticleDao(recorder),
            dispatcher = singleThread,
        )
        try {
            runBlocking {
                val caller = async(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.extract(extractTestArticle(), extractTestFeed(), ExtractMode.MANUAL)
                }
                assertTrue("caller never reached the transport", entered.await(10, TimeUnit.SECONDS))
                caller.cancel()

                val cancelled = try {
                    caller.await()
                    false
                } catch (e: CancellationException) {
                    true
                }
                assertTrue("caller cancellation must propagate", cancelled)

                gate.countDown()
                // The coordinator-owned pipeline survives the caller and its
                // best-effort write still lands (bounded poll, no fixed sleep).
                val deadline = System.currentTimeMillis() + 10_000
                while (recorder.writeCount < 1 && System.currentTimeMillis() < deadline) {
                    Thread.sleep(10)
                }
                assertEquals("pipeline must complete despite caller cancellation", 1, recorder.writeCount)

                // inFlight was cleaned up: a fresh extract runs a NEW pipeline
                // (second fetch) instead of awaiting the completed deferred.
                val second = coordinator.extract(extractTestArticle(), extractTestFeed(), ExtractMode.MANUAL)
                assertTrue("expected Stored on the fresh run, got $second", second is ExtractOutcome.Stored)
                assertEquals(2, transport.openCount)
                assertEquals(2, recorder.writeCount)
            }
        } finally {
            singleThread.close()
        }
    }
}
