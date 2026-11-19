package me.ash.reader.domain.service

import java.io.Closeable
import java.nio.charset.Charset
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.ash.reader.domain.model.extract.ExtractPolicy
import me.ash.reader.infrastructure.net.RedirectWalker
import me.ash.reader.infrastructure.net.SsrfGuard
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import okio.BufferedSource
import org.jsoup.Jsoup

/**
 * E17/E18 — the extraction fetch step: SSRF-guarded, size-capped,
 * charset-aware page retrieval, shared by every [ExtractCoordinator] mode.
 *
 * Contract:
 *  - The transport client NEVER follows redirects itself (E18
 *    `@ExtractHttpClient` sets `followRedirects(false)`); the manual
 *    redirect loop is the X2 [RedirectWalker] running the FULL [SsrfGuard]
 *    check on EVERY hop URL including the start (E17). Hop-limit exceeded
 *    → [FetchOutcome.TooManyHops]; a hop into a non-global address or any
 *    other guard reject → [FetchOutcome.Rejected].
 *  - Response body read is a STREAMING byte-count cap at
 *    [ExtractPolicy.MAX_RESPONSE_BYTES] (5 MiB, E18): the source is read in
 *    fixed chunks and closed the moment the cap is exceeded — a hostile
 *    multi-GB body never lands in memory → [FetchOutcome.Oversize].
 *  - Charset detection is EXTRACTED from the legacy RssHelper full-content
 *    fetch (`RssHelper.kt:155–186`) with behavior PRESERVED (E18): HTTP
 *    `Content-Type` charset first, else `meta[http-equiv=content-type]`
 *    charset sniffed from a UTF-8 pre-decode, else UTF-8; an unsupported
 *    meta charset falls back to UTF-8 exactly like the legacy path.
 *
 * JVM-testable without any HTTP-mock-server dependency (S36): the transport
 * is the [Transport] seam and DNS runs through the injected [SsrfGuard]'s
 * `okhttp3.Dns`. Network-level exceptions (timeouts, resets, resolution
 * races) propagate to the caller, which maps them to typed E21 reasons.
 */
class ExtractFetch(
    private val transport: Transport,
    private val ssrfGuard: SsrfGuard,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /** One GET exchange. Implementations MUST NOT follow redirects (E18). */
    interface Transport {
        fun open(url: String): TransportResponse
    }

    /** One exchange's result; [close] releases the underlying connection. */
    interface TransportResponse : Closeable {
        val code: Int
        val contentTypeHeader: String?
        val locationHeader: String?
        fun bodySource(): BufferedSource
    }

    sealed interface FetchOutcome {
        /** Final 2xx body, decoded with the preserved charset logic. */
        data class Body(val html: String, val finalUrl: String) : FetchOutcome

        /** A hop URL failed the E17 guard (start URL included). */
        data class Rejected(val url: String, val reason: SsrfGuard.RejectReason) : FetchOutcome

        /** E17 — the redirect chain exceeded [ExtractPolicy.MAX_HOPS]. */
        data object TooManyHops : FetchOutcome

        /** E18 — streamed body exceeded [ExtractPolicy.MAX_RESPONSE_BYTES]. */
        data object Oversize : FetchOutcome

        /** Final response was not 2xx (E21: silent no-op, typed reason). */
        data class Non2xx(val code: Int) : FetchOutcome

        /**
         * Final 2xx response advertised a non-HTML/non-text `Content-Type`
         * (e.g. `application/pdf`, `image/png`, `application/octet-stream`) —
         * rejected BEFORE the 5 MiB body is streamed into a String for
         * Readability/Jsoup (review lane 2). A missing/unparseable
         * `Content-Type` is allowed (the body is sniffed as before).
         */
        data class UnsupportedContentType(val contentType: String?) : FetchOutcome
    }

    /**
     * Guarded fetch of [startUrl]. The final response is cached so the walk
     * and the body read are ONE HTTP request for that URL (never re-fetched
     * after the walk); intermediate redirect responses are closed the moment
     * their `Location` is read, so a multi-hop chain never holds every hop's
     * socket open until the end (review lane 2).
     */
    suspend fun fetch(startUrl: String): FetchOutcome = withContext(ioDispatcher) {
        val responses = LinkedHashMap<String, TransportResponse>()
        try {
            val walker = RedirectWalker(
                guard = { url -> ssrfGuard.check(url) },
                transport = { url ->
                    val response = transport.open(url)
                    if (response.code in REDIRECT_CODES) {
                        // Intermediate hop: read the Location, then release
                        // the socket immediately — this response is never
                        // reused (a revisit re-opens, bounded by MAX_HOPS).
                        val location = response.locationHeader
                        runCatching { response.close() }
                        location
                    } else {
                        // Final (non-redirect) response: cache it for the
                        // body read below.
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
                    // The walk visited finalUrl, so its response is cached.
                    val response = responses.getValue(walked.finalUrl)
                    when {
                        response.code !in SUCCESS_CODES -> FetchOutcome.Non2xx(response.code)
                        // Non-HTML/non-text bodies (pdf, images, media,
                        // octet-stream) are rejected BEFORE the 5 MiB
                        // streaming read (review lane 2).
                        !isSupportedContentType(response.contentTypeHeader) ->
                            FetchOutcome.UnsupportedContentType(response.contentTypeHeader)
                        else -> {
                            val bytes = readAtMost(
                                response.bodySource(),
                                ExtractPolicy.MAX_RESPONSE_BYTES,
                            )
                            if (bytes == null) {
                                FetchOutcome.Oversize
                            } else {
                                FetchOutcome.Body(
                                    html = decode(bytes, response.contentTypeHeader, walked.finalUrl),
                                    finalUrl = walked.finalUrl,
                                )
                            }
                        }
                    }
                }
            }
        } finally {
            responses.values.forEach { runCatching { it.close() } }
        }
    }

    /**
     * Content-Type allow-list (review lane 2): absent or unparseable headers
     * are allowed (sniffed downstream, legacy behavior); otherwise only
     * `text` types, `application/xml`, `application/xhtml+xml` and `+xml`
     * suffixed application types reach Readability/Jsoup. Everything else
     * (pdf, image, audio, video, octet-stream, json, ...) is rejected before
     * the body is streamed into a String.
     */
    private fun isSupportedContentType(header: String?): Boolean {
        if (header.isNullOrBlank()) return true
        val mediaType = header.toMediaTypeOrNull() ?: return true
        val type = mediaType.type.lowercase()
        val subtype = mediaType.subtype.lowercase()
        if (type == "text") return true
        if (type == "application") {
            return subtype == "xml" || subtype == "xhtml+xml" || subtype.endsWith("+xml")
        }
        return false
    }

    /**
     * E18 streaming cap: reads in [READ_CHUNK_BYTES] chunks and stops the
     * moment the total EXCEEDS [maxBytes] (a body of exactly maxBytes is
     * allowed). Returns null when the cap was exceeded — the caller closes
     * the response in [fetch]'s finally block.
     */
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

    /**
     * Charset detection ported from the legacy RssHelper full-content fetch
     * (`RssHelper.kt:155–186`, E18) and hardened (review lane 2):
     * HTTP `Content-Type` charset first, else sniffed from a UTF-8
     * pre-decode — HTML5 `<meta charset="...">` OR the legacy
     * `meta[http-equiv=content-type]` content — else UTF-8. A charset token
     * is cleaned of quotes and trailing `;` parameters before
     * [Charset.forName]; any parse failure falls back to UTF-8 exactly like
     * the legacy path.
     */
    private fun decode(bytes: ByteArray, contentTypeHeader: String?, url: String): String {
        val charset = contentTypeHeader?.toMediaTypeOrNull()?.charset()
        if (charset != null) {
            return String(bytes, charset)
        }

        val peekContent = String(bytes, Charsets.UTF_8)

        val charsetFromMeta =
            runCatching {
                    val document = Jsoup.parse(peekContent, url)
                    // HTML5: <meta charset="..."> — the attribute IS the token.
                    normalizeCharsetToken(
                        document.selectFirst("meta[charset]")?.attr("charset")
                    )
                        // HTML4: <meta http-equiv="content-type"
                        //         content="text/html; charset=...; ...">
                        ?: document
                            .selectFirst("meta[http-equiv=content-type]")
                            ?.attr("content")
                            ?.lowercase()
                            ?.substringAfter("charset=", "")
                            ?.let { normalizeCharsetToken(it) }
                }
                .getOrNull() ?: Charsets.UTF_8

        return if (charsetFromMeta == Charsets.UTF_8) peekContent else String(bytes, charsetFromMeta)
    }

    /**
     * Cleans a raw charset token (quotes, trailing `;` parameters such as
     * `charset=windows-1252; foo=bar`) and resolves it; null when absent,
     * blank, or not a JVM-known charset.
     */
    private fun normalizeCharsetToken(token: String?): Charset? {
        if (token.isNullOrBlank()) return null
        val cleaned =
            token
                .substringBefore(";")
                .trim()
                .removeSurrounding("\"")
                .removeSurrounding("'")
                .trim()
        if (cleaned.isEmpty()) return null
        return runCatching { Charset.forName(cleaned) }.getOrNull()
    }

    /** E18 production transport over the dedicated `@ExtractHttpClient`. */
    class OkHttpTransport(private val client: OkHttpClient) : Transport {
        override fun open(url: String): TransportResponse =
            OkHttpTransportResponse(
                client.newCall(Request.Builder().url(url).get().build()).execute(),
            )
    }

    private class OkHttpTransportResponse(private val response: Response) : TransportResponse {
        override val code: Int get() = response.code
        override val contentTypeHeader: String? get() = response.header("Content-Type")
        override val locationHeader: String? get() = response.header("Location")
        override fun bodySource(): BufferedSource = response.body.source()
        override fun close() = response.close()
    }

    private companion object {
        const val READ_CHUNK_BYTES = 8_192L
        val REDIRECT_CODES = 300..399
        val SUCCESS_CODES = 200..299
    }
}
