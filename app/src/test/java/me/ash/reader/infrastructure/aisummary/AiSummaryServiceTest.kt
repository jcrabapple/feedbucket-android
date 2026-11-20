package me.ash.reader.infrastructure.aisummary

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * AiSummaryService JVM suite. A minimal loopback HTTP stub built on
 * [ServerSocket] (pure `java.net`, no HTTP-mock-server dependency) stands
 * in for an OpenAI-compatible endpoint: it records the request path,
 * Authorization header and JSON body, and replies with a configurable
 * status + payload. Loopback `http://` also exercises the local-endpoint
 * policy branch.
 */
class AiSummaryServiceTest {

    /** One-connection-at-a-time request/response stub over a raw socket. */
    private class StubHttpServer : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val acceptThread = Thread { acceptLoop() }

        @Volatile var lastPath: String? = null
        @Volatile var lastAuthHeader: String? = null
        @Volatile var lastBody: String? = null
        @Volatile var responseCode: Int = 200
        @Volatile var responseBody: String = "{}"

        val port: Int get() = server.localPort

        init {
            acceptThread.isDaemon = true
            acceptThread.start()
        }

        private fun acceptLoop() {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: Exception) {
                    return
                }
                try {
                    handle(socket)
                } catch (_: Exception) {
                    // fall through: always close the connection
                } finally {
                    try {
                        socket.close()
                    } catch (_: Exception) {
                    }
                }
            }
        }

        private fun handle(socket: Socket) {
            val input = socket.getInputStream()
            val headerBlock = readHeaderBlock(input)
            val lines = headerBlock.split("\r\n").filter { it.isNotEmpty() }
            if (lines.isEmpty()) return
            lastPath = lines[0].split(" ").getOrNull(1)
            lastAuthHeader = null
            var contentLength = 0
            for (line in lines.drop(1)) {
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                val name = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim()
                if (name.equals("Authorization", ignoreCase = true)) lastAuthHeader = value
                if (name.equals("Content-Length", ignoreCase = true)) {
                    contentLength = value.toIntOrNull() ?: 0
                }
            }
            lastBody =
                if (contentLength > 0) {
                    val buf = ByteArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = input.read(buf, read, contentLength - read)
                        if (n < 0) break
                        read += n
                    }
                    String(buf, 0, read, Charsets.UTF_8)
                } else {
                    ""
                }

            val payload = responseBody.toByteArray(Charsets.UTF_8)
            val status =
                "HTTP/1.1 ${responseCode} ${reasonFor(responseCode)}\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: ${payload.size}\r\n" +
                    "Connection: close\r\n" +
                    "\r\n"
            val out: OutputStream = socket.getOutputStream()
            out.write(status.toByteArray(Charsets.US_ASCII))
            out.write(payload)
            out.flush()
        }

        private fun readHeaderBlock(input: InputStream): String {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b == -1) break
                sb.append(b.toChar())
                if (sb.length >= 4 && sb.endsWith("\r\n\r\n")) break
            }
            return sb.toString()
        }

        private fun reasonFor(code: Int): String =
            when (code) {
                200 -> "OK"
                400 -> "Bad Request"
                401 -> "Unauthorized"
                403 -> "Forbidden"
                404 -> "Not Found"
                429 -> "Too Many Requests"
                500 -> "Internal Server Error"
                502 -> "Bad Gateway"
                else -> "Status"
            }

        override fun close() {
            try {
                server.close()
            } catch (_: Exception) {
            }
        }
    }

    private lateinit var stub: StubHttpServer
    private lateinit var service: AiSummaryService

    private val baseUrl: String
        get() = "http://127.0.0.1:${stub.port}/v1"

    @Before
    fun setUp() {
        stub = StubHttpServer()
        service = AiSummaryService(OkHttpClient(), Dispatchers.IO)
    }

    @After
    fun tearDown() {
        stub.close()
    }

    private fun requestJson(): JsonObject = Json.parseToJsonElement(stub.lastBody!!).jsonObject

    private fun userMessage(): String =
        requestJson()["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonPrimitive.content

    private fun systemMessage(): String =
        requestJson()["messages"]!!.jsonArray.first().jsonObject["content"]!!.jsonPrimitive.content

    private fun sampleInput(html: String? = "<p>Hello <b>world</b></p>") =
        ArticleSummaryInput(
            title = "Fixture Title",
            feedName = "Fixture Feed",
            author = "Fixture Author",
            html = html,
            sourceIsFullContent = false,
        )

    // --- endpoint normalization ---------------------------------------------

    @Test
    fun normalizeEndpointAppendsChatCompletions() {
        assertEquals(
            "https://openrouter.ai/api/v1/chat/completions",
            AiSummaryService.normalizeEndpoint("https://openrouter.ai/api/v1"),
        )
    }

    @Test
    fun normalizeEndpointTrimsTrailingSlashes() {
        assertEquals(
            "https://openrouter.ai/api/v1/chat/completions",
            AiSummaryService.normalizeEndpoint("https://openrouter.ai/api/v1///"),
        )
        assertEquals(
            "https://openrouter.ai/api/v1/chat/completions",
            AiSummaryService.normalizeEndpoint("  https://openrouter.ai/api/v1/  "),
        )
    }

    @Test
    fun normalizeEndpointKeepsExistingChatCompletionsSuffix() {
        assertEquals(
            "https://example.com/chat/completions",
            AiSummaryService.normalizeEndpoint("https://example.com/chat/completions"),
        )
        assertEquals(
            "https://example.com/v1/chat/completions",
            AiSummaryService.normalizeEndpoint("https://example.com/v1/chat/completions/"),
        )
    }

    @Test
    fun normalizeEndpointRejectsNonHttpSchemes() {
        for (bad in listOf("ftp://example.com/v1", "file:///etc/passwd", "ws://example.com")) {
            try {
                AiSummaryService.normalizeEndpoint(bad)
                throw AssertionError("expected rejection for $bad")
            } catch (expected: IllegalArgumentException) {
                // pass
            }
        }
    }

    @Test
    fun normalizeEndpointRejectsHttpForPublicHosts() {
        try {
            AiSummaryService.normalizeEndpoint("http://openrouter.ai/api/v1")
            throw AssertionError("expected rejection for public http endpoint")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("http://"))
        }
    }

    @Test
    fun normalizeEndpointAcceptsHttpForLoopbackLanAndDotLocal() {
        for (ok in listOf(
            "http://localhost:11434/v1",
            "http://127.0.0.1:8080/v1",
            "http://10.0.0.5:11434/v1",
            "http://192.168.1.100:11434/v1",
            "http://172.16.0.1/v1",
            "http://172.31.255.254/v1",
            "http://ollama.home.local/v1",
        )) {
            assertTrue(
                "expected acceptance for $ok",
                AiSummaryService.normalizeEndpoint(ok).endsWith("/chat/completions"),
            )
        }
    }

    @Test
    fun isLocalHostRejectsNearMissRanges() {
        assertFalse(AiSummaryService.isLocalHost("172.15.0.1"))
        assertFalse(AiSummaryService.isLocalHost("172.32.0.1"))
        assertFalse(AiSummaryService.isLocalHost("192.169.1.1"))
        assertFalse(AiSummaryService.isLocalHost("11.0.0.1"))
        assertFalse(AiSummaryService.isLocalHost("notlocal.example.com"))
        assertFalse(AiSummaryService.isLocalHost("example.local.evil.com"))
        assertTrue(AiSummaryService.isLocalHost("::1"))
        assertTrue(AiSummaryService.isLocalHost("[::1]"))
        assertTrue(AiSummaryService.isLocalHost("::ffff:127.0.0.1"))
    }

    @Test
    fun normalizeEndpointRejectsBlankAndGarbage() {
        for (bad in listOf("", "   ", "not a url", "https://")) {
            try {
                AiSummaryService.normalizeEndpoint(bad)
                throw AssertionError("expected rejection for '$bad'")
            } catch (expected: IllegalArgumentException) {
                // pass
            }
        }
    }

    // --- setup gap -------------------------------------------------------------

    @Test
    fun setupGapReportsFirstMissingSetting() {
        assertEquals(
            AiSummarySetupGap.BASE_URL,
            AiSummaryService.setupGap("", "model", true),
        )
        assertEquals(
            AiSummarySetupGap.MODEL,
            AiSummaryService.setupGap("https://openrouter.ai/api/v1", "", true),
        )
        assertEquals(
            AiSummarySetupGap.API_KEY,
            AiSummaryService.setupGap("https://openrouter.ai/api/v1", "model", false),
        )
        assertEquals(
            AiSummarySetupGap.NONE,
            AiSummaryService.setupGap("https://openrouter.ai/api/v1", "model", true),
        )
        // Local http endpoints work keyless (Ollama-style).
        assertEquals(
            AiSummarySetupGap.NONE,
            AiSummaryService.setupGap("http://192.168.1.100:11434/v1", "model", false),
        )
    }

    // --- summarize happy path ---------------------------------------------------

    @Test
    fun summarizeSendsContractRequestAndParsesStringContent() = runBlocking {
        stub.responseBody =
            """{"model":"echo-model","choices":[{"message":{"role":"assistant","content":"TL;DR done.\n- point"}}]}"""
        val outcome = service.summarize(baseUrl, "test-key", "the-model", sampleInput())
        val success = outcome as AiSummaryOutcome.Success
        assertEquals("TL;DR done.\n- point", success.text)
        assertEquals("echo-model", success.model)
        assertTrue(success.latencyMs >= 0)

        assertEquals("/v1/chat/completions", stub.lastPath)
        assertEquals("Bearer test-key", stub.lastAuthHeader)

        val body = requestJson()
        assertEquals("the-model", body["model"]!!.jsonPrimitive.content)
        assertEquals(0.2, body["temperature"]!!.jsonPrimitive.double, 0.0001)
        assertEquals(700, body["max_tokens"]!!.jsonPrimitive.int)
        val messages = body["messages"]!!.jsonArray
        assertEquals(2, messages.size)
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("user", messages[1].jsonObject["role"]!!.jsonPrimitive.content)
        // Hard-coded prompt contract: TL;DR + 3 to 5 bullets + grounding.
        assertTrue(systemMessage().contains("TL;DR"))
        assertTrue(systemMessage().contains("3 to 5"))
        assertTrue(systemMessage().contains("never invent"))
        // Article context + Jsoup-stripped plain text (no tags).
        assertTrue(userMessage().contains("Title: Fixture Title"))
        assertTrue(userMessage().contains("Feed: Fixture Feed"))
        assertTrue(userMessage().contains("Author: Fixture Author"))
        assertTrue(userMessage().contains("Hello world"))
        assertFalse(userMessage().contains("<p>"))
        assertFalse(userMessage().contains("<b>"))
    }

    @Test
    fun summarizeStripsScriptContent() = runBlocking {
        stub.responseBody = """{"choices":[{"message":{"content":"ok"}}]}"""
        service.summarize(
            baseUrl,
            "test-key",
            "m",
            sampleInput(html = "<p>Body text</p><script>evil()</script><style>.a{}</style>"),
        )
        assertTrue(userMessage().contains("Body text"))
        assertFalse(userMessage().contains("evil()"))
    }

    @Test
    fun summarizeParsesTextPartsArrayContent() = runBlocking {
        stub.responseBody =
            """{"choices":[{"message":{"content":[
               {"type":"text","text":"Part one. "},
               {"type":"text","text":"Part two"}]}}]}"""
        val outcome = service.summarize(baseUrl, "k", "m", sampleInput())
        assertEquals("Part one. Part two", (outcome as AiSummaryOutcome.Success).text)
    }

    @Test
    fun summarizeCapsBodyInput() = runBlocking {
        stub.responseBody = """{"choices":[{"message":{"content":"ok"}}]}"""
        val huge = "<p>" + "word ".repeat(20_000) + "</p>" // ~100k chars of text
        service.summarize(baseUrl, "k", "m", sampleInput(html = huge))
        val articleText = userMessage().substringAfter("Article text:\n")
        assertTrue(
            "body cap exceeded: ${articleText.length}",
            articleText.length <= AiSummaryService.MAX_BODY_CHARS,
        )
        assertTrue(articleText.length > AiSummaryService.MAX_BODY_CHARS - 100)
    }

    @Test
    fun summarizeFallsBackToRequestModelWhenResponseHasNone() = runBlocking {
        stub.responseBody = """{"choices":[{"message":{"content":"ok"}}]}"""
        val outcome = service.summarize(baseUrl, "k", "fallback-model", sampleInput())
        assertEquals("fallback-model", (outcome as AiSummaryOutcome.Success).model)
    }

    // --- auth policy -------------------------------------------------------------

    @Test
    fun summarizeAllowsKeylessLocalHttpAndOmitsAuthHeader() = runBlocking {
        stub.responseBody = """{"choices":[{"message":{"content":"ok"}}]}"""
        val outcome = service.summarize(baseUrl, "", "m", sampleInput())
        assertTrue(outcome is AiSummaryOutcome.Success)
        assertEquals(null, stub.lastAuthHeader)
    }

    @Test
    fun summarizeRequiresKeyForHttpsEndpointsWithoutNetwork() = runBlocking {
        // Unreachable port: proves the check short-circuits before any call.
        val outcome = service.summarize("https://127.0.0.1:9/v1", "", "m", sampleInput())
        val failure = outcome as AiSummaryOutcome.Failure
        assertTrue(failure.message.contains("API key"))
    }

    @Test
    fun summarizeRejectsBlankModelBeforeNetwork() = runBlocking {
        val outcome = service.summarize(baseUrl, "k", "  ", sampleInput())
        assertTrue((outcome as AiSummaryOutcome.Failure).message.contains("model"))
    }

    @Test
    fun summarizeRejectsInvalidBaseUrl() = runBlocking {
        val outcome = service.summarize("ftp://x/v1", "k", "m", sampleInput())
        assertTrue(outcome is AiSummaryOutcome.Failure)
    }

    @Test
    fun summarizeFailsGracefullyOnEmptyArticleBody() = runBlocking {
        val outcome = service.summarize(baseUrl, "k", "m", sampleInput(html = "  "))
        assertTrue((outcome as AiSummaryOutcome.Failure).message.contains("empty"))
    }

    // --- error handling ------------------------------------------------------------

    @Test
    fun summarizeSurfacesErrorMessageWithoutLeakingKey() = runBlocking {
        stub.responseCode = 401
        stub.responseBody = """{"error":{"message":"Invalid API key provided"}}"""
        val outcome = service.summarize(baseUrl, "super-secret-key-value", "m", sampleInput())
        val failure = outcome as AiSummaryOutcome.Failure
        assertTrue(failure.message.contains("HTTP 401"))
        assertTrue(failure.message.contains("Invalid API key provided"))
        assertFalse(failure.message.contains("super-secret-key-value"))
    }

    @Test
    fun summarizeHandlesPlainStringErrorField() {
        val outcome =
            AiSummaryService.parseCompletion("""{"error":"boom"}""", 500, "m", 5)
        assertEquals("HTTP 500: boom", (outcome as AiSummaryOutcome.Failure).message)
    }

    @Test
    fun summarizeHandlesEmptyChoices() = runBlocking {
        stub.responseBody = """{"choices":[]}"""
        val outcome = service.summarize(baseUrl, "k", "m", sampleInput())
        assertTrue((outcome as AiSummaryOutcome.Failure).message.contains("empty response"))
    }

    @Test
    fun summarizeHandlesNullContent() {
        val outcome =
            AiSummaryService.parseCompletion(
                """{"choices":[{"message":{"content":null}}]}""",
                200,
                "m",
                1,
            )
        assertTrue((outcome as AiSummaryOutcome.Failure).message.contains("empty response"))
    }

    @Test
    fun summarizeHandlesNonJsonBody() = runBlocking {
        stub.responseCode = 502
        stub.responseBody = "<html>Bad Gateway</html>"
        val outcome = service.summarize(baseUrl, "k", "m", sampleInput())
        assertEquals("HTTP 502", (outcome as AiSummaryOutcome.Failure).message)
    }

    @Test
    fun summarizeHandlesNonJsonBodyWith200() {
        val outcome = AiSummaryService.parseCompletion("not json at all", 200, "m", 1)
        assertEquals(
            "Unparseable response from server",
            (outcome as AiSummaryOutcome.Failure).message,
        )
    }

    @Test
    fun summarizeReportsNetworkErrors() = runBlocking {
        // Nothing listens on this loopback port; connect fails fast.
        val outcome =
            service.summarize("http://127.0.0.1:9/v1", "", "m", sampleInput())
        val failure = outcome as AiSummaryOutcome.Failure
        assertTrue(failure.message.startsWith("Network error"))
    }

    // --- testConnection probe -------------------------------------------------------

    @Test
    fun testConnectionSendsMinimalProbe() = runBlocking {
        stub.responseBody = """{"model":"probe-model","choices":[{"message":{"content":"OK"}}]}"""
        val outcome = service.testConnection(baseUrl, "probe-key", "probe-model")
        val success = outcome as AiSummaryOutcome.Success
        assertEquals("OK", success.text)
        assertEquals("probe-model", success.model)
        assertTrue(success.latencyMs >= 0)

        assertEquals("/v1/chat/completions", stub.lastPath)
        assertEquals("Bearer probe-key", stub.lastAuthHeader)
        val body = requestJson()
        assertEquals(16, body["max_tokens"]!!.jsonPrimitive.int)
        val messages = body["messages"]!!.jsonArray
        assertEquals(1, messages.size)
        assertEquals("user", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals(
            "Reply with OK.",
            messages[0].jsonObject["content"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun testConnectionReportsSanitizedFailure() = runBlocking {
        stub.responseCode = 403
        stub.responseBody = """{"error":{"message":"Forbidden region"}}"""
        val outcome = service.testConnection(baseUrl, "secret-key-material", "m")
        val failure = outcome as AiSummaryOutcome.Failure
        assertTrue(failure.message.contains("HTTP 403"))
        assertTrue(failure.message.contains("Forbidden region"))
        assertFalse(failure.message.contains("secret-key-material"))
    }

    @Test
    fun testConnectionRequiresKeyForHttps() = runBlocking {
        val outcome = service.testConnection("https://openrouter.ai/api/v1", "", "m")
        assertTrue((outcome as AiSummaryOutcome.Failure).message.contains("API key"))
    }

    @Test
    fun testConnectionRejectsBlankModel() = runBlocking {
        val outcome = service.testConnection(baseUrl, "k", "")
        assertTrue((outcome as AiSummaryOutcome.Failure).message.contains("model"))
    }

    // --- pure helpers -----------------------------------------------------------------

    @Test
    fun htmlToPlainTextHandlesNullAndBlank() {
        assertEquals("", AiSummaryService.htmlToPlainText(null))
        assertEquals("", AiSummaryService.htmlToPlainText("   "))
    }

    @Test
    fun capBodyKeepsShortTextIntact() {
        assertEquals("short", AiSummaryService.capBody("short"))
    }

    @Test
    fun buildUserMessageOmitsBlankContextLines() {
        val message =
            AiSummaryService.buildUserMessage(
                ArticleSummaryInput(
                    title = null,
                    feedName = "  ",
                    author = null,
                    html = "<p>Only body</p>",
                    sourceIsFullContent = true,
                )
            )
        assertFalse(message.contains("Title:"))
        assertFalse(message.contains("Feed:"))
        assertFalse(message.contains("Author:"))
        assertTrue(message.contains("Only body"))
    }
}
