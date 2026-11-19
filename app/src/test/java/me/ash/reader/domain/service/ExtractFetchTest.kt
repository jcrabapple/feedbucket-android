package me.ash.reader.domain.service

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.ash.reader.infrastructure.net.SsrfGuard
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review lane 2, defect 4 — [ExtractFetch] hardening pins:
 *  - non-HTML/non-text `Content-Type` is rejected BEFORE the 5 MiB body is
 *    streamed into a String (absent / text / XML-ish types still pass);
 *  - charset sniffing understands the HTML5 `<meta charset="...">` form and
 *    strips trailing `;` parameters after `charset=` (legacy
 *    `meta[http-equiv=content-type]` behavior preserved);
 *  - intermediate redirect responses are closed the moment their `Location`
 *    is read, instead of holding every hop's socket open until the end.
 *
 * Uses the shared table-driven fakes from [ExtractCoordinatorTest] (no
 * HTTP-mock-server dependency, S36).
 *
 * REDCHECK (recorded in the review handoff): restoring the "cache every hop,
 * close in finally" walk turns [redirectHopResponsesAreClosedBeforeTheFinalBodyRead]
 * red (hop closes move AFTER the body read); dropping the `;`-strip in
 * `normalizeCharsetToken` turns [metaCharsetTrailingParametersAreStripped] red.
 */
class ExtractFetchTest {

    /** Records lifecycle events IN CALL ORDER so close-vs-read order is pinned. */
    private class TrackingTransportResponse(
        override val code: Int,
        override val contentTypeHeader: String?,
        override val locationHeader: String?,
        private val body: ByteArray,
        private val label: String,
        private val events: MutableList<String>,
    ) : ExtractFetch.TransportResponse {
        private val closed = AtomicBoolean(false)

        override fun bodySource(): BufferedSource {
            events.add("body:$label")
            return Buffer().write(body)
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) events.add("close:$label")
        }
    }

    private fun fetchWith(transport: ExtractFetch.Transport): ExtractFetch =
        ExtractFetch(transport, SsrfGuard(FakeDns()), Dispatchers.Unconfined)

    // ------------------------------------------------------------------
    // Content-Type gate
    // ------------------------------------------------------------------

    @Test
    fun nonHtmlContentTypesAreRejectedBeforeTheBodyIsRead() = runBlocking {
        val rejected =
            listOf(
                "application/pdf",
                "image/png",
                "audio/mpeg",
                "video/mp4",
                "application/octet-stream",
                "application/json",
            )
        for (contentType in rejected) {
            val events = CopyOnWriteArrayList<String>()
            val transport = object : ExtractFetch.Transport {
                override fun open(url: String): ExtractFetch.TransportResponse =
                    TrackingTransportResponse(
                        code = 200,
                        contentTypeHeader = contentType,
                        locationHeader = null,
                        body = ByteArray(1024) { 'x'.code.toByte() },
                        label = "final",
                        events = events,
                    )
            }
            val outcome = fetchWith(transport).fetch(EXTRACT_TEST_LINK)
            assertTrue(
                "$contentType: expected UnsupportedContentType, got $outcome",
                outcome is ExtractFetch.FetchOutcome.UnsupportedContentType,
            )
            assertEquals(
                "$contentType: body must never be streamed into memory",
                listOf("close:final"),
                events.toList(),
            )
        }
    }

    @Test
    fun textXmlAndAbsentContentTypesAreStillFetched() = runBlocking {
        val allowed =
            listOf<String?>(
                "text/html",
                "text/html; charset=utf-8",
                "text/plain",
                "application/xhtml+xml",
                "application/xml",
                "application/rss+xml",
                null, // absent header → allowed (sniffed downstream, legacy behavior)
            )
        for (contentType in allowed) {
            val transport = FakeTransport()
            transport.respondBytes(
                EXTRACT_TEST_LINK,
                extractTestPage().toByteArray(),
                contentType = contentType,
            )
            val outcome = fetchWith(transport).fetch(EXTRACT_TEST_LINK)
            assertTrue("$contentType: expected Body, got $outcome", outcome is ExtractFetch.FetchOutcome.Body)
        }
    }

    // ------------------------------------------------------------------
    // Charset sniffing
    // ------------------------------------------------------------------

    @Test
    fun html5MetaCharsetIsHonoredWhenHeaderOmitsIt() = runBlocking {
        val html =
            "<html><head><meta charset=\"windows-1252\"></head><body><p>café naïve</p></body></html>"
        val transport = FakeTransport()
        transport.respondBytes(
            EXTRACT_TEST_LINK,
            html.toByteArray(charset("windows-1252")),
            contentType = "text/html",
        )
        val outcome = fetchWith(transport).fetch(EXTRACT_TEST_LINK)
        assertTrue(outcome is ExtractFetch.FetchOutcome.Body)
        assertTrue(
            "HTML5 <meta charset> must drive the decode",
            (outcome as ExtractFetch.FetchOutcome.Body).html.contains("café naïve"),
        )
    }

    @Test
    fun metaCharsetTrailingParametersAreStripped() = runBlocking {
        // Legacy sniff took everything after `charset=` verbatim, so a
        // trailing `;`-parameter made Charset.forName throw and silently
        // fell back to UTF-8 (mojibake on non-UTF-8 pages).
        val html =
            "<html><head><meta http-equiv=\"content-type\" " +
                "content=\"text/html; charset=windows-1252; x=y\"></head>" +
                "<body><p>café naïve</p></body></html>"
        val transport = FakeTransport()
        transport.respondBytes(
            EXTRACT_TEST_LINK,
            html.toByteArray(charset("windows-1252")),
            contentType = "text/html",
        )
        val outcome = fetchWith(transport).fetch(EXTRACT_TEST_LINK)
        assertTrue(outcome is ExtractFetch.FetchOutcome.Body)
        assertTrue(
            "charset token must be cleaned of trailing parameters",
            (outcome as ExtractFetch.FetchOutcome.Body).html.contains("café naïve"),
        )
    }

    @Test
    fun unknownMetaCharsetStillFallsBackToUtf8() = runBlocking {
        val html =
            "<html><head><meta charset=\"totally-not-a-charset\"></head>" +
                "<body><p>plain ascii body</p></body></html>"
        val transport = FakeTransport()
        transport.respondBytes(EXTRACT_TEST_LINK, html.toByteArray(), contentType = "text/html")
        val outcome = fetchWith(transport).fetch(EXTRACT_TEST_LINK)
        assertTrue(outcome is ExtractFetch.FetchOutcome.Body)
        assertTrue(
            (outcome as ExtractFetch.FetchOutcome.Body).html.contains("plain ascii body"),
        )
    }

    // ------------------------------------------------------------------
    // Hop socket cleanup
    // ------------------------------------------------------------------

    @Test
    fun redirectHopResponsesAreClosedBeforeTheFinalBodyRead() = runBlocking {
        val events = CopyOnWriteArrayList<String>()
        val base = "http://93.184.216.34/h"
        val transport = object : ExtractFetch.Transport {
            override fun open(url: String): ExtractFetch.TransportResponse =
                when (url) {
                    "${base}0" ->
                        TrackingTransportResponse(302, null, "${base}1", ByteArray(0), "hop0", events)
                    "${base}1" ->
                        TrackingTransportResponse(302, null, "${base}2", ByteArray(0), "hop1", events)
                    else ->
                        TrackingTransportResponse(
                            200,
                            "text/html",
                            null,
                            extractTestPage().toByteArray(),
                            "final",
                            events,
                        )
                }
        }

        val outcome = fetchWith(transport).fetch("${base}0")

        assertTrue("expected Body, got $outcome", outcome is ExtractFetch.FetchOutcome.Body)
        // Intermediate hop sockets are released DURING the walk — before the
        // final body is streamed — and the final response closes in finally.
        assertEquals(
            listOf("close:hop0", "close:hop1", "body:final", "close:final"),
            events.toList(),
        )
    }

    @Test
    fun rejectedHopClosesTheRedirectThatPointedAtIt() = runBlocking {
        // The redirect response that pointed into a private range must still
        // have been closed immediately (its Location was read), leaving
        // nothing open for the finally block to leak.
        val events = CopyOnWriteArrayList<String>()
        val start = "http://93.184.216.34/h0"
        val transport = object : ExtractFetch.Transport {
            override fun open(url: String): ExtractFetch.TransportResponse {
                require(url == start) { "unexpected fetch: $url" }
                return TrackingTransportResponse(
                    302,
                    null,
                    "http://10.0.0.1/blocked",
                    ByteArray(0),
                    "hop0",
                    events,
                )
            }
        }

        val outcome = fetchWith(transport).fetch(start)

        assertTrue("expected Rejected, got $outcome", outcome is ExtractFetch.FetchOutcome.Rejected)
        assertEquals(listOf("close:hop0"), events.toList())
    }
}
